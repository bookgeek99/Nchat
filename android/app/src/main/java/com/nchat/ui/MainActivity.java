package com.nchat.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.nchat.R;
import com.nchat.classify.AiClassifier;
import com.nchat.classify.RuleClassifier;
import com.nchat.data.NchatDb;
import com.nchat.model.Decision;
import com.nchat.model.NotifCategory;
import com.nchat.model.NotifItem;
import com.nchat.mod.ModuleInstaller;
import com.nchat.net.DaemonClient;
import com.nchat.root.Shell;
import com.nchat.ui.adapter.CardAdapter;
import com.nchat.util.Insets;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面：卡片流式通知决策。
 */
public class MainActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private CardAdapter adapter;
    private LinearLayout layoutEmpty, layoutNeedSetup, layoutActions, rowPrimary, rowSecondary, layoutBottom;
    private TextView tvProgressTitle, tvProgressSub, tvEmptyTitle, tvEmptyHint, tvSetupDesc, tvAiStatus, tvEmptyIcon;
    private ProgressBar progressBar;
    private ImageButton btnRefresh, btnSettings, btnHistory;
    private LinearLayout rowChannel;
    private TextView tvChannelName, tvChannelState;
    private androidx.appcompat.widget.SwitchCompat swChannel;
    /** 防止代码里 setChecked 触发监听造成误写 */
    private boolean channelBinding = false;
    /** 是否已经成功展示过数据。启动流程里有几个异步 post 可能晚于 applyLoaded 执行，
     *  为 true 后忽略这些「迟到」的 showLoading，否则界面会永久停在加载中。 */
    private boolean readyOnce = false;
    private Button btnSetup;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);

    private final List<NotifItem> queue = new ArrayList<>();
    private int totalCount = 0;
    private int doneCount = 0;

    private NchatDb db;

    private static final String ASSET_DAEMON = "nchatd.jar";
    private static final String DAEMON_VERSION = "1";

    @Override
    protected void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        db = NchatDb.get(this);

        // Android 15+ 强制 edge-to-edge：一个 listener 同时处理顶部和底部。
        // 注意：不能在 layoutActions 上单独加底部 padding —— 它是 wrap_content 的线性容器，
        // 加上导航栏高度后会把自身挤到父容器外，导致内部按钮被 layout 成 0x0（不可见）。
        // 统一在根容器上加，卡片区（weight=1）会自动让出空间。
        Insets.applyBoth(this, findViewById(R.id.root));

        bindViews();
        setupPager();
        setupListeners();
        setupChannelRow();

        // 启动时确保 daemon 在跑
        ensureDaemonThenLoad();
    }

    private void bindViews() {
        viewPager = findViewById(R.id.viewPager);
        layoutEmpty = findViewById(R.id.layoutEmpty);
        layoutNeedSetup = findViewById(R.id.layoutNeedSetup);
        layoutActions = findViewById(R.id.layoutActions);
        layoutBottom = findViewById(R.id.layoutBottom);
        rowPrimary = findViewById(R.id.rowPrimary);
        rowSecondary = findViewById(R.id.rowSecondary);
        tvProgressTitle = findViewById(R.id.tvProgressTitle);
        tvProgressSub = findViewById(R.id.tvProgressSub);
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle);
        tvEmptyIcon = findViewById(R.id.tvEmptyIcon);
        tvEmptyHint = findViewById(R.id.tvEmptyHint);
        tvSetupDesc = findViewById(R.id.tvSetupDesc);
        tvAiStatus = findViewById(R.id.tvAiStatus);
        progressBar = findViewById(R.id.progressBar);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnSettings = findViewById(R.id.btnSettings);
        btnSetup = findViewById(R.id.btnSetup);
        btnHistory = findViewById(R.id.btnHistory);
        rowChannel = findViewById(R.id.rowChannel);
        tvChannelName = findViewById(R.id.tvChannelName);
        tvChannelState = findViewById(R.id.tvChannelState);
        swChannel = findViewById(R.id.swChannel);
    }

    private void setupPager() {
        adapter = new CardAdapter();
        viewPager.setAdapter(adapter);
        viewPager.setOffscreenPageLimit(1);
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                // 适配器还没数据时（初始 attach 阶段会回调 0），不渲染，
                // 由 applyLoaded 统一渲染，避免按钮区闪烁/消失。
                if (adapter.getCount() == 0) return;
                renderActionsFor(position);
                updateProgress();
            }
        });
        adapter.setOnNotifActionClick((item, idx) -> {
            io.execute(() -> {
                boolean ok = DaemonClient.triggerAction(item.key, idx);
                ui.post(() -> {
                    if (ok) {
                        recordDecision(item, Decision.TYPE_DONE, false, true);
                        advance();
                    } else {
                        toast("操作失败");
                    }
                });
            });
        });
    }

    private void setupListeners() {
        btnRefresh.setOnClickListener(v -> {
            showLoadingForce("正在刷新…");
            loadNotifications(true);
        });
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        btnSetup.setOnClickListener(v -> doSetup());
        btnHistory.setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));
    }

    // ==================== 渠道行 ====================
    /**
     * 绑定底部「通知渠道 + 禁用开关」行。
     * 开关直接写渠道 importance（0=关闭），不需要再走「屏蔽渠道」按钮。
     */
    private void setupChannelRow() {
        swChannel.setOnCheckedChangeListener((btn, checked) -> {
            if (channelBinding) return;
            NotifItem it = adapter.getItem(viewPager.getCurrentItem());
            if (it == null || it.channel == null || it.channel.isEmpty()) return;
            final boolean wantOn = checked;
            final NotifItem target = it;
            btn.setEnabled(false);
            io.execute(() -> {
                boolean ok;
                if (wantOn) {
                    ok = DaemonClient.unmuteChannel(target.pkg, target.uid, target.channel);
                } else {
                    ok = DaemonClient.muteChannel(target.pkg, target.uid, target.channel);
                }
                ui.post(() -> {
                    btn.setEnabled(true);
                    if (ok) {
                        target.channelImportance = wantOn ? 3 : 0;
                        tvChannelState.setText(wantOn ? "开启" : "已禁用");
                        toast(wantOn ? "已恢复该渠道" : "已禁用该渠道");
                    } else {
                        // 回滚 UI，避免显示与实际不一致
                        channelBinding = true;
                        swChannel.setChecked(!wantOn);
                        channelBinding = false;
                        toast("操作失败，渠道可能已不存在");
                    }
                });
            });
        });
    }

    /** 根据当前卡片刷新渠道行 */
    private void renderChannelRow(NotifItem it) {
        if (it == null) {
            rowChannel.setVisibility(View.GONE);
            return;
        }
        // 从「待办」还原的条目：原通知已不在通知栏，没有渠道可操作，
        // 显示一行提示让用户知道这条是之前存下来的待办。
        if (it.fromTodo) {
            rowChannel.setVisibility(View.VISIBLE);
            String cn = it.channelName == null || it.channelName.isEmpty()
                    ? "待办" : it.channelName;
            tvChannelName.setText("来自待办 · " + cn);
            // 右侧显示计划时间，让用户知道这条是什么时候提醒的
            if (it.planAt > 0) {
                tvChannelState.setText("计划 " + fmtPlan(it.planAt));
            } else {
                tvChannelState.setText("");
            }
            swChannel.setEnabled(false);
            if (swChannel.isChecked()) {
                channelBinding = true;
                swChannel.setChecked(false);
                channelBinding = false;
            }
            return;
        }
        swChannel.setEnabled(true);
        if (it.channel == null || it.channel.isEmpty()) {
            rowChannel.setVisibility(View.GONE);
            return;
        }
        rowChannel.setVisibility(View.VISIBLE);
        String name = it.channelName == null || it.channelName.isEmpty()
                ? it.channel : it.channelName;
        tvChannelName.setText(name);
        boolean on = it.channelImportance != 0;
        tvChannelState.setText(on ? "开启" : "已禁用");
        // 只有值确实要变时才需要 guard（值相同不会回调，guard 会永久卡住）
        if (swChannel.isChecked() != on) {
            channelBinding = true;
            swChannel.setChecked(on);
            channelBinding = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从设置页返回时可能配了 AI
        updateAiStatusHint();

        // 每次回到前台都重新拉一次列表：
        // 之前在别的 App 里收到的通知不会推给我们，靠 onCreate 那次读会漏掉最新的。
        // readyOnce 用来跳过「首次 onCreate 的重复请求」（那时 ensureDaemonThenLoad 正在跑）。
        if (readyOnce) {
            loadNotifications(false);
        }
    }

    // ==================== 启动/模块 ====================

    private void ensureDaemonThenLoad() {
        showLoading("正在启动守护进程…");
        io.execute(() -> {
            // 清理旧版本 bug 遗留的重复待办（每个通知 key 只留一条）
            try {
                int n = db.dedupeTodos();
                if (n > 0) android.util.Log.i("Nchat", "dedupeTodos removed " + n);
            } catch (Throwable ignore) {}

            boolean alive = DaemonClient.ping();
            boolean moduleInstalled = ModuleInstaller.isInstalled();

            // App 升级后内置 daemon jar 可能变了，但模块里还是旧的（只在 deploy 时推送）。
            // 这里比对指纹，不一致就覆盖并重启，否则新接口字段会一直缺失。
            if (moduleInstalled && ModuleInstaller.needsJarSync(this, ASSET_DAEMON)) {
                ui.post(() -> showLoading("正在同步守护进程…"));
                ModuleInstaller.syncJarIfNeeded(this, ASSET_DAEMON);
                Shell.stopDaemon();
                alive = false;
            }

            if (!alive && moduleInstalled) {
                // 模块已部署但进程没跑 → 重启它
                ModuleInstaller.startFromModule();
                for (int i = 0; i < 12; i++) {
                    try { Thread.sleep(700); } catch (Throwable ignore) {}
                    if (DaemonClient.ping()) { alive = true; break; }
                }
            }

            final boolean ok = alive;
            final boolean needSetup = !moduleInstalled;
            ui.post(() -> {
                if (ok) {
                    layoutNeedSetup.setVisibility(View.GONE);
                    viewPager.setVisibility(View.VISIBLE);
                    showBottom();
                    loadNotifications(false);
                } else if (needSetup) {
                    showNeedSetup();
                } else {
                    showNeedSetup();
                    tvSetupDesc.setText("模块已部署，但守护进程未运行。\n点下方按钮重启守护。");
                    btnSetup.setText("重启守护");
                    // 立即尝试一次启动，并把结果反馈出来
                    io.execute(() -> {
                        ModuleInstaller.Result r = ModuleInstaller.startFromModule();
                        boolean up = false;
                        for (int i = 0; i < 20; i++) {
                            try { Thread.sleep(500); } catch (Throwable ignore) {}
                            if (DaemonClient.ping()) { up = true; break; }
                        }
                        final boolean fup = up;
                        final String m = r.message;
                        ui.post(() -> {
                            if (fup) {
                                layoutNeedSetup.setVisibility(View.GONE);
                                viewPager.setVisibility(View.VISIBLE);
                                showBottom();
                                loadNotifications(false);
                            } else {
                                tvSetupDesc.setText("启动失败：\n" + m);
                            }
                        });
                    });
                }
            });
        });
    }

    private void showNeedSetup() {
        layoutNeedSetup.setVisibility(View.VISIBLE);
        viewPager.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.GONE);
        hideBottom();
        tvProgressTitle.setText("未激活");
        tvProgressSub.setText("");

        // 不做预判定：直接允许点击，由 deploy() 实际执行 su 时决定成败
        // （首次会弹 Magisk 授权框，用户点允许并勾选"记住"即可）
        tvSetupDesc.setText("需要部署系统守护模块（Magisk，systemless）\n"
                + "部署后可在后台读取通知并支持一键清理渠道\n"
                + "首次会请求 root 授权，请点「允许」\n"
                + "不修改任何系统文件");
        btnSetup.setEnabled(true);
        btnSetup.setText("一键激活");
    }

    private void doSetup() {
        btnSetup.setEnabled(false);
        btnSetup.setText("部署中…");
        showLoading("正在部署守护进程…");
        io.execute(() -> {
            ModuleInstaller.Result r = ModuleInstaller.deploy(this, ASSET_DAEMON, DAEMON_VERSION);
            final String msg = r.message;
            // 等 daemon 起来（部署里会触发授权框，给足时间）
            boolean alive = false;
            for (int i = 0; i < 40; i++) {
                try { Thread.sleep(600); } catch (Throwable ignore) {}
                if (DaemonClient.ping()) { alive = true; break; }
            }
            final boolean ok = alive;
            ui.post(() -> {
                btnSetup.setEnabled(true);
                btnSetup.setText("一键激活");
                if (ok) {
                    toast("激活成功");
                    layoutNeedSetup.setVisibility(View.GONE);
                    viewPager.setVisibility(View.VISIBLE);
                    showBottom();
                    loadNotifications(true);
                } else {
                    toast("激活失败：" + msg);
                }
            });
        });
    }

    // ==================== 加载 ====================

    private void loadNotifications(boolean showToast) {
        io.execute(() -> {
            DaemonClient.State st = DaemonClient.fetchState();
            List<NotifItem> raw = st.items;

            // 过滤常驻 + 已决策 + 聚合摘要
            // 聚合摘要（GroupSummary）是系统把一个 App 的多条通知折叠成一条的「壳」，
            // 内容与明细重复。规则：同一 groupKey 下只要存在非 summary 的明细，
            // 就丢掉这条 summary；若整个组只有 summary（App 只发了它），则保留。
            java.util.Set<String> groupsWithChildren = new java.util.HashSet<>();
            for (NotifItem it : raw) {
                if (it.groupKey != null && !it.groupSummary) groupsWithChildren.add(it.groupKey);
            }
            // 「处理过的通知不再出现」这条规则不能只凭 DB 里有没有记录来判断。
            // 如果 cancel 因竞态/权限失败，通知会留在通知栏；此时若仍然跳过，
            // 用户就会看到「通知栏还有一堆、App 里却说全处理完了」。
            // 正确的判据：**这条通知现在还活着吗**。raw 是通知栏的实时快照，
            // 里面出现的都还活着 —— 只有当它挂着未完成待办时才交给「待办恢复」处理，
            // 其余情况一律正常显示。
            java.util.Set<String> pendingKeys = new java.util.HashSet<>();
            for (Decision d : db.queryTodosAll()) {
                if (d.notifKey != null) pendingKeys.add(d.notifKey);
            }
            List<NotifItem> filtered = new ArrayList<>();
            for (NotifItem it : raw) {
                if (it.isPersistent()) continue;
                if (it.groupSummary) continue;  // 摘要条目一律不进决策流
                if (pendingKeys.contains(it.key)) continue;  // 已挂待办，下面会单独恢复
                filtered.add(it);
            }

            // 「待办」优先：上次标记为稍后读/稍后办、还没勾销的，重新进入决策流放最前面
            List<NotifItem> restored = new ArrayList<>();
            for (Decision d : db.queryTodos()) {
                restored.add(NotifItem.fromDecision(d));
            }
            if (!restored.isEmpty()) {
                filtered.addAll(0, restored);
            }

            // 规则分类（待办条目已带分类，跳过）
            for (NotifItem it : filtered) {
                if (it.fromTodo) continue;
                RuleClassifier.classify(it, db);
            }

            // 低置信度交给 AI
            final List<NotifItem> needAi = new ArrayList<>();
            for (NotifItem it : filtered) {
                if (it.fromTodo) continue;
                if (it.confidence < 0.75f && AiClassifier.isEnabled(this)) {
                    NotifCategory cached = db.getAiCache(it.key);
                    if (cached != null) {
                        it.aiCategory = cached;
                        it.confidence = 0.9f;
                    } else {
                        needAi.add(it);
                    }
                }
            }

            ui.post(() -> applyLoaded(filtered, st.error, showToast));

            if (!needAi.isEmpty()) {
                ui.post(() -> {
                    tvAiStatus.setText("AI 分析 " + needAi.size() + " 条…");
                });
                AiClassifier.classifyAsync(this, needAi, new AiClassifier.Callback() {
                    @Override
                    public void onResult(int index, NotifCategory cat, float confidence) {
                        if (index >= 0 && index < needAi.size()) {
                            NotifItem it = needAi.get(index);
                            it.aiCategory = cat;
                            it.confidence = confidence;
                            db.putAiCache(it.key, cat, confidence);
                            ui.post(() -> { adapter.notifyDataSetChanged(); renderActionsFor(viewPager.getCurrentItem()); });
                        }
                    }

                    @Override
                    public void onError(String msg) {
                        ui.post(() -> tvAiStatus.setText("AI 失败：" + msg));
                    }

                    @Override
                    public void onFinished() {
                        ui.post(() -> {
                            tvAiStatus.setText("AI 分类完成");
                            ui.postDelayed(() -> tvAiStatus.setText(""), 2500);
                        });
                    }
                });
            }
        });
    }

    private void applyLoaded(List<NotifItem> list, String error, boolean showToast) {
        readyOnce = true;
        queue.clear();
        queue.addAll(list);
        totalCount = list.size();
        doneCount = 0;

        adapter.submit(queue);
        updateAiStatusHint();

        if (list.isEmpty()) {
            showEmpty(error);
        } else {
            layoutEmpty.setVisibility(View.GONE);
            layoutNeedSetup.setVisibility(View.GONE);
            rowChannel.setVisibility(View.VISIBLE);
            viewPager.setVisibility(View.VISIBLE);
            showBottom();
            // 先切页再渲染。ViewPager2 的 setCurrentItem(0) 在已经是 0 时不会回调
            // onPageSelected，所以必须显式 render 一次，否则「第一条通知没有按钮」。
            viewPager.setCurrentItem(0, false);
            renderActionsFor(0);
        }
        updateProgress();

        if (showToast) toast("已刷新 · " + list.size() + " 条待处理");
    }

    /**
     * 加载中状态：部署模块 / 同步 daemon jar / 等 daemon 启动时显示，
     * 避免这段空窗期被误显示成「读取失败」或「0 / 0」。
     */
    /** 会加载提示（已就绪则忽略，用于启动流程） */
    private void showLoading(String msg) {
        if (readyOnce) return;
        showLoadingForce(msg);
    }

    /** 无条件显示加载提示（用于用户主动刷新） */
    private void showLoadingForce(String msg) {
        viewPager.setVisibility(View.GONE);
        layoutNeedSetup.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.VISIBLE);
        hideBottom();
        rowChannel.setVisibility(View.GONE);
        tvProgressTitle.setText("加载中");
        tvProgressSub.setText("");
        tvEmptyIcon.setText("◌");
        tvEmptyIcon.setTextColor(0xFF4ADE80);
        tvEmptyTitle.setText(msg == null ? "正在读取通知…" : msg);
        tvEmptyHint.setText("首次启用或升级后需要几秒启动守护进程");
        progressBar.setProgress(0);
    }

    private void showEmpty(String error) {
        viewPager.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.VISIBLE);
        // 按钮区和渠道行必须一起收起来，否则处理完最后一条后渠道行会留在屏幕上
        hideActionArea();
        tvProgressTitle.setText("待处理");
        tvProgressSub.setText("没有需要处理的通知");
        tvEmptyIcon.setText("✓");
        tvEmptyIcon.setTextColor(getColor(R.color.text_tertiary));

        if (error != null) {
            tvEmptyTitle.setText("读取失败");
            tvEmptyHint.setText(error);
        } else {
            tvEmptyTitle.setText("全部处理完了");
            long today = db.countSince(com.nchat.util.TimeUtil.startOfDay(System.currentTimeMillis()));
            tvEmptyHint.setText(today > 0 ? "今天已处理 " + today + " 条" : "通知栏很干净");
        }
    }

    private void updateAiStatusHint() {
        if (!AiClassifier.isEnabled(this)) {
            tvAiStatus.setText("未开启 AI（使用本地规则分类）");
            ui.postDelayed(() -> tvAiStatus.setText(""), 3000);
        }
    }

    // ==================== 进度 ====================

    private void updateProgress() {
        int remain = adapter.getCount();
        int idx = viewPager.getCurrentItem();
        tvProgressTitle.setText(remain == 0 ? "已完成" : "待处理 " + remain);
        if (remain > 0) {
            tvProgressSub.setText((Math.min(idx + 1, remain)) + " / " + remain);
            progressBar.setProgress((int) ((doneCount * 100f) / Math.max(1, totalCount)));
        } else {
            tvProgressSub.setText("");
            progressBar.setProgress(100);
        }
    }

    // ==================== 按钮渲染 ====================

    private void renderActionsFor(int pos) {
        NotifItem it = adapter.getItem(pos);
        rowPrimary.removeAllViews();
        rowSecondary.removeAllViews();

        if (it == null) {
            // 没有卡片了：整块底部区域（渠道行 + 按钮区）一起收起，
            // 不能只隐藏按钮而留下渠道行。
            hideActionArea();
            return;
        }

        // 先让容器可见再 addView，否则子 View 会被测量成 0x0
        showBottom();
        renderChannelRow(it);

        NotifCategory cat = it.aiCategory == null ? NotifCategory.OTHER : it.aiCategory;

        // ---- 按钮区统一结构：最多三个按钮，各占一行 ----
        // 第 1 个：打开（有 contentIntent 才有；否则用分类对应的主操作顶上）
        // 第 2 个：次要操作
        // 第 3 个：不感兴趣 / 已完成（收尾）
        //
        // 之前 TOOL 和 default 分支都没有「打开」按钮，用户遇到需要去 App 里
        // 处理的工具类通知时无路可走，只能先转待办再绕一圈。
        boolean canOpen = it.hasIntent && !it.fromTodo;
        String openLabel;
        switch (cat) {
            case MARKETING: openLabel = "立即去办"; break;
            case TOOL:      openLabel = "去处理";   break;
            case INFO:      openLabel = "立即阅读"; break;
            default:        openLabel = "打开通知"; break;
        }

        if (canOpen) {
            addPrimary(openLabel, 0xFF1F6F6F, () -> doReadNow(it));
        } else {
            // 没有可跳转的目标（例如从待办还原、或通知本身无 contentIntent）
            if (cat == NotifCategory.TOOL || cat == NotifCategory.OTHER) {
                addPrimary("已完成", 0xFF1F5F3A, () -> doDone(it));
            } else {
                addPrimary("标记已读", 0xFF1F6F6F, () -> doDone(it));
            }
        }

        // 第二个：稍后
        String laterLabel = (cat == NotifCategory.INFO) ? "稍后读" : "稍后办";
        addPrimary(laterLabel, 0xFF23324A, () -> doLater(it, cat == NotifCategory.INFO
                ? Decision.TYPE_READ_LATER : Decision.TYPE_DO_LATER));

        // 第三个：收尾（所有分类统一）
        addSecondary("不感兴趣", 0xFF3A1F24, 0xFFFF7A8A, () -> doIgnore(it));

        // 所有按钮加完后统一重排一次（分散的 requestLayout 会让子 View 停在 0x0）
        layoutActions.post(() -> {
            layoutActions.requestLayout();
            rowPrimary.requestLayout();
            rowSecondary.requestLayout();
        });

    }

    /**
     * 底部操作区（按钮 + 渠道行）统一收起。
     * 之前只在「无卡片」时隐藏 layoutActions，但渠道行是独立的一行，
     * 结果处理完最后一条后渠道行还留在屏幕上。
     */
    /**
     * 底部操作区（渠道行 + 按钮区）统一显隐。
     * 两者同属 layoutBottom，只控制这一个容器就不会出现
     * 「卡片没了但渠道行还留在屏幕上」的错位状态。
     */
    private void hideActionArea() {
        hideBottom();
    }

    private void showBottom() {
        if (layoutBottom != null) {
            layoutBottom.setVisibility(View.VISIBLE);
            // 父容器可见还不够：hideBottom() 同时把子容器置为 GONE，
            // 这里必须把 layoutActions 一起恢复，否则会出现「只有渠道行、按钮区空白」。
            if (layoutActions != null) layoutActions.setVisibility(View.VISIBLE);
            layoutBottom.requestLayout();
            layoutBottom.post(() -> {
                layoutBottom.requestLayout();
                if (layoutActions != null) layoutActions.requestLayout();
                if (rowPrimary != null) rowPrimary.requestLayout();
                if (rowSecondary != null) rowSecondary.requestLayout();
            });
        }
    }

    private void hideBottom() {
        if (layoutBottom != null) layoutBottom.setVisibility(View.GONE);
                if (layoutActions != null) layoutActions.setVisibility(View.GONE);
        if (rowChannel != null) rowChannel.setVisibility(View.GONE);
    }

    private void addPrimary(String text, int bgColor, Runnable r) {
        rowPrimary.addView(makeBtn(text, bgColor, 0xFFFFFFFF, r, true));
        // 所有按钮加完后统一重排，避免子 View 停在 0x0
        rowPrimary.post(() -> {
            if (rowPrimary != null) rowPrimary.requestLayout();
            if (rowSecondary != null) rowSecondary.requestLayout();
        });
    }

    private void addSecondary(String text, int bgColor, int textColor, Runnable r) {
        rowSecondary.addView(makeBtn(text, bgColor, textColor, r, false));
    }

    private TextView makeBtn(String text, int bg, int fg, Runnable r, boolean flex) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(fg);
        tv.setGravity(android.view.Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(13));
        d.setColor(bg);
        d.setStroke(dp(1), 0x22FFFFFF);
        tv.setBackground(d);
        // 不用 0dp+weight（在本次改版的层级里实测会被 layout 成 0x0，按钮"隐形"），
        // 改成显式计算宽度：屏幕宽 - 两侧 18dp 边距 - 按钮间 8dp 间隙，再除以 2。
        // 一行一个按钮：宽度直接吃满可用区域（屏幕宽 - 两侧 18dp 边距）
        int gap = dp(7);
        int side = dp(18);
        int w = getResources().getDisplayMetrics().widthPixels - side * 2;
        int h = dp(flex ? 46 : 42);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        // 三个按钮各占一行，纵向排列，只保留上间距
        if (rowPrimary.getChildCount() > 0 || rowSecondary.getChildCount() > 0) {
            lp.topMargin = gap;
        }
        tv.setLayoutParams(lp);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setOnClickListener(v -> r.run());
        return tv;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ==================== 决策动作 ====================

    private void doReadNow(NotifItem it) {
        if (it.fromTodo) {
            // 通知栏里已不存在，跳不了；直接视为完成
            recordDecision(it, Decision.TYPE_DONE, false, true);
            advance();
            return;
        }
        io.execute(() -> {
            boolean ok = it.hasIntent && DaemonClient.click(it.key);
            ui.post(() -> {
                if (!ok) {
                    toast("该通知没有可跳转的目标");
                    recordDecision(it, Decision.TYPE_DONE, false, true);
                    advance();
                    return;
                }
                recordDecision(it, Decision.TYPE_DONE, false, true);

                // 跳转后延迟推进卡片：目标 App 需要时间切前台，
                // 立刻 advance() 的话我们的重排会把它压回去，看起来像"没反应"。
                // 同事后校验：如果 2.5 秒后我们仍在前台，说明这条通知的
                // contentIntent 其实打不开（典型：常驻播放类通知指向内部播放页），
                // 给用户一个明确提示，而不是让他以为点了没反应。
                ui.postDelayed(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (!isSelfForeground()) {
                        // 已经切到别的 App，正常推进
                        advance();
                    } else {
                        toast("这条通知本身无法跳转（可能是常驻/播放类通知）");
                        advance();
                    }
                }, 2500);
            });
        });
    }

    private void doDone(NotifItem it) {
        if (it.fromTodo) {
            markTodoFinished(it);
            recordDecision(it, Decision.TYPE_DONE, false, true);
            advance();
            return;
        }
        io.execute(() -> {
            DaemonClient.cancel(it.key);
            ui.post(() -> {
                recordDecision(it, Decision.TYPE_DONE, false, false);
                advance();
            });
        });
    }

    private void doIgnore(NotifItem it) {
        if (it.fromTodo) {
            markTodoFinished(it);
            recordDecision(it, Decision.TYPE_IGNORE, false, true);
            advance();
            return;
        }
        io.execute(() -> {
            DaemonClient.cancel(it.key);
            ui.post(() -> {
                recordDecision(it, Decision.TYPE_IGNORE, false, false);
                advance();
            });
        });
    }

    /** 把计划时间格式化成「今天 20:00」/「明天 09:00」/「10-21 10:00」 */
    private String fmtPlan(long ts) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts);
        String hm = String.format(java.util.Locale.getDefault(), "%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
        boolean sameDay = now.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR)
                && now.get(java.util.Calendar.DAY_OF_YEAR) == c.get(java.util.Calendar.DAY_OF_YEAR);
        if (sameDay) return "今天 " + hm;
        now.add(java.util.Calendar.DAY_OF_YEAR, 1);
        boolean tomorrow = now.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR)
                && now.get(java.util.Calendar.DAY_OF_YEAR) == c.get(java.util.Calendar.DAY_OF_YEAR);
        if (tomorrow) return "明天 " + hm;
        return String.format(java.util.Locale.getDefault(), "%02d-%02d ",
                c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH)) + hm;
    }

    /** 计划办理时间的预设项 */
    private static final String[] PLAN_LABELS = {
            "现在就办", "今天 12:00", "今天 18:00", "今天 20:00",
            "今天晚上", "明天 09:00", "明天 20:00", "本周末"
    };

    /**
     * 「稍后读 / 稍后办 / 转为待办」统一入口。
     * 关键改动：
     *  1) 先选计划时间（到点前不再出现在决策流里）
     *  2) 用 upsertTodo 写入，同一条通知只会有一条待办记录
     *     （之前每次都 insert，导致点一次变两条、再点变三条）
     */
    private void doLater(NotifItem it, int type) {
        showPlanPicker(label -> {
            final long planAt = planTimeOf(label);
            io.execute(() -> {
                // 原通知还在通知栏的话清掉它
                if (!it.fromTodo) DaemonClient.cancel(it.key);
                Decision d = buildDecision(it, type, true);
                d.planAt = planAt;
                db.upsertTodo(d);
                ui.post(() -> {
                    toast(planAt <= 0 ? "已加入待办" : "已加入待办 · " + label);
                    advance();
                });
            });
        });
    }

    /** 弹计划时间选择框 */
    private interface PlanCallback { void onPick(String label); }

    private void showPlanPicker(PlanCallback cb) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("什么时候提醒你？")
                .setItems(PLAN_LABELS, (d, which) -> cb.onPick(PLAN_LABELS[which]))
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 把预设文案换算成时间戳。
     * 返回 0 表示「现在就办」（不延后，立刻回到决策流）。
     */
    private long planTimeOf(String label) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int hour = c.get(java.util.Calendar.HOUR_OF_DAY);

        if (label.startsWith("现在就办")) return 0;

        if (label.startsWith("今天")) {
            int h = parseHour(label);
            c.set(java.util.Calendar.HOUR_OF_DAY, h);
            c.set(java.util.Calendar.MINUTE, 0);
            c.set(java.util.Calendar.SECOND, 0);
            c.set(java.util.Calendar.MILLISECOND, 0);
            // 今天这个点已经过了 → 顺延到明天同一时刻
            if (c.getTimeInMillis() <= System.currentTimeMillis()) {
                c.add(java.util.Calendar.DAY_OF_YEAR, 1);
            }
            return c.getTimeInMillis();
        }

        if (label.startsWith("明天")) {
            int h = parseHour(label);
            c.add(java.util.Calendar.DAY_OF_YEAR, 1);
            c.set(java.util.Calendar.HOUR_OF_DAY, h);
            c.set(java.util.Calendar.MINUTE, 0);
            c.set(java.util.Calendar.SECOND, 0);
            c.set(java.util.Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        }

        if (label.startsWith("今天晚上")) {
            int h = hour < 20 ? 20 : hour + 2;   // 已过 20 点则 2 小时后
            c.set(java.util.Calendar.HOUR_OF_DAY, Math.min(h, 23));
            c.set(java.util.Calendar.MINUTE, 0);
            c.set(java.util.Calendar.SECOND, 0);
            c.set(java.util.Calendar.MILLISECOND, 0);
            if (c.getTimeInMillis() <= System.currentTimeMillis()) {
                c.add(java.util.Calendar.DAY_OF_YEAR, 1);
            }
            return c.getTimeInMillis();
        }

        if (label.startsWith("本周末")) {
            // 下一个周六 10:00
            int dow = c.get(java.util.Calendar.DAY_OF_WEEK);   // 周日=1 … 周六=7
            int add = (java.util.Calendar.SATURDAY - dow + 7) % 7;
            if (add == 0 && c.get(java.util.Calendar.HOUR_OF_DAY) >= 10) add = 7;
            c.add(java.util.Calendar.DAY_OF_YEAR, add);
            c.set(java.util.Calendar.HOUR_OF_DAY, 10);
            c.set(java.util.Calendar.MINUTE, 0);
            c.set(java.util.Calendar.SECOND, 0);
            c.set(java.util.Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        }

        return 0;
    }

    /** 从 "今天 20:00" / "明天 09:00" 里取出小时 */
    private int parseHour(String label) {
        try {
            int i = label.indexOf(':');
            if (i > 0) {
                // 找到冒号前最后一串数字
                int j = i - 1;
                while (j >= 0 && !Character.isDigit(label.charAt(j))) j--;
                int k = j;
                while (k >= 0 && Character.isDigit(label.charAt(k))) k--;
                return Integer.parseInt(label.substring(k + 1, j + 1));
            }
        } catch (Throwable ignore) {}
        return 9;
    }

    /** 构造一条决策记录 */
    private Decision buildDecision(NotifItem it, int type, boolean todoPending) {
        Decision d = new Decision();
        d.notifKey = it.key;
        d.pkg = it.pkg;
        d.uidChannel = it.pkgUidChannel();
        d.type = type;
        d.decidedAt = System.currentTimeMillis();
        d.title = it.displayTitle();
        d.body = it.body();
        d.channelName = it.channelName;
        d.category = it.aiCategory;
        d.todoPending = todoPending;
        return d;
    }

    /** 待办条目被处理掉时，把对应历史记录的 pending 清掉 */
    private void markTodoFinished(NotifItem it) {
        io.execute(() -> db.markTodoPendingByKey(it.key, 0));
    }

    private void doMute(NotifItem it) {
        // 二次确认
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("屏蔽渠道")
                .setMessage("将关闭「" + com.nchat.NchatApp.get().appLabel(it.pkg) + "」的渠道：\n"
                        + (it.channelName == null || it.channelName.isEmpty() ? it.channel : it.channelName)
                        + "\n\n以后该渠道的通知将不再出现。可在「渠道管理」里恢复。")
                .setPositiveButton("屏蔽", (d, w) -> doMuteConfirm(it))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doMuteConfirm(NotifItem it) {
        io.execute(() -> {
            boolean ok = DaemonClient.muteChannel(it.pkg, it.uid, it.channel);
            ui.post(() -> {
                if (ok) {
                    recordDecision(it, Decision.TYPE_MUTED, false, true);
                    toast("已屏蔽该渠道");
                    advance();
                } else {
                    toast("屏蔽失败（可能渠道已不存在）");
                }
            });
        });
    }

    /** 记录决策并入库 */
    private void recordDecision(NotifItem it, int type, boolean todoPending, boolean alreadyCanceled) {
        final Decision d = buildDecision(it, type, todoPending);
        final long rowId = it.decisionRowId;
        final String key = it.key;
        io.execute(() -> {
            if (rowId > 0) {
                // 这条是从待办还原出来的，历史里已经有记录了 → 只更新，不再插新行。
                // 否则每处理一次就会多出一条重复记录。
                db.updateDecision(rowId, type, todoPending, 0);
            } else if (key != null && db.hasHistory(key)) {
                // 兜底：不是从待办还原，但同一条通知已经有过记录（例如重新出现），
                // 交给 upsertTodo 做「同 key 只保留一条」，避免历史无限膨胀。
                if (todoPending) db.upsertTodo(d); else db.insertDecision(d);
            } else {
                db.insertDecision(d);
            }
        });
    }

    /** 从队列中移除当前项并前进 */
    private void advance() {
        int pos = viewPager.getCurrentItem();
        NotifItem cur = adapter.getItem(pos);
        if (cur != null) {
            queue.remove(cur);
            doneCount++;
        }
        adapter.submit(queue);

        if (queue.isEmpty()) {
            showEmpty(null);
        } else {
            int next = Math.min(pos, queue.size() - 1);
            viewPager.setCurrentItem(next, false);
            renderActionsFor(next);
        }
        updateProgress();
    }

    /** 判断当前前台应用是否还是自己（用于校验跳转是否真的生效） */
    private boolean isSelfForeground() {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            java.util.List<android.app.ActivityManager.RunningAppProcessInfo> list =
                    am.getRunningAppProcesses();
            if (list == null) return true;
            for (android.app.ActivityManager.RunningAppProcessInfo pi : list) {
                if (pi.importance == android.app.ActivityManager.RunningAppProcessInfo
                        .IMPORTANCE_FOREGROUND) {
                    return pi.processName != null && pi.processName.startsWith(getPackageName());
                }
            }
        } catch (Throwable ignore) {}
        return true;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}