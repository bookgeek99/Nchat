package com.nchat.ui.adapter;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.nchat.NchatApp;
import com.nchat.R;
import com.nchat.model.NotifCategory;
import com.nchat.model.NotifItem;
import com.nchat.util.NotifGrouper;
import com.nchat.util.TimeUtil;
import java.util.ArrayList;
import java.util.List;
/**
 * 「页」适配器：一页 = 一组相似通知，纵向排列。
 *
 * 交互模型（第十三/十四轮之后重新设计的）：
 *   - **卡片本身不带按钮**，只展示内容
 *   - **点卡片 = 立即办理这一条**（打开通知），办理完这张卡片自己消失
 *   - 其余操作（稍后 / 已阅 / 不感兴趣）走底部的公共按钮，
 *     作用于「当前选中的那一条」
 *   - 选中态：点一下卡片即选中（会高亮），底部按钮默认作用于第一条
 *
 * 这样一屏里能塞更多内容，也不必每条都重复四个按钮。
 */
public class CardAdapter extends RecyclerView.Adapter<CardAdapter.VH> {

    public interface OnCardAction {
        /** 点了卡片正文 = 立即办理这条 */
        void onCardClick(NotifItem item);
        /** 点卡片的原通知 action 按钮 */
        void onNotifAction(NotifItem item, int actionIndex);
    }
    private final List<List<NotifItem>> pages = new ArrayList<>();
    private OnCardAction listener;
    /** 当前选中的条目 —— 底部公共按钮作用于它 */
    private NotifItem selected;
    public void setOnCardAction(OnCardAction l) {
        this.listener = l;
    }
    public void submit(List<List<NotifItem>> newPages) {
        pages.clear();
        if (newPages != null) pages.addAll(newPages);
        // 选中的条目如果已经不在任何页里了，清掉选中态
        if (selected != null && !contains(selected)) selected = null;
        notifyDataSetChanged();
    }
    private boolean contains(NotifItem it) {
        for (List<NotifItem> p : pages) {
            if (p.contains(it)) return true;
        }
        return false;
    }
    /** 当前选中；没有选中时返回该页第一条 */
    public NotifItem getSelected(int pageIdx) {
        if (selected != null && contains(selected)) return selected;
        return getItem(pageIdx);
    }
    public void setSelected(NotifItem it) {
        if (selected == it) return;
        selected = it;
        notifyDataSetChanged();
    }
    public List<NotifItem> getPage(int pageIdx) {
        return pageIdx >= 0 && pageIdx < pages.size() ? pages.get(pageIdx) : null;
    }
    public NotifItem getItem(int pageIdx) {
        List<NotifItem> page = getPage(pageIdx);
        return page == null || page.isEmpty() ? null : page.get(0);
    }
    public int getCount() {
        return pages.size();
    }
    /** 总条数（不是页数）—— 进度显示用 */
    public int getTotalItems() {
        int n = 0;
        for (List<NotifItem> p : pages) n += p.size();
        return n;
    }
    /**
     * 从某页里移除一条。
     * @return 该页是否已空
     */
    public boolean removeItem(int pageIdx, NotifItem it) {
        if (pageIdx < 0 || pageIdx >= pages.size()) return false;
        List<NotifItem> page = pages.get(pageIdx);
        if (!page.remove(it)) return false;
        if (selected == it) {
            selected = page.isEmpty() ? null : page.get(0);
        }
        if (page.isEmpty()) {
            pages.remove(pageIdx);
            notifyItemRemoved(pageIdx);
            return true;
        }
        notifyItemChanged(pageIdx);
        return false;
    }
    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.page_cards, parent, false);
        return new VH(v);
    }
    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        List<NotifItem> page = pages.get(position);
        h.llCards.removeAllViews();
        String label = NotifGrouper.pageLabel(page);
        if (label.isEmpty()) {
            h.tvPageHeader.setVisibility(View.GONE);
        } else {
            h.tvPageHeader.setVisibility(View.VISIBLE);
            h.tvPageHeader.setText(label + " · 点卡片即办理");
        }
        for (NotifItem it : page) {
            h.llCards.addView(buildCard(h, it));
        }
    }
    /** 构建单条通知的卡片 View（无按钮） */
    private View buildCard(VH h, NotifItem it) {
        View v = LayoutInflater.from(h.itemView.getContext())
                .inflate(R.layout.item_card_row, h.llCards, false);
        NchatApp app = NchatApp.get();

        ImageView ivIcon = v.findViewById(R.id.ivIcon);
        TextView tvAppName = v.findViewById(R.id.tvAppName);
        TextView tvTime = v.findViewById(R.id.tvTime);
        TextView tvCategory = v.findViewById(R.id.tvCategory);
        TextView tvChannel = v.findViewById(R.id.tvChannel);
        TextView tvTitle = v.findViewById(R.id.tvTitle);
        ScrollView svBody = v.findViewById(R.id.svBody);
        TextView tvBody = v.findViewById(R.id.tvBody);
        View vCatBar = v.findViewById(R.id.vCatBar);
        View hsvActions = v.findViewById(R.id.hsvActions);
        LinearLayout llNotifActions = v.findViewById(R.id.llNotifActions);

        ivIcon.setImageDrawable(app.appIcon(it.pkg));
        tvAppName.setText(app.appLabel(it.pkg));
        tvTime.setText(TimeUtil.relative(it.postTime));
        tvTitle.setText(it.displayTitle());

        String body = it.body();
        tvBody.setText(body);
        svBody.setVisibility(body.isEmpty() ? View.GONE : View.VISIBLE);

        NotifCategory cat = it.aiCategory == null ? NotifCategory.OTHER : it.aiCategory;
        tvCategory.setText(cat.label);
        tvCategory.setTextColor(cat.color);
        setBarColor(vCatBar, cat.color);
        tvChannel.setText(it.channelName == null ? "" : it.channelName);

        // 原通知自带 action（这类是通知本身提供的快捷操作，保留）
        llNotifActions.removeAllViews();
        if (it.actions != null && !it.actions.isEmpty()) {
            hsvActions.setVisibility(View.VISIBLE);
            for (int i = 0; i < it.actions.size(); i++) {
                final int idx = i;
                TextView btn = new TextView(v.getContext());
                btn.setText(it.actions.get(i));
                btn.setTextSize(11);
                btn.setTextColor(0xFF9AA5B8);
                btn.setBackgroundResource(R.drawable.bg_chip);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(v, 8);
                btn.setLayoutParams(lp);
                btn.setPadding(dp(v, 12), dp(v, 6), dp(v, 12), dp(v, 6));
                btn.setOnClickListener(b -> {
                    if (listener != null) listener.onNotifAction(it, idx);
                });
                llNotifActions.addView(btn);
            }
        } else {
            hsvActions.setVisibility(View.GONE);
        }

        // 选中态描边：底部按钮作用于「选中的这条」，视觉上必须能看出来
        boolean isSel = (selected == it);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(v, 14));
        bg.setColor(0xFF1A1F29);
        bg.setStroke(dp(v, isSel ? 2 : 0), 0xFF4ADE80);
        v.setBackground(bg);

        // 点卡片 = 立即办理
        v.setClickable(true);
        v.setOnClickListener(b -> {
            if (listener != null) listener.onCardClick(it);
        });

        return v;
    }
    private void setBarColor(View v, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(v, 2));
        d.setColor(color);
        v.setBackground(d);
    }
    private int dp(View v, int val) {
        return (int) (val * v.getResources().getDisplayMetrics().density);
    }
    @Override
    public int getItemCount() {
        return pages.size();
    }
    static class VH extends RecyclerView.ViewHolder {
        TextView tvPageHeader;
        LinearLayout llCards;
        ScrollView svPage;
        VH(@NonNull View v) {
            super(v);
            tvPageHeader = v.findViewById(R.id.tvPageHeader);
            llCards = v.findViewById(R.id.llCards);
            svPage = v.findViewById(R.id.svPage);
        }
    }
}