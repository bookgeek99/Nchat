package com.nchat.net;

import android.util.Log;

import com.nchat.model.NotifItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 与 nchatd 通信的客户端（127.0.0.1:18765）。
 */
public class DaemonClient {

    private static final String TAG = "NchatClient";
    public static final int PORT = 18765;
    private static final String BASE = "http://127.0.0.1:" + PORT;
    /** daemon 的 click 会等最多 2.5s 校验前台，这里给 4s 余量 */
    private static final int CLICK_TIMEOUT = 4000;

    private static final int TIMEOUT = 8000;

    public static class State {
        public boolean ok;
        public int count;
        public List<NotifItem> items = new ArrayList<>();
        public String error;
    }

    /** 健康检查，超时短一些 */
    public static boolean ping() {
        try {
            String r = get("/ping", 1200);
            return r != null && r.contains("\"ok\":true");
        } catch (Throwable t) {
            return false;
        }
    }

    public static State fetchState() {
        State st = new State();
        try {
            String raw = get("/state", TIMEOUT);
            if (raw == null) {
                st.error = "无响应";
                return st;
            }
            JSONObject o = new JSONObject(raw);
            st.ok = o.optBoolean("ok", false);
            st.count = o.optInt("count", 0);
            JSONArray arr = o.optJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    st.items.add(NotifItem.fromJson(arr.getJSONObject(i)));
                }
            }
        } catch (Throwable t) {
            st.error = t.getMessage() == null ? t.toString() : t.getMessage();
        }
        return st;
    }

    public static boolean cancel(String key) {
        return call("/cancel/" + enc(key));
    }

    /**
     * 点击结果。
     *
     * 关键：daemon 侧的 ok **不再等于「跳转成功」**。
     * pi.send() 返回 true 只代表 PendingIntent 送达系统，
     * Android 15 BAL 会在 ActivityTaskManager 层静默拦截，
     * 此时 send 依然成功但界面毫无变化 —— 用户看到的就是「点了没反应」。
     *
     * 所以 daemon 现在会实际比对前台窗口，把真实结果放在 jump / reason 里。
     */
    public static class ClickResult {
        /** daemon 是否成功把 PendingIntent 发出（不代表跳转成功） */
        public boolean ok;
        /** true = 前台确实变了，跳转成功 */
        public boolean jumped;
        /** 耗时（毫秒） */
        public long costMs = -1;
        /** bal_block / no_change / already_front / notification_not_found / send_failed */
        public String reason;
        /** 跳转后（或未跳转时的）前台 Activity */
        public String top;
        /** 给用户看的一句话说明 */
        public String hint;

        public String reasonLabel() {
            if (reason == null) return jumped ? "成功" : "未知";
            switch (reason) {
                case "bal_block":  return "被后台启动限制拦截";
                case "no_change":  return "目标页面没有响应";
                case "already_front": return "目标 App 已在前台";
                case "notification_not_found": return "通知已消失";
                case "send_failed": return "发送失败";
                default: return reason;
            }
        }
    }

    /** 兼容旧调用：只关心「daemon 有没有把 intent 发出去」时用 */
    public static boolean click(String key) {
        return clickDetailed(key).ok;
    }

    /**
     * 点击通知并拿到真实跳转结果。
     * daemon 内部会等到前台窗口变化或 2.5s 超时才返回，
     * 所以这里超时要给足（4s），不能用 call() 的短超时。
     */
    public static ClickResult clickDetailed(String key) {
        ClickResult r = new ClickResult();
        String raw = null;
        try {
            raw = get("/click/" + enc(key), CLICK_TIMEOUT);
        } catch (Throwable t) {
            Log.w(TAG, "clickDetailed failed: " + t);
            r.reason = "send_failed";
            r.hint = String.valueOf(t.getMessage());
            return r;
        }
        if (raw == null) {
            r.reason = "send_failed";
            r.hint = "daemon 无响应";
            return r;
        }
        try {
            JSONObject o = new JSONObject(raw);
            r.ok = o.optBoolean("ok", false);
            r.jumped = o.optInt("jump", 0) == 1;
            r.costMs = o.optLong("costMs", -1);
            r.reason = o.isNull("reason") ? null : o.optString("reason");
            r.top = o.isNull("top") ? null : o.optString("top");
            r.hint = o.isNull("hint") ? null : o.optString("hint");
            if (!r.ok && r.reason == null) {
                // 老版本 daemon 只回 {"ok":false,"err":"..."}
                String err = o.isNull("err") ? "" : o.optString("err");
                r.reason = err.contains("not found") ? "notification_not_found" : "send_failed";
                r.hint = err;
            }
        } catch (Throwable t) {
            // 老 daemon 回的就是 {"ok":true}
            r.ok = raw.contains("\"ok\":true");
            r.jumped = r.ok;
        }
        return r;
    }

    public static boolean triggerAction(String key, int idx) {
        return call("/action/" + enc(key) + "/" + idx);
    }

    public static boolean muteChannel(String pkg, int uid, String channelId) {
        return call("/mute/" + enc(pkg + "|" + uid + "|" + channelId));
    }

    public static boolean unmuteChannel(String pkg, int uid, String channelId) {
        return call("/unmute/" + enc(pkg + "|" + uid + "|" + channelId));
    }

    public static boolean refresh() {
        return call("/refresh");
    }

    public static boolean shutdown() {
        return call("/shutdown");
    }

    public static String channelsJson(String pkg, int uid) {
        try {
            return get("/channels/" + enc(pkg) + "/" + uid, TIMEOUT);
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 底层 ====================

    private static boolean call(String path) {
        try {
            String r = get(path, TIMEOUT);
            return r != null && r.contains("\"ok\":true");
        } catch (Throwable t) {
            Log.w(TAG, "call " + path + " failed: " + t);
            return false;
        }
    }

    private static String get(String path, int timeoutMs) throws Exception {
        URL url = new URL(BASE + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("Connection", "close");
        try {
            int code = conn.getResponseCode();
            if (code != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }
}