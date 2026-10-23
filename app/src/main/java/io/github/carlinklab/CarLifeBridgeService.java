package io.github.carlinklab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
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
    public static final String ACTION_PROJECT = "io.github.carlinklab.action.PROJECT";
    public static final String ACTION_DASHBOARD = "io.github.carlinklab.action.DASHBOARD";
    public static final String ACTION_CAR_HOME = "io.github.carlinklab.action.CAR_HOME";
    private static final String ACTION_NAV_MAP = "io.github.carlinklab.action.NAV_MAP";
    private static final String ACTION_NAV_ROUTE = "io.github.carlinklab.action.NAV_ROUTE";
    private static final String ACTION_NAV_STOP = "io.github.carlinklab.action.NAV_STOP";
    private static final String EXTRA_PROJECTION_DATA = "projection_data";
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
    private volatile H264FrameEncoder videoEncoder;
    private ScreenProjectionEncoder projectionEncoder;
    private IndependentNavigationEncoder navigationEncoder;
    private NavigationSession navigationSession;
    private volatile boolean navigationMode;
    private boolean gpsActive;
    private volatile boolean projecting;
    private volatile boolean carHome;
    private volatile boolean videoRequested;
    private volatile long streamGeneration;
    private volatile String projectionStatus = "车机画面：胎压仪表";
    private volatile CarLifeClient.Size videoSize;
    private volatile TpmsSnapshot lastSnapshot;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private volatile boolean started;
    private static volatile CarLifeBridgeService active;

    public static boolean isRunning() { return active != null && active.started; }
    public static TpmsSnapshot latestSnapshot() { return active == null ? null : active.lastSnapshot; }
    public static boolean isProjecting() { return active != null && active.projecting; }
    public static boolean isCarHome() { return active != null && active.carHome; }
    public static boolean isNavigationMode() { return active != null && active.navigationMode; }
    public static boolean isNavigating() {
        return active != null && active.navigationSession != null && active.navigationSession.navigating;
    }
    public static String navigationStatus() {
        CarLifeBridgeService service = active;
        if (service == null || service.navigationSession == null) return "打开独立地图后，可连接电脑模拟车机查看";
        return service.navigationSession.status + (canProject() ? " · 车机视频已连接" : " · 等待车机视频连接");
    }
    public static void showNavigation(Context context) { sendNavigation(context, new Intent().setAction(ACTION_NAV_MAP)); }
    public static void startNavigation(Context context, String name, double latitude, double longitude, boolean demo) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || latitude < -90 || latitude > 90
                || longitude < -180 || longitude > 180) throw new IllegalArgumentException("无效的目的地坐标");
        sendNavigation(context, new Intent().setAction(ACTION_NAV_ROUTE).putExtra("name", name)
                .putExtra("latitude", latitude).putExtra("longitude", longitude).putExtra("demo", demo));
    }
    public static void stopNavigation(Context context) {
        if (active != null) context.startService(new Intent(context, CarLifeBridgeService.class).setAction(ACTION_NAV_STOP));
    }
    private static void sendNavigation(Context context, Intent intent) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("bridge", MODE_PRIVATE);
        start(context, prefs.getString("host", "192.168.0.10"), prefs.getInt("port", 35000));
        context.startService(intent.setClass(context, CarLifeBridgeService.class));
    }
    public static boolean canProject() {
        CarLifeBridgeService service = active;
        return service != null && service.started && service.videoRequested && service.videoSize != null
                && service.carLifeClient != null && service.carLifeClient.isVideoConnected();
    }
    public static String projectionStatus() {
        return active == null ? "请先连接车机，再开始导航投屏" : active.projectionStatus;
    }

    public static void project(Context context, Intent consentData) {
        context.startService(new Intent(context, CarLifeBridgeService.class).setAction(ACTION_PROJECT)
                .putExtra(EXTRA_PROJECTION_DATA, consentData));
    }

    public static void showDashboard(Context context) {
        if (active != null) context.startService(new Intent(context, CarLifeBridgeService.class)
                .setAction(ACTION_DASHBOARD));
    }

    public static void showCarHome(Context context) {
        if (active != null) context.startService(new Intent(context, CarLifeBridgeService.class)
                .setAction(ACTION_CAR_HOME));
    }

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
        carHome = getSharedPreferences("bridge", MODE_PRIVATE).getBoolean("car_home", false);
        projectionStatus = carHome ? "车机画面：独立车载桌面" : "车机画面：胎压仪表";
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_NAV_MAP.equals(action) || ACTION_NAV_ROUTE.equals(action)) {
            openNavigation(intent, ACTION_NAV_ROUTE.equals(action));
            return started ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_NAV_STOP.equals(action)) {
            endNavigation();
            if (started) updateForeground(projecting);
            return started ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_PROJECT.equals(action)) {
            Intent consent = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent.class)
                    : intent.getParcelableExtra(EXTRA_PROJECTION_DATA);
            startProjection(consent);
            if (!started) stopSelf();
            return started ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_DASHBOARD.equals(action) || ACTION_CAR_HOME.equals(action)) {
            navigationMode = false;
            endNavigation();
            carHome = ACTION_CAR_HOME.equals(action);
            getSharedPreferences("bridge", MODE_PRIVATE).edit().putBoolean("car_home", carHome).apply();
            restoreDashboard(carHome ? "车机画面：独立车载桌面" : "车机画面：胎压仪表");
            if (!started) stopSelf();
            return started ? START_STICKY : START_NOT_STICKY;
        }
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
        android.content.SharedPreferences prefs = getSharedPreferences("bridge", MODE_PRIVATE);
        if (intent == null) {
            host = prefs.getString("host", "192.168.0.10");
            port = prefs.getInt("port", 35000);
        }
        prefs.edit().putString("host", host).putInt("port", port).apply();
        updateForeground(projecting);
        startBridge(host, port);
        return START_STICKY;
    }

    private void startBridge(String host, int port) {
        if (started) {
            return;
        }
        started = true;
        active = this;
        acquireLocks();
        broadcastLog("后台服务启动：CarLife + ELM327");
        carLifeClient = new CarLifeClient(this, this);
        carLifeClient.start();
        FuelPriceClient.refresh(this);
        tpmsDataSource = new TucsonTpmsDataSource(this, host, port);
        tpmsDataSource.start(this);
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
    }

    private void stopBridge() {
        if (!started) {
            releaseLocks();
            return;
        }
        started = false;
        active = null;
        mainHandler.removeCallbacksAndMessages(null);
        videoRequested = false;
        stopVideoStream();
        navigationMode = false;
        gpsActive = false;
        if (navigationSession != null) { navigationSession.close(); navigationSession = null; }
        if (tpmsDataSource != null) {
            tpmsDataSource.stop();
            tpmsDataSource = null;
        }
        if (carLifeClient != null) {
            carLifeClient.shutdown();
            carLifeClient = null;
        }
        videoSize = null;
        lastSnapshot = null;
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
        PendingIntent dashboardPending = PendingIntent.getService(this, 12,
                new Intent(this, CarLifeBridgeService.class).setAction(ACTION_DASHBOARD),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_car)
                .setContentTitle(getString(R.string.bridge_notification_title))
                .setContentText(projecting ? "导航投屏运行中 · 胎压持续采集" : navigationMode
                        ? "独立导航运行中 · 胎压持续采集" : getString(R.string.bridge_notification_text))
                .setOngoing(true)
                .setContentIntent(openPending)
                .addAction(0, "恢复胎压仪表", dashboardPending)
                .addAction(0, getString(R.string.bridge_notification_stop), stopPending)
                .build();
    }
    @Override
    public void onLog(String message) {
        broadcastLog(message);
    }

    @Override
    public void onVideoEncoderInit(int width, int height, int frameRate) {
        mainHandler.post(() -> {
            if (!started) return;
            if (width <= 0 || height <= 0 || width > 4096 || height > 4096
                    || (width & 1) != 0 || (height & 1) != 0) {
                videoRequested = false;
                stopVideoStream();
                videoSize = null;
                broadcastLog("无效的车机视频尺寸：" + width + "x" + height);
                return;
            }
            boolean changed = videoSize != null && (videoSize.width != width || videoSize.height != height
                    || videoSize.frameRate != frameRate);
            if (changed) stopVideoStream();
            videoSize = new CarLifeClient.Size(width, height, frameRate);
            broadcastLog("VIDEO NEGOTIATED " + width + "x" + height + "@" + frameRate);
            if (videoRequested) startVideoStream();
        });
    }

    @Override
    public void onVideoStart() {
        broadcastLog("VIDEO START requested by HU");
        mainHandler.post(() -> {
            if (!started) return;
            videoRequested = true;
            if (projectionEncoder != null) projectionEncoder.requestKeyFrame();
            else if (navigationEncoder != null) navigationEncoder.requestKeyFrame();
            else startVideoStream();
        });
    }

    @Override
    public void onVideoStop() {
        broadcastLog("VIDEO STOP requested by HU");
        mainHandler.post(() -> {
            videoRequested = false;
            stopVideoStream();
        });
    }

    private void startVideoStream() {
        if (!started || !videoRequested || videoEncoder != null || projectionEncoder != null || navigationEncoder != null || videoSize == null) {
            return;
        }
        if (navigationMode) { startNavigationVideo(); return; }
        int fps = videoSize.frameRate <= 0 ? 10 : Math.min(videoSize.frameRate, 30);
        final long generation = ++streamGeneration;
        videoEncoder = new H264FrameEncoder(this, videoSize.width, videoSize.height, fps,
                new H264FrameEncoder.Listener() {
                    @Override
                    public void onFrame(byte[] h264Data, long timestampMillis) {
                        if (generation == streamGeneration && carLifeClient != null) {
                            carLifeClient.sendVideoFrame(h264Data, timestampMillis);
                        }
                    }

                    @Override
                    public void onLog(String message) {
                        broadcastLog(message);
                    }
                }, carHome);
        videoEncoder.updateSnapshot(lastSnapshot, lastSnapshot == null ? "waiting for TPMS data" : null);
        videoEncoder.start();
    }

    private void stopVideoStream() {
        ++streamGeneration;
        stopProjection();
        if (navigationEncoder != null) { navigationEncoder.close(); navigationEncoder = null; }
        if (videoEncoder != null) {
            videoEncoder.close();
            videoEncoder = null;
        }
    }

    private void updateForeground(boolean projection) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if (projection) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (gpsActive) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            startForeground(NOTIFICATION_ID, buildNotification(), type);
        } else startForeground(NOTIFICATION_ID, buildNotification());
    }

    private void startProjection(Intent consent) {
        if (projecting) {
            broadcastLog("导航投屏已经运行；更换共享应用前请先恢复胎压仪表");
            return;
        }
        if (!canProject() || consent == null) {
            broadcastLog("导航投屏未启动：请先连接车机并等待仪表画面出现，再重新授权");
            return;
        }
        navigationMode = false;
        endNavigation();
        stopVideoStream();
        final long generation = ++streamGeneration;
        MediaProjection projection = null;
        try {
            // Android 14 requires promotion AFTER consent and BEFORE getMediaProjection().
            updateForeground(true);
            projection = getSystemService(MediaProjectionManager.class)
                    .getMediaProjection(Activity.RESULT_OK, consent);
            if (projection == null) throw new IllegalStateException("屏幕共享授权无效");
            projectionEncoder = new ScreenProjectionEncoder(projection, new ScreenProjectionEncoder.Listener() {
                @Override public void onFrame(byte[] data, long timestampMillis) {
                    if (generation == streamGeneration && carLifeClient != null) {
                        carLifeClient.sendVideoFrame(data, timestampMillis);
                    }
                }
                @Override public void onLog(String message) { broadcastLog(message); }
                @Override public void onStopped(String reason) {
                    if (generation == streamGeneration) restoreDashboard(reason);
                }
            });
            projecting = true;
            projectionStatus = "车机画面：导航投屏中 · 请在手机上操作导航";
            projectionEncoder.start(this, videoSize.width, videoSize.height, videoSize.frameRate);
            updateForeground(true);
            broadcastLog(projectionStatus);
        } catch (Exception error) {
            if (projectionEncoder == null && projection != null) projection.stop();
            restoreDashboard("导航投屏启动失败：" + error.getMessage() + "；已恢复胎压仪表");
        }
    }

    private void stopProjection() {
        boolean wasProjecting = projecting;
        projecting = false;
        if (projectionEncoder != null) {
            projectionEncoder.close();
            projectionEncoder = null;
        }
        if (wasProjecting) projectionStatus = "导航投屏已结束，重新投屏需要再次授权";
        if (started) updateForeground(false);
    }

    private void restoreDashboard(String reason) {
        stopVideoStream();
        projectionStatus = reason;
        if (started) startVideoStream();
        broadcastLog(reason);
    }

    private void endNavigation() {
        gpsActive = false;
        if (navigationSession != null) navigationSession.stop();
    }

    private void openNavigation(Intent intent, boolean route) {
        if (!started) { stopSelf(); return; }
        try {
            if (navigationSession == null) navigationSession = new NavigationSession(this, new NavigationSession.Listener() {
                @Override public void onStatus(String text) { broadcastLog("导航：" + text); }
                @Override public void onFinished() { gpsActive = false; if (started) updateForeground(projecting); }
            });
            if (!navigationMode || projectionEncoder != null) {
                stopVideoStream();
                navigationMode = true;
            }
            if (route) {
                if (navigationSession.isCalculating()) {
                    broadcastLog("上一条路线仍在规划，请等待完成后再开始新的导航");
                    return;
                }
                gpsActive = !intent.getBooleanExtra("demo", false);
                updateForeground(false);
                navigationSession.route(intent.getStringExtra("name"), intent.getDoubleExtra("latitude", 0),
                        intent.getDoubleExtra("longitude", 0), !gpsActive);
            }
            projectionStatus = "车机画面：高德独立导航 · 手机可返回桌面";
            updateForeground(false);
            startVideoStream();
        } catch (Exception error) {
            navigationMode = false;
            endNavigation();
            restoreDashboard("独立导航启动失败：" + error.getClass().getSimpleName() + "；请检查导航服务授权和定位权限");
        }
    }

    private void startNavigationVideo() {
        if (navigationSession == null) { navigationMode = false; startVideoStream(); return; }
        final long generation = ++streamGeneration;
        navigationEncoder = new IndependentNavigationEncoder(navigationSession, new IndependentNavigationEncoder.Listener() {
            @Override public void onFrame(byte[] data, long timestampMillis) {
                if (generation == streamGeneration && carLifeClient != null) carLifeClient.sendVideoFrame(data, timestampMillis);
            }
            @Override public void onLog(String message) { broadcastLog(message); }
            @Override public void onStopped(String reason) {
                if (generation != streamGeneration) return;
                navigationMode = false;
                endNavigation();
                restoreDashboard(reason);
            }
        });
        try { navigationEncoder.start(this, videoSize.width, videoSize.height, videoSize.frameRate); }
        catch (Exception error) {
            navigationMode = false;
            endNavigation();
            restoreDashboard("独立导航画面创建失败：" + error.getClass().getSimpleName());
        }
    }

    @Override
    public void onSnapshot(TpmsSnapshot snapshot) {
        lastSnapshot = snapshot;
        H264FrameEncoder encoder = videoEncoder;
        if (encoder != null) encoder.updateSnapshot(snapshot, null);
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
