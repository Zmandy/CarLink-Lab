package io.github.carlinklab;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CarLifeClient {
    private static final String TAG = "CarLifeClient";
    public static final int CMD_PORT = 7240;
    public static final int VIDEO_PORT = 8240;
    public static final int MEDIA_PORT = 9240;
    public static final int TTS_PORT = 9241;
    public static final int VR_PORT = 9242;
    public static final int TOUCH_PORT = 9340;

    public static final int MSG_CMD_HU_PROTOCOL_VERSION = 0x00018001;
    public static final int MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS = 0x00010002;
    public static final int MSG_CMD_HU_INFO = 0x00018003;
    public static final int MSG_CMD_MD_INFO = 0x00010004;
    public static final int MSG_CMD_VIDEO_ENCODER_INIT = 0x00018007;
    public static final int MSG_CMD_VIDEO_ENCODER_INIT_DONE = 0x00010008;
    public static final int MSG_CMD_VIDEO_ENCODER_START = 0x00018009;
    public static final int MSG_CMD_VIDEO_ENCODER_PAUSE = 0x0001800A;
    public static final int MSG_CMD_VIDEO_ENCODER_RESET = 0x0001800B;
    public static final int MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE = 0x0001800C;
    public static final int MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE = 0x0001000D;
    public static final int MSG_VIDEO_DATA = 0x00020001;

    public interface Listener {
        void onLog(String message);
        void onVideoEncoderInit(int width, int height, int frameRate);
        void onVideoStart();
        void onVideoStop();
    }

    private final int[] ports = {
            CMD_PORT, VIDEO_PORT, MEDIA_PORT, TTS_PORT, VR_PORT, TOUCH_PORT
    };
    private final Context appContext;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<ServerSocket> serverSockets = new ArrayList<>();
    private final Map<Integer, Socket> channelSockets = new ConcurrentHashMap<>();
    private final Map<Integer, OutputStream> channelOutputs = new ConcurrentHashMap<>();
    private int negotiatedWidth;
    private int negotiatedHeight;
    private int negotiatedFrameRate;

    public CarLifeClient(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        log("Starting CarLife client listeners");
        for (int port : ports) {
            executor.execute(() -> listenOnPort(port));
        }
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log("Stopping CarLife client");
        synchronized (serverSockets) {
            for (ServerSocket socket : serverSockets) {
                closeQuietly(socket);
            }
            serverSockets.clear();
        }
        for (Socket socket : channelSockets.values()) {
            closeQuietly(socket);
        }
        channelSockets.clear();
        channelOutputs.clear();
    }

    public void shutdown() {
        stop();
        executor.shutdownNow();
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isCommandConnected() {
        return channelOutputs.containsKey(CMD_PORT);
    }

    public boolean isVideoConnected() {
        return channelOutputs.containsKey(VIDEO_PORT);
    }

    @Nullable
    public Size getNegotiatedVideoSize() {
        if (negotiatedWidth <= 0 || negotiatedHeight <= 0) {
            return null;
        }
        return new Size(negotiatedWidth, negotiatedHeight, negotiatedFrameRate);
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
            log("LISTEN 127.0.0.1:" + port);
            while (running.get()) {
                Socket socket = serverSocket.accept();
                channelSockets.put(port, socket);
                channelOutputs.put(port, socket.getOutputStream());
                log("CONNECT port=" + port + " peer=" + socket.getRemoteSocketAddress());
                executor.execute(() -> handleChannel(socket, port));
            }
        } catch (SocketException error) {
            if (running.get()) {
                log("SOCKET error on " + port + ": " + error.getMessage());
            }
        } catch (IOException error) {
            if (running.get()) {
                log("BIND error on " + port + ": " + error.getMessage());
            }
        } finally {
            closeQuietly(serverSocket);
            synchronized (serverSockets) {
                serverSockets.remove(serverSocket);
            }
        }
    }

    private void handleChannel(Socket socket, int port) {
        try {
            InputStream input = socket.getInputStream();
            if (port == CMD_PORT) {
                readCommandChannel(input);
            } else {
                byte[] buffer = new byte[8192];
                while (running.get()) {
                    int count = input.read(buffer);
                    if (count < 0) {
                        break;
                    }
                    if (count > 0) {
                        log("RX port=" + port + " bytes=" + count);
                    }
                }
            }
        } catch (IOException error) {
            if (running.get()) {
                log("READ error port=" + port + ": " + error.getMessage());
            }
        } finally {
            channelSockets.remove(port);
            channelOutputs.remove(port);
            closeQuietly(socket);
            log("CLOSE port=" + port);
            if (port == VIDEO_PORT) {
                listener.onVideoStop();
            }
        }
    }
    private void readCommandChannel(InputStream input) throws IOException {
        while (running.get()) {
            byte[] header = new byte[8];
            readFully(input, header);
            int payloadLength = readU16(header, 0);
            int serviceType = readU32(header, 4);
            if (payloadLength < 0 || payloadLength > 512 * 1024) {
                throw new IOException("Invalid command payload length: " + payloadLength);
            }
            byte[] payload = new byte[payloadLength];
            readFully(input, payload);
            log("CMD type=0x" + hex32(serviceType) + " len=" + payloadLength);
            handleCommand(serviceType, payload);
        }
    }

    private void handleCommand(int serviceType, byte[] payload) {
        switch (serviceType) {
            case MSG_CMD_HU_PROTOCOL_VERSION:
                handleProtocolVersion(payload);
                break;
            case MSG_CMD_HU_INFO:
                log("HU info: " + printable(payload));
                sendMdInfo();
                break;
            case MSG_CMD_VIDEO_ENCODER_INIT:
                handleVideoEncoderInit(payload);
                break;
            case MSG_CMD_VIDEO_ENCODER_START:
                log("HU requested video encoder start");
                listener.onVideoStart();
                break;
            case MSG_CMD_VIDEO_ENCODER_PAUSE:
            case MSG_CMD_VIDEO_ENCODER_RESET:
                log("HU requested video stop");
                listener.onVideoStop();
                break;
            case MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE:
                log("HU requested frame-rate change: " + printable(payload));
                sendCommand(MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE, payload);
                break;
            default:
                log("Unknown command payload=" + hex(payload, 128));
                break;
        }
    }

    private void handleProtocolVersion(byte[] payload) {
        ParsedMessage parsed = parseVarintFields(payload);
        int major = (int) parsed.values.getOrDefault(1, 0L).longValue();
        int minor = (int) parsed.values.getOrDefault(2, 0L).longValue();
        log("HU protocol version " + major + "." + minor);
        byte[] match = new byte[]{0x08, 0x01};
        sendCommand(MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS, match);
    }

    private void handleVideoEncoderInit(byte[] payload) {
        ParsedMessage parsed = parseVarintFields(payload);
        negotiatedWidth = (int) parsed.values.getOrDefault(1, 0L).longValue();
        negotiatedHeight = (int) parsed.values.getOrDefault(2, 0L).longValue();
        negotiatedFrameRate = (int) parsed.values.getOrDefault(3, 0L).longValue();
        log("Video init " + negotiatedWidth + "x" + negotiatedHeight + "@" + negotiatedFrameRate);
        sendCommand(MSG_CMD_VIDEO_ENCODER_INIT_DONE, payload);
        listener.onVideoEncoderInit(negotiatedWidth, negotiatedHeight, negotiatedFrameRate);
    }

    private void sendMdInfo() {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeStringField(payload, 1, "Android");
        writeStringField(payload, 13, "CarLink Lab");
        writeStringField(payload, 14, Build.MODEL == null ? "Android" : Build.MODEL);
        writeVarintField(payload, 21, Build.VERSION.SDK_INT);
        sendCommand(MSG_CMD_MD_INFO, payload.toByteArray());
    }

    public void sendCommand(int serviceType, byte[] payload) {
        OutputStream output = channelOutputs.get(CMD_PORT);
        if (output == null) {
            log("Command channel is not connected");
            return;
        }
        try {
            synchronized (output) {
                byte[] header = new byte[8];
                writeU16(header, 0, payload.length);
                writeU32(header, 4, serviceType);
                output.write(header);
                output.write(payload);
                output.flush();
            }
            log("TX cmd type=0x" + hex32(serviceType) + " len=" + payload.length);
        } catch (IOException error) {
            log("Command send failed: " + error.getMessage());
        }
    }

    public boolean sendVideoFrame(byte[] data, long timestampMillis) {
        OutputStream output = channelOutputs.get(VIDEO_PORT);
        if (output == null || data == null || data.length == 0) {
            return false;
        }
        try {
            synchronized (output) {
                byte[] header = new byte[12];
                writeU32(header, 0, data.length);
                writeU32(header, 4, (int) (timestampMillis & 0xffffffffL));
                writeU32(header, 8, MSG_VIDEO_DATA);
                output.write(header);
                output.write(data);
                output.flush();
            }
            return true;
        } catch (IOException error) {
            log("Video send failed: " + error.getMessage());
            return false;
        }
    }
    private static ParsedMessage parseVarintFields(byte[] data) {
        ParsedMessage result = new ParsedMessage();
        int offset = 0;
        while (offset < data.length) {
            long tag = 0;
            int shift = 0;
            while (offset < data.length) {
                int value = data[offset++] & 0xff;
                tag |= (long) (value & 0x7f) << shift;
                if ((value & 0x80) == 0) {
                    break;
                }
                shift += 7;
                if (shift > 63) {
                    throw new IllegalArgumentException("Invalid protobuf tag");
                }
            }
            int field = (int) (tag >>> 3);
            int wireType = (int) (tag & 0x07);
            if (wireType == 0) {
                long value = 0;
                shift = 0;
                while (offset < data.length) {
                    int current = data[offset++] & 0xff;
                    value |= (long) (current & 0x7f) << shift;
                    if ((current & 0x80) == 0) {
                        break;
                    }
                    shift += 7;
                }
                result.values.put(field, value);
            } else if (wireType == 2) {
                long length = 0;
                shift = 0;
                while (offset < data.length) {
                    int current = data[offset++] & 0xff;
                    length |= (long) (current & 0x7f) << shift;
                    if ((current & 0x80) == 0) {
                        break;
                    }
                    shift += 7;
                }
                int byteLength = (int) length;
                if (byteLength < 0 || offset + byteLength > data.length) {
                    break;
                }
                byte[] bytes = new byte[byteLength];
                System.arraycopy(data, offset, bytes, 0, byteLength);
                result.bytes.put(field, bytes);
                offset += byteLength;
            } else {
                break;
            }
        }
        return result;
    }

    private static void writeStringField(ByteArrayOutputStream output, int field, String value) {
        if (value == null) {
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarint(output, (field << 3) | 2);
        writeVarint(output, bytes.length);
        output.write(bytes, 0, bytes.length);
    }

    private static void writeVarintField(ByteArrayOutputStream output, int field, long value) {
        writeVarint(output, field << 3);
        writeVarint(output, value);
    }

    private static void writeVarint(ByteArrayOutputStream output, long value) {
        long current = value;
        while ((current & ~0x7fL) != 0) {
            output.write((int) ((current & 0x7f) | 0x80));
            current >>>= 7;
        }
        output.write((int) current);
    }

    private static void readFully(InputStream input, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int count = input.read(target, offset, target.length - offset);
            if (count < 0) {
                throw new IOException("Unexpected end of stream");
            }
            offset += count;
        }
    }

    private static int readU16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static int readU32(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }

    private static void writeU16(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 8) & 0xff);
        data[offset + 1] = (byte) (value & 0xff);
    }

    private static void writeU32(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 24) & 0xff);
        data[offset + 1] = (byte) ((value >> 16) & 0xff);
        data[offset + 2] = (byte) ((value >> 8) & 0xff);
        data[offset + 3] = (byte) (value & 0xff);
    }

    private static String hex32(int value) {
        return String.format(java.util.Locale.US, "%08X", value);
    }

    private static String hex(byte[] data, int limit) {
        StringBuilder result = new StringBuilder();
        int count = Math.min(data.length, limit);
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                result.append(' ');
            }
            result.append(String.format(java.util.Locale.US, "%02X", data[index] & 0xff));
        }
        if (data.length > limit) {
            result.append(" ...");
        }
        return result.toString();
    }

    private static String printable(byte[] data) {
        StringBuilder result = new StringBuilder();
        for (byte value : data) {
            int current = value & 0xff;
            if (current >= 32 && current <= 126) {
                result.append((char) current);
            } else {
                result.append('.');
            }
        }
        return result.toString();
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

    private void log(String message) {
        Log.d(TAG, message);
        listener.onLog(message);
    }

    public static final class Size {
        public final int width;
        public final int height;
        public final int frameRate;

        Size(int width, int height, int frameRate) {
            this.width = width;
            this.height = height;
            this.frameRate = frameRate;
        }
    }

    private static final class ParsedMessage {
        final Map<Integer, Long> values = new ConcurrentHashMap<>();
        final Map<Integer, byte[]> bytes = new ConcurrentHashMap<>();
    }
}