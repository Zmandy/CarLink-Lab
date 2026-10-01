package io.github.carlinklab;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;

public final class MainActivity extends ComponentActivity {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String[]> openAudio = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            this::onAudioPicked
    );

    private TextView statusView;
    private TextView nowPlayingView;
    private Button playPauseButton;
    private ListenableFuture<MediaController> controllerFuture;
    @Nullable
    private Uri pendingUri;
    @Nullable
    private MediaController controller;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setupUi();
        } catch (RuntimeException | LinkageError error) {
            showStartupError(error);
        }
    }

    private void setupUi() {
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status);
        String lastCrash = CarLifeLabApplication.readLastCrash(getApplication());
        if (lastCrash != null) {
            statusView.setText(getString(R.string.last_crash, lastCrash));
        }
        nowPlayingView = findViewById(R.id.now_playing);
        playPauseButton = findViewById(R.id.play_pause);

        Button openButton = findViewById(R.id.open_audio);
        openButton.setOnClickListener(view -> openAudio.launch(new String[]{"audio/*"}));

        Button previousButton = findViewById(R.id.previous);
        previousButton.setOnClickListener(view -> {
            ensureSession();
            if (controller != null) {
                controller.seekToPreviousMediaItem();
                controller.play();
            }
        });

        Button nextButton = findViewById(R.id.next);
        nextButton.setOnClickListener(view -> {
            ensureSession();
            if (controller != null) {
                controller.seekToNextMediaItem();
                controller.play();
            }
        });

        playPauseButton.setOnClickListener(view -> {
            ensureSession();
            if (controller == null) {
                return;
            }
            if (controller.isPlaying()) {
                controller.pause();
            } else {
                controller.play();
            }
        });

        Button carLifeButton = findViewById(R.id.open_carlife);
        carLifeButton.setOnClickListener(view -> openOfficialCarLife());

        Button diagnosticsButton = findViewById(R.id.usb_diagnostics);
        diagnosticsButton.setOnClickListener(view ->
                startActivity(new Intent(this, UsbDiagnosticsActivity.class)));
        Button probeButton = findViewById(R.id.probe_button);
        probeButton.setOnClickListener(view ->
                startActivity(new Intent(this, CarLifeProbeActivity.class)));
        Button clientButton = findViewById(R.id.carlife_client);
        clientButton.setOnClickListener(view ->
                startActivity(new Intent(this, CarLifeClientActivity.class)));

        Button tpmsButton = findViewById(R.id.tpms_dashboard);
        tpmsButton.setOnClickListener(view ->
                startActivity(new Intent(this, TpmsDashboardActivity.class)));
        Button scanButton = findViewById(R.id.tpms_scan);
        scanButton.setOnClickListener(view ->
                startActivity(new Intent(this, TpmsScanActivity.class)));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
        }
    }
    private void ensureSession() {
        if (controllerFuture != null) {
            return;
        }
        setupSession();
    }

    private void setupSession() {
        SessionToken token = new SessionToken(
                this,
                new ComponentName(this, PlaybackService.class)
        );
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(this::onControllerConnected, mainHandler::post);
    }
    private void onControllerConnected() {
        try {
            controller = controllerFuture.get();
            controller.addListener(new Player.Listener() {
                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    updateStatus();
                }

                @Override
                public void onMediaMetadataChanged(MediaMetadata mediaMetadata) {
                    updateStatus();
                }
            });
            if (pendingUri != null) {
                Uri uri = pendingUri;
                pendingUri = null;
                playUri(uri);
                return;
            }
            updateStatus();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            showError(error.getMessage());
        } catch (ExecutionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            showError(cause.getMessage());
        }
    }

    private void openOfficialCarLife() {
        String[] packages = {
                "com.baidu.carlife",
                "com.baidu.carlife.hyundai",
                "com.baidu.carlife.vw"
        };
        for (String packageName : packages) {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                startActivity(launchIntent);
                statusView.setText(R.string.status_carlife_opened);
                return;
            }
        }

        try {
            startActivity(new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://carlife.baidu.com/carlife/android")
            ));
            statusView.setText(R.string.status_carlife_missing);
        } catch (ActivityNotFoundException error) {
            showError(error.getMessage());
        }
    }

    private void onAudioPicked(@Nullable Uri uri) {
        if (uri == null) {
            return;
        }

        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // Some document providers grant only one-time access.
        }

        if (controller != null) {
            playUri(uri);
        } else {
            pendingUri = uri;
            ensureSession();
            statusView.setText(R.string.status_connecting);
        }
    }

    private void playUri(Uri uri) {
        if (controller == null) {
            pendingUri = uri;
            return;
        }

        String title = queryDisplayName(uri);
        MediaItem item = new MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(getString(R.string.app_name))
                        .build())
                .build();

        controller.setMediaItem(item);
        controller.prepare();
        controller.play();
        statusView.setText(getString(R.string.status_picked, title));
    }
    private void updateStatus() {
        if (controller == null) {
            return;
        }
        CharSequence title = controller.getMediaMetadata().title;
        String displayTitle = title == null ? getString(R.string.no_track_selected) : title.toString();
        nowPlayingView.setText(getString(R.string.now_playing, displayTitle));
        playPauseButton.setText(controller.isPlaying() ? R.string.pause : R.string.play);

        if (controller.isPlaying()) {
            statusView.setText(getString(R.string.status_playing, displayTitle));
        } else if (controller.getMediaItemCount() > 0) {
            statusView.setText(getString(R.string.status_paused, displayTitle));
        } else {
            statusView.setText(R.string.status_ready);
        }
    }

    private String queryDisplayName(Uri uri) {
        ContentResolver resolver = getContentResolver();
        try (Cursor cursor = resolver.query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String value = cursor.getString(column);
                    if (value != null && !value.trim().isEmpty()) {
                        return value;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Fall back to the content URI.
        }
        String fallback = uri.getLastPathSegment();
        return fallback == null ? getString(R.string.app_name) : fallback;
    }

    private void showError(@Nullable String message) {
        if (statusView != null) {
            statusView.setText(getString(
                    R.string.status_error,
                    message == null ? "unknown" : message
            ));
        }
    }

    private void showStartupError(Throwable error) {
        String details = Log.getStackTraceString(error);
        ScrollView scrollView = new ScrollView(this);
        TextView errorView = new TextView(this);
        errorView.setText(getString(R.string.startup_error, details));
        errorView.setTextColor(getColor(R.color.text_primary));
        errorView.setTextSize(13f);
        errorView.setTextIsSelectable(true);
        errorView.setPadding(32, 32, 32, 32);
        errorView.setBackgroundColor(getColor(R.color.surface));
        scrollView.addView(errorView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        setContentView(scrollView);
    }

    @Override
    protected void onDestroy() {
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
        }
        controller = null;
        super.onDestroy();
    }
}