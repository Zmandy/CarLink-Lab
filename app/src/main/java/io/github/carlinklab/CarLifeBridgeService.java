package io.github.carlinklab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public final class CarLifeBridgeService extends Service implements CarLifeClient.Listener, TpmsDataSource.Listener {
    public static final String ACTION_START = "io.github.carlinklab.action.START_BRIDGE";
    public static final String ACTION_STOP = "io.github.carlinklab.action.STOP_BRIDGE";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    private static final String CHANNEL_ID = "carlife_bridge";
    private static final int NOTIFICATION_ID = 4101;
    private static final Set<EventListener> EVENTS = new CopyOnWriteArraySet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface EventListener {
        void onLog(String message);
        void onSnapshot(TpmsSnapshot snapshot);
    }

    private CarLifeClient carLifeClient;
    private TucsonTpmsDataSource tpmsDataSource;
    private H264FrameEncoder videoEncoder;
    private CarLifeClient.Size videoSize;
    private TpmsSnapshot lastSnapshot;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private boolean started;

    public static void start(Context context, String host, int port) {
        Intent intent = new Intent(context, CarLifeBridgeService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, CarLifeBridgeService.class).setAction(ACTION_STOP);
        context.startService(intent);
    }

    public static void addListener(EventListener listener) {
        EVENTS.add(listener);
    }

    public static void removeListener(EventListener listener) {
        EVENTS.remove(listener);
    }
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopBridge();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        String host = intent == null ? "192.168.0.10" : intent.getStringExtra(EXTRA_HOST);
        int port = intent == null ? 35000 : intent.getIntExtra(EXTRA_PORT, 35000);
        if (host == null || host.trim().isEmpty()) {
            host = "192.168.0.10";
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        startBridge(host, port);
        return START_STICKY;
    }

    private void startBridge(String host, int port) {
        if (started) {
            return;
        }
        started = true;
        acquireLocks();
        broadcastLog("后台服务启动：CarLife + ELM327");
        carLifeClient = new CarLifeClient(this, this);
        carLifeClient.start();
        FuelPriceClient.refresh(this);
        tpmsDataSource = new TucsonTpmsDataSource(this, host, port);
        tpmsDataSource.start(this);
    }

    private void stopBridge() {
        if (!started) {
            releaseLocks();
            return;
        }
        started = false;
        if (videoEncoder != null) {
            videoEncoder.close();
            videoEncoder = null;
        }
        if (tpmsDataSource != null) {
            tpmsDataSource.stop();
            tpmsDataSource = null;
        }
        if (carLifeClient != null) {
            carLifeClient.shutdown();
            carLifeClient = null;
        }
        releaseLocks();
        broadcastLog("后台服务已停止");
    }

    @Override
    public void onDestroy() {
        stopBridge();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.bridge_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.bridge_notification_channel_desc));
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, CarLifeClientActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                this,
                10,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        Intent stopIntent = new Intent(this, CarLifeBridgeService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this,
                11,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(getString(R.string.bridge_notification_title))
                .setContentText(getString(R.string.bridge_notification_text))
                .setOngoing(true)
                .setContentIntent(openPending)
                .addAction(0, getString(R.string.bridge_notification_stop), stopPending)
                .build();
    }
    @Override
    public void onLog(String message) {
        broadcastLog(message);
    }

    @Override
    public void onVideoEncoderInit(int width, int height, int frameRate) {
        videoSize = new CarLifeClient.Size(width, height, frameRate);
        broadcastLog("VIDEO NEGOTIATED " + width + "x" + height + "@" + frameRate);
    }

    @Override
    public void onVideoStart() {
        broadcastLog("VIDEO START requested by HU");
        mainHandler.post(this::startVideoStream);
    }

    @Override
    public void onVideoStop() {
        broadcastLog("VIDEO STOP requested by HU");
        mainHandler.post(this::stopVideoStream);
    }

    private void startVideoStream() {
        if (videoEncoder != null || videoSize == null) {
            return;
        }
        int fps = videoSize.frameRate <= 0 ? 10 : Math.min(videoSize.frameRate, 30);
        videoEncoder = new H264FrameEncoder(this, videoSize.width, videoSize.height, fps,
                new H264FrameEncoder.Listener() {
                    @Override
                    public void onFrame(byte[] h264Data, long timestampMillis) {
                        if (carLifeClient != null) {
                            carLifeClient.sendVideoFrame(h264Data, timestampMillis);
                        }
                    }

                    @Override
                    public void onLog(String message) {
                        broadcastLog(message);
                    }
                });
        videoEncoder.updateSnapshot(lastSnapshot, lastSnapshot == null ? "waiting for TPMS data" : null);
        videoEncoder.start();
    }

    private void stopVideoStream() {
        if (videoEncoder != null) {
            videoEncoder.close();
            videoEncoder = null;
        }
    }

    @Override
    public void onSnapshot(TpmsSnapshot snapshot) {
        lastSnapshot = snapshot;
        if (videoEncoder != null) {
            videoEncoder.updateSnapshot(snapshot, null);
        }
        for (EventListener event : EVENTS) {
            event.onSnapshot(snapshot);
        }
    }

    @Override
    public void onStatus(String status) {
        broadcastLog(status);
    }

    private void broadcastLog(String message) {
        android.util.Log.d("CarLifeBridge", message);
        for (EventListener event : EVENTS) {
            event.onLog(message);
        }
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "CarLinkLab::CarLifeBridge"
            );
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifiManager != null) {
            wifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "CarLinkLab::WifiElm327"
            );
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        wifiLock = null;
    }
}
