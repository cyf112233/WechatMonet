package com.wechat.monet.injector;

import android.app.Activity;
import android.app.Application;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

public final class Bridge {
    private static volatile boolean initialized;

    private Bridge() {}

    public static void initialize(Application application) {
        if (initialized || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        initialized = true;
        application.registerActivityLifecycleCallbacks(new Hooks());
    }

    private static final class Hooks implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            recolor(activity);
            View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
            if (decor != null) {
                decor.post(() -> recolor(activity));
            }
        }

        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivityResumed(Activity activity) { recolor(activity); }
        @Override public void onActivityPaused(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
        @Override public void onActivityDestroyed(Activity activity) {}
    }

    private static void recolor(Activity activity) {
        if (activity.getWindow() != null) {
            activity.getWindow().setStatusBarColor(getColor(activity, "system_accent1_600"));
            activity.getWindow().setNavigationBarColor(getColor(activity, "system_neutral2_200"));
        }
        View root = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        if (root != null) traverse(root, activity);
    }

    private static void traverse(View view, Activity activity) {
        recolorView(view, activity);
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                traverse(group.getChildAt(i), activity);
            }
        }
    }

    private static void recolorView(View view, Activity activity) {
        Drawable background = view.getBackground();
        if (background instanceof ColorDrawable colorDrawable) {
            int mapped = mapColor(colorDrawable.getColor(), activity);
            if (mapped != colorDrawable.getColor()) {
                view.setBackground(new ColorDrawable(mapped));
            }
        }

        if (view instanceof TextView textView) {
            int mapped = mapColor(textView.getCurrentTextColor(), activity);
            if (mapped != textView.getCurrentTextColor()) {
                textView.setTextColor(mapped);
            }
        }

        if (view instanceof ImageView imageView) {
            imageView.setColorFilter(getColor(activity, "system_accent2_600"));
        }
    }

    private static int mapColor(int original, Activity activity) {
        int color = original & 0x00FFFFFF;
        if (isSimilar(color, 0x07C160)) return withAlpha(original, getColor(activity, "system_accent1_600"));
        if (isSimilar(color, 0x06AD56)) return withAlpha(original, getColor(activity, "system_accent1_700"));
        if (isSimilar(color, 0x576B95)) return withAlpha(original, getColor(activity, "system_accent3_600"));
        if (isSimilar(color, 0xEDEDED)) return withAlpha(original, getColor(activity, "system_neutral1_100"));
        return original;
    }

    private static boolean isSimilar(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b)) < 30
            && Math.abs(Color.green(a) - Color.green(b)) < 30
            && Math.abs(Color.blue(a) - Color.blue(b)) < 30;
    }

    private static int withAlpha(int source, int replacement) {
        return (source & 0xFF000000) | (replacement & 0x00FFFFFF);
    }

    private static int getColor(Activity activity, String name) {
        int id = activity.getResources().getIdentifier(name, "color", "android");
        return id != 0 ? activity.getColor(id) : Color.TRANSPARENT;
    }
}
