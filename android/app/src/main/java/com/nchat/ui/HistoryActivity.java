package com.nchat.ui;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.nchat.util.Insets;
import com.nchat.R;
import com.nchat.data.NchatDb;
import com.nchat.model.Decision;
import com.nchat.ui.adapter.HistoryAdapter;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 通知历史 / 待办清单。
 */
public class HistoryActivity extends AppCompatActivity {

    private RecyclerView recycler;
    private HistoryAdapter adapter;
    private EditText etSearch;
    private TextView tvEmpty, tvTitle;
    private LinearLayout llFilters;
    private NchatDb db;

    private boolean todoMode = false;
    private int currentFilter = -1;  // -1 = 全部
    private String keyword = "";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private static final int[] FILTER_TYPES = {
            -1, Decision.TYPE_DONE, Decision.TYPE_READ_LATER,
            Decision.TYPE_DO_LATER, Decision.TYPE_IGNORE, Decision.TYPE_MUTED
    };
    private static final String[] FILTER_LABELS = {
            "全部", "已完成", "稍后读", "稍后办", "不感兴趣", "已屏蔽"
    };

    @Override
    protected void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_history);
        // Android 15+ edge-to-edge：根视图同时补状态栏(顶)和导航栏(底)
        Insets.applyBoth(this, findViewById(android.R.id.content));
        db = NchatDb.get(this);

        todoMode = "todos".equals(getIntent().getStringExtra("mode"));

        tvTitle = findViewById(R.id.tvTitle);
        etSearch = findViewById(R.id.etSearch);
        recycler = findViewById(R.id.recycler);
        tvEmpty = findViewById(R.id.tvEmpty);
        llFilters = findViewById(R.id.llFilters);

        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new HistoryAdapter();
        adapter.setTodoMode(todoMode);
        adapter.setListener(new HistoryAdapter.Listener() {
            @Override
            public void onTodoDone(Decision d) {
                io.execute(() -> {
                    db.markTodoDone(d.id);
                    ui.post(() -> { load(); });
                });
            }

            @Override
            public void onTodoDelete(Decision d) {
                io.execute(() -> {
                    db.deleteDecision(d.id);
                    ui.post(() -> { load(); });
                });
            }
        });
        recycler.setAdapter(adapter);

        if (todoMode) {
            tvTitle.setText("待办清单");
            etSearch.setVisibility(View.GONE);
            llFilters.setVisibility(View.GONE);
        } else {
            buildFilters();
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    keyword = s.toString();
                    load();
                }
            });
        }

        load();
    }

    private void buildFilters() {
        for (int i = 0; i < FILTER_TYPES.length; i++) {
            final int type = FILTER_TYPES[i];
            TextView chip = new TextView(this);
            chip.setText(FILTER_LABELS[i]);
            chip.setTextSize(12);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(16), dp(8), dp(16), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setTag(type);
            chip.setOnClickListener(v -> {
                currentFilter = type;
                updateChipStyles();
                load();
            });
            llFilters.addView(chip);
        }
        updateChipStyles();
    }

    private void updateChipStyles() {
        for (int i = 0; i < llFilters.getChildCount(); i++) {
            TextView chip = (TextView) llFilters.getChildAt(i);
            int type = (int) chip.getTag();
            boolean active = type == currentFilter;
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(dp(99));
            d.setColor(active ? 0xFF5B8DEF : 0xFF1E2430);
            if (!active) d.setStroke(dp(1), 0xFF2A3242);
            chip.setBackground(d);
            chip.setTextColor(active ? 0xFFFFFFFF : 0xFF9AA5B8);
        }
    }

    private void load() {
        io.execute(() -> {
            List<Decision> list;
            if (todoMode) {
                list = db.queryTodos();
            } else {
                list = db.queryHistory(currentFilter, keyword, 500);
            }
            final List<Decision> f = list;
            ui.post(() -> {
                adapter.submit(f);
                tvEmpty.setVisibility(f.isEmpty() ? View.VISIBLE : View.GONE);
                recycler.setVisibility(f.isEmpty() ? View.GONE : View.VISIBLE);
                tvEmpty.setText(todoMode ? "没有待办" : "暂无记录");
            });
        });
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