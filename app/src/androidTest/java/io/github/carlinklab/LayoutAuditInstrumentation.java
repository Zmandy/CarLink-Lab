package io.github.carlinklab;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Device-level checks of actual measured controls, including off-screen scroll content. */
public final class LayoutAuditInstrumentation extends Instrumentation {
    private volatile Activity resumed;
    @Override public void callActivityOnResume(Activity activity) {
        super.callActivityOnResume(activity);
        resumed = activity;
    }

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        JSONArray pages = new JSONArray();
        try {
            for (String name : new String[]{"MainActivity", "CarLifeClientActivity", "TpmsDashboardActivity",
                    "UsbDiagnosticsActivity", "TpmsScanActivity", "CarLifeProbeActivity"}) {
                Intent intent = new Intent().setClassName(getTargetContext(), "io.github.carlinklab." + name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                resumed = null;
                Bundle progress = new Bundle();
                progress.putString("page", name);
                sendStatus(1, progress);
                if (name.equals("MainActivity")) {
                    android.os.ParcelFileDescriptor launch = getUiAutomation().executeShellCommand(
                            "am start -n io.github.carlinklab/.MainActivity");
                    launch.close();
                } else runOnMainSync(() -> getTargetContext().startActivity(intent));
                long deadline = SystemClock.uptimeMillis() + 8000;
                while ((resumed == null || !resumed.getClass().getSimpleName().equals(name))
                        && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
                Activity activity = resumed;
                if (activity == null || !activity.getClass().getSimpleName().equals(name))
                    throw new IllegalStateException("Activity did not resume: " + name);
                SystemClock.sleep(700);
                runOnMainSync(() -> {
                    try { pages.put(audit(activity, name)); }
                    catch (Exception error) { throw new RuntimeException(error); }
                });
                runOnMainSync(activity::finish);
                SystemClock.sleep(100);
            }
            android.graphics.drawable.Drawable icon = getTargetContext().getPackageManager()
                    .getApplicationIcon(getTargetContext().getPackageName());
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, 512, 512);
            icon.draw(new android.graphics.Canvas(bitmap));
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes);
            result.putString("launcher_icon_png", android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP));
            result.putString("layout_report", pages.toString());
            finish(Activity.RESULT_OK, result);
        } catch (Exception error) {
            result.putString("error", error.toString());
            result.putString("layout_report", pages.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private JSONObject audit(Activity activity, String name) throws Exception {
        List<View> controls = new ArrayList<>();
        View root = activity.findViewById(android.R.id.content);
        collect(root, controls);
        JSONArray small = new JSONArray(), overlap = new JSONArray(), clipped = new JSONArray();
        float density = activity.getResources().getDisplayMetrics().density;
        for (int i = 0; i < controls.size(); i++) {
            View view = controls.get(i);
            if (view.getWidth() + .5f < 48 * density || view.getHeight() + .5f < 48 * density) small.put(id(view));
            if (view instanceof Button) {
                TextView text = (TextView) view;
                android.text.Layout layout = text.getLayout();
                if (layout != null) {
                    int available = text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom();
                    if (layout.getHeight() > available + 1) clipped.put(id(view) + ": text height");
                    for (int line = 0; line < layout.getLineCount(); line++) {
                        if (layout.getEllipsisCount(line) > 0) clipped.put(id(view) + ": ellipsis");
                        if (layout.getLineWidth(line) > layout.getWidth() + 1) clipped.put(id(view) + ": text width");
                    }
                }
            }
            Rect rect = rect(view);
            for (int j = i + 1; j < controls.size(); j++) {
                if (Rect.intersects(rect, rect(controls.get(j)))) overlap.put(id(view) + " / " + id(controls.get(j)));
            }
        }
        JSONObject page = new JSONObject();
        page.put("activity", name);
        page.put("widthDp", root.getWidth() / density);
        page.put("fontScale", activity.getResources().getConfiguration().fontScale);
        page.put("controls", controls.size());
        page.put("smallTargets", small);
        page.put("overlap", overlap);
        page.put("clippedButtonText", clipped);
        return page;
    }

    private void collect(View view, List<View> controls) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof Button || view instanceof EditText || view instanceof ImageButton) controls.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), controls);
        }
    }

    private Rect rect(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
    }

    private String id(View view) {
        return view.getId() == View.NO_ID ? view.getClass().getSimpleName() : view.getResources().getResourceEntryName(view.getId());
    }
}
