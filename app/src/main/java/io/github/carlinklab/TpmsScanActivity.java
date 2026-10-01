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

public final class TpmsScanActivity extends ComponentActivity implements WifiElm327Client.Listener {
    private final StringBuilder log = new StringBuilder();
    private final StringBuilder hits = new StringBuilder();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private WifiElm327Client client;
    private TextView statusView;
    private TextView logView;
    private EditText hostInput;
    private EditText portInput;
    private EditText headerInput;
    private EditText startDidInput;
    private EditText endDidInput;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tpms_scan);
        statusView = findViewById(R.id.scan_status);
        logView = findViewById(R.id.scan_log);
        hostInput = findViewById(R.id.scan_host);
        portInput = findViewById(R.id.scan_port);
        headerInput = findViewById(R.id.scan_headers);
        startDidInput = findViewById(R.id.scan_start_did);
        endDidInput = findViewById(R.id.scan_end_did);

        Button connect = findViewById(R.id.scan_connect);
        Button disconnect = findViewById(R.id.scan_disconnect);
        Button scan = findViewById(R.id.scan_start);
        Button stop = findViewById(R.id.scan_stop);
        Button copy = findViewById(R.id.scan_copy);

        client = new WifiElm327Client(this);
        connect.setOnClickListener(view -> connectAdapter());
        disconnect.setOnClickListener(view -> client.disconnect());
        scan.setOnClickListener(view -> startScan());
        stop.setOnClickListener(view -> client.stopScan());
        copy.setOnClickListener(view -> copyLog());

        append("WiFi ELM327 TPMS scanner ready");
        append("Connect the phone to the ELM327 WiFi network first.");
    }

    private void connectAdapter() {
        String host = hostInput.getText().toString().trim();
        int port;
        try {
            port = Integer.parseInt(portInput.getText().toString().trim());
        } catch (NumberFormatException error) {
            append("Invalid TCP port");
            return;
        }
        if (host.isEmpty() || port < 1 || port > 65535) {
            append("Invalid IP address or port");
            return;
        }
        client.connect(host, port);
    }

    private void startScan() {
        String headersText = headerInput.getText().toString().trim();
        if (headersText.isEmpty()) {
            append("Header list is empty");
            return;
        }
        String[] headers = headersText.split("[,;\\s]+");
        int startDid;
        int endDid;
        try {
            startDid = Integer.parseInt(startDidInput.getText().toString().trim(), 16);
            endDid = Integer.parseInt(endDidInput.getText().toString().trim(), 16);
        } catch (NumberFormatException error) {
            append("DID range is invalid");
            return;
        }
        if (startDid < 0 || endDid > 0xffff || startDid > endDid) {
            append("DID range must be between 0000 and FFFF");
            return;
        }
        hits.setLength(0);
        client.scan(headers, startDid, endDid);
    }
    @Override
    public void onLog(String message) {
        append(message);
    }

    @Override
    public void onConnectionState(boolean connected) {
        runOnUiThread(() -> statusView.setText(connected ? "ELM327 connected" : "ELM327 disconnected"));
    }

    @Override
    public void onProgress(int completed, int total) {
        runOnUiThread(() -> statusView.setText("Scanning " + completed + "/" + total));
    }

    @Override
    public void onResult(WifiElm327Client.ScanResult result) {
        runOnUiThread(() -> {
            hits.append(result.format()).append('\n');
            append("HIT " + result.format());
        });
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
        String output = hits.length() > 0
                ? "HITS:\n" + hits + "\nFULL LOG:\n" + log
                : log.toString();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("TPMS scan log", output));
        append("Log copied to clipboard");
    }

    @Override
    protected void onDestroy() {
        if (client != null) {
            client.shutdown();
        }
        super.onDestroy();
    }
}