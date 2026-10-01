package io.github.carlinklab;

public interface TpmsDataSource {
    interface Listener {
        void onSnapshot(TpmsSnapshot snapshot);
        void onStatus(String status);
    }

    void start(Listener listener);
    void stop();
    String getName();
}