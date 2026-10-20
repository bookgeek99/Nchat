package com.nchat.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条通知的完整快照（来自 daemon /state）。
 */
public class NotifItem {
    public String key;
    public String pkg;
    public int uid;
    public int id;
    public long postTime;
    public String channel;
    public String channelName;
    public String category;   // Notification.category 原始值
    public int flags;
    public boolean ongoing;
    public boolean clearable;
    public String title;
    public String text;
    public String bigText;
    public String subText;
    public boolean hasIntent;
    // ---- v3 来源追踪：contentIntent 的创建者与目标 ----
    /** 触发本通知的 PendingIntent 创建者包名（推送通道来源） */
    public String intentPkg;
    /** 目标组件 / action / data（Android 12+ 多为 null） */
    public String intentTarget;
    public String intentAction;
    public String intentData;
    /** contentIntent 的 flag 原始值（0 表示未知） */
    public int intentFlags;

    // ---- 运行时附加：这张卡片的跳转结果（不入 /state，由 App 侧回写） ----
    /** -1 未尝试 / 0 失败 / 1 成功 */
    public int jumpResult = Decision.JUMP_NONE;
    /** 跳转耗时（毫秒）；-1 表示未尝试 */
    public long jumpCostMs = -1;
    public List<String> actions = new ArrayList<>();
    public String groupKey;           // 分组 key（null 表示未分组）
    public boolean groupSummary;      // true = 该 App 多条通知的「聚合摘要」条目
    public int channelImportance;     // 渠道重要性，0=已关闭，-1=未知
    public boolean noisy;             // daemon 判定的系统/常驻类
    /** true = 从「待办」还原出来的条目，通知栏里已不存在 */
    public boolean fromTodo;
    /** 若来自待办，这里是 decisions 表里那条记录的 id（0 表示无） */
    public long decisionRowId;

    // ---- 运行时附加字段（不入库） ----
    public NotifCategory aiCategory;  // 分类结果
    public float confidence;          // 分类置信度
    public String appLabel;           // 应用显示名

    public static NotifItem fromJson(JSONObject o) {
        NotifItem it = new NotifItem();
        it.key = o.optString("key");
        it.pkg = o.optString("pkg");
        it.uid = o.optInt("uid");
        it.id = o.optInt("id");
        it.postTime = o.optLong("postTime");
        it.channel = o.optString("channel");
        it.channelName = o.optString("channelName");
        it.category = o.isNull("category") ? null : o.optString("category");
        it.flags = o.optInt("flags");
        it.ongoing = o.optBoolean("ongoing");
        it.clearable = o.optBoolean("clearable");
        it.title = o.optString("title");
        it.text = o.optString("text");
        it.bigText = o.optString("bigText");
        it.subText = o.optString("subText");
        it.hasIntent = o.optBoolean("hasIntent");
        JSONObject pi = o.optJSONObject("intent");
        if (pi != null) {
            it.intentPkg = pi.isNull("pkg") ? null : pi.optString("pkg");
            it.intentTarget = pi.isNull("target") ? null : pi.optString("target");
            it.intentAction = pi.isNull("action") ? null : pi.optString("action");
            it.intentData = pi.isNull("data") ? null : pi.optString("data");
            it.intentFlags = pi.optInt("flags", 0);
        }
        it.groupKey = o.isNull("groupKey") ? null : o.optString("groupKey");
        it.groupSummary = o.optBoolean("isGroupSummary", false);
        it.channelImportance = o.optInt("channelImportance", -1);
        it.noisy = o.optBoolean("noisy", false);
        JSONArray arr = o.optJSONArray("actions");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) it.actions.add(arr.optString(i));
        }
        return it;
    }

    /**
     * 是否是系统常驻类通知（不进决策流）。
     * 依据：ONGOING_EVENT / FOREGROUND_SERVICE / 媒体传输类
     */
    public boolean isPersistent() {
        // 注意：不能对 FLAG_ONGOING_EVENT / FLAG_FOREGROUND_SERVICE 一刀切 ——
        // 很多 App 的普通推送也会带这些标记，一律过滤会造成
        // 「通知栏还有一堆、App 里却说全处理完了」。
        // 真正该过滤的只有 daemon 判定的系统/常驻类（noisy），
        // 以及明确的媒体播放通道。
        if ("transport".equals(category)) return true;
        // 用户划不掉的通知 = 常驻/前台服务通知。
        // 这类通知的任务就是「一直挂在那儿」，不是等你处理的东西；
        // 而且它们的 contentIntent 通常指向自己 App 的主界面，
        // 点一下只会跳回那个 App（点了 Operit 的常驻通知就跳回 Operit）。
        // clearable 比 ongoing 可靠：ongoing 很多普通推送也带，clearable=false 一定是常驻。
        if (!clearable) return true;
        // 播放/媒体类常驻通知：点「立即阅读」只会回到播放页或干脆没反应，直接不进决策流
        if (ongoing && channelName != null
                && MEDIA_CHANNEL.matcher(channelName).find()) return true;
        // 无标题无内容的骨架通知（如分组摘要）
        if (title.isEmpty() && text.isEmpty() && bigText.isEmpty()) return true;
        // daemon 判定的系统/常驻类
        if (noisy) return true;
        return false;
    }

    /** 渠道名命中即视为媒体播放类（配合 ongoing 判定） */
    private static final java.util.regex.Pattern MEDIA_CHANNEL =
            java.util.regex.Pattern.compile("播放|媒体|音频|视频|music|media|playback|player",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 卡片正文：优先 bigText，其次 text */
    public String body() {
        String b = clean(bigText);
        if (!b.isEmpty()) return b;
        return clean(text);
    }

    /** 系统占位符：部分 App 在通知栏压缩布局里塞这些词，无实际信息量 */
    private static final String[] PLACEHOLDER_TITLES = {
            "GroupSummary", "Notification"
    };

    /**
     * 清理通知文本：
     * 1) 全角/不间断空格归一，连续空白压成单个空格并 trim
     * 2) 去掉系统占位符整词
     */
    public static String clean(String raw) {
        if (raw == null) return "";
        String s = raw.replace('\u00A0', ' ').replace('\u3000', ' ');
        s = s.replaceAll("[\\s]+", " ").trim();
        for (String ph : PLACEHOLDER_TITLES) {
            if (s.equalsIgnoreCase(ph)) return "";
            s = s.replaceAll("(?i)(^|[ ])" + java.util.regex.Pattern.quote(ph) + "([ ]|$)", " ")
                 .replaceAll("[\\s]+", " ").trim();
        }
        return s;
    }

    public String displayTitle() {
        String t = clean(title);
        if (!t.isEmpty()) return t;
        String sub = clean(subText);
        if (!sub.isEmpty()) return sub;
        return "(无标题)";
    }

    /**
     * 从历史决策记录还原成一张卡片（「待办」重新进入决策流时用）。
     * 注意：这类条目在原通知栏里已经不存在了（当初就被 cancel 掉了），
     * 所以 key 用的是历史记录里的旧 key，任何「取消/跳转」都不会生效，
     * App 侧对它们的处理是「只记录决策、不再调用 daemon」。
     */
    public static NotifItem fromDecision(com.nchat.model.Decision d) {
        NotifItem it = new NotifItem();
        it.key = d.notifKey;
        it.pkg = d.pkg;
        it.title = d.title;
        it.text = d.body;
        it.channelName = d.channelName;
        it.aiCategory = d.category;
        it.confidence = 1.0f;
        it.postTime = d.decidedAt;
        it.channelImportance = -1;
        // 标记为「已不存在于通知栏」
        it.fromTodo = true;
        it.decisionRowId = d.id;
        it.planAt = d.planAt;
        it.intentPkg = d.intentPkg;
        it.intentTarget = d.intentTarget;
        it.intentAction = d.intentAction;
        it.intentData = d.intentData;
        return it;
    }

    /** 从待办还原时带的计划时间（0=无） */
    public long planAt;

    /** 用于 AI 分类的紧凑文本 */
    public String forClassify() {
        return pkg + " | " + channelName + " | " + displayTitle() + " | " + body();
    }

    public String pkgUidChannel() {
        return pkg + "|" + uid + "|" + channel;
    }
}