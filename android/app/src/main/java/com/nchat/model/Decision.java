package com.nchat.model;

/**
 * 用户对一条通知做出的决策。
 */
public class Decision {
    public static final int TYPE_NONE = 0;
    public static final int TYPE_IGNORE = 1;      // 不感兴趣
    public static final int TYPE_READ_LATER = 2;  // 稍后读
    public static final int TYPE_DO_LATER = 3;    // 稍后办
    public static final int TYPE_DONE = 4;        // 已完成
    public static final int TYPE_MUTED = 5;       // 已屏蔽该渠道

    public long id;
    public String notifKey;
    public String pkg;
    public String uidChannel;   // pkg|uid|channel 用于屏蔽
    public int type;
    public long decidedAt;
    public String title;
    public String body;
    public String channelName;
    public NotifCategory category;
    public boolean todoPending;  // 待办是否还未勾销
    /** 计划办理时间（毫秒时间戳）；0 表示不计划，立即进入决策流 */
    public long planAt;
}