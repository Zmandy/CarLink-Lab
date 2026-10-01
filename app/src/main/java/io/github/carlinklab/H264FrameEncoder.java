package io.github.carlinklab;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.Image;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class H264FrameEncoder implements AutoCloseable {
    private static final String TAG = "H264FrameEncoder";
    public interface Listener {
        void onFrame(byte[] h264Data, long timestampMillis);
        void onLog(String message);
    }

    private final int width;
    private final int height;
    private final int frameRate;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final WebViewDashboardRenderer renderer;
    private final Bitmap bitmap;
    private final int[] pixels;
    private final ByteArrayOutputStream pendingNal = new ByteArrayOutputStream();
    private MediaCodec codec;
    private byte[] codecConfig;
    private boolean sentCodecConfig;
    private volatile TpmsSnapshot snapshot;
    private volatile String status = "TPMS data";
    private Bitmap inputBitmap;
    private long frameIndex;

    public H264FrameEncoder(Context context, int width, int height, int frameRate, Listener listener) {
        this.width = width;
        this.height = height;
        this.frameRate = Math.max(1, Math.min(frameRate <= 0 ? 10 : frameRate, 30));
        this.listener = listener;
        this.renderer = new WebViewDashboardRenderer(context, width, height);
        this.bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        this.inputBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        this.pixels = new int[width * height];
    }
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            codec = createCodec();
            codec.start();
            log("H.264 encoder started " + width + "x" + height + "@" + frameRate);
            long periodMs = Math.max(1, 1000L / frameRate);
            scheduler.scheduleAtFixedRate(this::encodeOneFrame, 0, periodMs, TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            running.set(false);
            log("Encoder start failed: " + error);
        }
    }

    public void updateSnapshot(@Nullable TpmsSnapshot snapshot, String status) {
        this.snapshot = snapshot;
        if (status != null) {
            this.status = status;
        }
        renderer.update(snapshot, status);
    }

    private MediaCodec createCodec() throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 1_200_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        }
        MediaCodec result = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        result.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        return result;
    }

    private synchronized void encodeOneFrame() {
        if (!running.get() || codec == null) {
            return;
        }
        try {
            renderer.copyTo(bitmap);
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

            int inputIndex = codec.dequeueInputBuffer(10_000);
            if (inputIndex < 0) {
                return;
            }
            Image image = codec.getInputImage(inputIndex);
            if (image == null) {
                log("Encoder input image is null");
                codec.queueInputBuffer(inputIndex, 0, 0, 0, 0);
                return;
            }
            fillYuv(image, pixels);
            long ptsUs = SystemClock.elapsedRealtime() * 1000L;
            int flags = (SystemClock.elapsedRealtime() % 1000L) < 30L
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME
                    : 0;
            codec.queueInputBuffer(inputIndex, 0, width * height * 3 / 2, ptsUs, flags);
            drainEncoder(false);
        } catch (Exception error) {
            log("Encode error: " + error);
        }
    }

    @Override
    public void close() {
        running.set(false);
        scheduler.shutdownNow();
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
                // Best effort shutdown.
            }
            try {
                codec.release();
            } catch (Exception ignored) {
                // Best effort shutdown.
            }
            codec = null;
        }
        renderer.close();
    }

    private void fillYuv(Image image, int[] argb) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int color = argb[y * width + x];
                int red = (color >> 16) & 0xff;
                int green = (color >> 8) & 0xff;
                int blue = color & 0xff;
                int yValue = ((66 * red + 129 * green + 25 * blue + 128) >> 8) + 16;
                yBuffer.put(y * yRowStride + x * yPixelStride, (byte) clampByte(yValue));
            }
        }

        for (int y = 0; y < height / 2; y++) {
            for (int x = 0; x < width / 2; x++) {
                int color = argb[(y * 2) * width + (x * 2)];
                int red = (color >> 16) & 0xff;
                int green = (color >> 8) & 0xff;
                int blue = color & 0xff;
                int uValue = ((-38 * red - 74 * green + 112 * blue + 128) >> 8) + 128;
                int vValue = ((112 * red - 94 * green - 18 * blue + 128) >> 8) + 128;
                uBuffer.put(y * uRowStride + x * uPixelStride, (byte) clampByte(uValue));
                vBuffer.put(y * vRowStride + x * vPixelStride, (byte) clampByte(vValue));
            }
        }
    }

    private void drainEncoder(boolean endOfStream) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        if (endOfStream) {
            codec.signalEndOfInputStream();
        }
        while (true) {
            int outputIndex = codec.dequeueOutputBuffer(info, 0);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                ByteBuffer csd0 = codec.getOutputFormat().getByteBuffer("csd-0");
                ByteBuffer csd1 = codec.getOutputFormat().getByteBuffer("csd-1");
                if (csd0 != null) {
                    codecConfig = merge(csd0, csd1);
                    log("H.264 codec config captured");
                }
                continue;
            }
            if (outputIndex < 0) {
                continue;
            }

            ByteBuffer output = codec.getOutputBuffer(outputIndex);
            if (output != null && info.size > 0) {
                output.position(info.offset);
                output.limit(info.offset + info.size);
                byte[] data = new byte[info.size];
                output.get(data);
                boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (config) {
                    codecConfig = data;
                } else {
                    byte[] frame = data;
                    if (!sentCodecConfig && codecConfig != null && codecConfig.length > 0) {
                        frame = concat(codecConfig, data);
                        sentCodecConfig = true;
                    }
                    listener.onFrame(frame, info.presentationTimeUs / 1000L);
                }
            }
            codec.releaseOutputBuffer(outputIndex, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                break;
            }
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static byte[] merge(ByteBuffer first, ByteBuffer second) {
        int firstSize = first == null ? 0 : first.remaining();
        int secondSize = second == null ? 0 : second.remaining();
        byte[] result = new byte[firstSize + secondSize];
        if (first != null) {
            first.get(result, 0, firstSize);
        }
        if (second != null) {
            second.get(result, firstSize, secondSize);
        }
        return result;
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private void log(String message) {
        Log.d(TAG, message);
        listener.onLog(message);
    }
}
