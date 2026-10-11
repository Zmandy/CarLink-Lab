package io.github.carlinklab;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;

public final class TpmsDashboardActivity extends GlassActivity implements TpmsDataSource.Listener {
    private static final String TAG = "TpmsDashboard";
    private WebViewDashboardRenderer renderer;
    private TpmsDataSource dataSource;
    private TextView statusView;
    private ImageView previewView;
    private Bitmap previewBitmap;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tpms_dashboard);
        statusView = findViewById(R.id.tpms_status);
        previewView = findViewById(R.id.tpms_preview);
        previewView.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            android.util.DisplayMetrics display = getResources().getDisplayMetrics();
            boolean export = getIntent().getBooleanExtra("export_preview", false);
            int width = export ? 800 : Math.max(240, Math.round(previewView.getWidth() / display.density));
            int height = export ? 480 : Math.max(240, Math.round(previewView.getHeight() / display.density));
            if (!export && width > height) {
                width = 800;
                height = 480;
            }
            float outputScale = export ? 1f : display.density;
            previewBitmap = Bitmap.createBitmap(Math.round(width * outputScale),
                    Math.round(height * outputScale), Bitmap.Config.ARGB_8888);
            renderer = new WebViewDashboardRenderer(this, width, height, outputScale);
            renderer.copyTo(previewBitmap);
            previewView.setImageBitmap(previewBitmap);
            dataSource = new MockTpmsDataSource();
            dataSource.start(this);
            exportPreviewIfRequested();
        });
    }

    @Override
    public void onSnapshot(TpmsSnapshot snapshot) {
        runOnUiThread(() -> {
            if (renderer == null || isFinishing() || isDestroyed()) return;
            renderer.update(snapshot, null);
            previewView.postDelayed(() -> {
                if (renderer == null || isFinishing() || isDestroyed()) return;
                renderer.copyTo(previewBitmap);
                previewView.setImageBitmap(previewBitmap);
            }, 150L);
            statusView.setText(snapshot.source + (snapshot.stale ? " · 数据过期" : ""));
        });
    }

    @Override
    public void onStatus(String status) {
        runOnUiThread(() -> statusView.setText(status));
    }

    private void exportPreviewIfRequested() {
        if (!getIntent().getBooleanExtra("export_preview", false)) {
            return;
        }
        previewView.postDelayed(() -> {
            try {
                File directory = new File(getExternalFilesDir(null), "preview");
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new IllegalStateException("Cannot create preview directory");
                }
                File output = new File(directory, "tpms_webview_preview.png");
                try (FileOutputStream stream = new FileOutputStream(output)) {
                    renderer.copyTo(previewBitmap);
                    previewBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                }
                Log.d(TAG, "Preview exported: " + output.getAbsolutePath());
            } catch (Exception error) {
                Log.w(TAG, "Preview export failed", error);
            }
        }, 5_000L);
    }

    @Override
    protected void onDestroy() {
        if (dataSource != null) {
            dataSource.stop();
        }
        if (renderer != null) {
            renderer.close();
        }
        super.onDestroy();
    }
}
