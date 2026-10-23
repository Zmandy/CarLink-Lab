package io.github.carlinklab;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import com.amap.api.services.core.PoiItem;
import com.amap.api.services.poisearch.PoiResult;
import com.amap.api.services.poisearch.PoiSearch;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Uses only a fixed public POI and an explicitly simulated route; never grants privacy/location consent. */
public final class NavigationAuditInstrumentation extends Instrumentation {
    private volatile Activity activity;
    private volatile int code = -1, found;
    private long holdMillis;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        holdMillis = 0;
        if (args != null && args.containsKey("hold_ms")) holdMillis = Long.parseLong(args.getString("hold_ms", "0"));
        start();
    }
    @Override public void callActivityOnResume(Activity page) { super.callActivityOnResume(page); activity = page; }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!NavigationSdk.hasConsent(getTargetContext())) throw new IllegalStateException("User privacy consent required");
            android.os.ParcelFileDescriptor launch = getUiAutomation().executeShellCommand(
                    "am start -W -n io.github.carlinklab/.NavigationActivity");
            launch.close();
            waitFor(() -> activity instanceof NavigationActivity, 10_000, "navigation activity");
            runOnMainSync(() -> CarLifeBridgeService.showNavigation(activity));
            waitFor(CarLifeBridgeService::isNavigationMode, 15_000, "navigation mode");
            result.putBoolean("independent_navigation_mode", true);
            CountDownLatch searchDone = new CountDownLatch(1);
            runOnMainSync(() -> {
                try {
                    PoiSearch.Query query = new PoiSearch.Query("北京站", "", "北京");
                    query.setPageSize(5);
                    PoiSearch search = new PoiSearch(activity, query);
                    search.setOnPoiSearchListener(new PoiSearch.OnPoiSearchListener() {
                        @Override public void onPoiSearched(PoiResult pois, int value) {
                            code = value;
                            found = pois == null ? 0 : pois.getPois().size();
                            searchDone.countDown();
                        }
                        @Override public void onPoiItemSearched(PoiItem item, int value) {}
                    });
                    search.searchPOIAsyn();
                } catch (Exception error) { code = -2; searchDone.countDown(); }
            });
            if (!searchDone.await(30, TimeUnit.SECONDS) || code != 1000 || found < 1)
                throw new IllegalStateException("POI search failed, code=" + code + ", count=" + found);
            result.putInt("search_code", code);
            result.putInt("search_count", found);
            sendStatus(1, result);
            runOnMainSync(() -> {
                try { com.amap.api.navi.AMapNavi.getInstance(activity).addAMapNaviListener(new NaviListenerAdapter() {
                    @Override public void onInitNaviSuccess() { report("engine_init_success"); }
                    @Override public void onCalculateRouteSuccess(com.amap.api.navi.model.AMapCalcRouteResult route) {
                        report("route_success_id=" + route.getRouteRequestId());
                    }
                    @Override public void onCalculateRouteFailure(com.amap.api.navi.model.AMapCalcRouteResult route) {
                        report("route_failure_code=" + route.getErrorCode() + ", id=" + route.getRouteRequestId());
                    }
                    @Override public void onCalculateRouteSuccess(int[] ids) { report("legacy_route_success"); }
                    @Override public void onCalculateRouteFailure(int value) { report("legacy_route_failure=" + value); }
                    @Override public void onStartNavi(int type) { report("engine_start_type=" + type); }
                }); } catch (Exception error) { throw new RuntimeException(error); }
            });
            runOnMainSync(() -> CarLifeBridgeService.startNavigation(activity, "北京站", 39.90498, 116.42729, true));
            for (int attempt = 0; attempt < 40 && !CarLifeBridgeService.isNavigating(); attempt++) {
                SystemClock.sleep(1000);
                if (attempt % 5 == 0) report(CarLifeBridgeService.navigationStatus());
            }
            if (!CarLifeBridgeService.isNavigating()) throw new IllegalStateException("simulated navigation failed");
            result.putBoolean("simulated_navigation", true);
            sendStatus(2, result);
            SystemClock.sleep(Math.min(Math.max(holdMillis, 0), 120_000));
            finish(Activity.RESULT_OK, result);
        } catch (Exception error) {
            result.putString("error", error.toString());
            result.putString("state", CarLifeBridgeService.navigationStatus());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void report(String text) { Bundle state = new Bundle(); state.putString("navigation", text); sendStatus(3, state); }
    private interface Condition { boolean ready(); }
    private static void waitFor(Condition condition, long milliseconds, String label) {
        long deadline = SystemClock.uptimeMillis() + milliseconds;
        while (!condition.ready() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
        if (!condition.ready()) throw new IllegalStateException("Timed out: " + label);
    }
}
