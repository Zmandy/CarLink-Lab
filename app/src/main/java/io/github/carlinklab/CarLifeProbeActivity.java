package io.github.carlinklab;

import android.content.ClipData;
import android.content.ActivityNotFoundException;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CarLifeProbeActivity extends ComponentActivity {
    private static final int[] CARLIFE_PORTS = {7240, 8240, 9240, 9241, 9242, 9340};

    private final StringBuilder log = new StringBuilder();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<ServerSocket> serverSockets = new ArrayList<>();
    private final List<Socket> clientSockets = new ArrayList<>();
    private ExecutorService executor;
    private TextView logView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_carlife_probe);

        logView = findViewById(R.id.probe_log);
        Button start = findViewById(R.id.probe_start);
        Button stop = findViewById(R.id.probe_stop);
        Button copy = findViewById(R.id.probe_copy);
        Button clear = findViewById(R.id.probe_clear);
        Button appInfo = findViewById(R.id.probe_app_info);

        start.setOnClickListener(view -> startProbe());
        stop.setOnClickListener(view -> stopProbe());
        copy.setOnClickListener(view -> copyLog());
        appInfo.setOnClickListener(view -> openCarLifeAppInfo());
        clear.setOnClickListener(view -> {
            synchronized (log) {
                log.setLength(0);
            }
            render();
        });

        append("CarLife TCP probe ready");
        append("Ports: 7240, 8240, 9240, 9241, 9242, 9340");
        append("Force-stop the official CarLife app before starting.");
    }
    private void openCarLifeAppInfo() {
        String[] packages = {
                "com.baidu.carlife",
                "com.baidu.carlife.hyundai",
                "com.baidu.carlife.vw"
        };
        for (String packageName : packages) {
            try {
                Intent intent = new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + packageName)
                );
                startActivity(intent);
                return;
            } catch (ActivityNotFoundException ignored) {
                // Try the next package name.
            }
        }

        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_SETTINGS));
        } catch (ActivityNotFoundException error) {
            append("Cannot open app settings: " + error.getMessage());
        }
    }

    private void startProbe() {
        if (!running.compareAndSet(false, true)) {
            append("Probe is already running");
            return;
        }

        executor = Executors.newCachedThreadPool();
        append("START requested");
        for (int port : CARLIFE_PORTS) {
            executor.execute(() -> listenOnPort(port));
        }
    }

    private void stopProbe() {
        if (!running.compareAndSet(true, false)) {
            append("Probe is not running");
            return;
        }

        synchronized (serverSockets) {
            for (ServerSocket socket : serverSockets) {
                closeQuietly(socket);
            }
            serverSockets.clear();
        }
        synchronized (clientSockets) {
            for (Socket socket : clientSockets) {
                closeQuietly(socket);
            }
            clientSockets.clear();
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        append("STOP requested");
    }

    private void listenOnPort(int port) {
        ServerSocket serverSocket = null;
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            synchronized (serverSockets) {
                serverSockets.add(serverSocket);
            }
            append("LISTEN 127.0.0.1:" + port);

            while (running.get()) {
                Socket client = serverSocket.accept();
                synchronized (clientSockets) {
                    clientSockets.add(client);
                }
                final ServerSocket currentServer = serverSocket;
                executor.execute(() -> readConnection(client, port, currentServer));
            }
        } catch (IOException error) {
            if (running.get()) {
                append("BIND/CONNECT error on " + port + ": " + error.getMessage());
                append("If address is in use, force-stop official CarLife first.");
            }
        } finally {
            closeQuietly(serverSocket);
            synchronized (serverSockets) {
                serverSockets.remove(serverSocket);
            }
        }
    }

    private void readConnection(Socket socket, int port, ServerSocket owner) {
        String peer = String.valueOf(socket.getRemoteSocketAddress());
        append("CONNECT port=" + port + " peer=" + peer);
        boolean received = false;
        try {
            socket.setSoTimeout(15000);
            InputStream input = socket.getInputStream();
            byte[] buffer = new byte[4096];
            while (running.get()) {
                int count;
                try {
                    count = input.read(buffer);
                } catch (SocketTimeoutException timeout) {
                    if (!received) {
                        append("NO DATA port=" + port + " after 15s");
                    }
                    continue;
                }
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    continue;
                }
                received = true;
                byte[] data = new byte[count];
                System.arraycopy(buffer, 0, data, 0, count);
                append("RX port=" + port + " bytes=" + count);
                append(hexDump(data));
            }
        } catch (IOException error) {
            if (running.get()) {
                append("READ error port=" + port + ": " + error.getMessage());
            }
        } finally {
            append("CLOSE port=" + port);
            closeQuietly(socket);
            synchronized (clientSockets) {
                clientSockets.remove(socket);
            }
            if (!owner.isClosed() && !running.get()) {
                closeQuietly(owner);
            }
        }
    }
    private String hexDump(byte[] data) {
        StringBuilder text = new StringBuilder();
        int limit = Math.min(data.length, 256);
        for (int offset = 0; offset < limit; offset += 16) {
            text.append(String.format(Locale.US, "%04X  ", offset));
            int rowEnd = Math.min(offset + 16, limit);
            for (int index = offset; index < offset + 16; index++) {
                if (index < rowEnd) {
                    text.append(String.format(Locale.US, "%02X ", data[index]));
                } else {
                    text.append("   ");
                }
            }
            text.append(" |");
            for (int index = offset; index < rowEnd; index++) {
                int value = data[index] & 0xFF;
                char character = value >= 32 && value <= 126 ? (char) value : '.';
                text.append(character);
            }
            text.append("|\n");
        }
        if (data.length > limit) {
            text.append("... ").append(data.length - limit).append(" more bytes\n");
        }
        return text.toString().trim();
    }
    private void append(String message) {
        synchronized (log) {
            log.append('[')
                    .append(timeFormat.format(new Date()))
                    .append("] ")
                    .append(message)
                    .append('\n');
        }
        runOnUiThread(this::render);
    }

    private void render() {
        if (logView == null) {
            return;
        }
        String snapshot;
        synchronized (log) {
            snapshot = log.toString();
        }
        logView.setText(snapshot);
        logView.post(() -> {
            int scrollAmount = logView.getLayout() == null
                    ? 0
                    : logView.getLayout().getLineTop(logView.getLineCount()) - logView.getHeight();
            logView.scrollTo(0, Math.max(scrollAmount, 0));
        });
    }

    private void copyLog() {
        String snapshot;
        synchronized (log) {
            snapshot = log.toString();
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("CarLife TCP probe", snapshot));
        append("Log copied to clipboard");
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Best effort shutdown.
        }
    }

    @Override
    protected void onDestroy() {
        stopProbe();
        super.onDestroy();
    }
}