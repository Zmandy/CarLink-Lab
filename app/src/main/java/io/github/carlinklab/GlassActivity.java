package io.github.carlinklab;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/** Shared Android glass styling, accessible controls and responsive action rows. */
public abstract class GlassActivity extends ComponentActivity {
    @Override
    public void setContentView(int layoutId) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.os.Build.VERSION.SDK_INT >= 27
                ? Color.TRANSPARENT : 0xFF405273);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(true);
        bars.setAppearanceLightNavigationBars(true);
        super.setContentView(layoutId);
        ViewGroup content = findViewById(android.R.id.content);
        content.setBackground(new Atmosphere());
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(safe.left, safe.top, safe.right, Math.max(safe.bottom, keyboard.bottom));
            return insets;
        });
        ViewCompat.requestApplyInsets(content);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        View back = findViewById(R.id.glass_back);
        if (back != null) back.setOnClickListener(view -> finish());
        adapt(content);
    }

    private void adapt(View view) {
        if (view instanceof Button) {
            // Keep system ripple / click handling; animate only when the user allows motion.
            if (Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f) {
                view.setOnTouchListener((button, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        button.animate().scaleX(.98f).scaleY(.98f).setDuration(100).start();
                    } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                            || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        button.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                    }
                    return false;
                });
            }
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        if (group instanceof LinearLayout && ((LinearLayout) group).getOrientation() == LinearLayout.HORIZONTAL) {
            float fontScale = getResources().getConfiguration().fontScale;
            int available = getResources().getConfiguration().screenWidthDp;
            boolean controlsOnly = group.getChildCount() > 1;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                boolean tire = child.getId() == R.id.client_tire_fl || child.getId() == R.id.client_tire_fr
                        || child.getId() == R.id.client_tire_rl || child.getId() == R.id.client_tire_rr;
                controlsOnly &= child instanceof Button || child instanceof EditText || (child instanceof TextView && tire);
            }
            if (controlsOnly && (available < 360 || fontScale >= 1.25f)) {
                ((LinearLayout) group).setOrientation(LinearLayout.VERTICAL);
                for (int i = 0; i < group.getChildCount(); i++) {
                    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) group.getChildAt(i).getLayoutParams();
                    params.width = ViewGroup.LayoutParams.MATCH_PARENT;
                    params.weight = 0;
                    params.setMarginStart(0);
                    params.topMargin = i == 0 ? 0 : Math.round(10 * getResources().getDisplayMetrics().density);
                    group.getChildAt(i).setLayoutParams(params);
                }
            }
        }
        for (int i = 0; i < group.getChildCount(); i++) adapt(group.getChildAt(i));
    }

    private static final class Atmosphere extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shader[] glows = new Shader[0];

        @Override protected void onBoundsChange(android.graphics.Rect bounds) {
            float w = Math.max(1, bounds.width()), h = Math.max(1, bounds.height());
            glows = new Shader[]{glow(w * .12f, h * .10f, w * .90f, 0x7798BFF8),
                    glow(w * .98f, h * .50f, w * .85f, 0x55B9ACF0),
                    glow(w * .10f, h * .88f, w * .90f, 0x4489D9CF)};
        }

        private static Shader glow(float x, float y, float radius, int color) {
            return new RadialGradient(x, y, radius, color, Color.TRANSPARENT, Shader.TileMode.CLAMP);
        }

        @Override public void draw(Canvas canvas) {
            canvas.drawColor(0xFFEBF0FA);
            for (Shader shader : glows) {
                paint.setShader(shader);
                canvas.drawRect(getBounds(), paint);
            }
            paint.setShader(null);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}
