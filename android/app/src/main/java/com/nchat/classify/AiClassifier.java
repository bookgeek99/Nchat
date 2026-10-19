package com.nchat.classify;

import android.content.Context;
import android.content.SharedPreferences;

import com.nchat.model.NotifCategory;
import com.nchat.model.NotifItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DeepSeek 批量分类。
 * 只对规则置信度低的批次调用，减少 token 消耗。
 */
public class AiClassifier {

    public static final String PREF = "nchat_ai";
    public static final String KEY_ENABLED = "ai_enabled";
    public static final String KEY_BASE_URL = "ai_base_url";
    public static final String KEY_API_KEY = "ai_api_key";
    public static final String KEY_MODEL = "ai_model";

    public static final String DEFAULT_BASE = "https://api.deepseek.com";
    public static final String DEFAULT_MODEL = "deepseek-chat";

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    public interface Callback {
        void onResult(int index, NotifCategory cat, float confidence);
        void onError(String msg);
        void onFinished();
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p.getBoolean(KEY_ENABLED, false) && !p.getString(KEY_API_KEY, "").isEmpty();
    }

    /**
     * 异步批量分类。
     * @param items 待分类（只传规则置信度低的）
     */
    public static void classifyAsync(Context ctx, List<NotifItem> items, Callback cb) {
        if (items.isEmpty()) {
            cb.onFinished();
            return;
        }
        POOL.execute(() -> {
            try {
                doClassify(ctx, items, cb);
            } catch (Throwable t) {
                cb.onError(t.getMessage() == null ? t.toString() : t.getMessage());
            } finally {
                cb.onFinished();
            }
        });
    }

    private static void doClassify(Context ctx, List<NotifItem> items, Callback cb) throws Exception {
        SharedPreferences p = prefs(ctx);
        String base = p.getString(KEY_BASE_URL, DEFAULT_BASE);
        String key = p.getString(KEY_API_KEY, "");
        String model = p.getString(KEY_MODEL, DEFAULT_MODEL);

        // 分批，每批最多 20 条
        int batchSize = 20;
        for (int start = 0; start < items.size(); start += batchSize) {
            int end = Math.min(start + batchSize, items.size());
            List<NotifItem> batch = items.subList(start, end);

            StringBuilder userContent = new StringBuilder();
            userContent.append("请对以下通知进行分类。每条输出 JSON：{\"i\":序号,\"c\":\"info|marketing|tool|other\",\"p\":0-1的置信度}\n");
            userContent.append("分类定义：\n");
            userContent.append("- info：资讯、新闻、内容更新、视频更新、社区动态，用户需要阅读\n");
            userContent.append("- marketing：营销、促销、优惠、活动、广告推广\n");
            userContent.append("- tool：工具类待办，如外卖/快递/打车/导航/支付/验证码，需要在现实中做动作\n");
            userContent.append("- other：无法归入以上\n");
            userContent.append("返回一个 JSON 数组，不要任何其它文字。\n\n通知列表：\n");
            for (int i = 0; i < batch.size(); i++) {
                NotifItem it = batch.get(i);
                userContent.append(i).append(". pkg=").append(it.pkg);
                if (it.channelName != null && !it.channelName.isEmpty()) {
                    userContent.append(" | 渠道=").append(it.channelName);
                }
                userContent.append(" | 标题=").append(trunc(it.displayTitle(), 80));
                String body = trunc(it.body(), 160);
                if (!body.isEmpty()) userContent.append(" | 正文=").append(body);
                userContent.append('\n');
            }

            String resp = post(base, key, model, userContent.toString());
            parseAndDispatch(resp, start, batch.size(), cb);
        }
    }

    private static void parseAndDispatch(String resp, int offset, int count, Callback cb) {
        // 从返回内容里抠出 JSON 数组
        int lb = resp.indexOf('[');
        int rb = resp.lastIndexOf(']');
        if (lb < 0 || rb <= lb) return;
        String json = resp.substring(lb, rb + 1);
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                int idx = o.optInt("i", i);
                String c = o.optString("c", "other");
                double pr = o.optDouble("p", 0.7);
                NotifCategory cat = map(c);
                if (idx >= 0 && idx < count) {
                    cb.onResult(offset + idx, cat, (float) pr);
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private static NotifCategory map(String s) {
        switch (s) {
            case "info": return NotifCategory.INFO;
            case "marketing": return NotifCategory.MARKETING;
            case "tool": return NotifCategory.TOOL;
            default: return NotifCategory.OTHER;
        }
    }

    private static String post(String base, String key, String model, String userContent) throws Exception {
        String urlStr = base.endsWith("/") ? base + "chat/completions" : base + "/chat/completions";
        if (base.endsWith("/v1")) {
            urlStr = base + "/chat/completions";
        } else if (!base.contains("/v1")) {
            urlStr = base + "/chat/completions";
        }

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Authorization", "Bearer " + key);

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0.1);
        body.put("max_tokens", 2000);
        JSONArray msgs = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", "你是通知分类器。只输出 JSON 数组，不输出任何解释。");
        msgs.put(sys);
        JSONObject usr = new JSONObject();
        usr.put("role", "user");
        usr.put("content", userContent);
        msgs.put(usr);
        body.put("messages", msgs);

        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(data);
        }

        int code = conn.getResponseCode();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": " + trunc(sb.toString(), 200));
        }

        // 提取 choices[0].message.content
        JSONObject resp = new JSONObject(sb.toString());
        JSONArray choices = resp.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return "";
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        return msg == null ? "" : msg.optString("content", "");
    }

    private static String trunc(String s, int n) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    /** 同步测试连接 */
    public static String testConnection(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String base = p.getString(KEY_BASE_URL, DEFAULT_BASE);
        String key = p.getString(KEY_API_KEY, "");
        String model = p.getString(KEY_MODEL, DEFAULT_MODEL);
        try {
            String r = post(base, key, model,
                    "分类这一条：pkg=com.test | 标题=限时优惠 | 正文=全场5折起\n返回 JSON 数组。");
            return "OK: " + trunc(r, 300);
        } catch (Throwable t) {
            return "ERR: " + (t.getMessage() == null ? t.toString() : t.getMessage());
        }
    }
}