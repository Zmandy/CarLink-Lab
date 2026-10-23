package io.github.carlinklab;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CarLifeClientActivity extends GlassActivity implements CarLifeBridgeService.EventListener {
    private final StringBuilder log = new StringBuilder();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private TextView logView;
    private TextView statusView;
    private EditText titleInput;
    private EditText provinceInput;
    private EditText fuelGradeInput;
    private EditText hostInput;
    private EditText portInput;
    private TextView metricsView;
    private TextView projectionView;
    private Button projectionButton;
    private Button dashboardButton;
    private Button carHomeButton;
    private boolean projectionConsentPending;
    private final ActivityResultLauncher<Intent> projectionPermission = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                projectionConsentPending = false;
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    if (CarLifeBridgeService.canProject()) {
                        CarLifeBridgeService.project(this, result.getData());
                        append("已授权屏幕共享，正在切换车机画面；请打开导航应用");
                    } else append("车机视频连接已断开；连接恢复后请重新开始投屏");
                } else append("已取消屏幕共享，继续显示胎压仪表");
                updateProjectionControls();
            });
    private final android.os.Handler refreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshStatus = new Runnable() {
        @Override public void run() {
            updateProjectionControls();
            if (!CarLifeBridgeService.isRunning()) {
                statusView.setText("后台连接已停止");
                metricsView.setText("等待车辆连接");
                for (int id : new int[]{R.id.client_tire_fl, R.id.client_tire_fr, R.id.client_tire_rl, R.id.client_tire_rr}) {
                    String name = id == R.id.client_tire_fl ? "左前" : id == R.id.client_tire_fr ? "右前" : id == R.id.client_tire_rl ? "左后" : "右后";
                    ((TextView) findViewById(id)).setText(name + "\n等待胎压 / 胎温");
                }
            } else {
                TpmsSnapshot latest = CarLifeBridgeService.latestSnapshot();
                if (latest != null) onSnapshot(latest);
                else statusView.setText("后台连接运行中 · 等待车辆数据");
            }
            refreshHandler.postDelayed(this, 1_000L);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_carlife_client);
        metricsView = findViewById(R.id.client_metrics);
        projectionView = findViewById(R.id.client_projection_status);
        projectionButton = findViewById(R.id.client_project);
        dashboardButton = findViewById(R.id.client_dashboard);
        carHomeButton = findViewById(R.id.client_car_home);
        carHomeButton.setOnClickListener(view -> CarLifeBridgeService.showCarHome(this));
        findViewById(R.id.client_navigation).setOnClickListener(view -> startActivity(new Intent(this, NavigationActivity.class)));
        projectionButton.setOnClickListener(view -> {
            if (!CarLifeBridgeService.canProject()) {
                append("请先连接车机，等待胎压仪表显示后再开始投屏");
                return;
            }
            try {
                projectionConsentPending = true;
                projectionPermission.launch(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent());
            } catch (RuntimeException error) {
                projectionConsentPending = false;
                append("无法打开屏幕共享授权：" + error.getMessage());
            }
            updateProjectionControls();
        });
        dashboardButton.setOnClickListener(view -> CarLifeBridgeService.showDashboard(this));
        findViewById(R.id.client_toggle_log).setOnClickListener(view -> {
            android.view.View panel = findViewById(R.id.client_log_panel);
            panel.setVisibility(panel.getVisibility() == android.view.View.VISIBLE ? android.view.View.GONE : android.view.View.VISIBLE);
        });
        findViewById(R.id.client_battery).setOnClickListener(view -> {
            try {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                startActivity(new android.content.Intent(pm.isIgnoringBatteryOptimizations(getPackageName())
                        ? android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                        : android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (android.content.ActivityNotFoundException e) {
                startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + getPackageName())));
            }
        });
        logView = findViewById(R.id.client_log);
        statusView = findViewById(R.id.client_status);
        titleInput = findViewById(R.id.client_title);
        provinceInput = findViewById(R.id.client_province);
        fuelGradeInput = findViewById(R.id.client_fuel_grade);
        hostInput = findViewById(R.id.client_host);
        portInput = findViewById(R.id.client_port);
        android.content.SharedPreferences prefs = getSharedPreferences("bridge", MODE_PRIVATE);
        hostInput.setText(prefs.getString("host", "192.168.0.10"));
        portInput.setText(String.valueOf(prefs.getInt("port", 35000)));
        titleInput.setText(DashboardPreferences.getTitle(this));
        provinceInput.setText(DashboardPreferences.getProvince(this));
        fuelGradeInput.setText(DashboardPreferences.getFuelGrade(this));

        Button start = findViewById(R.id.client_start);
        Button stop = findViewById(R.id.client_stop);
        Button copy = findViewById(R.id.client_copy);

        start.setOnClickListener(view -> startBridge());
        stop.setOnClickListener(view -> CarLifeBridgeService.stop(this));
        copy.setOnClickListener(view -> copyLog());

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 900);
        }
        append("CarLife 后台客户端就绪");
        if (getIntent().getBooleanExtra("car_home", false)) {
            getSharedPreferences("bridge", MODE_PRIVATE).edit().putBoolean("car_home", true).apply();
        }
        if (getIntent().getBooleanExtra("auto_start", false)) {
            startBridge();
        }
        if (getIntent().getBooleanExtra("car_home", false)) {
            CarLifeBridgeService.showCarHome(this);
        }
        append("先确认 ELM327 WiFi 可连接，再点击开始。服务会保持 CarLife 与 OBD 后台连接。");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra("auto_start", false)) startBridge();
        if (intent.getBooleanExtra("car_home", false)) CarLifeBridgeService.showCarHome(this);
    }

    private void updateProjectionControls() {
        if (projectionView == null) return;
        boolean projecting = CarLifeBridgeService.isProjecting();
        projectionView.setText(CarLifeBridgeService.projectionStatus());
        projectionButton.setEnabled(!projectionConsentPending && !projecting && CarLifeBridgeService.canProject());
        dashboardButton.setEnabled(CarLifeBridgeService.isRunning() && (projecting || CarLifeBridgeService.isCarHome() || CarLifeBridgeService.isNavigationMode()));
        carHomeButton.setEnabled(CarLifeBridgeService.isRunning() && (projecting || !CarLifeBridgeService.isCarHome() || CarLifeBridgeService.isNavigationMode()));
    }

    private void startBridge() {
        String host = hostInput.getText().toString().trim();
        int port;
        try {
            port = Integer.parseInt(portInput.getText().toString().trim());
        } catch (NumberFormatException error) {
            append("ELM327 端口无效");
            return;
        }
        if (host.isEmpty() || port < 1 || port > 65535) {
            append("ELM327 地址无效");
            return;
        }
        DashboardPreferences.setTitle(this, titleInput.getText().toString());
        DashboardPreferences.setProvince(this, provinceInput.getText().toString());
        DashboardPreferences.setFuelGrade(this, fuelGradeInput.getText().toString());
        FuelPriceClient.refresh(this);
        CarLifeBridgeService.start(this, host, port);
    }

    @Override
    protected void onStart() {
        super.onStart();
        CarLifeBridgeService.addListener(this);
        statusView.setText(CarLifeBridgeService.isRunning() ? "后台连接运行中" : "后台连接已停止");
        TpmsSnapshot latest = CarLifeBridgeService.latestSnapshot();
        if (latest != null) onSnapshot(latest);
        refreshHandler.post(refreshStatus);
    }

    @Override
    protected void onStop() {
        refreshHandler.removeCallbacks(refreshStatus);
        CarLifeBridgeService.removeListener(this);
        super.onStop();
    }

    @Override
    public void onLog(String message) {
        append(message);
    }

    @Override
    public void onSnapshot(TpmsSnapshot snapshot) {
        runOnUiThread(() -> {
            boolean old = snapshot.stale || System.currentTimeMillis() - snapshot.updatedAtMillis > 30_000;
            statusView.setText(old ? "车辆数据已过期，等待重新连接" : "车辆数据已更新 · 后台运行中");
            String fuel = !snapshot.fuelAvailable || old ? "实时油耗  —"
                    : snapshot.speedKmh < 0 ? "实时油耗  —（等待车速）"
                    : snapshot.speedKmh > 5
                    ? String.format(Locale.CHINA, "实时油耗  %.1f L/100km", snapshot.instantL100Km)
                    : String.format(Locale.CHINA, "怠速燃油  %.2f L/h", snapshot.fuelRateLph);
            ((TextView) findViewById(R.id.client_tire_fl)).setText(tire("左前", snapshot.frontLeftKpa, snapshot.frontLeftTempC, old));
            ((TextView) findViewById(R.id.client_tire_fr)).setText(tire("右前", snapshot.frontRightKpa, snapshot.frontRightTempC, old));
            ((TextView) findViewById(R.id.client_tire_rl)).setText(tire("左后", snapshot.rearLeftKpa, snapshot.rearLeftTempC, old));
            ((TextView) findViewById(R.id.client_tire_rr)).setText(tire("右后", snapshot.rearRightKpa, snapshot.rearRightTempC, old));
            metricsView.setText(fuel);
        });
    }

    private String tire(String label, int pressure, int temp, boolean old) {
        return label + "\n" + (old || pressure <= 0 ? "—" : pressure + " kPa")
                + "\n" + (old || temp == Integer.MIN_VALUE ? "胎温待更新" : temp + "°C");
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateProjectionControls();
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        TextView battery = findViewById(R.id.client_battery_status);
        battery.setText((pm.isIgnoringBatteryOptimizations(getPackageName()) ? "系统省电限制已解除。" : "系统省电限制仍开启。")
                + "小米手机：在应用信息中把省电策略设为无限制，并允许后台自启动。开启连接后可锁屏；请保留运行通知。");
    }

    private void append(String message) {
        runOnUiThread(() -> {
            log.append('[')
                    .append(timeFormat.format(new Date()))
                    .append("] ")
                    .append(message)
                    .append('\n');
            if (log.length() > 24000) log.delete(0, log.length() - 16000);
            render();
        });
    }

    private void render() {
        logView.setText(log.toString());
        logView.post(() -> {
            int scrollAmount = logView.getLayout() == null
                    ? 0
                    : logView.getLayout().getLineTop(logView.getLineCount()) - logView.getHeight();
            logView.scrollTo(0, Math.max(scrollAmount, 0));
        });
    }

    private void copyLog() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("CarLife bridge log", log.toString()));
        append("日志已复制");
    }
}
