package io.github.carlinklab;

import android.app.Application;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;

public final class CarLifeLabApplication extends Application {
    static final String CRASH_FILE = "last-crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            writeCrash(error);
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    private void writeCrash(Throwable error) {
        StringWriter buffer = new StringWriter();
        error.printStackTrace(new PrintWriter(buffer));
        File file = new File(getFilesDir(), CRASH_FILE);
        try (FileWriter writer = new FileWriter(file, false)) {
            writer.write(buffer.toString());
        } catch (Exception ignored) {
            // Crash logging must not hide the original failure.
        }
    }

    static String readLastCrash(Application application) {
        File file = new File(application.getFilesDir(), CRASH_FILE);
        if (!file.isFile()) {
            return null;
        }
        try {
            return new String(java.nio.file.Files.readAllBytes(file.toPath()));
        } catch (Exception ignored) {
            return null;
        }
    }
}