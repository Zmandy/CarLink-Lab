package io.github.carlinklab;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Elm327BluetoothClient {
    private static final String TAG = "Elm327Client";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public interface Listener {
        void onLog(String message);
        void onConnectionState(boolean connected);
        void onProgress(int completed, int total);
        void onResult(ScanResult result);
    }

    public static final class ScanResult {
        public final String header;
        public final String did;
        public final String response;

        ScanResult(String header, String did, String response) {
            this.header = header;
            this.did = did;
            this.response = response;
        }

        public String format() {
            return header + " 22" + did + " -> " + response;
        }
    }

    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private BluetoothSocket socket;
    private InputStream input;
    private OutputStream output;

    public Elm327BluetoothClient(Listener listener) {
        this.listener = listener;
    }

    public void connect(BluetoothDevice device) {
        executor.execute(() -> {
            disconnectInternal();
            try {
                log("Connecting to " + safeName(device));
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
                input = socket.getInputStream();
                output = socket.getOutputStream();
                connected.set(true);
                listener.onConnectionState(true);
                log("Bluetooth SPP connected");
                initializeAdapter();
            } catch (Exception error) {
                log("Connect failed: " + error);
                disconnectInternal();
            }
        });
    }

    public void disconnect() {
        executor.execute(this::disconnectInternal);
    }

    public void stopScan() {
        scanning.set(false);
        log("Scan stop requested");
    }

    public void scan(String[] headers, int startDid, int endDid) {
        executor.execute(() -> scanInternal(headers, startDid, endDid));
    }
    private void initializeAdapter() {
        sendAndLog("ATZ", 5000);
        sendAndLog("ATE0", 1500);
        sendAndLog("ATL0", 1500);
        sendAndLog("ATS1", 1500);
        sendAndLog("ATH1", 1500);
        sendAndLog("ATSP6", 1500);
        sendAndLog("ATCFC1", 1500);
        sendAndLog("ATST10", 1500);
        sendAndLog("ATDP", 1500);
    }

    private void scanInternal(String[] headers, int startDid, int endDid) {
        if (!connected.get()) {
            log("Adapter is not connected");
            return;
        }
        if (!scanning.compareAndSet(false, true)) {
            log("Scan is already running");
            return;
        }

        int start = Math.max(0, Math.min(startDid, 0xffff));
        int end = Math.max(start, Math.min(endDid, 0xffff));
        int didCount = end - start + 1;
        int total = headers.length * didCount;
        int completed = 0;
        log("Scanning " + total + " requests");
        try {
            for (String header : headers) {
                if (!scanning.get()) {
                    break;
                }
                String normalizedHeader = header.replaceAll("\\s+", "").toUpperCase(Locale.US);
                sendAndLog("ATSH" + normalizedHeader, 1500);
                for (int did = start; did <= end && scanning.get(); did++) {
                    String didText = String.format(Locale.US, "%04X", did);
                    String response = sendCommand("22" + didText, 1200);
                    if (isPositiveResponse(response, didText)) {
                        String cleaned = cleanResponse(response);
                        ScanResult result = new ScanResult(normalizedHeader, didText, cleaned);
                        listener.onResult(result);
                        log("HIT " + result.format());
                    }
                    completed++;
                    if (completed % 16 == 0 || completed == total) {
                        listener.onProgress(completed, total);
                    }
                }
            }
        } finally {
            scanning.set(false);
            listener.onProgress(completed, total);
            log("Scan finished: " + completed + "/" + total);
        }
    }

    private boolean isPositiveResponse(String response, String did) {
        String compact = compactHex(response);
        return compact.contains("62" + did);
    }

    private void sendAndLog(String command, int timeoutMillis) {
        String response = sendCommand(command, timeoutMillis);
        log("> " + command + "\n" + cleanResponse(response));
    }

    private synchronized String sendCommand(String command, int timeoutMillis) {
        if (!connected.get() || output == null || input == null) {
            return "";
        }
        try {
            while (input.available() > 0) {
                input.read();
            }
            output.write((command + "\r").getBytes(StandardCharsets.US_ASCII));
            output.flush();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            long deadline = System.currentTimeMillis() + timeoutMillis;
            long lastDataAt = System.currentTimeMillis();
            while (System.currentTimeMillis() < deadline) {
                if (input.available() > 0) {
                    int value = input.read();
                    if (value < 0) {
                        break;
                    }
                    buffer.write(value);
                    lastDataAt = System.currentTimeMillis();
                    if (value == '>') {
                        break;
                    }
                } else {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (buffer.size() > 0 && System.currentTimeMillis() - lastDataAt > 250) {
                        break;
                    }
                }
            }
            return buffer.toString(StandardCharsets.US_ASCII.name());
        } catch (Exception error) {
            log("Command failed " + command + ": " + error);
            disconnectInternal();
            return "";
        }
    }
    private void disconnectInternal() {
        scanning.set(false);
        connected.set(false);
        try {
            if (input != null) {
                input.close();
            }
        } catch (IOException ignored) {
            // Best effort.
        }
        try {
            if (output != null) {
                output.close();
            }
        } catch (IOException ignored) {
            // Best effort.
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // Best effort.
        }
        input = null;
        output = null;
        socket = null;
        listener.onConnectionState(false);
    }

    public void shutdown() {
        scanning.set(false);
        executor.execute(this::disconnectInternal);
        executor.shutdown();
    }

    private static String cleanResponse(String response) {
        if (response == null) {
            return "";
        }
        return response.replace("\r", "\n")
                .replace(">", "")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{2,}", "\n")
                .trim();
    }

    private static String compactHex(String response) {
        if (response == null) {
            return "";
        }
        return response.toUpperCase(Locale.US)
                .replace("SEARCHING...", "")
                .replaceAll("[^0-9A-F]", "");
    }

    private static String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null ? device.getAddress() : name;
        } catch (SecurityException error) {
            return device.getAddress();
        }
    }

    private static void logStatic(String message) {
        Log.d(TAG, message);
    }

    private void log(String message) {
        Log.d(TAG, message);
        listener.onLog(message);
    }
}