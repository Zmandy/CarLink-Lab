package io.github.carlinklab;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.amap.api.services.core.PoiItem;
import com.amap.api.services.poisearch.PoiResult;
import com.amap.api.services.poisearch.PoiSearch;

/** The phone is the destination controller; the car map has its own display and lifecycle. */
public final class NavigationActivity extends GlassActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView engineStatus;
    private EditText keyword, city;
    private LinearLayout results;
    private PoiItem selected;
    private int searchGeneration;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (engineStatus != null) engineStatus.setText(CarLifeBridgeService.navigationStatus());
            main.postDelayed(this, 1000);
        }
    };
    private final ActivityResultLauncher<String[]> locationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), granted -> {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startSelected();
                else status.setText("实时驾车导航需要精确位置权限，请在系统设置中授权后重试");
            });

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        body.setPadding(padding, padding, padding, padding);
        scroll.addView(body);
        setContentView(scroll);
        TextView title = text(body, "独立车载导航", 26);
        title.setTextColor(0xff182c43);
        text(body, "在手机选择目的地，地图与胎压显示到车机。导航声音暂由手机播放。", 15);
        status = text(body, "等待开始", 16);
        engineStatus = text(body, "", 14);
        button(body, "显示独立地图", v -> openMap());
        city = input(body, "城市（可留空）");
        keyword = input(body, "搜索目的地");
        button(body, "搜索", v -> search());
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        body.addView(results);
        button(body, "开始导航到选中地点", v -> {
            if (selected == null) { status.setText("请先搜索并选择目的地"); return; }
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startSelected();
            else locationPermission.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
        });
        button(body, "停止导航", v -> CarLifeBridgeService.stopNavigation(this));
        text(body, "测试功能：以下按钮使用北京固定路线，不使用实时定位。", 14);
        button(body, "模拟导航测试（北京示例）", v -> {
            if (initialize()) CarLifeBridgeService.startNavigation(this, "北京站", 39.90498, 116.42729, true);
        });
        button(body, "恢复胎压仪表", v -> CarLifeBridgeService.showDashboard(this));
        if (!NavigationSdk.hasConsent(this)) showDisclosure();
        else openMap();
    }

    private void showDisclosure() {
        new AlertDialog.Builder(this).setTitle("高德导航服务说明")
                .setMessage("本功能使用北京高德图强科技有限公司提供的高德地图、导航、定位和搜索 SDK，"
                        + "用于地图显示、目的地搜索、路线规划和导航。使用期间会向高德传输搜索词、网络与设备信息；"
                        + "授权定位后还会处理位置、GNSS、传感器及 Wi-Fi 等定位相关信息。"
                        + "详细范围与处理规则请阅读高德隐私政策。\n\n"
                        + "接受后才会初始化 SDK。精确定位权限仅在启动实时导航时申请。")
                .setNeutralButton("阅读隐私政策", (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://lbs.amap.com/pages/privacy/"))))
                .setNegativeButton("暂不使用", (d, w) -> finish())
                .setPositiveButton("同意并启用", (d, w) -> { NavigationSdk.accept(this); openMap(); })
                .setOnCancelListener(d -> finish()).show();
    }
    private boolean initialize() {
        if (!NavigationSdk.hasConsent(this)) { showDisclosure(); return false; }
        try { NavigationSdk.initialize(this); return true; }
        catch (Exception error) { status.setText(error.getMessage()); return false; }
    }
    private void openMap() {
        if (initialize()) CarLifeBridgeService.showNavigation(this);
    }
    private void startSelected() {
        if (selected == null || !initialize()) return;
        CarLifeBridgeService.startNavigation(this, selected.getTitle(), selected.getLatLonPoint().getLatitude(),
                selected.getLatLonPoint().getLongitude(), false);
    }
    private void search() {
        if (!initialize()) return;
        String term = keyword.getText().toString().trim();
        if (term.isEmpty()) { status.setText("请输入目的地名称"); return; }
        int generation = ++searchGeneration;
        selected = null;
        results.removeAllViews();
        status.setText("正在搜索目的地");
        try {
            PoiSearch.Query query = new PoiSearch.Query(term, "", city.getText().toString().trim());
            query.setPageSize(10);
            query.setPageNum(0);
            PoiSearch search = new PoiSearch(this, query);
            search.setOnPoiSearchListener(new PoiSearch.OnPoiSearchListener() {
                @Override public void onPoiSearched(PoiResult result, int code) {
                    if (isFinishing() || isDestroyed() || generation != searchGeneration) return;
                    if (code != 1000 || result == null) { status.setText("搜索失败，错误码 " + code); return; }
                    if (result.getPois().isEmpty()) { status.setText("没有找到地点，请补充城市或关键词"); return; }
                    status.setText("请选择目的地");
                    for (PoiItem item : result.getPois()) {
                        if (item.getLatLonPoint() == null) continue;
                        button(results, item.getTitle() + "\n" + item.getCityName() + item.getSnippet(), v -> {
                            selected = item;
                            for (int i = 0; i < results.getChildCount(); i++) results.getChildAt(i).setSelected(false);
                            v.setSelected(true);
                            status.setText("已选择：" + item.getTitle());
                        });
                    }
                }
                @Override public void onPoiItemSearched(PoiItem item, int code) { }
            });
            search.searchPOIAsyn();
        } catch (Exception error) { status.setText("搜索无法启动，请检查 Key 与网络"); }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(0, dp(8), 0, dp(8));
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
    private EditText input(LinearLayout parent, String hint) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setSingleLine(true);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
    private void button(LinearLayout parent, String label, View.OnClickListener action) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setMinHeight(dp(48));
        view.setOnClickListener(action);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
    }
    @Override protected void onResume() { super.onResume(); main.post(refresh); }
    @Override protected void onPause() { main.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { ++searchGeneration; main.removeCallbacksAndMessages(null); super.onDestroy(); }
}
