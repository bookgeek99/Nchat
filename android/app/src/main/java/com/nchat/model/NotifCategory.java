package com.nchat.model;

/**
 * 通知分类枚举。
 */
public enum NotifCategory {
    INFO("信息", 0xFF38D9D9),
    MARKETING("营销", 0xFFFFA23A),
    TOOL("工具", 0xFF4ADE80),
    OTHER("其他", 0xFFA78BFA);

    public final String label;
    public final int color;

    NotifCategory(String label, int color) {
        this.label = label;
        this.color = color;
    }
}