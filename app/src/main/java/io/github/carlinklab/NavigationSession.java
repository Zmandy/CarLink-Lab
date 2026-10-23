package io.github.carlinklab;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.maps.model.LatLng;
import com.amap.api.navi.AMapNavi;
import com.amap.api.navi.enums.NaviType;
import com.amap.api.navi.model.AMapCalcRouteResult;
import com.amap.api.navi.model.NaviInfo;
import com.amap.api.navi.model.NaviPoi;
import com.amap.api.navi.model.AMapLaneInfo;
import com.amap.api.navi.model.AMapNaviCross;
import com.amap.api.navi.model.AMapModelCross;

/** Service-owned navigation engine: it does not belong to the phone Activity or video encoder. */
final class NavigationSession implements AutoCloseable {
    interface Listener { void onStatus(String text); void onFinished(); }
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AMapNavi navi;
    final NavigationVisuals visuals = new NavigationVisuals();
    private final NaviListenerAdapter callbacks = new NaviListenerAdapter() {
        @Override public void onInitNaviSuccess() { main.post(() -> { ready = true; if (pendingRoute != null) pendingRoute.run(); else setStatus("地图浏览 · 请在手机搜索目的地"); }); }
        @Override public void onInitNaviFailure() { main.post(() -> fail("导航初始化失败，请检查网络和 Key 权限")); }
        @Override public void onCalculateRouteSuccess(AMapCalcRouteResult result) { main.post(() -> routeReady(result)); }
        @Override public void onCalculateRouteFailure(AMapCalcRouteResult result) {
            main.post(() -> {
                if (!expectedResult(result)) return;
                calculating = false;
                if (routePending) fail("路线规划失败，错误码 " + result.getErrorCode());
            });
        }
        @Override public void onNaviInfoUpdate(NaviInfo info) { main.post(() -> { if (!closed) guidance = info; }); }
        @Override public void onArriveDestination() { main.post(() -> { if (!closed) { stop(); setStatus("已到达目的地"); listener.onFinished(); } }); }
        @Override public void onEndEmulatorNavi() { main.post(() -> { if (!closed) { stop(); setStatus("模拟导航已结束"); listener.onFinished(); } }); }
        @Override public void onGpsSignalWeak(boolean weak) { main.post(() -> gpsWeak = weak); }
        @Override public void showLaneInfo(AMapLaneInfo info) {
            main.post(() -> { if (!closed && navigating) { visuals.showLanes(info); logVisuals(); } });
        }
        @Override public void showLaneInfo(AMapLaneInfo[] info, byte[] background, byte[] recommended) {
            byte[] bg = background == null ? null : background.clone();
            byte[] fg = recommended == null ? null : recommended.clone();
            main.post(() -> { if (!closed && navigating) { visuals.showLanes(bg, fg); logVisuals(); } });
        }
        @Override public void hideLaneInfo() { main.post(() -> { if (!closed) visuals.hideLanes(); }); }
        @Override public void showCross(AMapNaviCross cross) {
            main.post(() -> { if (!closed && navigating) { visuals.showRealCross(cross); logVisuals(); } });
        }
        @Override public void hideCross() { main.post(() -> { if (!closed) visuals.hideRealCross(); }); }
        @Override public void showModeCross(AMapModelCross cross) {
            byte[] bytes = cross == null || cross.getPicBuf1() == null ? null : cross.getPicBuf1().clone();
            main.post(() -> { if (!closed && navigating) { visuals.showModelCross(bytes); logVisuals(); } });
        }
        @Override public void hideModeCross() { main.post(() -> { if (!closed) visuals.hideModelCross(); }); }
    };
    private boolean ready, closed, routePending;
    private boolean calculating;
    private volatile int routeRequestId = -1;
    private long requestGeneration;
    private Runnable pendingRoute;
    private AMapLocationClient location;
    volatile String status = "正在初始化高德导航";
    volatile String destination = "";
    volatile boolean simulated, navigating, gpsWeak;
    volatile NaviInfo guidance;

    NavigationSession(Context context, Listener listener) throws Exception {
        NavigationSdk.initialize(context);
        this.context = context.getApplicationContext();
        this.listener = listener;
        navi = AMapNavi.getInstance(this.context);
        navi.addAMapNaviListener(callbacks);
        navi.setUseInnerVoice(true);
    }

    void route(String name, double latitude, double longitude, boolean demo) {
        stop();
        destination = demo ? "北京站（模拟测试）" : name;
        simulated = demo;
        long generation = ++requestGeneration;
        pendingRoute = () -> {
            pendingRoute = null;
            if (closed || generation != requestGeneration) return;
            if (demo) {
                calculate(new LatLng(39.90872, 116.39748), new LatLng(39.90498, 116.42729));
                return;
            }
            setStatus("正在定位，请稍候");
            try {
                location = new AMapLocationClient(context);
                AMapLocationClientOption options = new AMapLocationClientOption();
                options.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
                options.setOnceLocation(true);
                options.setOnceLocationLatest(true);
                options.setNeedAddress(false);
                options.setHttpTimeOut(15_000);
                location.setLocationOption(options);
                location.setLocationListener(fix -> main.post(() -> {
                    if (closed || generation != requestGeneration) return;
                    closeLocation();
                    if (fix == null || fix.getErrorCode() != 0) { fail("定位失败，错误码 " + (fix == null ? -1 : fix.getErrorCode())); return; }
                    calculate(new LatLng(fix.getLatitude(), fix.getLongitude()), new LatLng(latitude, longitude));
                }));
                location.startLocation();
            } catch (Exception error) { fail("无法启动定位，请检查定位权限"); }
        };
        if (ready) pendingRoute.run();
        else setStatus("等待导航引擎初始化");
        main.postDelayed(() -> {
            if (!closed && generation == requestGeneration && !navigating) fail("导航准备超时，请检查网络后重试");
        }, 35_000);
    }

    private void calculate(LatLng start, LatLng end) {
        if (closed) return;
        setStatus(simulated ? "正在规划模拟测试路线" : "正在规划驾车路线");
        routePending = true;
        calculating = true;
        routeRequestId = -1;
        boolean accepted = navi.calculateDriveRoute(new NaviPoi("起点", start, ""),
                new NaviPoi(destination, end, ""), null, 0, id -> routeRequestId = id);
        if (!accepted) { calculating = false; fail("路线规划请求未受理，请重试"); }
    }

    private void routeReady(AMapCalcRouteResult result) {
        if (!expectedResult(result)) return;
        calculating = false;
        if (!routePending) return; // A stopped request must never start navigation later.
        routePending = false;
        int[] ids = result.getRouteid();
        if (ids != null && ids.length > 0) navi.selectRouteId(ids[0]);
        navi.setEmulatorNaviSpeed(60);
        navigating = navi.startNavi(simulated ? NaviType.EMULATOR : NaviType.GPS);
        if (!navigating) { fail("导航未能启动，请重试"); return; }
        setStatus(simulated ? "模拟导航 · 北京示例路线" : "实时导航中");
    }

    private boolean expectedResult(AMapCalcRouteResult result) {
        // This SDK also emits successful results with the optional request ID left at zero.
        return !closed && calculating && (routeRequestId < 0 || result.getRouteRequestId() == 0
                || result.getRouteRequestId() == routeRequestId);
    }
    boolean isCalculating() { return calculating; }
    private void logVisuals() { android.util.Log.i("CarLinkNavigation", "SDK guidance " + visuals.diagnostics()); }

    private void setStatus(String text) {
        if (closed) return;
        status = text;
        listener.onStatus(text);
    }
    private void fail(String text) {
        stop();
        setStatus(text);
        listener.onFinished();
    }
    void stop() {
        ++requestGeneration;
        pendingRoute = null;
        stopEngine();
        status = "地图浏览 · 请在手机搜索目的地";
    }
    private void stopEngine() {
        routePending = false;
        navigating = false;
        guidance = null;
        visuals.clear();
        gpsWeak = false;
        closeLocation();
        navi.stopNavi();
        navi.stopGPS();
    }
    private void closeLocation() {
        if (location != null) { location.stopLocation(); location.onDestroy(); location = null; }
    }
    @Override public void close() {
        if (closed) return;
        stop();
        closed = true;
        main.removeCallbacksAndMessages(null);
        navi.removeAMapNaviListener(callbacks);
        AMapNavi.destroy();
    }
}
