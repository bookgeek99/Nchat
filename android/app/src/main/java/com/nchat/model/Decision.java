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
    /**
     * 已阅：看到通知内容就够了，不需要点进去。
     * 与「不感兴趣」的区别 ——
     *   已阅：内容对我有价值，我只是现在不需要展开；
     *   不感兴趣：这条我不想要（可作为负面样本，用于将来判断该屏蔽哪个渠道）。
     * 统计时两者要分开计数，别混成一个「负面」标签。
     */
    public static final int TYPE_SEEN = 6;

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

    /** 决策类型的中文标签（历史页、统计页共用） */
    public static String typeLabel(int type) {
        switch (type) {
            case TYPE_IGNORE:     return "不感兴趣";
            case TYPE_READ_LATER: return "稍后读";
            case TYPE_DO_LATER:   return "稍后办";
            case TYPE_DONE:       return "已完成";
            case TYPE_MUTED:      return "已屏蔽";
            case TYPE_SEEN:       return "已阅";
            default:              return "未处理";
        }
    }
}