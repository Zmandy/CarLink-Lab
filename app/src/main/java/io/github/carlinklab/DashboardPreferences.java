package io.github.carlinklab;

import android.content.Context;

public final class DashboardPreferences {
    private static final String PREFS = "dashboard_preferences";
    private static final String KEY_TITLE = "welcome_title";
    private static final String KEY_PROVINCE = "fuel_province";
    private static final String KEY_FUEL_GRADE = "fuel_grade";
    private static final String KEY_FUEL_PRICE = "fuel_price";
    private static final String KEY_FUEL_PRICE_GRADE = "fuel_price_grade";
    private static final String KEY_FUEL_PRICE_PROVINCE = "fuel_price_province";
    private static final String KEY_FUEL_PRICE_FETCHED_AT = "fuel_price_fetched_at";
    private static final String DEFAULT_TITLE = "欢迎公主上车";
    private static final String DEFAULT_PROVINCE = "上海";
    private static final String DEFAULT_FUEL_GRADE = "92#";

    private DashboardPreferences() {
    }

    public static String getTitle(Context context) {
        String value = prefs(context).getString(KEY_TITLE, DEFAULT_TITLE);
        return value == null || value.trim().isEmpty() ? DEFAULT_TITLE : value;
    }

    public static void setTitle(Context context, String title) {
        String value = title == null || title.trim().isEmpty() ? DEFAULT_TITLE : title.trim();
        prefs(context).edit().putString(KEY_TITLE, value).apply();
    }

    public static String getProvince(Context context) {
        String value = prefs(context).getString(KEY_PROVINCE, DEFAULT_PROVINCE);
        return value == null || value.trim().isEmpty() ? DEFAULT_PROVINCE : value.trim();
    }

    public static void setProvince(Context context, String province) {
        String value = province == null || province.trim().isEmpty() ? DEFAULT_PROVINCE : province.trim();
        prefs(context).edit().putString(KEY_PROVINCE, value).apply();
    }

    public static String getFuelGrade(Context context) {
        String value = prefs(context).getString(KEY_FUEL_GRADE, DEFAULT_FUEL_GRADE);
        return normalizeGrade(value);
    }

    public static void setFuelGrade(Context context, String grade) {
        prefs(context).edit().putString(KEY_FUEL_GRADE, normalizeGrade(grade)).apply();
    }

    public static double getFuelPrice(Context context) {
        return Double.longBitsToDouble(prefs(context).getLong(KEY_FUEL_PRICE,
                Double.doubleToRawLongBits(0.0d)));
    }

    public static void setFuelPrice(Context context, String grade, double price) {
        prefs(context).edit()
                .putLong(KEY_FUEL_PRICE, Double.doubleToRawLongBits(price))
                .putString(KEY_FUEL_PRICE_GRADE, normalizeGrade(grade))
                .putString(KEY_FUEL_PRICE_PROVINCE, getProvince(context))
                .putLong(KEY_FUEL_PRICE_FETCHED_AT, System.currentTimeMillis())
                .apply();
    }

    public static String getFuelPriceGrade(Context context) {
        return normalizeGrade(prefs(context).getString(KEY_FUEL_PRICE_GRADE, ""));
    }

    public static String getFuelPriceProvince(Context context) {
        return prefs(context).getString(KEY_FUEL_PRICE_PROVINCE, "");
    }

    public static long getFuelPriceFetchedAt(Context context) {
        return prefs(context).getLong(KEY_FUEL_PRICE_FETCHED_AT, 0L);
    }

    public static String priceKeyForGrade(String grade) {
        switch (normalizeGrade(grade)) {
            case "89#":
                return "p89";
            case "95#":
                return "p95";
            case "98#":
                return "p98";
            case "0#":
                return "p0";
            case "92#":
            default:
                return "p92";
        }
    }

    private static String normalizeGrade(String grade) {
        if (grade == null) {
            return DEFAULT_FUEL_GRADE;
        }
        String value = grade.trim().toUpperCase();
        if (value.contains("98")) {
            return "98#";
        }
        if (value.contains("95")) {
            return "95#";
        }
        if (value.contains("89")) {
            return "89#";
        }
        if (value.contains("0#") || value.contains("柴油")) {
            return "0#";
        }
        return DEFAULT_FUEL_GRADE;
    }

    private static android.content.SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
