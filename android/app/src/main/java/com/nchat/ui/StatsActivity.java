package com.nchat.ui;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.nchat.NchatApp;
import com.nchat.R;
import com.nchat.data.NchatDb;
import com.nchat.model.Decision;
import com.nchat.util.Insets;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统计页。
 *
 * 目的不只是「看个数字」，而是**为将来的智能判断积累和展示样本**：
 *  - 各类决策的分布：知道用户整体偏"已阅"还是偏"不感兴趣"
 *  - 按渠道的决策分布：某个渠道如果长期高「不感兴趣」，就值得建议直接屏蔽
 *  - 时段分布：知道通知高峰在哪，方便做免打扰
 *
 * 其中「已阅」和「不感兴趣」必须分开统计 ——
 * 前者是"有价值但不用展开"，后者是"我不想要"，混在一起就没法用于训练/推断。
 */
public class StatsActivity extends AppCompatActivity {

    private NchatDb db;
    private LinearLayout content;
    private TextView tvRange;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    /** 统计区间：0=全部, 1=今天, 2=近 7 天, 3=近 30 天 */
    private int range = 0;

    private static final String[] RANGE_LABELS = {"全部", "今天", "近 7 天", "近 30 天"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        db = NchatDb.get(this);
        setContentView(buildUi());
        Insets.applyBoth(this, findViewById(android.R.id.content));
        reload();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));

        // 顶部：标题 + 区间切换
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(20), dp(18), dp(20), dp(8));

        TextView title = new TextView(this);
        title.setText("使用统计");
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(22);
        top.addView(title);

        TextView sub = new TextView(this);
        sub.setText("记录每一条通知的处理方式，为将来的智能判断积累样本");
        sub.setTextColor(getColor(R.color.text_tertiary));
        sub.setTextSize(12);
        sub.setPadding(0, dp(4), 0, dp(12));
        top.addView(sub);

        // 区间切换按钮行
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < RANGE_LABELS.length; i++) {
            final int idx = i;
            TextView chip = new TextView(this);
            chip.setText(RANGE_LABELS[i]);
            chip.setTextSize(13);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(12), dp(7), dp(12), dp(7));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            chip.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                range = idx;
                reload();
            });
            chips.addView(chip);
        }
        top.addView(chips);
        root.addView(top);

        tvRange = sub;

        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(28));
        sv.addView(content);
        root.addView(sv);

        // insets 要在 setContentView 之后才能拿到真实值，这里先返回，
        // onCreate 里再补
        return root;
    }

    private long sinceTs() {
        long now = System.currentTimeMillis();
        switch (range) {
            case 1: return com.nchat.util.TimeUtil.startOfDay(now);
            case 2: return now - 7L * 24 * 3600 * 1000;
            case 3: return now - 30L * 24 * 3600 * 1000;
            default: return 0;
        }
    }

    private void reload() {
        rebuildChips();
        io.execute(() -> {
            final long since = sinceTs();
            final List<NchatDb.TypeCount> byType = db.statsByType(since);
            final List<NchatDb.ChannelStat> byChannel = db.statsByChannel(3);
            final int[] hours = db.hourHistogram();
            final int totalAll = db.countAll();
            final int total = sum(byType);
            ui.post(() -> render(byType, byChannel, hours, total, totalAll));
        });
    }

    private static int sum(List<NchatDb.TypeCount> l) {
        int n = 0;
        for (NchatDb.TypeCount t : l) n += t.count;
        return n;
    }

    private void rebuildChips() {
        // 简化处理：整页重绘时 chip 状态在 render 里同步
    }

    private void render(List<NchatDb.TypeCount> byType, List<NchatDb.ChannelStat> byChannel,
                        int[] hours, int total, int totalAll) {
        content.removeAllViews();

        if (total == 0) {
            TextView tv = new TextView(this);
            tv.setText("还没有可供统计的记录。\n处理几条通知后再来看。");
            tv.setTextColor(getColor(R.color.text_tertiary));
            tv.setTextSize(14);
            tv.setPadding(0, dp(24), 0, 0);
            content.addView(tv);
            return;
        }

        // ── 概览 ──
        content.addView(sectionTitle("总览"));
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.addView(statCard("已处理", String.valueOf(total), "条", 0xFF4ADE80), weightLp(0));
        cards.addView(statCard("累计", String.valueOf(totalAll), "条", 0xFF60A5FA), weightLp(1));
        content.addView(cards);

        // ── 决策分布（关键：已阅 / 不感兴趣 分开） ──
        content.addView(sectionTitle("决策分布"));
        int[] order = {
                Decision.TYPE_SEEN, Decision.TYPE_DONE, Decision.TYPE_IGNORE,
                Decision.TYPE_READ_LATER, Decision.TYPE_DO_LATER, Decision.TYPE_MUTED
        };
        int[] barColors = {
                0xFF4ADE80,   // 已阅
                0xFF22C55E,   // 已完成
                0xFFFF7A8A,   // 不感兴趣
                0xFF60A5FA,   // 稍后读
                0xFF818CF8,   // 稍后办
                0xFF9CA3AF    // 已屏蔽
        };
        for (int i = 0; i < order.length; i++) {
            int cnt = find(byType, order[i]);
            if (cnt == 0) continue;
            content.addView(barRow(Decision.typeLabel(order[i]), cnt, total, barColors[i]));
        }

        // ── 渠道维度 ──
        content.addView(sectionTitle("渠道画像"));
        TextView hint = new TextView(this);
        hint.setText("按「不感兴趣」占比排序。占比高的渠道，可以直接考虑屏蔽。");
        hint.setTextColor(getColor(R.color.text_tertiary));
        hint.setTextSize(11);
        hint.setPadding(0, 0, 0, dp(8));
        content.addView(hint);

        int shown = 0;
        for (NchatDb.ChannelStat st : byChannel) {
            if (st.total < 3) continue;
            if (shown++ >= 12) break;
            content.addView(channelRow(st));
        }
        if (shown == 0) {
            TextView tv = new TextView(this);
            tv.setText("样本还太少（每个渠道至少 3 条才会显示）");
            tv.setTextColor(getColor(R.color.text_tertiary));
            tv.setTextSize(12);
            content.addView(tv);
        }

        // ── 时段分布 ──
        content.addView(sectionTitle("活跃时段"));
        content.addView(hourChart(hours));
    }

    private LinearLayout.LayoutParams weightLp(int idx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (idx > 0) lp.leftMargin = dp(10);
        return lp;
    }

    private View statCard(String label, String value, String unit, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0xFF1A1D24);
        box.setBackground(bg);

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextColor(getColor(R.color.text_tertiary));
        tvLabel.setTextSize(12);
        box.addView(tvLabel);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        TextView tvVal = new TextView(this);
        tvVal.setText(value);
        tvVal.setTextColor(color);
        tvVal.setTextSize(26);
        row.addView(tvVal);
        TextView tvUnit = new TextView(this);
        tvUnit.setText(" " + unit);
        tvUnit.setTextColor(getColor(R.color.text_tertiary));
        tvUnit.setTextSize(12);
        row.addView(tvUnit);
        box.addView(row);
        return box;
    }

    private View sectionTitle(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(getColor(R.color.text_primary));
        tv.setTextSize(15);
        tv.setPadding(0, dp(22), 0, dp(10));
        return tv;
    }

    /** 一行「标签 + 进度条 + 数量」 */
    private View barRow(String label, int cnt, int total, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextColor(getColor(R.color.text_secondary));
        tvLabel.setTextSize(13);
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(tvLabel);

        TextView tvCnt = new TextView(this);
        int pct = total <= 0 ? 0 : Math.round(cnt * 100f / total);
        tvCnt.setText(cnt + " 条 · " + pct + "%");
        tvCnt.setTextColor(getColor(R.color.text_tertiary));
        tvCnt.setTextSize(12);
        head.addView(tvCnt);
        row.addView(head);

        // 进度条
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6)));
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setCornerRadius(dp(3));
        trackBg.setColor(0xFF23262F);
        track.setBackground(trackBg);
        track.setPadding(0, dp(10), 0, 0);

        LinearLayout barWrap = new LinearLayout(this);
        barWrap.setOrientation(LinearLayout.HORIZONTAL);
        barWrap.setPadding(0, dp(4), 0, 0);
        View bar = new View(this);
        int w = Math.max(1, Math.round(getResources().getDisplayMetrics().widthPixels
                * 0.85f * (total <= 0 ? 0 : cnt / (float) total)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(w, dp(6));
        bar.setLayoutParams(blp);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setCornerRadius(dp(3));
        barBg.setColor(color);
        bar.setBackground(barBg);
        barWrap.addView(bar);
        row.addView(barWrap);

        return row;
    }

    private View channelRow(NchatDb.ChannelStat st) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(11), dp(14), dp(11));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0xFF1A1D24);
        box.setBackground(bg);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        boxLp.bottomMargin = dp(8);
        box.setLayoutParams(boxLp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvApp = new TextView(this);
        tvApp.setText(NchatApp.get().appLabel(st.pkg));
        tvApp.setTextColor(getColor(R.color.text_secondary));
        tvApp.setTextSize(11);
        left.addView(tvApp);

        TextView tvCh = new TextView(this);
        tvCh.setText(st.channelName == null || st.channelName.isEmpty()
                ? "(未命名渠道)" : st.channelName);
        tvCh.setTextColor(getColor(R.color.text_primary));
        tvCh.setTextSize(13);
        tvCh.setMaxLines(1);
        tvCh.setEllipsize(android.text.TextUtils.TruncateAt.END);
        left.addView(tvCh);
        head.addView(left);

        TextView tvRatio = new TextView(this);
        int pct = Math.round(st.negativeRatio() * 100);
        tvRatio.setText(pct + "%");
        tvRatio.setTextSize(18);
        tvRatio.setTextColor(pct >= 60 ? 0xFFFF7A8A
                : pct >= 30 ? 0xFFFBBF24 : 0xFF4ADE80);
        head.addView(tvRatio);
        box.addView(head);

        TextView tvDetail = new TextView(this);
        tvDetail.setText("共 " + st.total + " 条：" + st.seen + " 已阅 · "
                + st.ignore + " 不感兴趣 · " + st.done + " 已完成 · " + st.later + " 稍后");
        tvDetail.setTextColor(getColor(R.color.text_tertiary));
        tvDetail.setTextSize(11);
        tvDetail.setPadding(0, dp(5), 0, 0);
        box.addView(tvDetail);

        return box;
    }

    /** 24 小时柱状图（简易，用字符高度表示） */
    private View hourChart(int[] hours) {
        int max = 1;
        for (int h : hours) max = Math.max(max, h);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.BOTTOM);
        wrap.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(90)));

        for (int i = 0; i < 24; i++) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.BOTTOM);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(90), 1f);
            col.setLayoutParams(clp);

            View bar = new View(this);
            int h = hours[i] == 0 ? dp(2) : Math.max(dp(4), dp(64) * hours[i] / max);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, h);
            blp.leftMargin = dp(1);
            blp.rightMargin = dp(1);
            bar.setLayoutParams(blp);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(2));
            bg.setColor(hours[i] == 0 ? 0xFF2A2E38 : 0xFF60A5FA);
            bar.setBackground(bg);
            col.addView(bar);
            wrap.addView(col);
        }

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.addView(wrap);

        TextView axis = new TextView(this);
        axis.setText("0    6    12    18    23 时");
        axis.setTextColor(getColor(R.color.text_tertiary));
        axis.setTextSize(10);
        axis.setPadding(0, dp(4), 0, 0);
        outer.addView(axis);

        int peak = 0;
        for (int i = 1; i < 24; i++) if (hours[i] > hours[peak]) peak = i;
        if (hours[peak] > 0) {
            TextView tvPeak = new TextView(this);
            tvPeak.setText("通知最多时段：" + peak + ":00 - " + (peak + 1) + ":00（"
                    + hours[peak] + " 条）");
            tvPeak.setTextColor(getColor(R.color.text_secondary));
            tvPeak.setTextSize(12);
            tvPeak.setPadding(0, dp(8), 0, 0);
            outer.addView(tvPeak);
        }
        return outer;
    }

    private static int find(List<NchatDb.TypeCount> l, int type) {
        for (NchatDb.TypeCount t : l) if (t.type == type) return t.count;
        return 0;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}