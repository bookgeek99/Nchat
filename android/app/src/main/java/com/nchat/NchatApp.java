package com.nchat;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

import java.util.HashMap;
import java.util.Map;

/**
 * 应用级单例，缓存 App 名称与图标。
 */
public class NchatApp extends Application {

    private static NchatApp instance;
    private final Map<String, String> labelCache = new HashMap<>();
    private final LruCache<String, Drawable> iconCache = new LruCache<>(128);

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    public static NchatApp get() {
        return instance;
    }

    /** 应用显示名，失败返回包名 */
    public String appLabel(String pkg) {
        String cached = labelCache.get(pkg);
        if (cached != null) return cached;
        String label = pkg;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            if (cs != null && cs.length() > 0) label = cs.toString();
        } catch (Throwable ignore) {}
        labelCache.put(pkg, label);
        return label;
    }

    public Drawable appIcon(String pkg) {
        Drawable d = iconCache.get(pkg);
        if (d != null) return d;
        try {
            d = getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable ignore) {}
        if (d != null) iconCache.put(pkg, d);
        return d;
    }
}