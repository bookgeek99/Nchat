import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.os.Process;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * nchatd —— Nchat 通知观测守护进程
 *
 * 运行身份：uid=0 (magisk context)
 * 启动：app_process /system/bin NchatDaemon
 *
 * 提供 HTTP 接口 (127.0.0.1:18765)：
 *   GET  /ping                    健康检查
 *   GET  /state                   全量活跃通知 + 渠道信息
 *   GET  /cancel/{urlencoded key} 取消通知
 *   GET  /action/{idx}/{actionIdx} 触发通知的某个 action
 *   GET  /click/{key}             触发通知的 contentIntent（跳转）
 *   GET  /mute/{pkg}|{uid}|{chId} 关闭渠道
 *   GET  /unmute/{pkg}|{uid}|{chId} 恢复渠道
 *   GET  /channels/{pkg}/{uid}    查询某包所有渠道
 *   GET  /refresh                 从系统重新拉取活跃通知
 *   GET  /shutdown                退出
 */
public class NchatDaemon {

    static final int PORT = 18765;
    static final String VERSION = "1";
    static final String OUT_DIR = "/sdcard/Develop/nchat";
    static PrintWriter log;

    static Object inm;
    static ListenerImpl listener;
    static Object wrapper;
    static Context sysCtx;
    static final Map<String, StatusBarNotification> active = new LinkedHashMap<>();
    static volatile boolean running = true;

    public static void main(String[] args) {
        try {
            new File(OUT_DIR).mkdirs();
            log = new PrintWriter(new FileOutputStream(new File(OUT_DIR, "daemon.log"), true), true);
            p("=== nchatd v" + VERSION + " start ===");
            p("uid=" + Process.myUid() + " api=" + android.os.Build.VERSION.SDK_INT);
            Looper.prepareMainLooper();

            Object at = Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentActivityThread").invoke(null);
            if (at == null) {
                at = Class.forName("android.app.ActivityThread")
                        .getDeclaredMethod("systemMain").invoke(null);
            }
            Method gsc = Class.forName("android.app.ActivityThread").getDeclaredMethod("getSystemContext");
            gsc.setAccessible(true);
            sysCtx = (Context) gsc.invoke(at);
            p("systemContext ok");

            Object nms = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "notification");
            inm = Class.forName("android.app.INotificationManager$Stub")
                    .getMethod("asInterface", android.os.IBinder.class).invoke(null, nms);
            p("INM ok");

            register();

            Thread t = new Thread(NchatDaemon::serve, "nchat-http");
            t.setDaemon(true);
            t.start();

            Looper.loop();
        } catch (Throwable t) {
            p("FATAL " + Log.getStackTraceString(t));
        }
    }

    // ==================== 注册监听 ====================

    static void register() {
        try {
            listener = new ListenerImpl();
            ComponentName cn = new ComponentName("/data/local/tmp/nchatd/nchatd.jar",
                    "NchatDaemon$ListenerImpl");
            Method m = NotificationListenerService.class.getMethod(
                    "registerAsSystemService", Context.class, ComponentName.class, int.class);
            m.invoke(listener, sysCtx, cn, -1);
            p("register OK");

            Field wf = NotificationListenerService.class.getDeclaredField("mWrapper");
            wf.setAccessible(true);
            wrapper = wf.get(listener);
            p("wrapper=" + wrapper);
        } catch (Throwable t) {
            p("register FAILED " + Log.getStackTraceString(t));
        }
    }

    public static class ListenerImpl extends NotificationListenerService {
        @Override
        public void onListenerConnected() {
            p("[CB] connected");
            refreshActive();
        }

        @Override
        public void onNotificationPosted(StatusBarNotification sbn) {
            synchronized (active) { active.put(sbn.getKey(), sbn); }
            p("[CB] posted " + sbn.getKey());
        }

        @Override
        public void onNotificationRemoved(StatusBarNotification sbn) {
            synchronized (active) { active.remove(sbn.getKey()); }
            p("[CB] removed " + sbn.getKey());
        }
    }

    static void refreshActive() {
        try {
            StatusBarNotification[] arr = listener.getActiveNotifications();
            synchronized (active) {
                active.clear();
                if (arr != null) for (StatusBarNotification s : arr) active.put(s.getKey(), s);
            }
            p("refreshActive n=" + (arr == null ? 0 : arr.length));
        } catch (Throwable t) {
            p("refreshActive FAILED " + t);
        }
    }

    // ==================== HTTP 服务 ====================

    static void serve() {
        ServerSocket ss = null;
        try {
            ss = new ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"));
            p("listening 127.0.0.1:" + PORT);
            while (running) {
                Socket s = ss.accept();
                try {
                    handle(s);
                } catch (Throwable t) {
                    p("handle err " + t);
                } finally {
                    try { s.close(); } catch (Throwable ignore) {}
                }
            }
        } catch (Throwable t) {
            p("serve FATAL " + Log.getStackTraceString(t));
        } finally {
            try { if (ss != null) ss.close(); } catch (Throwable ignore) {}
        }
    }

    static void handle(Socket s) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
        String reqLine = in.readLine();
        if (reqLine == null) return;
        String[] parts = reqLine.split(" ");
        String path = parts.length > 1 ? parts[1] : "/";
        // 消费 headers（可能带 body，但本服务只需要 GET）
        String line;
        int contentLength = 0;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring(15).trim());
            }
        }
        for (int i = 0; i < contentLength; i++) in.read();

        String body;
        try {
            body = dispatch(path);
        } catch (Throwable t) {
            body = error(rootMsg(t));
        }

        byte[] bytes = body.getBytes("UTF-8");
        PrintWriter out = new PrintWriter(s.getOutputStream());
        out.print("HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n");
        out.flush();
        s.getOutputStream().write(bytes);
        s.getOutputStream().flush();
    }

    static String dispatch(String rawPath) throws Exception {
        // 关键：先解码再匹配路径，否则 /cancel/0%7C... 无法命中 /cancel/ 前缀
        int q0 = rawPath.indexOf('?');
        if (q0 >= 0) rawPath = rawPath.substring(0, q0);
        String path = java.net.URLDecoder.decode(rawPath, "UTF-8");
        if (!path.startsWith("/state")) p("REQ " + path);

        if (path.equals("/") || path.startsWith("/ping")) return pingJson();
        if (path.startsWith("/state")) return stateJson();
        if (path.startsWith("/cancel/")) { cancel(path.substring(8)); return ok(); }
        if (path.startsWith("/click/")) return click(path.substring(7));
        if (path.startsWith("/action/")) return action(path.substring(8));
        if (path.startsWith("/mute/")) return channelOp(path.substring(6), true);
        if (path.startsWith("/unmute/")) return channelOp(path.substring(8), false);
        if (path.startsWith("/channels/")) return channelsJson(path.substring(10));
        if (path.startsWith("/refresh")) { refreshActive(); return ok(); }
        if (path.startsWith("/shutdown")) {
            p("shutdown requested");
            running = false;
            new Thread(() -> { try { Thread.sleep(300); } catch (Throwable ignore) {} System.exit(0); }).start();
            return ok();
        }
        return error("unknown path");
    }

    // ==================== 操作 ====================

    static void cancel(String key) throws Exception {
        Method m = inm.getClass().getMethod("cancelNotificationsFromListener",
                Class.forName("android.service.notification.INotificationListener"), String[].class);
        m.invoke(inm, wrapper, new String[]{key});
        p("cancel ok " + key);
    }

    /** /click/{key} —— 触发 contentIntent */
    static String click(String key) {
        try {
            StatusBarNotification sbn;
            synchronized (active) { sbn = active.get(key); }
            if (sbn == null) return error("notification not found");
            PendingIntent pi = sbn.getNotification().contentIntent;
            if (pi == null) return error("no contentIntent");

            // Android 12+ 后台启动 Activity 受限：
            // daemon 是后台 uid=0 进程，直接 pi.send() 会被 BAL 拦截（返回成功但什么都没发生）。
            // 必须显式带上「允许后台启动」的 ActivityOptions，并把 sender 上下文带上。
            boolean sent = false;
            String lastErr = null;

            // 方案 1：ActivityOptions 明确允许后台启动（API 34+）
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                try {
                    android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                    opts.setPendingIntentBackgroundActivityStartMode(
                            android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    pi.send(sysCtx, 0, null, null, null, null, opts.toBundle());
                    sent = true;
                } catch (Throwable t) {
                    lastErr = rootMsg(t);
                }
            }
            // 方案 2：ActivityOptions 允许（API 31~33）
            if (!sent && android.os.Build.VERSION.SDK_INT >= 31) {
                try {
                    android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                    android.app.ActivityOptions.class
                            .getMethod("setPendingIntentBackgroundActivityLaunchAllowed", boolean.class)
                            .invoke(opts, true);
                    pi.send(sysCtx, 0, null, null, null, null, opts.toBundle());
                    sent = true;
                } catch (Throwable t) {
                    lastErr = rootMsg(t);
                }
            }
            // 方案 3：兜底，带 Context 的 send
            if (!sent) {
                try {
                    pi.send(sysCtx, 0, null, null, null, null, null);
                    sent = true;
                } catch (Throwable t) {
                    lastErr = rootMsg(t);
                    // 最后再试一次无参版本
                    try { pi.send(); sent = true; } catch (Throwable t2) { lastErr = rootMsg(t2); }
                }
            }

            if (!sent) {
                p("click FAILED " + key + " : " + lastErr);
                return error("send failed: " + lastErr);
            }
            p("click ok " + key);
            return ok();
        } catch (Throwable t) {
            p("click FAILED " + rootMsg(t));
            return error(rootMsg(t));
        }
    }

    /** /action/{key}/{idx} —— 触发通知的第 idx 个 action */
    static String action(String spec) {
        try {
            int idx = spec.lastIndexOf('/');
            String key = spec.substring(0, idx);
            int ai = Integer.parseInt(spec.substring(idx + 1));
            StatusBarNotification sbn;
            synchronized (active) { sbn = active.get(key); }
            if (sbn == null) return error("notification not found");
            Notification.Action[] actions = sbn.getNotification().actions;
            if (actions == null || ai >= actions.length) return error("action index out of range");
            actions[ai].actionIntent.send();
            p("action ok " + key + " #" + ai);
            return ok();
        } catch (Throwable t) {
            return error(rootMsg(t));
        }
    }

    /** spec: pkg|uid|channelId */
    static String channelOp(String spec, boolean mute) {
        try {
            // 注意：用 limit=3，通道 ID 本身可能含 |（如 mipush|pkg|100031），不能被切开
            String[] f = spec.split("\\|", 3);
            String pkg = f[0];
            int uid = Integer.parseInt(f[1]);
            String chId = f.length > 2 ? f[2] : "";

            NotificationChannel target = findChannel(pkg, uid, chId);
            if (target == null) {
                p("channelOp: channel not found " + pkg + "/" + chId);
                return error("channel not found");
            }

            // IMPORTANCE_NONE=0 / IMPORTANCE_DEFAULT=3（常量在 NotificationManager 中，这里直接写字面值）
            int targetImp = mute ? 0 : 3;
            target.setImportance(targetImp);

            // 优先走非 listener 路径：updateNotificationChannelForPackage(String, int, NotificationChannel)
            // （privilegedListener 路径要求 listener 是 system listener，我们不是，会被拒）
            boolean done = false;
            try {
                Method u = inm.getClass().getMethod("updateNotificationChannelForPackage",
                        String.class, int.class, NotificationChannel.class);
                u.invoke(inm, pkg, uid, target);
                done = true;
            } catch (Throwable t1) {
                p("updateForPackage failed: " + rootMsg(t1));
            }

            if (!done) {
                Method u = inm.getClass().getMethod("updateNotificationChannelFromPrivilegedListener",
                        Class.forName("android.service.notification.INotificationListener"),
                        String.class, Class.forName("android.os.UserHandle"), NotificationChannel.class);
                u.invoke(inm, wrapper, pkg, Process.myUserHandle(), target);
            }
            p((mute ? "mute" : "unmute") + " ok " + pkg + "/" + chId + " imp=" + targetImp);
            return ok();
        } catch (Throwable t) {
            p("channelOp FAILED " + rootMsg(t));
            return error(rootMsg(t));
        }
    }

    static NotificationChannel findChannel(String pkg, int uid, String chId) throws Exception {
        Method g = inm.getClass().getMethod("getNotificationChannelsForPackage",
                String.class, int.class, boolean.class);
        Object slice = g.invoke(inm, pkg, uid, false);
        List<?> list = (List<?>) slice.getClass().getMethod("getList").invoke(slice);
        for (Object o : list) {
            NotificationChannel c = (NotificationChannel) o;
            if (c.getId().equals(chId)) return c;
        }
        return null;
    }

    static String channelsJson(String spec) {
        try {
            String[] f = spec.split("/", -1);
            String pkg = f[0];
            int uid = Integer.parseInt(f[1]);
            Method g = inm.getClass().getMethod("getNotificationChannelsForPackage",
                    String.class, int.class, boolean.class);
            Object slice = g.invoke(inm, pkg, uid, false);
            List<?> list = (List<?>) slice.getClass().getMethod("getList").invoke(slice);
            StringBuilder sb = new StringBuilder("{\"ok\":true,\"channels\":[");
            boolean first = true;
            for (Object o : list) {
                NotificationChannel c = (NotificationChannel) o;
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"id\":\"").append(esc(c.getId())).append("\",");
                sb.append("\"name\":\"").append(esc(c.getName() + "")).append("\",");
                sb.append("\"importance\":").append(c.getImportance()).append(",");
                sb.append("\"desc\":\"").append(esc(c.getDescription() == null ? "" : c.getDescription())).append("\"}");
            }
            return sb.append("]}").toString();
        } catch (Throwable t) {
            return error(rootMsg(t));
        }
    }

    // ==================== JSON ====================

    static String pingJson() {
        return "{\"ok\":true,\"version\":\"" + VERSION + "\",\"uid\":" + Process.myUid()
                + ",\"api\":" + android.os.Build.VERSION.SDK_INT + "}";
    }

    static String stateJson() {
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"count\":");
        synchronized (active) {
            sb.append(active.size()).append(",\"items\":[");
            boolean first = true;
            for (StatusBarNotification sbn : active.values()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(itemJson(sbn));
            }
        }
        return sb.append("]}").toString();
    }

    static String itemJson(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle ex = n.extras;
        String cid = channelId(n);
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"key\":\"").append(esc(sbn.getKey())).append("\",");
        sb.append("\"pkg\":\"").append(esc(sbn.getPackageName())).append("\",");
        sb.append("\"uid\":").append(sbn.getUid()).append(',');
        sb.append("\"id\":").append(sbn.getId()).append(',');
        sb.append("\"postTime\":").append(sbn.getPostTime()).append(',');
        sb.append("\"channel\":\"").append(esc(cid)).append("\",");
        sb.append("\"channelName\":\"").append(esc(channelName(sbn.getPackageName(), sbn.getUid(), cid))).append("\",");
        sb.append("\"category\":").append(n.category == null ? "null" : "\"" + esc(n.category) + "\"").append(',');
        sb.append("\"flags\":").append(n.flags).append(',');
        sb.append("\"ongoing\":").append(sbn.isOngoing()).append(',');
        sb.append("\"clearable\":").append(sbn.isClearable()).append(',');
        sb.append("\"title\":\"").append(esc(cs(ex.getCharSequence(Notification.EXTRA_TITLE)))).append("\",");
        sb.append("\"text\":\"").append(esc(cs(ex.getCharSequence(Notification.EXTRA_TEXT)))).append("\",");
        sb.append("\"bigText\":\"").append(esc(cs(ex.getCharSequence(Notification.EXTRA_BIG_TEXT)))).append("\",");
        sb.append("\"subText\":\"").append(esc(cs(ex.getCharSequence(Notification.EXTRA_SUB_TEXT)))).append("\",");
        sb.append("\"hasIntent\":").append(n.contentIntent != null).append(',');
        // 分组信息：用于在 App 侧剔除「聚合摘要」通知（GroupSummary）
        String gk = callStr(sbn, "getGroup");
        sb.append("\"groupKey\":").append(gk == null ? "null" : "\"" + esc(gk) + "\"").append(',');
        sb.append("\"isGroupSummary\":").append(callBool(n, "isGroupSummary")).append(',');
        // 渠道重要性：App 侧用来渲染开关（0=已关闭）
        sb.append("\"channelImportance\":").append(channelImportance(sbn.getPackageName(), sbn.getUid(), cid)).append(',');
        // 是否属于「系统/常驻/降噪」类，App 侧默认过滤
        sb.append("\"noisy\":").append(isNoisy(sbn)).append(',');
        sb.append("\"actions\":[");
        if (n.actions != null) {
            for (int i = 0; i < n.actions.length; i++) {
                if (i > 0) sb.append(',');
                sb.append("\"").append(esc(cs(n.actions[i].title))).append("\"");
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    /** getGroup() / isGroupSummary() 都是 @hide，只能反射 */
    static String callStr(Object o, String m) {
        try {
            Object r = o.getClass().getMethod(m).invoke(o);
            return r == null ? null : String.valueOf(r);
        } catch (Throwable ignore) { return null; }
    }

    static boolean callBool(Object o, String m) {
        try {
            Object r = o.getClass().getMethod(m).invoke(o);
            return r != null && (Boolean) r;
        } catch (Throwable ignore) { return false; }
    }

    /** 渠道 importance，取不到返回 -1 */
    static int channelImportance(String pkg, int uid, String cid) {
        try {
            NotificationChannel c = findChannel(pkg, uid, cid);
            return c == null ? -1 : c.getImportance();
        } catch (Throwable ignore) { return -1; }
    }

    /**
     * 判定「不该进入决策流」的通知：
     * - 常驻通知（前台服务、音乐播放器等）：用户无法直接清掉
     * - 系统低优先级/静默组：UNIMPORTANT、系统 UI 提示等
     * App 侧还会再做一次白名单判断，这里只给原始信号。
     */
    /**
     * 是否是「系统/常驻」类噪音通知。
     *
     * 注意：早期版本用 `sbn.isOngoing()` 一刀切，会误伤大量正常通知
     * （很多 App 的普通推送也带 ongoing 标记），导致「通知栏明明有一堆、
     * App 里却说全处理完了」。现在改成更保守的判据：
     *   - 系统进程（android / systemui）的通知
     *   - 明确的服务/CATEGORY_SERVICE、媒体传输 CATEGORY_TRANSPORT
     *   - 小米系明确的系统组件包
     * 其余一律交给上层正常展示，由用户自己决定要不要「不感兴趣」。
     */
    static boolean isNoisy(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null) return true;
        String pkg = sbn.getPackageName();
        if (pkg == null) return true;

        // 系统进程自身的杂项通知
        if ("android".equals(pkg) || "com.android.systemui".equals(pkg)) return true;

        // 【最强信号】用户划不掉的通知 = 常驻服务/前台服务。
        // 这类通知本来就该一直挂在那里，不属于「待处理」的范畴；
        // 而且它们通常 contentIntent 指向自己的主界面，
        // 点一下只会跳回那个 App（比如点了 Operit 的常驻通知就跳回 Operit），
        // 对用户毫无意义。sbn.isClearable() 比 isOngoing() 可靠得多 ——
        // ongoing 标记很多普通推送也会带，而 clearable=false 一定是常驻。
        if (!sbn.isClearable()) return true;

        // 明确的媒体传输 / 前台服务类
        if (Notification.CATEGORY_TRANSPORT.equals(n.category)) return true;
        if (Notification.CATEGORY_SERVICE.equals(n.category)) return true;

        // 小米系统组件（耳机提示、跨屏协同、互联服务、扫描设备等），
        // 这些都是纯状态展示，点了也没有可去的地方。
        if (NOISY_PKGS.contains(pkg)) return true;

        return false;
    }

    /** 明确属于「状态展示型系统组件」的包名 */
    static final java.util.Set<String> NOISY_PKGS = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.miui.misound",              // 耳机使用提示 / 声音助手
            "com.xiaomi.mirror",             // 跨屏协同
            "com.xiaomi.mi_connect_service", // 小米互联服务 / 扫描设备
            "com.milink.service",            // 全场景音乐服务
            "com.miui.tsmclient",            // 钱包后台服务
            "com.miui.securitycenter",       // 安全辅助通知
            "com.xiaomi.aicr"                // Xiaomi HyperAI
    ));

    static String channelName(String pkg, int uid, String cid) {
        try {
            NotificationChannel c = findChannel(pkg, uid, cid);
            return c == null ? "" : String.valueOf(c.getName());
        } catch (Throwable ignore) { return ""; }
    }

    static String channelId(Notification n) {
        try {
            Object r = Notification.class.getMethod("getChannelId").invoke(n);
            return r == null ? "" : r.toString();
        } catch (Throwable t) { return ""; }
    }

    static String cs(CharSequence c) { return c == null ? "" : c.toString(); }

    static String ok() { return "{\"ok\":true}"; }

    static String error(String msg) { return "{\"ok\":false,\"err\":\"" + esc(msg) + "\"}"; }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        return sb.toString();
    }

    static String rootMsg(Throwable t) {
        while (t instanceof InvocationTargetException && t.getCause() != null) t = t.getCause();
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    static synchronized void p(String s) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss").format(new Date()) + " " + s;
        if (log != null) { log.println(line); log.flush(); }
        System.out.println(line);
    }
}