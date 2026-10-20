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
            case Decision.TYPE_SEEN: status = "已阅"; break;
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
        // ---- v3 来源追踪：展示 intent 来源 / 跳转结果，便于事后排查 ----
        String trace = buildTrace(d);
        if (trace.isEmpty()) {
            h.tvTrace.setVisibility(View.GONE);
        } else {
            h.tvTrace.setText(trace);
            h.tvTrace.setVisibility(View.VISIBLE);
        }

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

    /**
     * 拼一行「来源追踪」文本。
     * 刻意保持精简：一段话能读完，且可选中复制（方便贴进日志）。
     *
     * 典型输出：
     *   来源 com.xiaomi.xmsf -> com.tencent.mm.ui.LauncherUI  [跳转成功 320ms]
     *   来源 com.ruanmei.ithome  id=43250940  flags=0x18  ch=news
     */
    private static String buildTrace(Decision d) {
        StringBuilder sb = new StringBuilder();
        if (d.intentPkg != null && !d.intentPkg.isEmpty()) {
            sb.append("来源 ").append(d.intentPkg);
        }
        if (d.intentTarget != null && !d.intentTarget.isEmpty()) {
            if (sb.length() > 0) sb.append(" -> ");
            sb.append(d.intentTarget);
        } else if (d.notifId != 0) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("id=").append(d.notifId);
        }
        if (d.notifFlags != 0) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("flags=0x").append(Integer.toHexString(d.notifFlags));
        }
        if (d.notifChannel != null && !d.notifChannel.isEmpty()) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("ch=").append(d.notifChannel);
        }
        if (d.notifTag != null && !d.notifTag.isEmpty()) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("tag=").append(d.notifTag);
        }
        if (d.isGroupSummary) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("分组摘要");
        }
        if (d.jumpResult == Decision.JUMP_OK) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("[跳转成功");
            if (d.jumpCostMs >= 0) sb.append(" ").append(d.jumpCostMs).append("ms");
            sb.append("]");
        } else if (d.jumpResult == Decision.JUMP_FAIL) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("[跳转失败]");
        }
        return sb.toString();
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
        TextView tvCat, tvApp, tvStatus, tvTitle, tvBody, tvTime, tvTrace, btnTodoDone, btnTodoDelete;
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
            tvTrace = v.findViewById(R.id.tvTrace);
            btnTodoDone = v.findViewById(R.id.btnTodoDone);
            btnTodoDelete = v.findViewById(R.id.btnTodoDelete);
            llTodoOps = v.findViewById(R.id.llTodoOps);
        }
    }
}