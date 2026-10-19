package com.nchat.root;

import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * root 命令执行封装。
 * 注意：设备上 shell 默认已是 uid=0（magisk context），但 App 进程是普通 uid，
 * 仍然需要 su 提权。
 */
public class Shell {

    private static final String TAG = "NchatShell";

    public static class Result {
        public int code;
        public String out = "";
        public String err = "";

        public boolean ok() {
            return code == 0;
        }

        @Override
        public String toString() {
            return "code=" + code + " out=" + out + " err=" + err;
        }
    }

    /** 执行一条 root 命令（等返回值） */
    public static Result exec(String cmd) {
        return exec(cmd, 20000);
    }

    public static Result exec(String cmd, long timeoutMs) {
        Result r = new Result();
        Process p = null;
        try {
            // 直接 su -c "<原始 cmd>"（不套 sh -c，保持 magisk su 的调用者识别）
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(false);
            p = pb.start();
            // 立刻关闭 su 的 stdin，避免 magisk su 等待输入
            try { p.getOutputStream().close(); } catch (Throwable ignore) {}
            final Process fp = p;
            final Result fr = r;

            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(fp.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                    fr.out = sb.toString();
                } catch (Throwable ignore) {}
            });
            reader.start();

            Thread errReader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(fp.getErrorStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                    fr.err = sb.toString();
                } catch (Throwable ignore) {}
            });
            errReader.start();

            boolean finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                r.code = -1;
                r.err = "timeout";
                return r;
            }
            r.code = p.exitValue();
            reader.join(2000);
            errReader.join(2000);
        } catch (Throwable t) {
            r.code = -2;
            r.err = String.valueOf(t.getMessage());
            Log.w(TAG, "exec failed: " + cmd, t);
        } finally {
            if (p != null) p.destroy();
        }
        return r;
    }

    /** 单引号 shell 转义 */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 检查是否有 root。
     * 注意：第一次调用会弹 Magisk 授权框，用户可能还没点，所以超时给足，
     * 且失败后隔一会儿再试（授权框点掉后第二次就能拿到）。
     */
    public static boolean hasRoot() {
        for (int attempt = 0; attempt < 2; attempt++) {
            Result r = exec("id", 20000);
            if (r.ok() && r.out.contains("uid=0")) return true;
            try { Thread.sleep(500); } catch (Throwable ignore) {}
        }
        return false;
    }

    /**
     * 宽松判定 root 是否可用（不阻塞、不弹太多框）。
     * 策略：执行 `su -c "echo ROOTOK"`，短超时 3 秒。
     *  - 若 Magisk 已授权 → 立刻返回 ROOTOK
     *  - 若尚未授权 → 会弹框，3 秒超时后返回 false（用户点完允许后下次调用就会 true）
     */
    public static boolean rootLikelyAvailable() {
        // 先试 su exist
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "echo ROOTOK"});
            final StringBuilder sb = new StringBuilder();
            Thread t = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = br.readLine()) != null) sb.append(l);
                } catch (Throwable ignore) {}
            });
            t.start();
            boolean fin = p.waitFor(4000, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!fin) {
                p.destroyForcibly();
                return false;
            }
            t.join(500);
            return sb.toString().contains("ROOTOK");
        } catch (Throwable e) {
            // su 不存在 → 尝试看 magisk 可执行文件（root 进程才能读 /data/adb，这里大概率失败，仅作补充）
            try {
                return new java.io.File("/system/bin/su").exists()
                        || new java.io.File("/system/xbin/su").exists();
            } catch (Throwable ignore) {
                return false;
            }
        }
    }

    /** 检查 magisk 是否可用 */
    public static boolean hasMagisk() {
        Result r = exec("which magisk || ls /data/adb/magisk/magisk", 5000);
        return r.ok() && !r.out.trim().isEmpty();
    }

    /**
     * 杀掉所有 nchatd daemon 进程。
     * 不能用 pkill -f NchatDaemon —— 进程名显示为 "app_process"，cmdline 才是完整参数，
     * 而 toybox ps 不带 -f 时看不到。改为扫 /proc/<pid>/cmdline 精确匹配。
     */
    public static final String KILL_CMD =
            "for d in /proc/[0-9]*; do "
            + "  if grep -qa 'NchatDaemon' $d/cmdline 2>/dev/null; then "
            + "    kill -9 ${d#/proc/} 2>/dev/null; "
            + "  fi; "
            + "done; true";

    /** daemon 是否在跑（扫 cmdline） */
    public static boolean daemonRunning() {
        Result r = exec("for d in /proc/[0-9]*; do "
                + "if grep -qa 'NchatDaemon' $d/cmdline 2>/dev/null; then echo FOUND; break; fi; "
                + "done", 5000);
        return r.out.contains("FOUND");
    }

    /**
     * 启动 daemon（最可靠方案：写脚本文件 -> su 执行脚本 -> 脚本自写 status.txt）。
     *
     * 为什么不用 su -c "一长串命令"：
     * App 进程下 magisk su 对超长嵌套引号 + 重定向 + 后台作业的解析容易失配，
     * 表现为 out 为空、daemon 以 ClassNotFoundException 崩溃（CLASSPATH 未生效）。
     * 写脚本可以完全避免引号地狱，并且脚本把真实结果落到文件里，便于排查。
     */
    public static final String K = "/data/local/tmp/nchatd";

    public static Result startDaemon(String jarPath) {
        StringBuilder sh = new StringBuilder();
        sh.append("#!/system/bin/sh\n");
        sh.append("K=" + K + "\n");
        sh.append("S=$K/status.txt\n");
        sh.append("echo PREPARING > $S\n");
        sh.append("mkdir -p $K || { echo FAIL_MKDIR > $S; exit 1; }\n");
        sh.append("cp " + jarPath + " $K/nchatd.jar || { echo FAIL_CP > $S; exit 1; }\n");
        sh.append("chmod 644 $K/nchatd.jar\n");
        sh.append("for d in /proc/[0-9]*; do "
                + "if grep -qa NchatDaemon $d/cmdline 2>/dev/null; "
                + "then kill -9 ${d#/proc/} 2>/dev/null; fi; done\n");
        sh.append("sleep 1\n");
        sh.append(": > $K/stdout.txt\n");
        sh.append("export CLASSPATH=$K/nchatd.jar\n");
        sh.append("cd $K || { echo FAIL_CD > $S; exit 1; }\n");
        sh.append("setsid /system/bin/app_process /system/bin NchatDaemon "
                + "</dev/null >>$K/stdout.txt 2>&1 &\n");
        sh.append("echo LAUNCHED > $S\n");
        sh.append("exit 0\n");

        String scriptPath = K + "/start.sh";
        String b64 = android.util.Base64.encodeToString(
                sh.toString().getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        Result w = exec("mkdir -p " + K + "; echo " + b64
                + " | base64 -d > " + scriptPath + "; chmod 755 " + scriptPath
                + "; echo WROTE", 15000);
        if (!w.out.contains("WROTE")) {
            Log.w(TAG, "write start.sh failed: " + w);
            return w;
        }

        // 注意：exec() 内部用 ProcessBuilder("su","-c",cmd)，cmd 是单个参数，
        // 所以这里不能再套单引号，直接给 "sh /path/start.sh"。
        exec("sh " + scriptPath, 25000);
        Result r = exec("sleep 3; cat " + K + "/status.txt 2>/dev/null", 15000);
        return r;
    }

    public static Result stopDaemon() {
        return exec(KILL_CMD, 8000);
    }

    /** 读文件 */
    public static String readFile(String path) {
        Result r = exec("cat " + path + " 2>/dev/null", 8000);
        return r.ok() ? r.out : null;
    }

    /** 写文件（用 base64 避免转义问题） */
    public static boolean writeFile(String path, byte[] data) {
        try {
            String b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
            // 分块写，避免命令行过长
            // 不用 $(dirname ...)：App 进程下 su -c 对 $() 的展开不可靠，
            // 这里在 Java 侧直接算出父目录。
            int slash = path.lastIndexOf('/');
            if (slash > 0) {
                Result mk = exec("mkdir -p " + path.substring(0, slash), 8000);
                if (!mk.ok()) return false;
            }
            // 用 heredoc 方式写 base64
            String cmd = "echo '" + b64 + "' | base64 -d > " + path;
            Result r = exec(cmd, 20000);
            return r.ok();
        } catch (Throwable t) {
            Log.w(TAG, "writeFile failed", t);
            return false;
        }
    }

    public static boolean exists(String path) {
        Result r = exec("test -e " + path + " && echo yes || echo no", 5000);
        return r.out.contains("yes");
    }

    public static boolean mkdirs(String path) {
        return exec("mkdir -p " + path, 8000).ok();
    }

    public static boolean chmod(String mode, String path) {
        return exec("chmod " + mode + " " + path, 5000).ok();
    }

    public static boolean rmrf(String path) {
        return exec("rm -rf " + path, 8000).ok();
    }
}