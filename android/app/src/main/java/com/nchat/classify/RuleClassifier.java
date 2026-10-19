package com.nchat.classify;

import com.nchat.data.NchatDb;
import com.nchat.model.NotifCategory;
import com.nchat.model.NotifItem;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 规则分类引擎。
 * 优先级：用户手工规则 > 已知包名 > 渠道名关键词 > 标题/正文关键词 > 兜底
 */
public class RuleClassifier {

    /** 明显是资讯/内容类应用 */
    private static final Set<String> INFO_PKGS = new HashSet<>(Arrays.asList(
            "com.ruanmei.ithome",              // IT之家
            "com.ss.android.article.news",     // 今日头条
            "com.netease.newsreader.activity", // 网易新闻
            "com.tencent.news",                // 腾讯新闻
            "com.zhihu.android",               // 知乎
            "tv.danmaku.bili",                 // B站
            "com.xingin.xhs",                  // 小红书
            "com.sina.weibo",                  // 微博
            "com.ss.android.ugc.aweme",        // 抖音
            "com.smile.gifmaker",              // 快手
            "com.coolapk.market",              // 酷安
            "com.zhongduomei.ricechen",        // 米游社
            "com.xiaomi.gamecenter",           // 小米游戏中心（资讯推送）
            "com.ifeng.news2",                 // 凤凰新闻
            "com.tencent.qqsports",            // 腾讯体育
            "com.douban.frodo"                 // 豆瓣
    ));

    /** 工具/服务类应用（外卖、快递、打车、导航、支付、出行） */
    private static final Set<String> TOOL_PKGS = new HashSet<>(Arrays.asList(
            "com.sankuai.meituan",             // 美团
            "com.sankuai.meituan.takeoutnew",  // 美团外卖
            "me.ele",                          // 饿了么
            "com.taobao.idlefish",             // 闲鱼
            "com.taobao.taobao",               // 淘宝（订单类）
            "com.jingdong.app.mall",           // 京东
            "com.xunmeng.pinduoduo",           // 拼多多
            "com.jd.jrapp",                    // 京东金融
            "com.sdu.didi.psnger",             // 滴滴出行
            "com.autonavi.minimap",            // 高德地图
            "com.baidu.BaiduMap",              // 百度地图
            "com.tencent.map",                 // 腾讯地图
            "com.cainiao.wireless",            // 菜鸟裹裹
            "com.taobao.caipiao.agentmock",    // 菜鸟
            "com.sf.activity",                 // 顺丰速运
            "com.zto.assistant",               // 中通
            "com.yunhan.sto",                  // 申通
            "com.ytoxl.yto",                   // 圆通
            "cn.com.jd.jdmobilelite",          // 京东快递
            "com.alibaba.aliexpresshd",        // 速卖通
            "com.eg.android.AlipayGphone",     // 支付宝（交易类）
            "com.tencent.mm",                  // 微信（消息不算工具，但支付通知算）
            "com.achievo.vipshop",             // 唯品会
            "com.meituan.retail.v.android",    // 美团买菜
            "com.dianping.v1",                 // 大众点评
            "com.miui.player",                 // 音乐（播放控制会被过滤）
            "com.xiaomi.scanner"               // 小米扫一扫
    ));

    /** 关键词：营销/促销 */
    private static final String[] MARKETING_KW = {
            "优惠", "折扣", "立减", "满减", "红包", "券", "秒杀", "限时",
            "特价", "促销", "活动", "抽奖", "免费", "0元", "低价", "补贴",
            "新品", "发布", "开售", "预售", "抢先", "首发", "来抢", "疯抢",
            "会员日", "大促", "年货节", "双11", "双11", "618", "狂欢",
            "领取", "送你", "送你一张", "专属", "福利", "礼包", "返现",
            "报名", "邀请", "砍价", "拼团", "百亿补贴", "省钱"
    };

    /** 关键词：工具/待办 */
    private static final String[] TOOL_KW = {
            "订单", "已接单", "配送", "骑手", "预计送达", "已送达", "取餐",
            "快递", "已发出", "已签收", "派送", "到货", "取件", "驿站", "柜",
            "司机", "已派单", "行程", "用车", "接驾", "预计到达",
            "导航", "路线", "拥堵", "前方", "已到达目的地",
            "支付成功", "付款成功", "到账", "扣款", "账单", "还款",
            "验证码", "取票", "登机", "检票", "车次", "航班",
            "充电", "连接", "配对", "更新完成", "下载完成", "同步"
    };

    /** 关键词：资讯 */
    private static final String[] INFO_KW = {
            "新闻", "资讯", "热点", "快讯", "头条", "报道", "评论", "专栏",
            "观点", "分析", "深度", "专访", "发布会", "技术", "行业",
            "更新了", "新视频", "直播", "开播", "追番", "更新至",
            "热搜", "推荐", "阅读", "文章", "答主", "关注的人"
    };

    /**
     * 分类。返回结果写入 item.aiCategory / item.confidence。
     * @param db 用于查用户手工规则和缓存，可为 null
     */
    public static void classify(NotifItem item, NchatDb db) {
        // 0) 用户手工规则优先
        if (db != null) {
            NotifCategory rule = db.getPkgRule(item.pkg);
            if (rule != null) {
                item.aiCategory = rule;
                item.confidence = 1.0f;
                return;
            }
        }

        // 1) 包名硬匹配
        if (TOOL_PKGS.contains(item.pkg)) {
            item.aiCategory = NotifCategory.TOOL;
            item.confidence = 0.9f;
            return;
        }
        if (INFO_PKGS.contains(item.pkg)) {
            item.aiCategory = NotifCategory.INFO;
            item.confidence = 0.85f;
            return;
        }

        // 2) 渠道名匹配
        String ch = (item.channelName == null ? "" : item.channelName);
        if (containsAny(ch, "营销", "活动", "优惠", "福利", "推荐", "广告", "推广")) {
            item.aiCategory = NotifCategory.MARKETING;
            item.confidence = 0.8f;
            return;
        }
        if (containsAny(ch, "订单", "物流", "配送", "行程", "服务")) {
            item.aiCategory = NotifCategory.TOOL;
            item.confidence = 0.8f;
            return;
        }
        if (containsAny(ch, "资讯", "新闻", "内容", "动态", "更新")) {
            item.aiCategory = NotifCategory.INFO;
            item.confidence = 0.75f;
            return;
        }

        // 3) 文本关键词打分
        String text = (item.displayTitle() + " " + item.body()).toLowerCase();
        int mkt = score(text, MARKETING_KW);
        int tool = score(text, TOOL_KW);
        int info = score(text, INFO_KW);

        int best = Math.max(mkt, Math.max(tool, info));
        if (best == 0) {
            item.aiCategory = NotifCategory.OTHER;
            item.confidence = 0.3f;
            return;
        }
        if (mkt == best) {
            item.aiCategory = NotifCategory.MARKETING;
            item.confidence = 0.6f + Math.min(0.25f, mkt * 0.08f);
        } else if (tool == best) {
            item.aiCategory = NotifCategory.TOOL;
            item.confidence = 0.6f + Math.min(0.25f, tool * 0.08f);
        } else {
            item.aiCategory = NotifCategory.INFO;
            item.confidence = 0.6f + Math.min(0.25f, info * 0.08f);
        }
    }

    private static int score(String text, String[] kws) {
        int s = 0;
        for (String kw : kws) {
            if (text.contains(kw.toLowerCase())) s++;
        }
        return s;
    }

    private static boolean containsAny(String s, String... kws) {
        for (String kw : kws) if (s.contains(kw)) return true;
        return false;
    }
}