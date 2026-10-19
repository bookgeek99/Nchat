package com.nchat.util;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

/**
 * 处理 Android 15+ 强制 edge-to-edge 导致的「内容顶到状态栏/被导航栏遮挡」。
 *
 * 不依赖 androidx core 的 WindowCompat（工程里没引 core-ktx 版本约束），
 * 直接用 WindowInsets 给根视图补 padding。
 *
 * 用法：在 setContentView 之后调用 Insets.apply(this, rootView, top, bottom);
 *   top=true 表示需要给状态栏留出顶部间距（一般给根容器）
 *   bottom=true 表示需要给导航栏留出底部间距（一般给底部按钮区）
 */
public final class Insets {
    private Insets() {}

    public static void applyTop(Activity a, View v) {
        apply(a, v, true, false);
    }

    public static void applyBottom(Activity a, View v) {
        apply(a, v, false, true);
    }

    public static void applyBoth(Activity a, View v) {
        apply(a, v, true, true);
    }

    public static void apply(Activity a, View v, final boolean top, final boolean bottom) {
        if (v == null) return;
        final int padL = v.getPaddingLeft();
        final int padT = v.getPaddingTop();
        final int padR = v.getPaddingRight();
        final int padB = v.getPaddingBottom();
        v.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                int sysTop, sysBottom;
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets i = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    sysTop = i.top;
                    sysBottom = i.bottom;
                } else {
                    sysTop = insets.getSystemWindowInsetTop();
                    sysBottom = insets.getSystemWindowInsetBottom();
                }
                view.setPadding(
                        padL,
                        padT + (top ? sysTop : 0),
                        padR,
                        padB + (bottom ? sysBottom : 0));
                return insets;
            }
        });
        v.requestApplyInsets();
    }
}