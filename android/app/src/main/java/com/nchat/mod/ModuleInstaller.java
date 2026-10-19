package com.nchat.mod;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.nchat.root.Shell;

import java.io.InputStream;

/**
 * Magisk 模块部署器（方案三：App 为主，模块为辅）。
 *
 * 目录结构：
 *   /data/adb/modules/nchatd/
 *     ├── module.prop
 *     ├── service.sh
 *     ├── uninstall.sh
 *     └── files/nchatd.jar   ← 由 App 推送，不随模块 zip
 *
 * 设计要点：
 *  - daemon jar 由 App 从 assets 读取后写入模块目录
 *  - service.sh 启动前检查 App 是否还安装着
 *  - App 升级后只需覆盖 jar 并重启 daemon，模块结构不变
 */
public class ModuleInstaller {

    private static final String TAG = "NchatModule";

    public static final String MODULE_ID = "nchatd";
    public static final String MODULE_DIR = "/data/adb/modules/" + MODULE_ID;
    public static final String JAR_IN_MODULE = MODULE_DIR + "/files/nchatd.jar";
    public static final String APP_PKG = "com.nchat";

    /** 模块是否已部署 */
    public static boolean isInstalled() {
        return Shell.exists(MODULE_DIR + "/module.prop");
    }

    /** 模块是否被禁用（Magisk 的 disable 标记文件） */
    public static boolean isDisabled() {
        return Shell.exists(MODULE_DIR + "/disable");
    }

    /** 读取模块里当前的 daemon 版本 */
    public static String installedJarVersion() {
        String s = Shell.readFile(MODULE_DIR + "/version");
        return s == null ? null : s.trim();
    }

    /**
     * 从 assets 部署（或更新）模块 + daemon jar。
     * @param assetJar assets 里的 daemon jar 文件名（如 "nchatd.jar"）
     * @param version  当前 App 内置的 daemon 版本号
     */
    public static Result deploy(Context ctx, String assetJar, String version) {
        Result r = new Result();
        try {
            // 不再预检 root：直接写文件，失败时用 su 的实际错误信息反馈
            // （预检会多弹一次授权框）

            // 1) 创建目录
            if (!Shell.mkdirs(MODULE_DIR + "/files")) {
                r.message = "无法创建模块目录";
                return r;
            }

            // 2) module.prop
            String moduleProp =
                    "id=nchatd\n"
                    + "name=Nchat 通知守护\n"
                    + "version=" + version + "\n"
                    + "versionCode=" + version.replace(".", "") + "\n"
                    + "author=nchat\n"
                    + "description=为 Nchat App 提供系统级通知读取/清理能力（root 身份运行，systemless）\n";
            if (!Shell.writeFile(MODULE_DIR + "/module.prop", moduleProp.getBytes("UTF-8"))) {
                r.message = "写入 module.prop 失败";
                return r;
            }
            Shell.writeFile(MODULE_DIR + "/version", version.getBytes("UTF-8"));

            // 3) service.sh —— 开机启动（从 assets 读取，单一真源）
            byte[] serviceSh = readAsset(ctx, "service.sh");
            if (serviceSh == null || serviceSh.length == 0) {
                r.message = "读取内置 service.sh 失败";
                return r;
            }
            if (!Shell.writeFile(MODULE_DIR + "/service.sh", serviceSh)) {
                r.message = "写入 service.sh 失败";
                return r;
            }
            Shell.chmod("755", MODULE_DIR + "/service.sh");

            // 4) uninstall.sh —— 卸载清理
            String uninstallSh = "#!/system/bin/sh\n"
                    + "for d in /proc/[0-9]*; do\n"
                    + "  if grep -qa NchatDaemon $d/cmdline 2>/dev/null; then kill -9 ${d#/proc/} 2>/dev/null; fi\n"
                    + "done\n"
                    + "rm -rf /data/local/tmp/nchatd\n";
            Shell.writeFile(MODULE_DIR + "/uninstall.sh", uninstallSh.getBytes("UTF-8"));
            Shell.chmod("755", MODULE_DIR + "/uninstall.sh");

            // 5) daemon jar（从 assets 读取）
            byte[] jar = readAsset(ctx, assetJar);
            if (jar == null || jar.length == 0) {
                r.message = "读取内置 daemon 失败";
                return r;
            }
            if (!Shell.writeFile(JAR_IN_MODULE, jar)) {
                r.message = "写入 nchatd.jar 失败";
                return r;
            }
            Shell.chmod("644", JAR_IN_MODULE);

            // 6) 立即启动（不等重启）
            Shell.exec("rm -f " + MODULE_DIR + "/disable", 5000);
            startFromModule();

            r.success = true;
            r.message = "模块已部署（" + jar.length + " 字节）";
            Log.i(TAG, r.message);
            return r;
        } catch (Throwable t) {
            r.message = "部署异常: " + t;
            Log.e(TAG, "deploy failed", t);
            return r;
        }
    }

    /** 从模块目录启动 daemon（统一委托给 Shell.startDaemon） */
    public static Result startFromModule() {
        Result out = new Result();
        Shell.Result r = Shell.startDaemon(JAR_IN_MODULE);
        out.success = r.out.contains("LAUNCHED");
        if (out.success) {
            out.message = "已启动";
        } else {
            out.message = "启动失败 code=" + r.code
                    + " status=" + r.out.trim()
                    + (r.err.trim().isEmpty() ? "" : " err=" + r.err.trim());
        }
        return out;
    }

    /** 停止并删除模块 */
    public static Result uninstall() {
        Result r = new Result();
        try {
            // 注意：不能用 pkill -f NchatDaemon（进程名是 app_process，pkill 匹配不到），
            // 必须用 Shell.KILL_CMD 扫 /proc/<pid>/cmdline。
            Shell.stopDaemon();
            // 先在模块目录建 disable，保证即使本次删除失败，下次开机也不会拉起
            Shell.exec("touch " + MODULE_DIR + "/disable", 5000);
            Shell.rmrf(MODULE_DIR);
            boolean gone = !Shell.exists(MODULE_DIR + "/module.prop");
            r.success = gone;
            r.message = gone ? "模块已移除（重启后彻底生效）" : "移除失败：目录仍存在";
        } catch (Throwable t) {
            r.message = "移除失败: " + t;
        }
        return r;
    }

    /** 只判断是否需要同步（不写文件），用于决定是否显示「同步中」提示 */
    public static boolean needsJarSync(Context ctx, String assetJar) {
        try {
            if (!isInstalled()) return false;
            byte[] jar = readAsset(ctx, assetJar);
            if (jar == null || jar.length == 0) return false;
            String have = Shell.readFile(MODULE_DIR + "/version");
            have = have == null ? "" : have.trim();
            return !fingerprint(jar).equals(have);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 确保模块里的 daemon jar 与 App 内置的一致。
     *
     * 背景：方案三里 daemon jar 由 App 从 assets 推送，但「推送」只在 deploy() 时发生。
     * App 升级（内置 jar 变了）后，如果模块已存在就不会再走 deploy，
     * 导致模块里跑的还是旧 daemon —— 表现为新接口字段缺失。
     *
     * 这里用「内置 jar 的字节长度 + CRC」当版本指纹，与模块里的 version 文件比对，
     * 不一致就覆盖并返回 true（调用方据此重启 daemon）。
     */
    public static boolean syncJarIfNeeded(Context ctx, String assetJar) {
        try {
            if (!isInstalled()) return false;
            byte[] jar = readAsset(ctx, assetJar);
            if (jar == null || jar.length == 0) return false;

            String want = fingerprint(jar);
            String have = Shell.readFile(MODULE_DIR + "/version");
            have = have == null ? "" : have.trim();
            if (want.equals(have)) return false;

            Log.i(TAG, "daemon jar 需要同步: " + have + " -> " + want);
            if (!Shell.writeFile(JAR_IN_MODULE, jar)) return false;
            Shell.chmod("644", JAR_IN_MODULE);
            Shell.writeFile(MODULE_DIR + "/version", want.getBytes("UTF-8"));
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "syncJarIfNeeded failed", t);
            return false;
        }
    }

    /** jar 内容指纹：长度 + 简单累加校验，够用且不用引 MessageDigest */
    private static String fingerprint(byte[] b) {
        long sum = 0;
        for (int i = 0; i < b.length; i++) {
            sum = (sum * 131 + (b[i] & 0xFF)) & 0xFFFFFFFFL;
        }
        return "1." + b.length + "." + Long.toHexString(sum);
    }

    /** 仅更新 jar 并重启（App 升级时用） */
    public static Result updateJar(Context ctx, String assetJar, String version) {
        Result r = new Result();
        try {
            byte[] jar = readAsset(ctx, assetJar);
            if (jar == null) {
                r.message = "读取内置 daemon 失败";
                return r;
            }
            if (!Shell.writeFile(JAR_IN_MODULE, jar)) {
                r.message = "写入失败";
                return r;
            }
            Shell.chmod("644", JAR_IN_MODULE);
            Shell.writeFile(MODULE_DIR + "/version", version.getBytes("UTF-8"));
            startFromModule();
            r.success = true;
            r.message = "daemon 已更新到 " + version;
        } catch (Throwable t) {
            r.message = "更新失败: " + t;
        }
        return r;
    }

    // ==================== 内部 ====================

    private static byte[] readAsset(Context ctx, String name) {
        try (InputStream is = ctx.getAssets().open(name)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Throwable t) {
            Log.e(TAG, "readAsset " + name + " failed", t);
            return null;
        }
    }

    public static class Result {
        public boolean success;
        public String message = "";
    }
}