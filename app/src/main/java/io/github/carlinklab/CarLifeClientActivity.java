package io.github.carlinklab;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CarLifeClientActivity extends ComponentActivity implements CarLifeBridgeService.EventListener {
    private final StringBuilder log = new StringBuilder();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private TextView logView;
    private TextView statusView;
    private EditText titleInput;
    private EditText provinceInput;
    private EditText fuelGradeInput;
    private EditText hostInput;
    private EditText portInput;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_carlife_client);
        logView = findViewById(R.id.client_log);
        statusView = findViewById(R.id.client_status);
        titleInput = findViewById(R.id.client_title);
        provinceInput = findViewById(R.id.client_province);
        fuelGradeInput = findViewById(R.id.client_fuel_grade);
        hostInput = findViewById(R.id.client_host);
        portInput = findViewById(R.id.client_port);
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
        if (getIntent().getBooleanExtra("auto_start", false)) {
            startBridge();
        }
        append("先确认 ELM327 WiFi 可连接，再点击开始。服务会保持 CarLife 与 OBD 后台连接。");
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
    }

    @Override
    protected void onStop() {
        CarLifeBridgeService.removeListener(this);
        super.onStop();
    }

    @Override
    public void onLog(String message) {
        append(message);
    }

    @Override
    public void onSnapshot(TpmsSnapshot snapshot) {
        runOnUiThread(() -> statusView.setText(
                "TPMS " + snapshot.frontLeftKpa + "/" + snapshot.frontRightKpa
                        + "/" + snapshot.rearLeftKpa + "/" + snapshot.rearRightKpa + " kPa"
        ));
    }

    private void append(String message) {
        runOnUiThread(() -> {
            log.append('[')
                    .append(timeFormat.format(new Date()))
                    .append("] ")
                    .append(message)
                    .append('\n');
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
