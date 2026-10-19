package com.nchat.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 时间格式化工具。
 */
public class TimeUtil {

    private static final SimpleDateFormat HM = new SimpleDateFormat("HH:mm", Locale.CHINA);
    private static final SimpleDateFormat MD_HM = new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA);

    /** 相对时间：刚刚 / 3分钟前 / 今天 14:20 / 昨天 14:20 / 8月3日 14:20 */
    public static String relative(long ts) {
        if (ts <= 0) return "";
        long now = System.currentTimeMillis();
        long diff = now - ts;
        if (diff < 60_000) return "刚刚";
        if (diff < 3600_000) return (diff / 60_000) + "分钟前";

        long todayStart = startOfDay(now);
        if (ts >= todayStart) return HM.format(new Date(ts));
        if (ts >= todayStart - 86400_000L) return "昨天 " + HM.format(new Date(ts));
        return MD_HM.format(new Date(ts));
    }

    public static long startOfDay(long ts) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}