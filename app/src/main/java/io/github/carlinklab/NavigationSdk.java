package io.github.carlinklab;

import android.content.Context;
import android.content.pm.PackageManager;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.services.core.ServiceSettings;

/** SDK access is gated by the disclosure in NavigationActivity, independently of location permission. */
final class NavigationSdk {
    private static boolean initialized;
    static boolean hasConsent(Context context) {
        return context.getSharedPreferences("navigation", Context.MODE_PRIVATE).getBoolean("amap_privacy_v1", false);
    }
    static void accept(Context context) {
        context.getSharedPreferences("navigation", Context.MODE_PRIVATE).edit().putBoolean("amap_privacy_v1", true).apply();
    }
    static synchronized void initialize(Context context) throws Exception {
        if (!hasConsent(context)) throw new IllegalStateException("请先阅读并确认导航服务说明");
        if (initialized) return;
        Context app = context.getApplicationContext();
        String key = app.getPackageManager().getApplicationInfo(app.getPackageName(),
                PackageManager.GET_META_DATA).metaData.getString("com.amap.api.v2.apikey", "");
        if (key.trim().isEmpty()) throw new IllegalStateException("当前安装包尚未配置高德 Key");
        MapsInitializer.updatePrivacyShow(app, true, true);
        MapsInitializer.updatePrivacyAgree(app, true);
        AMapLocationClient.updatePrivacyShow(app, true, true);
        AMapLocationClient.updatePrivacyAgree(app, true);
        ServiceSettings.updatePrivacyShow(app, true, true);
        ServiceSettings.updatePrivacyAgree(app, true);
        initialized = true;
    }
}
