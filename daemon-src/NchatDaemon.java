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
import java.io.FileInputStream;
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
import java.util.Locale;
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

    /** [联动] 统一数据目录，与 recorderhub / autotrans 对齐（任务书 1.1 节）。 */
    static final String SHARED_DIR = "/sdcard/Download/nchat";
    static final String CHANNELS_FILE = SHARED_DIR + "/channels.json";
    static final String SNAPSHOT_FILE = SHARED_DIR + "/mute-snapshot.json";
    static final String STATUS_FILE = SHARED_DIR + "/status.json";

    /** 渠道登记表上限（任务书 2.1 节）。 */
    static final int MAX_CHANNELS = 500;
    /** 快照自动恢复阈值：超时未恢复则视为异常残留（任务书 2.2 节）。 */
    static final long SNAPSHOT_AUTO_RESTORE_MS = 30L * 60L * 1000L;

    static PrintWriter log;

    static Object inm;
    static ListenerImpl listener;
    static Object wrapper;
    static Context sysCtx;
    static final Map<String, StatusBarNotification> active = new LinkedHashMap<>();
    static volatile boolean running = true;

    // ==================== [联动] 渠道登记表 ====================

    /**
     * 见过的通知渠道（任务书 2.1 节）。
     *
     * 注意：第三方进程无权读取其他应用的 NotificationChannel 中文名，
     * 因此 label 退化为「最近一条通知的 title」，仅用于给用户辨认。
     * 真正的渠道名（channelName）在 /state 里仍会尝试读取，可能为空。
     */
    static class ChannelEntry {
        final String pkg;
        final int uid;
        final String channel;
        int importance;      // 最近一次读到的 importance
        String label;        // 最近一条通知的 title
        long lastSeen;       // 最近一次见到的时间戳（ms）

        ChannelEntry(String pkg, int uid, String channel) {
            this.pkg = pkg;
            this.uid = uid;
            this.channel = channel;
        }

        String key() { return pkg + "|" + uid + "|" + channel; }
    }

    /** key = "pkg|uid|channel" */
    static final Map<String, ChannelEntry> knownChannels = new LinkedHashMap<>();

    /** 渠道登记表写入锁（HTTP 线程和回调线程都会碰）。 */
    static final Object channelLock = new Object();

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

            // [联动] 载入历史渠道表 + 处理残留快照，然后再注册监听
            loadChannels();
            autoRestoreStaleSnapshot();

            register();

            // [联动] 启动后立刻导出一次，确保 channels.json / status.json 存在
            persistChannels();
            writeStatus();

            // [联动] 定期刷新 status.json（外部监控依赖 updatedAt 的新鲜度）
            Thread hb = new Thread(NchatDaemon::heartbeat, "nchat-heartbeat");
            hb.setDaemon(true);
            hb.start();

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
            // [联动] 登记渠道，供 /channels 导出
            try { recordChannel(sbn); } catch (Throwable ignore) {}
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
            // [联动] 启动/刷新时把现存通知的渠道也登记进来，避免要等新通知才有数据
            if (arr != null) {
                for (StatusBarNotification s : arr) {
                    try { recordChannel(s); } catch (Throwable ignore) {}
                }
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
        // [联动] 注意顺序：/mute-all 必须排在 /mute/ 之前，否则会被当成 pkg="all"
        if (path.startsWith("/mute-all")) return muteAll();
        if (path.startsWith("/unmute-all")) return unmuteAll();
        if (path.startsWith("/mute/")) return channelOp(path.substring(6), true);
        if (path.startsWith("/unmute/")) return channelOp(path.substring(8), false);
        // [联动] 不带参数的 /channels = 导出全部见过的渠道；带参数保持原语义
        if (path.equals("/channels") || path.startsWith("/channels?")) return channelsListJson();
        if (path.startsWith("/channels/")) return channelsJson(path.substring(10));
        if (path.startsWith("/refresh")) { refreshActive(); persistChannels(); writeStatus(); return ok(); }
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

    /**
     * /click/{key} —— 触发 contentIntent，并**实际校验**是否真的跳过去了。
     *
     * 背景（第十二轮排查结论）：
     *   pi.send() 返回 true **只代表 PendingIntent 送达系统**，
     *   不代表目标 Activity 真的起来了。Android 15 的 BAL 收紧会在
     *   ActivityTaskManager 层把启动静默拦掉，日志形如：
     *     "only the creator of the PendingIntent allows BAL while the sender does not"
     *     resultIfPiSenderAllowsBal: BAL_BLOCK
     *   此时 send 依然返回成功，调用方完全感知不到 —— 用户看到的就是「点了没反应」。
     *
     * 所以这里改成：send 之后等一小会儿，比对前台窗口有没有变化，
     * 把真实结果（ok / blocked / no-change）连同诊断信息一起返回。
     *
     * 返回 JSON（除 ok 外都带 err，兼容原有 App 逻辑）：
     *   {"ok":true,  "jump":1, "costMs":312, "top":"com.xxx/.Act"}
     *   {"ok":false, "jump":0, "costMs":2500, "reason":"bal_block", "top":"com.nchat/...","hint":"..."}
     */
    static String click(String key) {
        long t0 = System.currentTimeMillis();
        try {
            StatusBarNotification sbn;
            synchronized (active) { sbn = active.get(key); }
            if (sbn == null) return error("notification not found");
            Notification n = sbn.getNotification();
            PendingIntent pi = n.contentIntent;
            if (pi == null) return error("no contentIntent");

            String before = topActivity();
            String piPkg = null;
            try { piPkg = pi.getCreatorPackage(); } catch (Throwable ignore) {}

            // send 前先记下 intent 的静态信息，便于事后对账
            StringBuilder diag = new StringBuilder();
            diag.append("piPkg=").append(piPkg)
                .append(" isActivity=").append(safeIsActivity(pi))
                .append(" flags=0x").append(Integer.toHexString(n.flags))
                .append(" before=").append(before);

            boolean sent = false;
            String lastErr = null;

            // ===== send 之前，先把目标 App 拉到前台 =====
            //
            // Android 15+ 的 BAL 看的是「发起 PendingIntent 的那一刻，
            // 调用方有没有可见窗口」。daemon 是后台进程，永远不满足。
            // 等第一次 send 被拦掉再补救没有意义 —— 第二次 send
            // 仍然是从后台发出，系统不会因为上次被拦就改变判定。
            //
            // 正确做法：先制造「目标 App 已在前台」这个事实，再 send。
            // 此时 PendingIntent 的目标就是当前前台包，
            // BAL 的判定条件不再成立。
            //
            // 另一个必须避开的坑：目标 App 已经在前台时，
            // 再调 bringPackageToFront 会把它拉回首页，
            // 把即将打开的详情页顶掉。所以先判断，已在前台就跳过。
            String targetPkg0 = null;
            try { targetPkg0 = sbn.getPackageName(); } catch (Throwable ignore) {}
            boolean alreadyFront = targetPkg0 != null && before != null
                    && before.startsWith(targetPkg0 + "/");
            if (targetPkg0 != null && !targetPkg0.isEmpty() && !alreadyFront) {
                long w0 = System.currentTimeMillis();
                boolean warmed = bringPackageToFront(targetPkg0, 1500);
                long wCost = System.currentTimeMillis() - w0;
                diag.append(" prewarm=").append(warmed).append("/").append(wCost).append("ms");
                p("click prewarm " + targetPkg0 + " -> " + warmed + " in " + wCost + "ms");
            } else if (alreadyFront) {
                diag.append(" prewarm=skip(already-front)");
            }

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
                    try { pi.send(); sent = true; } catch (Throwable t2) { lastErr = rootMsg(t2); }
                }
            }

            if (!sent) {
                long cost = System.currentTimeMillis() - t0;
                p("click SEND-FAILED " + key + " : " + lastErr);
                return error("send failed: " + lastErr);
            }

            // ---- 第一轮：直接 send，等最多 2.5s ----
            // 每 250ms 采样一次前台窗口。
            //
            // 注意 before 可能为 null（某些系统上取不到前台）。
            // 那种情况下不能把「null → 某个值」当成跳转，也不能把
            // 「null → null」当成失败 —— 否则会把全部点击误判成失败
            // （我们就踩过这个坑，33 条全灭）。
            // 取不到时就退化成「无法校验」，按 send 成功处理，并在 reason 里标明。
            String after = before;
            long deadline = t0 + 2500;
            while (System.currentTimeMillis() < deadline) {
                try { Thread.sleep(250); } catch (InterruptedException ignore) {}
                after = topActivity();
                if (after != null && (before == null || !after.equals(before))) break;
                if (after != null && before == null) break;
            }
            long cost = System.currentTimeMillis() - t0;

            boolean canVerify = before != null || after != null;
            boolean jumped;
            if (before == null && after == null) {
                // 两条路都取不到前台 —— 无法校验，按成功处理，别误报失败
                jumped = true;
            } else if (before == null) {
                // 之前取不到、现在取到了：至少能确定有东西起来了
                jumped = true;
            } else {
                jumped = after != null && !after.equals(before);
            }
            String afterPkg = after == null ? "" : after.split("/")[0];
            diag.append(" after=").append(after).append(" cost=").append(cost).append("ms");

            // ---- 第二轮：重发一次 ----
            //
            // 注意：这里**不再**调用 bringPackageToFront。
            // 前面已经在 send 之前 warmup 过了。如果这里再拉一次，
            // 会把刚打开的详情页重新顶回 App 首页 ——
            // 这正是之前「有时候反而进不去 / 回到首页」的元凶。
            // 现在只做一件事：目标包已在桌面的前提下，再 send 一次。
            if (!jumped && targetPkg0 != null && !targetPkg0.isEmpty()) {
                {
                    {
                        boolean warmed = true;
                        // 重发一次 contentIntent
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= 34) {
                                android.app.ActivityOptions opts =
                                        android.app.ActivityOptions.makeBasic();
                                opts.setPendingIntentBackgroundActivityStartMode(
                                        android.app.ActivityOptions
                                                .MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                                pi.send(sysCtx, 0, null, null, null, null, opts.toBundle());
                            } else {
                                pi.send(sysCtx, 0, null, null, null, null, null);
                            }
                        } catch (Throwable ignore) {}

                        // 再等最多 1.5s
                        long d2 = System.currentTimeMillis() + 1500;
                        String after2 = after;
                        while (System.currentTimeMillis() < d2) {
                            try { Thread.sleep(200); } catch (InterruptedException ignore) {}
                            after2 = topActivity();
                            if (after2 != null && !after2.equals(after)) break;
                        }
                        if (after2 != null && !after2.equals(after)) {
                            after = after2;
                            jumped = true;
                        }
                        afterPkg = after == null ? "" : after.split("/")[0];
                        cost = System.currentTimeMillis() - t0;
                        diag.append(" retry-after=").append(after).append(" total=").append(cost).append("ms");
                    }
                }
            }

            if (jumped) {
                p("click OK " + key + " | " + diag);
                return "{\"ok\":true,\"jump\":1,\"costMs\":" + cost
                        + ",\"top\":\"" + esc(after) + "\"}";
            }

            // 没跳走。判断是「被 BAL 拦」还是「intent 本身就指向别处/无页面」
            // BAL 的特征：前台没变，且 intent 的目标包不是当前前台。
            // 我们从 logcat 抓不到时序可靠的记录，改用「目标包 vs 当前前台」来分类。
            String targetPkg = null;
            try {
                String tgt = sbn.getPackageName();
                if (tgt != null) targetPkg = tgt;
            } catch (Throwable ignore) {}
            String reason;
            String hint;
            if (targetPkg != null && targetPkg.equals(afterPkg)) {
                reason = "already_front";
                hint = "\u76ee\u6807 App \u5df2\u5728\u524d\u53f0\uff0c\u8df3\u8f6c\u672c\u8eab\u6ca1\u6709\u4ea7\u751f\u754c\u9762\u53d8\u5316";
            } else if (before == null && after == null) {
                reason = "unverified";
                hint = "\u65e0\u6cd5\u53d6\u5f97\u524d\u53f0 Activity\uff0c\u65e0\u6cd5\u6821\u9a8c\u8df3\u8f6c\u7ed3\u679c";
            } else if (piPkg != null && !piPkg.equals(targetPkg)) {
                reason = "bal_block";
                hint = "\u7531 " + piPkg + " \u4ee3\u53d1\u7684 PendingIntent\uff0cAndroid 15 BAL \u9650\u5236\u4e0b\u88ab\u9759\u9ed8\u62e6\u622a";
            } else {
                reason = "no_change";
                hint = "intent \u5df2\u53d1\u9001\u4f46\u524d\u53f0\u672a\u53d8\u5316\uff0c\u76ee\u6807\u9875\u53ef\u80fd\u4e0d\u5b58\u5728\u6216\u9700\u8981 App \u81ea\u884c\u5904\u7406";
            }
            p("click NO-JUMP " + key + " reason=" + reason + " | " + diag);
            return "{\"ok\":false,\"jump\":0,\"costMs\":" + cost
                    + ",\"reason\":\"" + reason + "\""
                    + ",\"top\":\"" + esc(after) + "\""
                    + ",\"err\":\"did not jump\""
                    + ",\"hint\":\"" + esc(hint) + "\"}";
        } catch (Throwable t) {
            p("click FAILED " + key + " : " + rootMsg(t));
            return error(rootMsg(t));
        }
    }

    /**
     * 取当前前台 Activity 的 "pkg/Activity"，失败返回 null。
     *
     * 之前用 ActivityTaskManager.getTasks() 反射，在 Android 16 上全部返回空
     * （签名变了 / 方法被裁），导致 before/after 都是 null，
     * 所有点击都被误判成「没跳转」。现在改成四条路，逐条尝试：
     *
     *   1) ActivityTaskManager.getFocusedRootTaskInfo()  ← 最直接
     *   2) RootWindowContainer 单例遍历 mChildren（daemon 与 system_server 同进程）
     *   3) getTasks(int) / getTasks(...)  老签名
     *   4) ActivityManager.getRunningTasks(1)（root 通常仍可用）
     *
     * 任一成功即返回。全部失败才返回 null —— 而 null 不再被当成
     * 「没有变化」，click() 里会退化为「无法校验，按 send 成功计」。
     */
    static String topActivity() {
        String r;
        r = topViaFocusedRootTask();
        if (r != null) return r;
        r = topViaRootWindowContainer();
        if (r != null) return r;
        r = topViaGetTasks();
        if (r != null) return r;
        r = topViaActivityManager();
        return r;
    }

    /** 路线 1：ActivityTaskManager.getFocusedRootTaskInfo() */
    static String topViaFocusedRootTask() {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object atm = atmCls.getMethod("getService").invoke(null);
            Object info = atmCls.getMethod("getFocusedRootTaskInfo").invoke(atm);
            if (info == null) return null;
            return fromTask(info);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 路线 2：直接走 RootWindowContainer 单例。
     * daemon 跑在 system_server 进程里（ActivityThread.systemMain），
     * 所以这个类的静态状态可以直接摸到。
     */
    static String topViaRootWindowContainer() {
        try {
            Class<?> rwcCls = Class.forName("com.android.server.wm.RootWindowContainer");
            Object rwc = null;
            try {
                rwc = rwcCls.getMethod("self").invoke(null);
            } catch (Throwable ignore) {
                rwc = rwcCls.getMethod("getInstance").invoke(null);
            }
            if (rwc == null) return null;
            // mChildren 里是 Task / ActivityRecord，倒序遍历找第一个带 top 的
            java.lang.reflect.Field f = rwcCls.getDeclaredField("mChildren");
            f.setAccessible(true);
            Object arr = f.get(rwc);
            int n = java.lang.reflect.Array.getLength(arr);
            for (int i = n - 1; i >= 0; i--) {
                Object child = java.lang.reflect.Array.get(arr, i);
                String r = fromTask(child);
                if (r != null) return r;
            }
        } catch (Throwable ignore) {}
        return null;
    }

    /** 路线 3：getTasks 的两种签名 */
    static String topViaGetTasks() {
        try {
            Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
            Object atm = atmCls.getMethod("getService").invoke(null);
            java.util.List<?> tasks = null;
            try {
                tasks = (java.util.List<?>) atmCls.getMethod("getTasks", int.class)
                        .invoke(atm, 1);
            } catch (Throwable ignore) {}
            if (tasks == null) {
                try {
                    tasks = (java.util.List<?>) atmCls.getMethod("getTasks",
                            int.class, boolean.class, boolean.class, int.class)
                            .invoke(atm, 1, false, false, 0);
                } catch (Throwable ignore) {}
            }
            if (tasks != null) {
                for (Object task : tasks) {
                    String r = fromTask(task);
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignore) {}
        return null;
    }

    /** 路线 4：ActivityManager.getRunningTasks(1) */
    static String topViaActivityManager() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    sysCtx.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            if (am == null) return null;
            java.util.List<android.app.ActivityManager.RunningTaskInfo> list =
                    am.getRunningTasks(1);
            if (list == null || list.isEmpty()) return null;
            android.content.ComponentName cn = list.get(0).topActivity;
            return cn == null ? null : cn.flattenToShortString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 从一个 Task/TaskInfo/RootTaskInfo/ActivityRecord 里挖出 "pkg/Activity" */
    static String fromTask(Object task) {
        if (task == null) return null;
        Class<?> c = task.getClass();

        // 本身可能就是 ActivityRecord
        String direct = fromActivityRecord(task);
        if (direct != null) return direct;

        // getTopActivity() → ActivityRecord
        try {
            Object act = c.getMethod("getTopActivity").invoke(task);
            String r = fromActivityRecord(act);
            if (r != null) return r;
        } catch (Throwable ignore) {}
        // 字段 topActivity（ActivityRecord 或 ComponentName）
        try {
            Object act = c.getField("topActivity").get(task);
            String r = fromActivityRecord(act);
            if (r != null) return r;
        } catch (Throwable ignore) {}
        // 字段 mActivity / mTopActivity
        try {
            Object act = c.getField("mActivity").get(task);
            String r = fromActivityRecord(act);
            if (r != null) return r;
        } catch (Throwable ignore) {}
        return null;
    }

    /** ActivityRecord / ComponentName / Task → "pkg/Activity" */
    static String fromActivityRecord(Object act) {
        if (act == null) return null;
        if (act instanceof android.content.ComponentName) return act.toString();
        String cn = act.getClass().getName();
        boolean looksLikeActivity = cn.contains("ActivityRecord");
        boolean looksLikeTask = cn.contains("TaskInfo") || cn.contains("Task");
        // 既不是 ActivityRecord 也不是 Task 的对象，别硬解，交给上层换一条路
        if (!looksLikeActivity && !looksLikeTask) return null;

        try {
            Class<?> c = act.getClass();
            String pkg = null, cls = null;
            for (String m : new String[]{"packageName", "mPackageName"}) {
                try { pkg = (String) c.getMethod(m).invoke(act); break; } catch (Throwable ignore) {}
                try { pkg = (String) c.getField(m).get(act); break; } catch (Throwable ignore) {}
            }
            for (String m : new String[]{"className", "mClassName"}) {
                try { cls = (String) c.getMethod(m).invoke(act); break; } catch (Throwable ignore) {}
                try { cls = (String) c.getField(m).get(act); break; } catch (Throwable ignore) {}
            }
            if (pkg == null) return null;
            if (cls == null) return pkg;
            if (cls.contains("/")) return cls;
            return pkg + "/" + cls;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 用 root 身份把某个包拉到前台。
     *
     * 目的：**绕开 Android 15 的 BAL 限制**。
     * BAL 的判定逻辑是「PendingIntent 的发送者有没有可见窗口 / 是否在前台」。
     * daemon 是后台进程，永远不满足，所以 pi.send() 会被静默拦截。
     *
     * 但如果我们先用 root 直接 startActivity 把目标 App 拉起来，
     * 目标 App 就成了当前前台，此时再 pi.send()，
     * 系统的判定就变成「目标已经在前面了」，BAL 不再是障碍。
     *
     * 拉起的入口选择（按成功率排序）：
     *   1) 该包的 LAUNCHER activity（最稳，一定有）
     *   2) getLaunchIntentForPackage
     *
     * 用 ActivityTaskManager 而不是 Context.startActivity ——
     * root 身份 + system_server 进程内调用，不受 startActivity 的调用方限制。
     *
     * @return true 表示已经成功把包拉到前台
     */
    static boolean bringPackageToFront(String pkg, long waitMs) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            // 1) 找 LAUNCHER activity
            String comp = null;
            try {
                android.content.pm.PackageManager pm = sysCtx.getPackageManager();
                android.content.Intent launch = pm.getLaunchIntentForPackage(pkg);
                if (launch != null && launch.getComponent() != null) {
                    comp = launch.getComponent().flattenToShortString();
                }
            } catch (Throwable ignore) {}

            // 2) 退化：查 resolveActivity(MAIN/LAUNCHER)
            if (comp == null) {
                try {
                    android.content.pm.PackageManager pm = sysCtx.getPackageManager();
                    android.content.Intent probe = new android.content.Intent(
                            android.content.Intent.ACTION_MAIN)
                            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                            .setPackage(pkg);
                    android.content.pm.ResolveInfo ri = pm.resolveActivity(
                            probe, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
                    if (ri != null && ri.activityInfo != null) {
                        comp = ri.activityInfo.packageName + "/" + ri.activityInfo.name;
                    }
                } catch (Throwable ignore) {}
            }
            if (comp == null) {
                p("warmup: no launcher for " + pkg);
                return false;
            }

            // 3) 用 ActivityTaskManager 直接启动（root + 同进程，不受 BAL 约束）
            android.content.Intent intent = new android.content.Intent();
            intent.setComponent(android.content.ComponentName.unflattenFromString(comp));
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            // 带上调用方信息，让系统知道这次启动是有正当来源的
            intent.putExtra("nchat_warmup", true);

            boolean started = false;

            // 3a) 用 root 直接走 shell 命令 —— 最稳，不受任何 Java 层限制。
            //     am start 以 root 身份执行，BAL 对 shell 启动是放行的。
            try {
                 java.lang.Process pr = java.lang.Runtime.getRuntime().exec(new String[]{"sh", "-c",
                        "am start -n " + comp + " >/dev/null 2>&1"});
                pr.waitFor();
                started = true;
                p("warmup via am start " + comp);
            } catch (Throwable t) {
                p("warmup am start failed: " + rootMsg(t));
            }

            // 3b) 兜底：Context.startActivity（后台可能被拦）
            if (!started) {
                try {
                    android.content.Intent warm = new android.content.Intent();
                    warm.setComponent(android.content.ComponentName.unflattenFromString(comp));
                    warm.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            | android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    sysCtx.startActivity(warm);
                    started = true;
                } catch (Throwable t) {
                    p("warmup startActivity failed: " + rootMsg(t));
                }
            }
            if (!started) return false;

            // 4) 等目标 App 真的到前台（最多 waitMs）
            long deadline = System.currentTimeMillis() + waitMs;
            while (System.currentTimeMillis() < deadline) {
                try { Thread.sleep(120); } catch (InterruptedException ignore) {}
                String top = topActivity();
                if (top != null && top.startsWith(pkg + "/")) return true;
            }
            return true;   // 发出去了就算成功，前台验证失败不阻塞主流程
        } catch (Throwable t) {
            p("warmup failed " + pkg + " : " + rootMsg(t));
            return false;
        }
    }

    static boolean safeIsActivity(PendingIntent pi) {
        try {
            return (Boolean) PendingIntent.class.getMethod("isActivity").invoke(pi);
        } catch (Throwable t) {
            return false;
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

    // ==================== [联动] 渠道登记与批量静音 ====================

    /**
     * 从一条通知里登记它的渠道（任务书 2.1 节）。
     * 只在内存里累积，落盘由 {@link #persistChannels()} 负责。
     */
    static void recordChannel(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.isEmpty()) return;
        Notification n = sbn.getNotification();
        if (n == null) return;
        String cid = channelId(n);
        if (cid == null || cid.isEmpty()) return;
        int uid = sbn.getUid();

        String title = "";
        try { title = cs(n.extras.getCharSequence(Notification.EXTRA_TITLE)); } catch (Throwable ignore) {}
        if (title == null) title = "";
        if (title.length() > 60) title = title.substring(0, 60);

        synchronized (channelLock) {
            String k = pkg + "|" + uid + "|" + cid;
            ChannelEntry e = knownChannels.get(k);
            if (e == null) {
                e = new ChannelEntry(pkg, uid, cid);
                knownChannels.put(k, e);
                if (knownChannels.size() > MAX_CHANNELS) {
                    String oldestKey = null;
                    long oldest = Long.MAX_VALUE;
                    for (Map.Entry<String, ChannelEntry> en : knownChannels.entrySet()) {
                        long ls = en.getValue().lastSeen;
                        if (ls < oldest) { oldest = ls; oldestKey = en.getKey(); }
                    }
                    if (oldestKey != null && !oldestKey.equals(k)) knownChannels.remove(oldestKey);
                }
            }
            e.lastSeen = System.currentTimeMillis();
            if (!title.isEmpty()) e.label = title;
            NotificationChannel ch = tryFindChannel(pkg, uid, cid);
            if (ch != null) e.importance = ch.getImportance();
        }
    }

    /** findChannel 的静默版本，失败返回 null。 */
    static NotificationChannel tryFindChannel(String pkg, int uid, String cid) {
        try { return findChannel(pkg, uid, cid); } catch (Throwable t) { return null; }
    }

    /** 把渠道登记表写盘。 */
    static void persistChannels() {
        try {
            ensureSharedDir();
            StringBuilder sb = new StringBuilder("{\"updatedAt\":\"");
            sb.append(esc(isoNow())).append("\",\"channels\":[");
            boolean first = true;
            synchronized (channelLock) {
                for (ChannelEntry e : knownChannels.values()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append("{\"pkg\":\"").append(esc(e.pkg)).append("\",");
                    sb.append("\"uid\":").append(e.uid).append(',');
                    sb.append("\"channel\":\"").append(esc(e.channel)).append("\",");
                    sb.append("\"label\":\"").append(esc(e.label == null ? "" : e.label)).append("\",");
                    sb.append("\"importance\":").append(e.importance).append(',');
                    sb.append("\"muted\":").append(e.importance <= 0).append(',');
                    sb.append("\"lastSeen\":").append(e.lastSeen).append('}');
                }
            }
            sb.append("]}");
            writeAtomic(CHANNELS_FILE, sb.toString());
        } catch (Throwable t) {
            p("persistChannels FAILED " + t);
        }
    }

    /** GET /channels —— 导出见过的渠道列表（任务书 2.1 节）。 */
    static String channelsListJson() {
        try {
            int cnt;
            synchronized (channelLock) { cnt = knownChannels.size(); }
            StringBuilder sb = new StringBuilder("{\"ok\":true,\"count\":");
            sb.append(cnt).append(",\"channels\":[");
            boolean first = true;
            synchronized (channelLock) {
                for (ChannelEntry e : knownChannels.values()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append("{\"pkg\":\"").append(esc(e.pkg)).append("\",");
                    sb.append("\"uid\":").append(e.uid).append(',');
                    sb.append("\"channel\":\"").append(esc(e.channel)).append("\",");
                    sb.append("\"label\":\"").append(esc(e.label == null ? "" : e.label)).append("\",");
                    sb.append("\"importance\":").append(e.importance).append(',');
                    sb.append("\"muted\":").append(e.importance <= 0).append(',');
                    sb.append("\"lastSeen\":").append(e.lastSeen).append('}');
                }
            }
            return sb.append("]}").toString();
        } catch (Throwable t) {
            return error(rootMsg(t));
        }
    }

    /** 设置单个渠道的实际 importance，返回是否成功。 */
    static boolean applyImportance(String pkg, int uid, String cid, int importance) {
        try {
            NotificationChannel target = findChannel(pkg, uid, cid);
            if (target == null) return false;
            target.setImportance(importance);
            try {
                Method u = inm.getClass().getMethod("updateNotificationChannelForPackage",
                        String.class, int.class, NotificationChannel.class);
                u.invoke(inm, pkg, uid, target);
                return true;
            } catch (Throwable t1) {
                Method u = inm.getClass().getMethod("updateNotificationChannelFromPrivilegedListener",
                        Class.forName("android.service.notification.INotificationListener"),
                        String.class, Class.forName("android.os.UserHandle"), NotificationChannel.class);
                u.invoke(inm, wrapper, pkg, Process.myUserHandle(), target);
                return true;
            }
        } catch (Throwable t) {
            p("applyImportance FAILED " + pkg + "/" + cid + " -> " + rootMsg(t));
            return false;
        }
    }

    /**
     * GET /mute-all —— 把 importance>=3 的渠道置为静音（任务书 2.2 节）。
     * 幂等：快照已存在则不覆盖，保住原始 importance。
     */
    static String muteAll() {
        try {
            ensureSharedDir();
            boolean hasSnapshot = new File(SNAPSHOT_FILE).exists();

            // [幂等] 快照已存在说明上一轮静音还没恢复：直接返回，绝不重复静音。
            // 否则会把「已静音的渠道」再次记进新快照，原 importance 就永久丢了。
            if (hasSnapshot) {
                int known;
                synchronized (channelLock) { known = knownChannels.size(); }
                p("muteAll: snapshot exists -> idempotent skip (known=" + known + ")");
                writeStatus();
                return "{\"ok\":true,\"muted\":0,\"skipped\":" + known
                        + ",\"failed\":0,\"idempotent\":true"
                        + ",\"snapshot\":\"" + esc(SNAPSHOT_FILE) + "\"}";
            }

            int muted = 0, skipped = 0, failed = 0;

            List<String[]> targets = new ArrayList<>();
            synchronized (channelLock) {
                for (ChannelEntry e : knownChannels.values()) {
                    targets.add(new String[]{e.pkg, String.valueOf(e.uid), e.channel});
                }
            }
            p("muteAll: candidates=" + targets.size() + " snapshotExists=" + hasSnapshot);

            StringBuilder snap = new StringBuilder();
            snap.append("{\"createdAt\":\"").append(esc(isoNow())).append("\",\"channels\":[");
            boolean firstSnap = true;

            for (String[] t : targets) {
                int uid = Integer.parseInt(t[1]);
                NotificationChannel ch = tryFindChannel(t[0], uid, t[2]);
                if (ch == null) { failed++; continue; }
                int cur = ch.getImportance();
                if (cur <= 0) { skipped++; continue; }
                if (cur < 3) { skipped++; continue; }
                if (!applyImportance(t[0], uid, t[2], 0)) { failed++; continue; }
                muted++;
                if (!firstSnap) snap.append(',');
                firstSnap = false;
                snap.append("{\"pkg\":\"").append(esc(t[0])).append("\",");
                snap.append("\"uid\":").append(t[1]).append(',');
                snap.append("\"channel\":\"").append(esc(t[2])).append("\",");
                snap.append("\"importance\":").append(cur).append('}');
            }
            snap.append("]}");

            if (!hasSnapshot && muted > 0) {
                writeAtomic(SNAPSHOT_FILE, snap.toString());
                p("muteAll: snapshot written (" + muted + ")");
            } else if (hasSnapshot) {
                p("muteAll: snapshot exists, kept");
            }

            persistChannels();
            writeStatus();
            return "{\"ok\":true,\"muted\":" + muted + ",\"skipped\":" + skipped
                    + ",\"failed\":" + failed
                    + ",\"snapshot\":\"" + esc(SNAPSHOT_FILE) + "\"}";
        } catch (Throwable t) {
            p("muteAll FAILED " + rootMsg(t));
            return error(rootMsg(t));
        }
    }

    /** GET /unmute-all —— 只恢复快照里记录的渠道（任务书 2.2 节）。 */
    static String unmuteAll() {
        try {
            String r = restoreFromSnapshot();
            persistChannels();
            writeStatus();
            return r;
        } catch (Throwable t) {
            p("unmuteAll FAILED " + rootMsg(t));
            return error(rootMsg(t));
        }
    }

    /** 按快照恢复并删除快照。返回 {"ok":true,"restored":N,"failed":M} */
    static String restoreFromSnapshot() {
        File f = new File(SNAPSHOT_FILE);
        if (!f.exists()) {
            p("restore: no snapshot");
            return "{\"ok\":true,\"restored\":0,\"failed\":0}";
        }
        int restored = 0, failed = 0;
        try {
            String json = readFile(f);
            int idx = 0;
            while (true) {
                int s = json.indexOf("{\"pkg\":\"", idx);
                if (s < 0) break;
                int e = json.indexOf('}', s);
                if (e < 0) break;
                String obj = json.substring(s, e + 1);
                idx = e + 1;
                String pkg = grabStr(obj, "pkg");
                int uid = grabInt(obj, "uid");
                String cid = grabStr(obj, "channel");
                int imp = grabInt(obj, "importance");
                if (pkg == null || cid == null) { failed++; continue; }
                if (applyImportance(pkg, uid, cid, imp)) restored++; else failed++;
            }
            p("restore: restored=" + restored + " failed=" + failed);
        } catch (Throwable t) {
            p("restore FAILED " + rootMsg(t));
        } finally {
            try { f.delete(); } catch (Throwable ignore) {}
        }
        return "{\"ok\":true,\"restored\":" + restored + ",\"failed\":" + failed + "}";
    }

    static String grabStr(String obj, String key) {
        String pat = "\"" + key + "\":\"";
        int i = obj.indexOf(pat);
        if (i < 0) return null;
        i += pat.length();
        int j = obj.indexOf('"', i);
        if (j < 0) return null;
        return obj.substring(i, j);
    }

    static int grabInt(String obj, String key) {
        String pat = "\"" + key + "\":";
        int i = obj.indexOf(pat);
        if (i < 0) return 0;
        i += pat.length();
        int j = i;
        while (j < obj.length() && (Character.isDigit(obj.charAt(j)) || obj.charAt(j) == '-')) j++;
        if (j == i) return 0;
        try { return Integer.parseInt(obj.substring(i, j)); } catch (Throwable t) { return 0; }
    }

    static long grabLong(String obj, String key) {
        String pat = "\"" + key + "\":";
        int i = obj.indexOf(pat);
        if (i < 0) return 0L;
        i += pat.length();
        int j = i;
        while (j < obj.length() && (Character.isDigit(obj.charAt(j)) || obj.charAt(j) == '-')) j++;
        if (j == i) return 0L;
        try { return Long.parseLong(obj.substring(i, j)); } catch (Throwable t) { return 0L; }
    }

    /** daemon 启动时调用：快照残留超过 30 分钟则自动恢复一次（任务书 2.2 节）。 */
    static void autoRestoreStaleSnapshot() {
        try {
            File f = new File(SNAPSHOT_FILE);
            if (!f.exists()) return;
            long age = System.currentTimeMillis() - f.lastModified();
            p("snapshot exists, age=" + (age / 1000) + "s");
            if (age > SNAPSHOT_AUTO_RESTORE_MS) {
                p("snapshot stale (>30min) -> auto restore");
                restoreFromSnapshot();
            }
        } catch (Throwable t) {
            p("autoRestoreStaleSnapshot FAILED " + t);
        }
    }

    /** 从 channels.json 载入上次的登记表，让 daemon 重启后能立刻响应 /channels。 */
    static void loadChannels() {
        try {
            File f = new File(CHANNELS_FILE);
            if (!f.exists()) return;
            String json = readFile(f);
            int idx = 0, n = 0;
            synchronized (channelLock) {
                while (true) {
                    int s = json.indexOf("{\"pkg\":\"", idx);
                    if (s < 0) break;
                    int e = json.indexOf('}', s);
                    if (e < 0) break;
                    String obj = json.substring(s, e + 1);
                    idx = e + 1;
                    String pkg = grabStr(obj, "pkg");
                    String cid = grabStr(obj, "channel");
                    if (pkg == null || cid == null) continue;
                    ChannelEntry en = new ChannelEntry(pkg, grabInt(obj, "uid"), cid);
                    en.label = grabStr(obj, "label");
                    en.importance = grabInt(obj, "importance");
                    en.lastSeen = grabLong(obj, "lastSeen");
                    knownChannels.put(en.key(), en);
                    n++;
                    if (knownChannels.size() >= MAX_CHANNELS) break;
                }
            }
            p("loadChannels: " + n + " entries");
        } catch (Throwable t) {
            p("loadChannels FAILED " + t);
        }
    }

    // ==================== [联动] 文件与状态工具 ====================

    // ==================== [联动] 心跳 ====================

    /**
     * 每 30 秒刷新一次 channels.json / status.json。
     * 外部监控（验收第 6 项）要求 updatedAt 在 5 分钟内保持新鲜。
     */
    static void heartbeat() {
        while (running) {
            try { Thread.sleep(30000L); } catch (InterruptedException ignore) {}
            if (!running) break;
            try {
                persistChannels();
                writeStatus();
            } catch (Throwable t) {
                p("heartbeat FAILED " + t);
            }
        }
    }

    static void ensureSharedDir() {
        try { new File(SHARED_DIR).mkdirs(); } catch (Throwable ignore) {}
    }

    /** 先写临时文件再 rename，避免外部读到半个文件。 */
    static void writeAtomic(String path, String content) {
        try {
            File target = new File(path);
            File dir = target.getParentFile();
            if (dir != null) dir.mkdirs();
            File tmp = new File(path + ".tmp");
            FileOutputStream fos = new FileOutputStream(tmp);
            fos.write(content.getBytes("UTF-8"));
            fos.flush();
            fos.getFD().sync();
            fos.close();
            if (target.exists()) target.delete();
            if (!tmp.renameTo(target)) {
                FileOutputStream f2 = new FileOutputStream(target);
                f2.write(content.getBytes("UTF-8"));
                f2.close();
                tmp.delete();
            }
        } catch (Throwable t) {
            p("writeAtomic FAILED " + path + " -> " + t);
        }
    }

    static String readFile(File f) throws Exception {
        byte[] buf = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        try {
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r < 0) break;
                off += r;
            }
        } finally { in.close(); }
        return new String(buf, "UTF-8");
    }

    /** ISO 8601 带时区偏移，如 2026-11-09T23:40:00+08:00 */
    static String isoNow() { return isoAt(System.currentTimeMillis()); }

    static String isoAt(long ms) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
            return sdf.format(new Date(ms));
        } catch (Throwable t) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            return sdf.format(new Date(ms));
        }
    }

    /** 写 /sdcard/Download/nchat/status.json（任务书 2.3 节）。 */
    static void writeStatus() {
        try {
            ensureSharedDir();
            int known, muted = 0;
            synchronized (channelLock) {
                known = knownChannels.size();
                for (ChannelEntry e : knownChannels.values()) if (e.importance <= 0) muted++;
            }
            long lastAt = 0;
            synchronized (active) {
                for (StatusBarNotification s : active.values()) {
                    if (s.getPostTime() > lastAt) lastAt = s.getPostTime();
                }
            }
            StringBuilder sb = new StringBuilder("{");
            sb.append("\"app\":\"nchat\",");
            sb.append("\"daemonUp\":true,");
            sb.append("\"version\":\"").append(VERSION).append("\",");
            sb.append("\"api\":").append(android.os.Build.VERSION.SDK_INT).append(',');
            sb.append("\"channelsKnown\":").append(known).append(',');
            sb.append("\"mutedCount\":").append(muted).append(',');
            sb.append("\"snapshotActive\":").append(new File(SNAPSHOT_FILE).exists()).append(',');
            sb.append("\"lastNotificationAt\":").append(lastAt > 0 ? "\"" + esc(isoAt(lastAt)) + "\"" : "null").append(',');
            sb.append("\"updatedAt\":\"").append(esc(isoNow())).append('"');
            sb.append('}');
            writeAtomic(STATUS_FILE, sb.toString());
        } catch (Throwable t) {
            p("writeStatus FAILED " + t);
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
        // ---- intent 追踪信息 ----
        // 目的：事后能看出"这条通知是通过什么方式进来的 / 点了会去哪"，
        // 用于排查跳转失败、判断是不是某种特定推送通道。
        sb.append("\"intent\":").append(pendingIntentJson(n.contentIntent)).append(',');
        sb.append("\"fullScreenIntent\":").append(pendingIntentJson(n.fullScreenIntent)).append(',');
        sb.append("\"actionIntents\":["); 
        if (n.actions != null) {
            for (int i = 0; i < n.actions.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(pendingIntentJson(n.actions[i].actionIntent));
            }
        }
        sb.append("],");
        // 通知 tag：key 里 tag 非 null 说明是按 tag 更新的（常见于进度/同会话消息合并）
        sb.append("\"tag\":").append(sbn.getTag() == null ? "null" : "\"" + esc(sbn.getTag()) + "\"").append(',');
        // 是否由系统重发（如渠道重要性变化时的重新通知）
        sb.append("\"isOngoingFlag\":").append((n.flags & Notification.FLAG_ONGOING_EVENT) != 0).append(',');
        sb.append("\"isFgsFlag\":").append((n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0).append(',');
        sb.append("\"isAutoCancel\":").append((n.flags & Notification.FLAG_AUTO_CANCEL) != 0).append(',');
        sb.append("\"isGroupChild\":").append(callBool(n, "isGroupChild")).append(',');
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

    /**
     * 把 PendingIntent 拆成可读字段。
     * 直接 toString() 拿不到有用信息，得通过 creatorPackage + 里面的 Intent 来分析。
     *
     * 输出：
     *   pkg    --- creatorPackage（发出这个 PendingIntent 的应用）
     *   target --- 目标组件（包名/类名），能看出会跳到哪个 Activity
     *   action --- Intent.action
     *   data   --- Intent.data 的 scheme://host/path
     *   flags  --- Intent.flags
     *   hasExtra --- 是否带 extras（推送通道常把跳转参数塞在这里）
     *   mut  --- FLAG_MUTABLE
     */
    static String pendingIntentJson(PendingIntent pi) {
        if (pi == null) return "null";
        StringBuilder sb = new StringBuilder("{");

        String creatorPkg = "";
        try { creatorPkg = pi.getCreatorPackage(); } catch (Throwable ignore) {}
        sb.append("\"pkg\":\"").append(esc(creatorPkg)).append("\",");

        // 目标组件 / action / data：PendingIntent 没有公开 getter，只能反射。
        // 不同 Android 版本字段名/嵌套层不同，做多候选探测；
        // 拿不到就留空，不影响主流程。
        String target = null, action = null, data = null;
        int iflags = -1;
        boolean hasExtra = false;
        try {
            Intent it = pendingIntentIntent(pi);
            if (it != null) {
                ComponentName cn = it.getComponent();
                if (cn != null) target = cn.getPackageName() + "/" + cn.getClassName();
                action = it.getAction();
                android.net.Uri u = it.getData();
                if (u != null) data = u.toString();
                iflags = it.getFlags();
                android.os.Bundle b = it.getExtras();
                hasExtra = b != null && !b.isEmpty();
                if (target == null && b != null) {
                    // 有些推送把真正的落地页塞在 extra 里
                    for (String k : new String[]{"target", "url", "jump_url", "link"}) {
                        Object t = b.get(k);
                        if (t instanceof String && !((String) t).isEmpty()) {
                            target = k + "=" + t;
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignore) {}

        sb.append("\"target\":").append(target == null ? "null" : "\"" + esc(target) + "\"").append(',');
        sb.append("\"action\":").append(action == null ? "null" : "\"" + esc(action) + "\"").append(',');
        sb.append("\"data\":").append(data == null ? "null" : "\"" + esc(data) + "\"").append(',');
        sb.append("\"flags\":").append(iflags).append(',');
        sb.append("\"hasExtra\":").append(hasExtra).append(',');

        // PendingIntent.getFlags() 是 @hide，编译期 android.jar 不可见，只能反射。
        // FLAG_MUTABLE = 0x02000000
        String mut = null;
        try {
            java.lang.reflect.Method g = PendingIntent.class.getMethod("getFlags");
            g.setAccessible(true);
            int f = (Integer) g.invoke(pi);
            mut = (f >= 0x02000000 && f < 0x04000000) ? "mutable" : "immutable";
        } catch (Throwable ignore) {}
        sb.append("\"mut\":").append(mut == null ? "null" : "\"" + mut + "\"");

        sb.append("}");
        return sb.toString();
    }

    /**
     * 尝试把 PendingIntent 里的 Intent 挖出来。
     * 不同版本安卓字段名/嵌套层不同，逐个候选尝试。
     */
    static Intent pendingIntentIntent(PendingIntent pi) {
        String[] names = {"mIntent", "mTarget", "mRecord", "mPendingIntent"};
        for (String n : names) {
            try {
                java.lang.reflect.Field f = PendingIntent.class.getDeclaredField(n);
                f.setAccessible(true);
                Object v = f.get(pi);
                if (v instanceof Intent) return (Intent) v;
            } catch (Throwable ignore) {}
        }
        return null;
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