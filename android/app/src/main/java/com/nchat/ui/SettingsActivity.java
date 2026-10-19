package com.nchat.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.nchat.util.Insets;
import com.nchat.R;
import com.nchat.classify.AiClassifier;
import com.nchat.data.NchatDb;
import com.nchat.mod.ModuleInstaller;
import com.nchat.net.DaemonClient;
import com.nchat.root.Shell;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 设置页：AI 配置、模块管理、数据入口。
 */
public class SettingsActivity extends AppCompatActivity {

    private Switch swAi;
    private EditText etBaseUrl, etApiKey, etModel;
    private Button btnTestAi, btnRestartDaemon, btnUninstallModule;
    private Button btnChannels, btnClearAiCache;
    private TextView tvTestResult, tvModuleStatus, tvVersion;

    private SharedPreferences sp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        // Android 15+ edge-to-edge：根视图同时补状态栏(顶)和导航栏(底)
        Insets.applyBoth(this, findViewById(android.R.id.content));
        sp = AiClassifier.prefs(this);

        bind();
        loadValues();
        refreshModuleStatus();
    }

    private void bind() {
        swAi = findViewById(R.id.swAi);
        etBaseUrl = findViewById(R.id.etBaseUrl);
        etApiKey = findViewById(R.id.etApiKey);
        etModel = findViewById(R.id.etModel);
        btnTestAi = findViewById(R.id.btnTestAi);
        btnRestartDaemon = findViewById(R.id.btnRestartDaemon);
        btnUninstallModule = findViewById(R.id.btnUninstallModule);
        btnChannels = findViewById(R.id.btnChannels);
        btnClearAiCache = findViewById(R.id.btnClearAiCache);
        tvTestResult = findViewById(R.id.tvTestResult);
        tvModuleStatus = findViewById(R.id.tvModuleStatus);
        tvVersion = findViewById(R.id.tvVersion);

        tvVersion.setText("Nchat 1.0");

        btnTestAi.setOnClickListener(v -> testAi());
        btnRestartDaemon.setOnClickListener(v -> restartDaemon());
        btnUninstallModule.setOnClickListener(v -> uninstallModule());
        btnChannels.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, ChannelActivity.class)));
        btnClearAiCache.setOnClickListener(v -> {
            NchatDb.get(this).clearAiCache();
            toast("已清空 AI 缓存");
        });
    }

    private void loadValues() {
        swAi.setChecked(sp.getBoolean(AiClassifier.KEY_ENABLED, false));
        etBaseUrl.setText(sp.getString(AiClassifier.KEY_BASE_URL, AiClassifier.DEFAULT_BASE));
        etApiKey.setText(sp.getString(AiClassifier.KEY_API_KEY, ""));
        etModel.setText(sp.getString(AiClassifier.KEY_MODEL, AiClassifier.DEFAULT_MODEL));

        View.OnFocusChangeListener save = (v, hasFocus) -> {
            if (!hasFocus) saveAiConfig();
        };
        etBaseUrl.setOnFocusChangeListener(save);
        etApiKey.setOnFocusChangeListener(save);
        etModel.setOnFocusChangeListener(save);
        swAi.setOnCheckedChangeListener((b, checked) -> saveAiConfig());
    }

    private void saveAiConfig() {
        sp.edit()
                .putBoolean(AiClassifier.KEY_ENABLED, swAi.isChecked())
                .putString(AiClassifier.KEY_BASE_URL, etBaseUrl.getText().toString().trim())
                .putString(AiClassifier.KEY_API_KEY, etApiKey.getText().toString().trim())
                .putString(AiClassifier.KEY_MODEL, etModel.getText().toString().trim())
                .apply();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveAiConfig();
    }

    private void testAi() {
        saveAiConfig();
        btnTestAi.setEnabled(false);
        btnTestAi.setText("测试中…");
        tvTestResult.setVisibility(View.VISIBLE);
        tvTestResult.setText("正在请求…");
        io.execute(() -> {
            String r = AiClassifier.testConnection(this);
            ui.post(() -> {
                btnTestAi.setEnabled(true);
                btnTestAi.setText("测试连接");
                tvTestResult.setText(r);
                tvTestResult.setTextColor(r.startsWith("OK") ? 0xFF4ADE80 : 0xFFFF7A8A);
            });
        });
    }

    private void refreshModuleStatus() {
        io.execute(() -> {
            boolean root = Shell.hasRoot();
            boolean installed = ModuleInstaller.isInstalled();
            boolean alive = DaemonClient.ping();
            String ver = installed ? ModuleInstaller.installedJarVersion() : null;
            boolean disabled = installed && ModuleInstaller.isDisabled();

            StringBuilder sb = new StringBuilder();
            sb.append("root：").append(root ? "已获取" : "未获取").append('\n');
            sb.append("模块：").append(installed ? "已部署" : "未部署");
            if (installed && ver != null) sb.append("（v").append(ver).append("）");
            if (disabled) sb.append(" · 已禁用");
            sb.append('\n');
            sb.append("守护进程：").append(alive ? "运行中" : "未运行");

            ui.post(() -> tvModuleStatus.setText(sb.toString()));
        });
    }

    private void restartDaemon() {
        btnRestartDaemon.setEnabled(false);
        io.execute(() -> {
            String failReason = null;
            if (ModuleInstaller.isInstalled()) {
                ModuleInstaller.Result r = ModuleInstaller.startFromModule();
                if (!r.success) failReason = r.message;
            } else {
                Shell.Result r = Shell.startDaemon("/data/local/tmp/nchatd/nchatd.jar");
                if (!r.out.contains("LAUNCHED")) failReason = "status=" + r.out.trim();
            }
            for (int i = 0; i < 15; i++) {
                try { Thread.sleep(600); } catch (Throwable ignore) {}
                if (DaemonClient.ping()) break;
            }
            final boolean fok = DaemonClient.ping();
            final String reason = failReason;
            ui.post(() -> {
                btnRestartDaemon.setEnabled(true);
                toast(fok ? "守护已重启"
                        : ("重启失败" + (reason == null ? "" : "：" + reason)));
                refreshModuleStatus();
            });
        });
    }

    private void uninstallModule() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("移除模块")
                .setMessage("将删除 Magisk 模块与守护进程。\nApp 本身不受影响，但会失去通知读取能力。")
                .setPositiveButton("移除", (d, w) -> io.execute(() -> {
                    ModuleInstaller.Result r = ModuleInstaller.uninstall();
                    ui.post(() -> {
                        toast(r.message);
                        refreshModuleStatus();
                    });
                }))
                .setNegativeButton("取消", null)
                .show();
    }

    private void openHistory() {
        startActivity(new android.content.Intent(this, HistoryActivity.class));
    }

    private void openTodos() {
        android.content.Intent i = new android.content.Intent(this, HistoryActivity.class);
        i.putExtra("mode", "todos");
        startActivity(i);
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