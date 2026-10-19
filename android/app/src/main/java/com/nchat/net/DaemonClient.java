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

    public static boolean click(String key) {
        return call("/click/" + enc(key));
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