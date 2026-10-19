package com.nchat.ui.adapter;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.nchat.NchatApp;
import com.nchat.R;
import com.nchat.model.NotifCategory;
import com.nchat.model.NotifItem;
import com.nchat.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 卡片流适配器（ViewPager2 使用）。
 */
public class CardAdapter extends RecyclerView.Adapter<CardAdapter.VH> {

    public interface OnNotifActionClick {
        void onActionClick(NotifItem item, int actionIndex);
    }

    private final List<NotifItem> items = new ArrayList<>();
    private OnNotifActionClick actionListener;

    public void setOnNotifActionClick(OnNotifActionClick l) {
        this.actionListener = l;
    }

    public void submit(List<NotifItem> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public NotifItem getItem(int pos) {
        return pos >= 0 && pos < items.size() ? items.get(pos) : null;
    }

    public int getCount() {
        return items.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_card, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        NotifItem it = items.get(position);
        NchatApp app = NchatApp.get();

        h.tvAppName.setText(app.appLabel(it.pkg));
        h.ivIcon.setImageDrawable(app.appIcon(it.pkg));
        h.tvTime.setText(TimeUtil.relative(it.postTime));
        h.tvTitle.setText(it.displayTitle());

        String body = it.body();
        h.tvBody.setText(body);
        h.tvBody.setVisibility(body.isEmpty() ? View.GONE : View.VISIBLE);

        NotifCategory cat = it.aiCategory == null ? NotifCategory.OTHER : it.aiCategory;
        h.tvCategory.setText(cat.label);
        h.tvCategory.setTextColor(cat.color);
        setBarColor(h.vCatBar, cat.color);

        String chName = it.channelName == null ? "" : it.channelName;
        h.tvChannel.setText(chName);

        // 原通知的快捷 action
        h.llNotifActions.removeAllViews();
        if (it.actions != null && !it.actions.isEmpty()) {
            h.hsvActions.setVisibility(View.VISIBLE);
            for (int i = 0; i < it.actions.size(); i++) {
                final int idx = i;
                TextView btn = new TextView(h.itemView.getContext());
                btn.setText(it.actions.get(i));
                btn.setTextSize(12);
                btn.setTextColor(0xFF9AA5B8);
                btn.setBackgroundResource(R.drawable.bg_chip);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(h.itemView, 8);
                btn.setLayoutParams(lp);
                btn.setPadding(dp(h.itemView, 14), dp(h.itemView, 8), dp(h.itemView, 14), dp(h.itemView, 8));
                btn.setOnClickListener(v -> {
                    if (actionListener != null) actionListener.onActionClick(it, idx);
                });
                h.llNotifActions.addView(btn);
            }
        } else {
            h.hsvActions.setVisibility(View.GONE);
        }
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
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivIcon;
        TextView tvAppName, tvTime, tvCategory, tvChannel, tvTitle, tvBody;
        View vCatBar;
        LinearLayout llNotifActions;
        View hsvActions;

        VH(@NonNull View v) {
            super(v);
            ivIcon = v.findViewById(R.id.ivIcon);
            tvAppName = v.findViewById(R.id.tvAppName);
            tvTime = v.findViewById(R.id.tvTime);
            tvCategory = v.findViewById(R.id.tvCategory);
            tvChannel = v.findViewById(R.id.tvChannel);
            tvTitle = v.findViewById(R.id.tvTitle);
            tvBody = v.findViewById(R.id.tvBody);
            vCatBar = v.findViewById(R.id.vCatBar);
            llNotifActions = v.findViewById(R.id.llNotifActions);
            hsvActions = v.findViewById(R.id.hsvActions);
        }
    }
}