package io.github.carlinklab;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class FuelPriceClient {
    private static final String TAG = "FuelPriceClient";
    private static final long REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private FuelPriceClient() {
    }

    public static void refresh(Context context) {
        refresh(context, false);
    }

    public static void refresh(Context context, boolean force) {
        Context appContext = context.getApplicationContext();
        String grade = DashboardPreferences.getFuelGrade(appContext);
        long fetchedAt = DashboardPreferences.getFuelPriceFetchedAt(appContext);
        boolean sameGrade = grade.equals(DashboardPreferences.getFuelPriceGrade(appContext));
        boolean sameProvince = DashboardPreferences.getProvince(appContext)
                .equals(DashboardPreferences.getFuelPriceProvince(appContext));
        if (!force && sameGrade && sameProvince && System.currentTimeMillis() - fetchedAt < REFRESH_INTERVAL_MS
                && DashboardPreferences.getFuelPrice(appContext) > 0.0d) {
            return;
        }
        EXECUTOR.execute(() -> fetchNow(appContext, grade));
    }

    private static void fetchNow(Context context, String grade) {
        String province = DashboardPreferences.getProvince(context);
        try {
            String endpoint = "https://api.qqsuu.cn/api/dm-oilprice?prov="
                    + URLEncoder.encode(province, StandardCharsets.UTF_8.name());
            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(7000);
            connection.setRequestProperty("User-Agent", "CarLinkLab/1.0");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
                JSONObject root = new JSONObject(body.toString());
                JSONObject data = root.optJSONObject("data");
                if (data != null) {
                    String key = DashboardPreferences.priceKeyForGrade(grade);
                    String priceText = data.optString(key, "");
                    double price = Double.parseDouble(priceText);
                    if (price > 0.0d) {
                        DashboardPreferences.setFuelPrice(context, grade, price);
                        Log.d(TAG, grade + " price " + price + " for " + province
                                + " (" + data.optString("time", "") + ")");
                    }
                }
            } finally {
                connection.disconnect();
            }
        } catch (Exception error) {
            Log.w(TAG, "Fuel price refresh failed", error);
        }
    }
}
