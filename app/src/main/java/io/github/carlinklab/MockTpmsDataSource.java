package io.github.carlinklab;

import android.os.Handler;
import android.os.Looper;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MockTpmsDataSource implements TpmsDataSource {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Runnable updateTask = this::emit;
    private Listener listener;

    @Override
    public void start(Listener listener) {
        this.listener = listener;
        if (!running.compareAndSet(false, true)) {
            return;
        }
        listener.onStatus("模拟数据源已启动");
        emit();
    }

    @Override
    public void stop() {
        running.set(false);
        mainHandler.removeCallbacks(updateTask);
        if (listener != null) {
            listener.onStatus("模拟数据源已停止");
        }
    }

    @Override
    public String getName() {
        return "Mock TPMS";
    }

    private void emit() {
        if (!running.get() || listener == null) {
            return;
        }
        listener.onSnapshot(new TpmsSnapshot(
                230 + random.nextInt(9),
                230 + random.nextInt(9),
                228 + random.nextInt(9),
                228 + random.nextInt(9),
                28 + random.nextInt(5),
                28 + random.nextInt(5),
                27 + random.nextInt(5),
                27 + random.nextInt(5),
                System.currentTimeMillis(),
                false,
                getName(),
                42,
                5.8,
                7.8,
                8.4,
                true,
                0.42d,
                3.60d,
                8.57d
        ));
        mainHandler.postDelayed(updateTask, 1000);
    }
}
