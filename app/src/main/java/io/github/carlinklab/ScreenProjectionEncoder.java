package io.github.carlinklab;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/** One consent token, one virtual display. Lifecycle methods run on the main thread. */
public final class ScreenProjectionEncoder implements AutoCloseable {
    public interface Listener {
        void onFrame(byte[] data, long timestampMillis);
        void onLog(String message);
        void onStopped(String reason);
    }

    private final MediaProjection projection;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile MediaCodec codec;
    private Surface surface;
    private VirtualDisplay display;
    private Thread worker;
    private byte[] config;
    private boolean firstFrame = true;
    private final MediaProjection.Callback callback = new MediaProjection.Callback() {
        @Override public void onStop() {
            if (!closed.get()) listener.onStopped("屏幕共享已被系统停止，请重新授权；已恢复胎压仪表");
        }
    };

    public ScreenProjectionEncoder(MediaProjection projection, Listener listener) {
        this.projection = projection;
        this.listener = listener;
    }

    public void start(Context context, int width, int height, int frameRate) throws Exception {
        projection.registerCallback(callback, main);
        try {
            int fps = Math.max(1, Math.min(frameRate <= 0 ? 20 : frameRate, 30));
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(1_200_000,
                    (int) Math.min(8_000_000L, (long) width * height * 4)));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            if (android.os.Build.VERSION.SDK_INT >= 29) format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = codec.createInputSurface();
            codec.start();
            display = projection.createVirtualDisplay("CarLink navigation", width, height,
                    context.getResources().getDisplayMetrics().densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, main);
            if (display == null) throw new IllegalStateException("无法创建共享显示区域");
            worker = new Thread(this::drain, "navigation-h264");
            worker.start();
            listener.onLog("导航画面采集已启动 " + width + "x" + height + "@" + fps);
        } catch (Exception error) {
            close();
            // No worker owns the codec when setup fails.
            releaseCodec();
            throw error;
        }
    }

    public void requestKeyFrame() {
        try {
            MediaCodec current = codec;
            if (current != null && !closed.get()) {
                Bundle params = new Bundle();
                params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                current.setParameters(params);
            }
        } catch (IllegalStateException error) {
            listener.onLog("请求导航关键帧失败：" + error.getMessage());
        }
    }

    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long frames = 0;
        try {
            while (!closed.get()) {
                int index = codec.dequeueOutputBuffer(info, 20_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = codec.getOutputFormat();
                    config = H264AccessUnit.join(read(format.getByteBuffer("csd-0")),
                            read(format.getByteBuffer("csd-1")));
                } else if (index >= 0) {
                    byte[] data = null;
                    try {
                        ByteBuffer buffer = codec.getOutputBuffer(index);
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset);
                            buffer.limit(info.offset + info.size);
                            data = H264AccessUnit.annexB(read(buffer));
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false);
                    }
                    if (data != null && !closed.get()) {
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            config = data;
                        } else {
                            boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                            if (firstFrame || key) data = H264AccessUnit.join(config, data);
                            firstFrame = false;
                            listener.onFrame(data, info.presentationTimeUs / 1000L);
                            if (++frames == 1) listener.onLog("导航首帧已编码并提交 CarLife 视频通道");
                        }
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } catch (Exception error) {
            if (!closed.get()) main.post(() -> {
                if (!closed.get()) listener.onStopped("导航编码失败：" + error.getMessage() + "；已恢复胎压仪表");
            });
        } finally {
            releaseCodec();
        }
    }

    private static byte[] read(ByteBuffer buffer) {
        if (buffer == null) return new byte[0];
        ByteBuffer copy = buffer.duplicate();
        byte[] result = new byte[copy.remaining()];
        copy.get(result);
        return result;
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { projection.unregisterCallback(callback); } catch (RuntimeException ignored) { }
        if (display != null) {
            try { display.release(); } catch (RuntimeException ignored) { }
            display = null;
        }
        try { projection.stop(); } catch (RuntimeException ignored) { }
        if (worker != null) worker.interrupt();
    }

    private void releaseCodec() {
        if (codec != null) {
            try { codec.stop(); } catch (RuntimeException ignored) { }
            try { codec.release(); } catch (RuntimeException ignored) { }
            codec = null;
        }
        if (surface != null) { surface.release(); surface = null; }
    }
}
