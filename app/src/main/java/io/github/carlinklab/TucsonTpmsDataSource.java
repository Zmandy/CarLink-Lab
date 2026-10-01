package io.github.carlinklab;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TucsonTpmsDataSource implements TpmsDataSource {
    private static final String TAG = "TucsonTpms";
    private static final long POLL_INTERVAL_MS = 3000L;
    private static final double GASOLINE_AFR = 14.7d;
    private static final double GASOLINE_GRAMS_PER_LITER = 745.0d;

    private final Context appContext;
    private final String host;
    private final int port;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Socket socket;
    private InputStream input;
    private OutputStream output;
    private Listener listener;
    private FuelData lastFuel = FuelData.empty();
    private long lastFuelSampleMs;
    private double totalFuelLiters;
    private double totalDistanceKm;

    public TucsonTpmsDataSource(Context context, String host, int port) {
        this.appContext = context.getApplicationContext();
        this.host = host;
        this.port = port;
    }

    @Override
    public void start(Listener listener) {
        this.listener = listener;
        if (!running.compareAndSet(false, true)) {
            return;
        }
        executor.execute(this::connectionLoop);
    }

    @Override
    public void stop() {
        running.set(false);
        closeQuietly();
    }

    @Override
    public String getName() {
        return "ELM327 7D6/2106";
    }

    private void connectionLoop() {
        while (running.get()) {
            try {
                status("连接 WiFi ELM327 " + host + ":" + port);
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 5000);
                socket.setSoTimeout(1000);
                input = socket.getInputStream();
                output = socket.getOutputStream();
                initializeAdapter();
                lastFuelSampleMs = 0L;
                status("TPMS 数据源已连接");
                while (running.get()) {
                    String response = sendCommand("2106", 7000);
                    TpmsSnapshot tireSnapshot = parse2106(response);
                    if (tireSnapshot != null) {
                        FuelData fuel = readFuelData();
                        TpmsSnapshot snapshot = new TpmsSnapshot(
                                tireSnapshot.frontLeftKpa, tireSnapshot.frontRightKpa,
                                tireSnapshot.rearLeftKpa, tireSnapshot.rearRightKpa,
                                tireSnapshot.frontLeftTempC, tireSnapshot.frontRightTempC,
                                tireSnapshot.rearLeftTempC, tireSnapshot.rearRightTempC,
                                tireSnapshot.updatedAtMillis, false, tireSnapshot.source,
                                fuel.speedKmh, fuel.fuelRateLph, fuel.instantL100Km,
                                fuel.averageL100Km, fuel.available, fuel.tripFuelLiters,
                                fuel.currentCostYuan, fuel.fuelPriceYuanPerLiter
                        );
                        listener.onSnapshot(snapshot);
                    } else {
                        status("未解析到 7D6 / 21 06 数据");
                    }
                    sleep(POLL_INTERVAL_MS);
                }
            } catch (Exception error) {
                status("TPMS 连接失败：" + error.getMessage());
            } finally {
                closeQuietly();
            }
            sleep(5000);
        }
    }

    private void initializeAdapter() throws Exception {
        sendCommand("ATZ", 5000);
        sendCommand("ATE0", 1500);
        sendCommand("ATL0", 1500);
        sendCommand("ATS1", 1500);
        sendCommand("ATH1", 1500);
        sendCommand("ATSP6", 1500);
        sendCommand("ATCAF1", 1500);
        sendCommand("ATCFC1", 1500);
        sendCommand("ATST32", 1500);
        sendCommand("ATSH7D6", 1500);
        sendCommand("ATCRA7DE", 1500);
    }

    private synchronized String sendCommand(String command, int timeoutMs) throws Exception {
        if (output == null || input == null) {
            throw new IllegalStateException("ELM327 not connected");
        }
        while (input.available() > 0) {
            input.read();
        }
        output.write((command + "\r").getBytes(StandardCharsets.US_ASCII));
        output.flush();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + timeoutMs;
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
                Thread.sleep(5L);
                if (buffer.size() > 0 && System.currentTimeMillis() - lastDataAt > 450L) {
                    break;
                }
            }
        }
        String response = buffer.toString(StandardCharsets.US_ASCII.name()).trim();
        Log.d(TAG, "> " + command + "\n" + response);
        return response;
    }

    private FuelData readFuelData() {
        int speedKmh = lastFuel.speedKmh;
        double mafGps = lastFuel.mafGps;
        double fuelRateLph = lastFuel.fuelRateLph;
        boolean fresh = false;
        boolean rateFresh = false;

        try {
            sendCommand("ATSH7E0", 1500);
            sendCommand("ATCRA7E8", 1500);

            byte[] speed = parseStandardPayload(sendCommand("010D", 3500), 0x41, 0x0D);
            if (speed != null && speed.length >= 3) {
                speedKmh = speed[2] & 0xFF;
                fresh = true;
            }

            byte[] maf = parseStandardPayload(sendCommand("0110", 3500), 0x41, 0x10);
            if (maf != null && maf.length >= 4) {
                mafGps = (((maf[2] & 0xFF) << 8) | (maf[3] & 0xFF)) / 100.0d;
                fuelRateLph = mafGps / GASOLINE_AFR / GASOLINE_GRAMS_PER_LITER * 3600.0d;
                rateFresh = true;
                fresh = true;
            }

            byte[] directRate = parseStandardPayload(sendCommand("015E", 3500), 0x41, 0x5E);
            if (directRate != null && directRate.length >= 4) {
                fuelRateLph = (((directRate[2] & 0xFF) << 8) | (directRate[3] & 0xFF)) / 20.0d;
                rateFresh = true;
                fresh = true;
            }

            if (!rateFresh) {
                fuelRateLph = 0.0d;
            }
            if (!fresh && fuelRateLph <= 0.0d) {
                fuelRateLph = 0.0d;
                speedKmh = 0;
            }

            double instant = speedKmh > 5 && fuelRateLph > 0.0d
                    ? fuelRateLph / speedKmh * 100.0d
                    : 0.0d;
            long now = System.currentTimeMillis();
            if (lastFuelSampleMs > 0L && fresh) {
                double hours = Math.min(30_000.0d, now - lastFuelSampleMs) / 3_600_000.0d;
                totalFuelLiters += Math.max(0.0d, fuelRateLph) * hours;
                totalDistanceKm += Math.max(0, speedKmh) * hours;
            }
            if (fresh) {
                lastFuelSampleMs = now;
            }
            double average = totalDistanceKm > 0.05d
                    ? totalFuelLiters / totalDistanceKm * 100.0d
                    : 0.0d;
            double price = DashboardPreferences.getFuelPrice(appContext);
            double currentCost = totalFuelLiters * Math.max(0.0d, price);
            FuelData result = new FuelData(
                    speedKmh, mafGps, fuelRateLph, instant, average,
                    totalFuelLiters, currentCost, price, rateFresh
            );
            if (fresh) {
                lastFuel = result;
            }
            return result;
        } catch (Exception error) {
            return lastFuel;
        } finally {
            try {
                sendCommand("ATSH7D6", 1500);
                sendCommand("ATCRA7DE", 1500);
            } catch (Exception ignored) {
                // The next TPMS poll will restore the header.
            }
        }
    }

    private static byte[] parseStandardPayload(String raw, int service, int pid) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String upper = raw.toUpperCase(Locale.US);
        if (upper.contains("NO DATA") || upper.contains("CAN ERROR")
                || upper.contains("BUS ERROR") || upper.contains("DATA ERROR")) {
            return null;
        }
        for (String line : raw.split("\\r\\n|\\r|\\n")) {
            String hex = normalizeHex(line);
            if (hex.length() < 4) {
                continue;
            }
            int headerChars = detectHeaderChars(hex);
            if (headerChars < 0) {
                continue;
            }
            String body = hex.substring(headerChars);
            if (body.length() >= 4) {
                int first = Integer.parseInt(body.substring(0, 2), 16);
                int second = Integer.parseInt(body.substring(2, 4), 16);
                if (first == service && second == pid) {
                    return hexToBytes(body);
                }
                if ((first & 0xF0) == 0 && body.length() >= 6) {
                    int payloadService = Integer.parseInt(body.substring(2, 4), 16);
                    int payloadPid = Integer.parseInt(body.substring(4, 6), 16);
                    if (payloadService == service && payloadPid == pid) {
                        return hexToBytes(body.substring(2));
                    }
                }
            }
        }
        return null;
    }

    private TpmsSnapshot parse2106(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String upper = raw.toUpperCase(Locale.US);
        if (upper.contains("NO DATA") || upper.contains("CAN ERROR")
                || upper.contains("BUS ERROR") || upper.contains("DATA ERROR")) {
            return null;
        }

        Map<String, Assembler> messages = new LinkedHashMap<>();
        String[] lines = raw.split("\\r\\n|\\r|\\n");
        for (String line : lines) {
            String hex = normalizeHex(line);
            if (hex.length() < 2 || hex.startsWith("7F")) {
                continue;
            }
            int headerChars = detectHeaderChars(hex);
            if (headerChars < 0) {
                continue;
            }
            String header = headerChars == 0 ? "NOHEADER" : hex.substring(0, headerChars);
            parseIsoTpLine(header, hex.substring(headerChars), messages);
        }

        List<Assembler> candidates = new ArrayList<>(messages.values());
        candidates.sort(Comparator.comparingInt(item -> "7DE".equals(item.header) ? 0 : 1));
        for (Assembler assembler : candidates) {
            byte[] payload = assembler.completeOrBest();
            if (payload.length < 34 || (payload[0] & 0xFF) != 0x61 || (payload[1] & 0xFF) != 0x06) {
                continue;
            }
            int[] starts = {2, 10, 18, 26};
            int[] pressures = new int[4];
            int[] temperatures = new int[4];
            boolean valid = true;
            for (int index = 0; index < 4; index++) {
                int start = starts[index];
                int pressureRaw = payload[start + 4] & 0xFF;
                int temperatureRaw = payload[start + 5] & 0xFF;
                if (pressureRaw == 0 || pressureRaw == 0xFF || temperatureRaw == 0 || temperatureRaw == 0xFF) {
                    valid = false;
                    break;
                }
                pressures[index] = (int) Math.round(pressureRaw * 1.72368925d);
                temperatures[index] = temperatureRaw - 40;
            }
            if (valid) {
                return new TpmsSnapshot(
                        pressures[0], pressures[1], pressures[2], pressures[3],
                        temperatures[0], temperatures[1], temperatures[2], temperatures[3],
                        System.currentTimeMillis(), false, getName()
                );
            }
        }
        return null;
    }

    private static int detectHeaderChars(String hex) {
        if (hex.length() >= 5) {
            try {
                int header = Integer.parseInt(hex.substring(0, 3), 16);
                if (header >= 0x700 && header <= 0x7FF) {
                    return 3;
                }
            } catch (NumberFormatException ignored) {
                // Try without header.
            }
        }
        if (hex.startsWith("61") || hex.startsWith("7F")
                || hex.startsWith("41") || hex.startsWith("7E")) {
            return 0;
        }
        return -1;
    }

    private static void parseIsoTpLine(String header, String body, Map<String, Assembler> messages) {
        if (body.length() < 2) {
            return;
        }
        int first = Integer.parseInt(body.substring(0, 2), 16);
        int type = first >> 4;
        Assembler assembler = messages.computeIfAbsent(header, Assembler::new);
        if (type == 0) {
            int length = first & 0x0F;
            int end = Math.min(body.length(), 2 + length * 2);
            assembler.addSingle(hexToBytes(body.substring(2, end)));
        } else if (type == 1) {
            if (body.length() < 4) {
                return;
            }
            int second = Integer.parseInt(body.substring(2, 4), 16);
            assembler.begin(((first & 0x0F) << 8) | second);
            assembler.append(hexToBytes(body.substring(4)));
        } else if (type == 2) {
            assembler.append(hexToBytes(body.substring(2)));
        } else {
            assembler.addSingle(hexToBytes(body));
        }
    }

    private static String normalizeHex(String line) {
        return line == null ? "" : line.toUpperCase(Locale.US).replaceAll("[^0-9A-F]", "");
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() < 2) {
            return new byte[0];
        }
        int usable = hex.length() - (hex.length() % 2);
        byte[] result = new byte[usable / 2];
        for (int index = 0; index < usable; index += 2) {
            result[index / 2] = (byte) Integer.parseInt(hex.substring(index, index + 2), 16);
        }
        return result;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeQuietly() {
        try {
            if (input != null) {
                input.close();
            }
        } catch (Exception ignored) {
            // Best effort.
        }
        try {
            if (output != null) {
                output.close();
            }
        } catch (Exception ignored) {
            // Best effort.
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
            // Best effort.
        }
        input = null;
        output = null;
        socket = null;
    }

    private void status(String message) {
        Log.d(TAG, message);
        if (listener != null) {
            listener.onStatus(message);
        }
    }

    private static final class Assembler {
        final String header;
        int expectedLength = -1;
        byte[] single;
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        Assembler(String header) {
            this.header = header;
        }

        void addSingle(byte[] data) {
            if (single == null || data.length > single.length) {
                single = data;
            }
        }

        void begin(int length) {
            expectedLength = length;
            buffer.reset();
        }

        void append(byte[] data) {
            buffer.write(data, 0, data.length);
        }

        byte[] completeOrBest() {
            if (expectedLength > 0) {
                byte[] all = buffer.toByteArray();
                int take = Math.min(expectedLength, all.length);
                if (take > 0) {
                    byte[] result = new byte[take];
                    System.arraycopy(all, 0, result, 0, take);
                    return result;
                }
            }
            return single == null ? new byte[0] : single;
        }
    }

    private static final class FuelData {
        final int speedKmh;
        final double mafGps;
        final double fuelRateLph;
        final double instantL100Km;
        final double averageL100Km;
        final double tripFuelLiters;
        final double currentCostYuan;
        final double fuelPriceYuanPerLiter;
        final boolean available;

        FuelData(int speedKmh, double mafGps, double fuelRateLph,
                 double instantL100Km, double averageL100Km,
                 double tripFuelLiters, double currentCostYuan,
                 double fuelPriceYuanPerLiter, boolean available) {
            this.speedKmh = speedKmh;
            this.mafGps = mafGps;
            this.fuelRateLph = fuelRateLph;
            this.instantL100Km = instantL100Km;
            this.averageL100Km = averageL100Km;
            this.tripFuelLiters = tripFuelLiters;
            this.currentCostYuan = currentCostYuan;
            this.fuelPriceYuanPerLiter = fuelPriceYuanPerLiter;
            this.available = available;
        }

        static FuelData empty() {
            return new FuelData(0, 0, 0, 0, 0, 0, 0, 0, false);
        }
    }
}
