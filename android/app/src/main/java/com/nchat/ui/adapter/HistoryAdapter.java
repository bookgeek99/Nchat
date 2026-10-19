package com.nchat.ui.adapter;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.nchat.NchatApp;
import com.nchat.R;
import com.nchat.model.Decision;
import com.nchat.model.NotifCategory;
import com.nchat.util.TimeUtil;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 历史 / 待办列表适配器。
 */
public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {

    public interface Listener {
        void onTodoDone(Decision d);
        void onTodoDelete(Decision d);
    }

    private final List<Decision> items = new ArrayList<>();
    private boolean todoMode;
    private Listener listener;

    private static final SimpleDateFormat FULL =
            new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA);

    public void setTodoMode(boolean b) { this.todoMode = b; }

    public void setListener(Listener l) { this.listener = l; }

    public void submit(List<Decision> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Decision d = items.get(position);
        NotifCategory cat = d.category == null ? NotifCategory.OTHER : d.category;

        h.tvCat.setText(cat.label);
        h.tvCat.setTextColor(cat.color);
        setBar(h.vCat, cat.color);

        h.tvApp.setText(d.pkg == null ? "" : NchatApp.get().appLabel(d.pkg));

        String status;
        switch (d.type) {
            case Decision.TYPE_IGNORE: status = "不感兴趣"; break;
            case Decision.TYPE_READ_LATER: status = "稍后读"; break;
            case Decision.TYPE_DO_LATER: status = "稍后办"; break;
            case Decision.TYPE_DONE: status = "已完成"; break;
            case Decision.TYPE_MUTED: status = "已屏蔽"; break;
            default: status = ""; break;
        }
        if (todoMode && d.todoPending) status = "待办中";
        h.tvStatus.setText(status);

        h.tvTitle.setText(d.title == null ? "" : d.title);
        String body = d.body == null ? "" : d.body;
        h.tvBody.setText(body);
        h.tvBody.setVisibility(body.isEmpty() ? View.GONE : View.VISIBLE);

        StringBuilder meta = new StringBuilder(TimeUtil.relative(d.decidedAt));
        if (d.channelName != null && !d.channelName.isEmpty()) {
            meta.append(" · ").append(d.channelName);
        }
        h.tvTime.setText(meta);

        boolean showOps = todoMode && d.todoPending;
        h.llTodoOps.setVisibility(showOps ? View.VISIBLE : View.GONE);
        if (showOps) {
            h.btnTodoDone.setOnClickListener(v -> {
                if (listener != null) listener.onTodoDone(d);
            });
            h.btnTodoDelete.setOnClickListener(v -> {
                if (listener != null) listener.onTodoDelete(d);
            });
        }
    }

    private void setBar(View v, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(2 * v.getResources().getDisplayMetrics().density);
        g.setColor(color);
        v.setBackground(g);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    static class VH extends RecyclerView.ViewHolder {
        View vCat;
        TextView tvCat, tvApp, tvStatus, tvTitle, tvBody, tvTime, btnTodoDone, btnTodoDelete;
        LinearLayout llTodoOps;

        VH(@NonNull View v) {
            super(v);
            vCat = v.findViewById(R.id.vCat);
            tvCat = v.findViewById(R.id.tvCat);
            tvApp = v.findViewById(R.id.tvApp);
            tvStatus = v.findViewById(R.id.tvStatus);
            tvTitle = v.findViewById(R.id.tvTitle);
            tvBody = v.findViewById(R.id.tvBody);
            tvTime = v.findViewById(R.id.tvTime);
            btnTodoDone = v.findViewById(R.id.btnTodoDone);
            btnTodoDelete = v.findViewById(R.id.btnTodoDelete);
            llTodoOps = v.findViewById(R.id.llTodoOps);
        }
    }
}