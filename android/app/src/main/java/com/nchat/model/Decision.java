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

    // ==================== v3 追踪字段 ====================
    // 目的：事后能回答「这条通知是从哪进来的、点开时去了哪、成不成功」。

    /** 触发本通知的 PendingIntent 的创建者包名（推送通道来源，如 com.xiaomi.xmsf） */
    public String intentPkg;
    /** contentIntent 目标组件（Android 12+ 多为 null，受 hidden API 限制） */
    public String intentTarget;
    /** contentIntent 的 action */
    public String intentAction;
    /** contentIntent 的 data URI */
    public String intentData;
    /** 通知 tag（分组/聚合标记为 null） */
    public String notifTag;
    /** 通知 id（与 tag 组合成 notifKey） */
    public int notifId;
    /** Notification.flags 原始值，用于事后判断 ongoing / autoCancel / FGS 等 */
    public int notifFlags;
    /** Notification.category 字符串（msg/email/call/…） */
    public String notifCategory;
    /** 渠道 id */
    public String notifChannel;
    /** 分组 key */
    public String notifGroup;
    /** 是否分组摘要（isGroupSummary） */
    public boolean isGroupSummary;
    /** 通知发布时间（sbn.getPostTime()） */
    public long postTime;

    public static final int JUMP_NONE = -1;   // 未尝试
    public static final int JUMP_FAIL = 0;    // 尝试了但没跳过去
    public static final int JUMP_OK = 1;      // 成功跳到目标 App

    /** 跳转结果：-1 未尝试 / 0 失败 / 1 成功 */
    public int jumpResult = JUMP_NONE;
    /** 跳转耗时（毫秒）；-1 表示未尝试 */
    public long jumpCostMs = -1;

    /** 用于 UI 展示的一行来源摘要 */
    public String sourceSummary() {
        StringBuilder sb = new StringBuilder();
        if (intentPkg != null && !intentPkg.isEmpty()) sb.append(intentPkg);
        if (intentTarget != null && !intentTarget.isEmpty()) {
            if (sb.length() > 0) sb.append(" \u2192 ");
            sb.append(intentTarget);
        }
        if (jumpResult == JUMP_OK) {
            sb.append("  [跳转成功");
            if (jumpCostMs >= 0) sb.append(" ").append(jumpCostMs).append("ms");
            sb.append("]");
        } else if (jumpResult == JUMP_FAIL) {
            sb.append("  [跳转失败]");
        }
        return sb.toString();
    }

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