package com.nchat.util;
import com.nchat.model.NotifItem;
import java.util.ArrayList;
import java.util.List;
/**
 * 相似通知聚合。
 *
 * 场景：同一个 App 在短时间内反复推同一类通知
 * （「新消息：任务A已完成」「新消息：任务B已完成」…十几条），
 * 逐条看没什么意义，用户想要的是一次「已阅」全部清掉。
 *
 * 做法：把满足 {@link NotifItem#isSimilarTo} 的条目合并到第一条上，
 * 第一条的 groupMembers 存其余成员，groupSize 存总数。
 * 合并后的卡片在 UI 上显示「N 条相似」，操作时对整组生效。
 *
 * 设计取舍：
 *   - **只做单层聚合**，不做多级。通知栏里同 App 同渠道的相似通知，
 *     通常也就 2~10 条，一层就够。
 *   - **保持原有顺序**：以每条通知第一次出现的位置为准，
 *     组内其它成员从队列里移除，代表卡片留在原位。
 *   - 不做基于时间的窗口限制。通知栏里的东西本来就是「当前还没处理的」，
 *     同一渠道同标题的，不管隔多久都该一起看。
 */
public final class NotifGrouper {
    private NotifGrouper() {}
    /**
     * 就地聚合。返回的列表里每个元素都可能是「组代表」。
     * 输入的 list 不会被修改。
     */
    public static List<NotifItem> group(List<NotifItem> input) {
        if (input == null || input.size() <= 1) return input;
        List<NotifItem> out = new ArrayList<>();
        for (NotifItem it : input) {
            if (it == null) continue;
            // 找已有组代表里第一个相似且还没满的
            NotifItem host = findHost(out, it);
            if (host == null) {
                // 自己当代表。注意：从待办还原的条目已有 decisionRowId，
                // 不能被别人吞并（它们的 key 在通知栏里已不存在），
                // 但可以被其它同类吸收进去 —— 这里统一当代表处理。
                it.groupMembers = new ArrayList<>();
                it.groupSize = 1;
                out.add(it);
            } else {
                host.groupMembers.add(it);
                host.groupSize = host.groupMembers.size() + 1;
                // 组的时间取最新一条：用户看到「刚刚」比看到「3小时前」更符合预期
                if (it.postTime > host.postTime) host.postTime = it.postTime;
            }
        }
        return out;
    }
    private static NotifItem findHost(List<NotifItem> out, NotifItem it) {
        for (NotifItem host : out) {
            // 从待办还原的条目单独站着，不吞也不被吞
            if (host.fromTodo != it.fromTodo) continue;
            if (host.isSimilarTo(it)) return host;
        }
        return null;
    }
    /** 组内所有通知的标题摘要，用于卡片展示（最多取 3 条） */
    public static String memberPreview(NotifItem host) {
        if (host == null || !host.isGrouped()) return "";
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (NotifItem m : host.groupMembers) {
            if (shown >= 3) break;
            String b = m.body();
            if (b.isEmpty()) b = m.displayTitle();
            if (b.isEmpty()) continue;
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(b);
            shown++;
        }
        if (host.groupSize - 1 > shown) {
            sb.append("  ·  …等 ").append(host.groupSize).append(" 条");
        }
        return sb.toString();
    }
}