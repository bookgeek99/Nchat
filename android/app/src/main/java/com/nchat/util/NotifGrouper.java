package com.nchat.util;
import com.nchat.model.NotifItem;
import java.util.ArrayList;
import java.util.List;
/**
 * 相似通知「分组」—— 注意是分组，**不是合并**。
 *
 * 设计目标的转变：
 *   第一版把相似通知合并成一张卡片，只留一个「已阅 N 条」按钮。
 *   这等于替用户做了判断：万一两件事其实不一样，用户就没法分别处理了。
 *
 *   现在改成：相似通知**仍然是各自独立的条目**，有自己的按钮、自己的决策记录，
 *   只是被放进同一个「页」里上下排列展示。
 *   一个页面 = 一组相似通知，用户能在一屏里连续处理，不用左右翻页，
 *   但每一条还是自己决定去留。
 *
 * 数据结构：返回 List<List<NotifItem>>。外层是页，内层是这一页里的条目。
 * 单条通知就是只有一个元素的页。
 */
public final class NotifGrouper {
    private NotifGrouper() {}
    /**
     * 把通知按「相似」分组成页。
     * 组的顺序 = 组内第一条在输入里的顺序（保持通知栏原有次序）。
     * 组内顺序 = 输入顺序（通常最新在前）。
     */
    public static List<List<NotifItem>> group(List<NotifItem> input) {
        List<List<NotifItem>> pages = new ArrayList<>();
        if (input == null || input.isEmpty()) return pages;
        for (NotifItem it : input) {
            if (it == null) continue;
            List<NotifItem> host = findGroup(pages, it);
            if (host == null) {
                List<NotifItem> page = new ArrayList<>();
                page.add(it);
                pages.add(page);
            } else {
                host.add(it);
            }
        }
        return pages;
    }
    private static List<NotifItem> findGroup(List<List<NotifItem>> pages, NotifItem it) {
        for (List<NotifItem> page : pages) {
            NotifItem first = page.get(0);
            // 从待办还原的条目单独站着：它们的 key 在通知栏里已经不存在了，
            // 和真实通知混在一起会让整组操作的含义变得含糊。
            if (first.fromTodo != it.fromTodo) continue;
            if (first.isSimilarTo(it)) return page;
        }
        return null;
    }
    /** 一页里有多条时，给个「N 条相似」的抬头文案 */
    public static String pageLabel(List<NotifItem> page) {
        if (page == null || page.size() <= 1) return "";
        return page.size() + " 条相似";
    }
    /** 这一页里所有条目（用于批量登记 handledThisSession） */
    public static List<NotifItem> flatten(List<List<NotifItem>> pages) {
        List<NotifItem> all = new ArrayList<>();
        for (List<NotifItem> page : pages) all.addAll(page);
        return all;
    }
}