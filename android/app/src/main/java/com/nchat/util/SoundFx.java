package com.nchat.util;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.SoundPool;
import com.nchat.R;
/**
 * 操作音效。
 *
 * 用 SoundPool 而不是 MediaPlayer：音效只有几十毫秒，
 * 而且用户会连续快速点击（一批十几条），
 * MediaPlayer 每次都要 prepare，会明显拖慢手感；
 * SoundPool 预加载进内存，重复触发几乎零延迟。
 *
 * 三个开关（都在设置页里）：
 *   sound_enabled   总开关，默认开
 *   sound_volume    音量倍率，默认 0.7
 *   sound_vibrate   是否同时震动，默认开
 *
 * 注意：SoundPool 有并发限制，这里只放了两个音效，
 * 即便连点也不会被截断（rate 1.0、不限流）。
 */
public final class SoundFx {
    public static final String PREF = "nchat_sound";
    public static final String KEY_ENABLED = "sound_enabled";
    public static final String KEY_VOLUME = "sound_volume";
    public static final String KEY_VIBRATE = "sound_vibrate";

    private static SoundPool pool;
    private static int tickId = 0;
    private static int finishId = 0;
    private static boolean loaded = false;
    private static Context appCtx;

    private SoundFx() {}

    public static synchronized void init(Context ctx) {
        if (pool != null) return;
        appCtx = ctx.getApplicationContext();
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(attrs)
                .build();
        pool.setOnLoadCompleteListener((sp, sampleId, status) -> {
            if (status == 0) loaded = true;
        });
        try {
            tickId = pool.load(appCtx, R.raw.tick, 1);
            finishId = pool.load(appCtx, R.raw.finish, 1);
        } catch (Throwable ignore) {}
    }

    private static SharedPreferences prefs() {
        return appCtx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        if (appCtx == null) return true;
        return prefs().getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(boolean on) {
        if (appCtx == null) return;
        prefs().edit().putBoolean(KEY_ENABLED, on).apply();
    }

    public static float getVolume() {
        if (appCtx == null) return 0.7f;
        return prefs().getFloat(KEY_VOLUME, 0.7f);
    }

    public static void setVolume(float v) {
        if (appCtx == null) return;
        prefs().edit().putFloat(KEY_VOLUME, Math.max(0f, Math.min(1f, v))).apply();
    }

    public static boolean isVibrate() {
        if (appCtx == null) return true;
        return prefs().getBoolean(KEY_VIBRATE, true);
    }

    public static void setVibrate(boolean on) {
        if (appCtx == null) return;
        prefs().edit().putBoolean(KEY_VIBRATE, on).apply();
    }

    /** 处理单条通知时播放：一声清脆的水滴 */
    public static void playTick() {
        play(tickId);
    }

    /** 一批处理完 / 全部清空时播放：上行琶音 */
    public static void playFinish() {
        play(finishId);
    }

    private static void play(int id) {
        if (!isEnabled()) return;
        if (pool == null || !loaded || id == 0) return;
        float v = getVolume();
        try {
            pool.play(id, v, v, 1, 0, 1.0f);
        } catch (Throwable ignore) {}
    }

    /** 轻震动，和音效配套（有些场景静音，震动还有反馈） */
    public static void vibrateTick() {
        if (!isVibrate() || appCtx == null) return;
        try {
            android.os.Vibrator vib = (android.os.Vibrator)
                    appCtx.getSystemService(Context.VIBRATOR_SERVICE);
            if (vib == null || !vib.hasVibrator()) return;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(android.os.VibrationEffect.createOneShot(
                        14, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vib.vibrate(14);
            }
        } catch (Throwable ignore) {}
    }
}