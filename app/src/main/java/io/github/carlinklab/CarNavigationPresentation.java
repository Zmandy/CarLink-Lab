package io.github.carlinklab;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageView;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.model.LatLng;
import com.amap.api.navi.AMapNaviView;
import com.amap.api.navi.AMapNaviViewOptions;
import com.amap.api.navi.model.NaviInfo;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** A real SDK map on an app-owned display. No phone screen capture is involved. */
final class CarNavigationPresentation extends Presentation {
    private final NavigationSession session;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AMapNaviView map;
    private TextView clock, guidance, detail, tires, mode;
    private ImageView turn;
    private final Runnable tick = new Runnable() {
        @Override public void run() { update(); main.postDelayed(this, 1_000); }
    };

    CarNavigationPresentation(Context context, Display display, NavigationSession session) {
        super(context, display);
        this.session = session;
        setCancelable(false);
        getWindow().setType(WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.rgb(12, 22, 35));
        LinearLayout side = new LinearLayout(getContext());
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(dp(18), dp(14), dp(14), dp(14));
        root.addView(side, new LinearLayout.LayoutParams(0, -1, .30f));
        clock = label(side, "", 25, 0xfff3f6fc);
        mode = label(side, "独立导航", 14, 0xff8bc9ff);
        turn = new ImageView(getContext());
        turn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        turn.setVisibility(android.view.View.GONE);
        side.addView(turn, new LinearLayout.LayoutParams(dp(64), dp(48)));
        guidance = label(side, "地图浏览", 23, 0xffffffff);
        guidance.setMaxLines(3);
        detail = label(side, "请在手机搜索目的地", 15, 0xffb1c6dd);
        detail.setMaxLines(4);
        LinearLayout.LayoutParams flexible = new LinearLayout.LayoutParams(-1, 0, 1);
        tires = new TextView(getContext());
        tires.setTextColor(0xffb1c6dd);
        tires.setTextSize(14);
        tires.setGravity(Gravity.BOTTOM);
        side.addView(tires, flexible);
        AMapNaviViewOptions options = new AMapNaviViewOptions();
        options.setLayoutVisible(false);
        options.setAutoDrawRoute(true);
        options.setAutoChangeZoom(true);
        options.setAutoNaviViewNightMode(true);
        map = new AMapNaviView(getContext(), options);
        root.addView(map, new LinearLayout.LayoutParams(0, -1, .70f));
        setContentView(root);
        map.onCreate(state);
        map.onResume();
        map.setOnMapLoadedListener(() -> {
            android.util.Log.i("CarLinkNavigation", "Independent SDK map loaded");
            if (!session.navigating) map.getMap().moveCamera(CameraUpdateFactory.newLatLngZoom(
                    new LatLng(39.90872, 116.39748), 12));
        });
        main.post(tick);
    }
    private TextView label(LinearLayout parent, String text, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, 0, 0, dp(16));
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
    private int dp(int value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }
    private void update() {
        clock.setText(new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date()));
        mode.setText(session.simulated && session.navigating ? "模拟导航 · 非实时位置" : "高德独立导航");
        NaviInfo info = session.guidance;
        if (session.navigating && info != null) {
            turn.setVisibility(info.getIconBitmap() == null ? android.view.View.GONE : android.view.View.VISIBLE);
            turn.setImageBitmap(info.getIconBitmap());
            String road = info.getNextRoadName();
            guidance.setText(distance(info.getCurStepRetainDistance()) + "后\n" + (road == null || road.isEmpty() ? "继续行驶" : road));
            detail.setText("剩余 " + distance(info.getPathRetainDistance()) + " · "
                    + Math.max(1, (info.getPathRetainTime() + 59) / 60) + "分钟\n"
                    + (!session.simulated && session.gpsWeak ? "GPS 信号弱" : session.destination));
        } else {
            turn.setVisibility(android.view.View.GONE);
            guidance.setText(session.navigating ? "导航启动中" : "地图浏览");
            detail.setText(session.status);
        }
        TpmsSnapshot data = CarLifeBridgeService.latestSnapshot();
        boolean valid = data != null && !data.stale && System.currentTimeMillis() - data.updatedAtMillis < 30_000;
        tires.setText("胎压 / kPa\n\n左前 " + pressure(valid ? data.frontLeftKpa : 0)
                + "   右前 " + pressure(valid ? data.frontRightKpa : 0)
                + "\n\n左后 " + pressure(valid ? data.rearLeftKpa : 0)
                + "   右后 " + pressure(valid ? data.rearRightKpa : 0));
    }
    private static String pressure(int value) { return value > 0 ? String.valueOf(value) : "--"; }
    private static String distance(int meters) {
        return meters >= 1000 ? String.format(Locale.CHINA, "%.1f公里", meters / 1000.0) : meters + "米";
    }
    void closeScene() {
        main.removeCallbacksAndMessages(null);
        if (map != null) { map.onPause(); map.onDestroy(); map = null; }
        dismiss();
    }
}
