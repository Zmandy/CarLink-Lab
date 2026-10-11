package io.github.carlinklab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

public final class WebViewDashboardRenderer implements AutoCloseable {
    private static final String TAG = "WebViewDashboard";
    private static final long PERIODIC_REFRESH_MS = 15_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object frameLock = new Object();
    private final Bitmap frameBitmap;
    private final WebView webView;
    private final int width;
    private final int height;
    private final float renderDensity;
    private final float outputScale;
    private final Runnable periodicRefresh = new Runnable() {
        @Override
        public void run() {
            if (pageReady && !closed) pushDashboardData();
            drawFrameNow();
            if (!closed) {
                mainHandler.postDelayed(this, PERIODIC_REFRESH_MS);
            }
        }
    };

    private volatile boolean closed;
    private boolean pageReady;
    private boolean drawScheduled;
    private TpmsSnapshot snapshot;
    private String status = "waiting for TPMS data";

    public WebViewDashboardRenderer(Context context, int width, int height) {
        this(context, width, height, 1f);
    }

    public WebViewDashboardRenderer(Context context, int width, int height, float outputScale) {
        this.outputScale = outputScale;
        this.width = width;
        this.height = height;
        this.frameBitmap = Bitmap.createBitmap(Math.round(width * outputScale), Math.round(height * outputScale), Bitmap.Config.ARGB_8888);
        this.frameBitmap.eraseColor(Color.BLACK);

        // Chromium uses the real display density even for a configuration-context WebView.
        // Lay out in physical pixels, then scale the drawing back to the requested CSS frame.
        renderDensity = context.getResources().getDisplayMetrics().density;
        webView = new WebView(context);
        webView.setBackgroundColor(Color.BLACK);
        webView.setFocusable(false);
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setTextZoom(100);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMediaPlaybackRequiresUserGesture(false);
        webView.setInitialScale(100);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                view.resumeTimers();
                if (height > width) view.evaluateJavascript("document.documentElement.dataset.theme='light'", null);
                pushDashboardData();
                scheduleDraw(80L);
                mainHandler.removeCallbacks(periodicRefresh);
                mainHandler.postDelayed(periodicRefresh, PERIODIC_REFRESH_MS);
                Log.d(TAG, "TPMS template loaded: " + url);
                view.evaluateJavascript(
                        "(window.innerWidth + 'x' + window.innerHeight + '@' + window.devicePixelRatio)",
                        value -> Log.d(TAG, "WebView viewport=" + value)
                );
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                Log.w(TAG, "WebView error: " + error.getDescription());
            }
        });

        webView.measure(
                View.MeasureSpec.makeMeasureSpec(Math.round(width * renderDensity), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.round(height * renderDensity), View.MeasureSpec.EXACTLY)
        );
        webView.layout(0, 0, Math.round(width * renderDensity), Math.round(height * renderDensity));
        webView.loadUrl("file:///android_asset/tpms_template.html");
    }

    public void update(@Nullable TpmsSnapshot snapshot, String status) {
        mainHandler.post(() -> {
            if (closed) return;
            this.snapshot = snapshot;
            if (status != null) {
                this.status = status;
            }
            if (pageReady) {
                pushDashboardData();
                scheduleDraw(40L);
            }
        });
    }

    public void copyTo(Bitmap target) {
        synchronized (frameLock) {
            Canvas canvas = new Canvas(target);
            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(frameBitmap, 0, 0, null);
        }
    }

    private void scheduleDraw(long delayMs) {
        if (closed || drawScheduled) {
            return;
        }
        drawScheduled = true;
        mainHandler.postDelayed(() -> {
            drawScheduled = false;
            drawFrameNow();
        }, delayMs);
    }

    private void drawFrameNow() {
        if (closed || !pageReady) {
            return;
        }
        try {
            synchronized (frameLock) {
                Canvas canvas = new Canvas(frameBitmap);
                canvas.drawColor(Color.BLACK);
                canvas.scale(outputScale / renderDensity, outputScale / renderDensity);
                webView.draw(canvas);
            }
        } catch (Exception error) {
            Log.w(TAG, "Draw failed: " + error.getMessage());
        }
    }

    private void pushDashboardData() {
        if (!pageReady) {
            return;
        }
        try {
            JSONObject data = new JSONObject();
            data.put("title", DashboardPreferences.getTitle(webView.getContext()));
            if (snapshot != null && !snapshot.stale
                    && System.currentTimeMillis() - snapshot.updatedAtMillis < 30_000L) {
                JSONArray tires = new JSONArray();
                tires.put(tire(snapshot.frontLeftKpa, snapshot.frontLeftTempC));
                tires.put(tire(snapshot.frontRightKpa, snapshot.frontRightTempC));
                tires.put(tire(snapshot.rearLeftKpa, snapshot.rearLeftTempC));
                tires.put(tire(snapshot.rearRightKpa, snapshot.rearRightTempC));
                data.put("tires", tires);
                data.put("instant", snapshot.fuelAvailable && snapshot.speedKmh > 5 ? snapshot.instantL100Km : JSONObject.NULL);
                data.put("rate", snapshot.fuelAvailable ? snapshot.fuelRateLph : JSONObject.NULL);
                data.put("speed", snapshot.speedKmh >= 0 ? snapshot.speedKmh : JSONObject.NULL);
                if (snapshot.fuelAvailable) {
                    data.put("volume", snapshot.tripFuelLiters);
                    double price = snapshot.fuelPriceYuanPerLiter > 0.0d
                            ? snapshot.fuelPriceYuanPerLiter
                            : DashboardPreferences.getFuelPrice(webView.getContext());
                    if (price > 0.0d) {
                        data.put("price", price);
                        data.put("cost", snapshot.currentCostYuan);
                    } else {
                        data.put("price", JSONObject.NULL);
                        data.put("cost", JSONObject.NULL);
                    }
                } else {
                    data.put("volume", JSONObject.NULL);
                    data.put("price", JSONObject.NULL);
                    data.put("cost", JSONObject.NULL);
                }
                data.put("status", status);
                data.put("scoreAvailable", snapshot.drivingScoreAvailable);
                data.put("score", snapshot.drivingScore);
                data.put("harshAccel", snapshot.harshAccelerationCount);
                data.put("harshBrake", snapshot.harshBrakingCount);
                data.put("overspeed", snapshot.overspeedCount);
                data.put("idlePercent", snapshot.idlePercent);
            }
            String script = "window.updateDashboard(" + data + ");";
            webView.evaluateJavascript(script, value -> scheduleDraw(30L));
        } catch (Exception error) {
            Log.w(TAG, "Data update failed: " + error.getMessage());
        }
    }

    private static JSONArray tire(int pressureKpa, int temperatureC) {
        JSONArray tire = new JSONArray();
        tire.put(pressureKpa > 0 ? pressureKpa : JSONObject.NULL);
        tire.put(temperatureC != Integer.MIN_VALUE ? temperatureC : JSONObject.NULL);
        return tire;
    }

    @Override
    public void close() {
        closed = true;
        mainHandler.removeCallbacks(periodicRefresh);
        mainHandler.post(() -> {
            try {
                webView.stopLoading();
                webView.destroy();
            } catch (Exception ignored) {
                // Best effort shutdown.
            }
        });
    }
}
