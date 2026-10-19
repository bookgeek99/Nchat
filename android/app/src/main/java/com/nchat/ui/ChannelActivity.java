package com.nchat.ui;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.nchat.util.Insets;
import com.nchat.NchatApp;
import com.nchat.R;
import com.nchat.net.DaemonClient;
import com.nchat.root.Shell;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 渠道管理：按应用分组展示所有通知渠道，可单独开关。
 */
public class ChannelActivity extends AppCompatActivity {

    public static class PkgGroup {
        String pkg;
        int uid;
        String appName;
        List<Channel> channels = new ArrayList<>();
    }

    public static class Channel {
        String id;
        String name;
        String desc;
        int importance;
        boolean on() { return importance != 0; }
    }

    private RecyclerView recycler;
    private EditText etFilter;
    private ProgressBar progress;
    private TextView tvEmpty;
    private GroupAdapter adapter;

    private final List<PkgGroup> all = new ArrayList<>();
    private String filter = "";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_channel);
        // Android 15+ edge-to-edge：根视图同时补状态栏(顶)和导航栏(底)
        Insets.applyBoth(this, findViewById(android.R.id.content));

        recycler = findViewById(R.id.recycler);
        etFilter = findViewById(R.id.etFilter);
        progress = findViewById(R.id.progress);
        tvEmpty = findViewById(R.id.tvEmpty);

        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new GroupAdapter();
        recycler.setAdapter(adapter);

        etFilter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void afterTextChanged(Editable s) {
                filter = s.toString().trim();
                adapter.submit(filtered());
            }
        });

        load();
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);
        io.execute(() -> {
            // 用当前活跃通知里的包来枚举渠道；再补充历史里出现过的包
            List<PkgGroup> result = new ArrayList<>();
            List<String[]> pkgs = collectPackages();

            for (String[] pu : pkgs) {
                String pkg = pu[0];
                int uid;
                try { uid = Integer.parseInt(pu[1]); } catch (Throwable t) { uid = -1; }
                if (uid < 0) continue;

                String json = DaemonClient.channelsJson(pkg, uid);
                if (json == null) continue;
                try {
                    JSONObject o = new JSONObject(json);
                    if (!o.optBoolean("ok")) continue;
                    JSONArray arr = o.optJSONArray("channels");
                    if (arr == null || arr.length() == 0) continue;
                    PkgGroup g = new PkgGroup();
                    g.pkg = pkg;
                    g.uid = uid;
                    g.appName = NchatApp.get().appLabel(pkg);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject c = arr.optJSONObject(i);
                        if (c == null) continue;
                        Channel ch = new Channel();
                        ch.id = c.optString("id");
                        ch.name = c.optString("name");
                        ch.desc = c.optString("desc");
                        ch.importance = c.optInt("importance");
                        g.channels.add(ch);
                    }
                    if (!g.channels.isEmpty()) result.add(g);
                } catch (Throwable ignore) {}
            }

            final List<PkgGroup> f = result;
            ui.post(() -> {
                all.clear();
                all.addAll(f);
                progress.setVisibility(View.GONE);
                adapter.submit(filtered());
                tvEmpty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
                tvEmpty.setText(all.isEmpty() ? "未获取到渠道数据\n请确认守护进程运行中" : "");
            });
        });
    }

    /** 收集包名+uid：优先当前通知，其次历史 */
    private List<String[]> collectPackages() {
        Map<String, String> map = new LinkedHashMap<>();

        DaemonClient.State st = DaemonClient.fetchState();
        for (com.nchat.model.NotifItem it : st.items) {
            map.put(it.pkg, String.valueOf(it.uid));
        }

        if (map.isEmpty()) {
            // 兜底：从数据库历史里取
            List<com.nchat.model.Decision> hist =
                    com.nchat.data.NchatDb.get(this).queryHistory(-1, null, 200);
            for (com.nchat.model.Decision d : hist) {
                if (d.pkg != null && !map.containsKey(d.pkg)) {
                    String uid = pkgUid(d.pkg);
                    if (uid != null) map.put(d.pkg, uid);
                }
            }
        }

        List<String[]> list = new ArrayList<>();
        for (Map.Entry<String, String> e : map.entrySet()) {
            list.add(new String[]{e.getKey(), e.getValue()});
        }
        return list;
    }

    private String pkgUid(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(ai.uid);
        } catch (Throwable t) {
            return null;
        }
    }

    private List<PkgGroup> filtered() {
        if (filter.isEmpty()) return new ArrayList<>(all);
        String f = filter.toLowerCase();
        List<PkgGroup> out = new ArrayList<>();
        for (PkgGroup g : all) {
            if (g.appName.toLowerCase().contains(f) || g.pkg.toLowerCase().contains(f)) {
                out.add(g);
            }
        }
        return out;
    }

    private void toggle(PkgGroup g, Channel c, boolean on) {
        io.execute(() -> {
            boolean ok = on
                    ? DaemonClient.unmuteChannel(g.pkg, g.uid, c.id)
                    : DaemonClient.muteChannel(g.pkg, g.uid, c.id);
            ui.post(() -> {
                if (ok) {
                    c.importance = on ? 3 : 0;
                    adapter.notifyDataSetChanged();
                    toast(on ? "已开启" : "已关闭");
                } else {
                    toast("操作失败");
                    adapter.notifyDataSetChanged();
                }
            });
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ==================== Adapter ====================

    class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.VH> {

        private final List<PkgGroup> data = new ArrayList<>();

        void submit(List<PkgGroup> list) {
            data.clear();
            data.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_channel_group, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            PkgGroup g = data.get(position);
            h.tvAppName.setText(g.appName);
            h.tvPkg.setText(g.pkg);
            h.ivIcon.setImageDrawable(NchatApp.get().appIcon(g.pkg));

            int active = 0;
            for (Channel c : g.channels) if (c.on()) active++;
            h.tvActiveCount.setText(active + " / " + g.channels.size() + " 开启");

            h.llChannels.removeAllViews();
            for (Channel c : g.channels) {
                View row = LayoutInflater.from(h.itemView.getContext())
                        .inflate(R.layout.item_channel_row, h.llChannels, false);
                TextView tvName = row.findViewById(R.id.tvChName);
                TextView tvId = row.findViewById(R.id.tvChId);
                TextView tvState = row.findViewById(R.id.tvChState);
                android.widget.Switch sw = row.findViewById(R.id.swCh);

                String nm = c.name == null || c.name.isEmpty() ? c.id : c.name;
                tvName.setText(nm);
                tvId.setText(c.id);
                tvState.setText(c.on() ? "开启" : "已关闭");
                tvState.setTextColor(c.on() ? 0xFF4ADE80 : 0xFF5E6B80);

                sw.setOnCheckedChangeListener(null);
                sw.setChecked(c.on());
                sw.setOnCheckedChangeListener((btn, checked) -> toggle(g, c, checked));

                h.llChannels.addView(row);
            }
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            android.widget.ImageView ivIcon;
            TextView tvAppName, tvPkg, tvActiveCount;
            LinearLayout llChannels;

            VH(@NonNull View v) {
                super(v);
                ivIcon = v.findViewById(R.id.ivIcon);
                tvAppName = v.findViewById(R.id.tvAppName);
                tvPkg = v.findViewById(R.id.tvPkg);
                tvActiveCount = v.findViewById(R.id.tvActiveCount);
                llChannels = v.findViewById(R.id.llChannels);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}