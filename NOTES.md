# Nchat 项目技术档案

## 项目定位
Android 通知管理 App：读取通知栏全量通知 → 分类展示成卡片 → 用户逐条决策 → 处理后从通知栏清除。
个人自用，设备有 root + Magisk + LSPosed。**不改系统文件，只改系统设置/读受限信息。**

## 设备
- Redmi 2407FRK8EC (rothko)，Android 16 / SDK 36，HyperOS 3.x
- Magisk root，shell 默认 uid=0 且 context=u:r:magisk:s0

## 架构（方案三：App 为主，Magisk 模块为辅）
```
Nchat.apk (普通 app uid)
  ├─ UI: 卡片流 / 历史 / 待办 / 渠道管理 / 设置
  ├─ SQLite: 通知快照 + 决策 + AI 缓存
  ├─ ModuleInstaller: root 写 /data/adb/modules/nchatd/，推送 nchatd.jar
  └─ DaemonClient: HTTP 127.0.0.1:18765
        ↕
nchatd (uid=0, app_process, magisk context)
  ├─ NotificationListenerService.registerAsSystemService()
  ├─ 内存 active map（全量通知）
  └─ HTTP 服务: /state /cancel/{key} /mute/ /unmute/ /refresh
```

### 模块部署逻辑（关键设计）
- Magisk 模块目录 `/data/adb/modules/nchatd/`：module.prop / service.sh / uninstall.sh / files/nchatd.jar
- **daemon jar 由 App 推送，不随模块 zip 分发** → App 升级只需覆盖 jar
- App 首次启动检测模块存在性，不存在则一键部署（root 写文件 + 立即启动 daemon）
- service.sh 开机拉起前检查 App 是否还装着，没装则跳过（避免孤儿进程）
- App 卸载后模块残留但 daemon 不启动

## 技术验证结论（2026-10-18 实测通过）

### 1. 构建链路（必须这么做）
```bash
# 1) javac 编译（用 android.jar 作 classpath）
javac -source 17 -target 17 -nowarn -cp $SDK/platforms/android-36/android.jar -d classes src/*.java
# 2) d8 转 dex —— 注意：d8 输出目录需先存在，且要显式传 .class 文件（不能传目录）
mkdir -p dex && d8 --min-api 34 --lib android.jar --output dex classes/A.class classes/B.class
# 3) 打成 jar（必须叫 classes.dex，app_process 才认）
cd dex && jar cf ../nchatd.jar classes.dex
# 4) 运行
CLASSPATH=/data/local/tmp/nchatd/nchatd.jar app_process /system/bin NchatDaemon
```
- 坑：`d8 --output dexout` 若目录不存在报 "Invalid output"
- 坑：jar 里放 `.class` 会报 "Failed to find entry 'classes.dex'"，进程 SIGABRT
- 坑：设备上**没有** javac/jar，必须在 Ubuntu 端编译

### 2. 获取系统 Context（非 App 进程）
```java
Looper.prepareMainLooper();
Object at = ActivityThread.currentActivityThread();   // 返回 null
if (at == null) at = ActivityThread.systemMain();      // 必须显式调用
Context ctx = at.getSystemContext();
```
- 不调 systemMain 则 mSystemThread=false，拿不到系统 Context

### 3. 注册通知监听（Android 16 关键差异）
`NotificationManager.registerListener()` 在 API 36 **已被移除**。正确做法：
```java
NotificationListenerService nls = new ListenerImpl();
ComponentName cn = new ComponentName("/data/local/tmp/nchatd/nchatd.jar", "NchatDaemon$ListenerImpl");
Method m = NotificationListenerService.class.getMethod(
    "registerAsSystemService", Context.class, ComponentName.class, int.class);
m.invoke(nls, ctx, cn, -1);
```
- ComponentName 的 pkg 用 jar 绝对路径即可（不需要是真的安装包）
- 注册后 `onListenerConnected` 会回调，`getActiveNotifications()` 返回全量

### 4. 拿 privileged listener token
```java
Field wf = NotificationListenerService.class.getDeclaredField("mWrapper");
wf.setAccessible(true);
Object wrapper = wf.get(nls);   // NotificationListenerWrapper，实现 INotificationListener
```

### 5. 取消通知（唯一真正生效的路径）
```java
INotificationManager inm = INotificationManager.Stub.asInterface(
    ServiceManager.getService("notification"));
Method m = inm.getClass().getMethod("cancelNotificationsFromListener",
    INotificationListener.class, String[].class);
m.invoke(inm, wrapper, new String[]{key});
```
**踩坑记录（重要）**：
- `cancelNotificationWithTag()` 在 API 36 签名是 `(String callingPkg, String pkg, String tag, int id, int userId)` —— 最后一个参数是 **userId 不是 uid**
- 但即使参数全对，普通路径也**返回 OK 却没效果**：服务端把 caller 判成最后碰过的那个包（`com.ruanmei.ithome:0`），走 `resolveNotificationUid` 后静默失败
- `callingPkg=null` 也不生效
- **只有 privileged listener 路径有效**

### 6. 渠道读写
```java
// 读
Method g = inm.getClass().getMethod("getNotificationChannelsForPackage", String.class, int.class, boolean.class);
Object slice = g.invoke(inm, pkg, uid, false);
List<NotificationChannel> list = (List) slice.getClass().getMethod("getList").invoke(slice);

// 写（关闭/恢复）
channel.setImportance(0);  // 0=IMPORTANCE_NONE 即关闭；恢复用 3
Method u = inm.getClass().getMethod("updateNotificationChannelFromPrivilegedListener",
    INotificationListener.class, String.class, UserHandle.class, NotificationChannel.class);
u.invoke(inm, wrapper, pkg, Process.myUserHandle(), channel);
```
- 也可以用 `updateNotificationChannelForPackage(String,int,NotificationChannel)`（非 listener 路径）——**这条路径实测有效**
- 关闭后系统侧 `mImportance=0`、`mUserLockedFields=4`，dumpsys 可复核

### 7. Notification key 格式
`userId|pkg|id|tag|uid`，例：`0|com.ruanmei.ithome|433584591|null|10399`
- tag 为 "null" 字符串表示没有 tag

## 构建环境
- Ubuntu(proot) 端：JDK 17 javac jar，Android SDK /root/android-sdk
- d8: /root/android-sdk/build-tools/36.0.0/d8
- 工程目录：/root/projects/nchat
- 快速构建：/root/projects/nchat/build.sh
- 设备侧脚本：/sdcard/Develop/nchat/restart.sh

### 文件系统权限坑
- `/sdcard` 是 FUSE，**Ubuntu 端 root 删不掉 shell 创建的文件**
- 解法：用 `super_admin:shell` 删除（它是真 root）
- super_admin:shell 有危险命令过滤器：`rm -rf *`、`rm -rf ./x` 之类会被拦；`rm -f a b c` 和 `rm -rf dirname` 可以

## 需求决策（已与用户确认）
1. AI 分类：规则优先，低置信度才走 DeepSeek → **实际简化为：规则分类 + 可选 DeepSeek 增强**
2. 常驻/系统通知（FLAG_ONGOING_EVENT、FLAG_FOREGROUND_SERVICE、CATEGORY_TRANSPORT）：自动过滤，不进决策流
3. "立即阅读"= 启动通知的 contentIntent（跳转到 App），不是 TTS
4. 不做常驻通知栏入口，就是普通 App 图标
5. DeepSeek API：App 内可配置 BaseURL / Key / Model，本地存储
6. 三类：信息类(资讯) / 营销类 / 工具类(外卖快递打车导航)
7. 必须做通知历史记录（含已处理/不感兴趣/已清除）

## 端到端启动踩坑（2026-10-18 解决，最关键）

### code=137（SIGABRT/SIGKILL）根因与正解
**现象**：App 点"一键激活"后报 `启动失败 code=137 out= err=`，daemon 没起来。

**真正原因**（logcat 里 `F/DEBUG Abort message` 才看得到）：
```
Cmdline: /system/bin/app_process /system/bin NchatDaemon
Abort message: 'No pending exception expected: java.lang.ClassNotFoundException: NchatDaemon'
```
即 **CLASSPATH 没生效**，不是被 magisk 清理。

**为什么 CLASSPATH 会失效**：
在 App 进程（uid=10700）下用 `ProcessBuilder("su","-c", 超长命令)` 执行时，
magisk su 对「长串 + 嵌套单引号 + 重定向 + 后台 &」的解析会失配，
`export CLASSPATH=$K/nchatd.jar` 里的 `$K` 没被正确展开 → jar 找不到 → ClassNotFoundException。

**正解（必须照做）**：把要执行的逻辑**写成脚本文件**，再用一条极简命令跑它。
```java
// 1) 生成脚本内容，base64 传输避免任何转义
sh.append("#!/system/bin/sh\n");
sh.append("K=/data/local/tmp/nchatd\n");
sh.append("export CLASSPATH=$K/nchatd.jar\n");
sh.append("cd $K || exit 1\n");
sh.append("setsid /system/bin/app_process /system/bin NchatDaemon </dev/null >>$K/stdout.txt 2>&1 &\n");
sh.append("echo LAUNCHED > $K/status.txt\n");
// 2) 用 base64 写脚本
exec("echo <b64> | base64 -d > " + K + "/start.sh; chmod 755 ...; echo WROTE");
// 3) 单条命令执行（注意 exec 内部已是 su -c，这里不要再套单引号）
exec("sh " + K + "/start.sh", 25000);
// 4) 脚本把结果写进 status.txt，App 读它判断成败
exec("sleep 3; cat " + K + "/status.txt");
```
成功判据：status.txt 内容为 `LAUNCHED`。

### 其他相关修复
- `Shell.writeFile` 里不能用 `$(dirname path)` —— App 的 su 会话下 `$()` 展开不可靠；
  改成在 Java 侧用 `path.lastIndexOf('/')` 算出父目录再 `mkdir -p`。
- 杀 daemon 不能用 `pkill -f NchatDaemon`（进程名显示为 `app_process`），
  必须遍历 `/proc/[0-9]*/cmdline` 用 `grep -qa 'NchatDaemon'` 匹配后 `kill -9`。
- `service.sh` 开机拉起也要用 `setsid`，并加载入前做幂等检查（已在跑就 exit 0）。
- 通知标题清理：部分 App（B站/微博）的 title 形如 `新消息<大量空格>GroupSummary`，
  App 端需把连续空白压成单空格，并剔除 `GroupSummary` 这类占位词。

### UI 自动化测试注意
- 每次工具调用会把 Operit 拉回前台，所以点完坐标后要重新 `am start` 回 App 再 dump。
- 屏幕进 Doze 时 `uiautomator dump` 会失败，先 `input keyevent KEYCODE_WAKEUP` + 上滑解锁。
- 带二次确认的按钮（如"移除模块"）要点对话框里的确认按钮。

## 当前状态（2026-10-18 全部验证通过）
- [x] daemon 核心能力验证（读通知 / 取消 / 点击跳转 / 渠道开关）
- [x] App 工程搭建（7 个 Java 包，深色卡片流 UI）
- [x] 模块部署逻辑（一键激活 / 开机自启 / 移除模块 / 升级 jar）
- [x] 端到端流程：一键激活 → 卡片流 → 决策 → 通知真正消失
- [x] 渠道管理页：开关读写 dumpsys 复核通过（importance 4→0→3）
- [x] 开机自启：service.sh 独立拉起 daemon 验证通过

## 第二轮 UI/交互修复（2026-10-18 晚）

用户实测反馈的 4 个问题，全部修复并回归验证。

### 1. 顶部被状态栏压、底部被导航栏挡
**原因**：Android 15+ 强制 edge-to-edge，主题没处理 insets。
**正解**：新增 `com.nchat.util.Insets`，用 `OnApplyWindowInsetsListener` 给视图补 padding。
- 主界面：`Insets.applyBoth(this, findViewById(R.id.root))` —— **必须加在根容器上**
- 其他 Activity：`Insets.applyBoth(this, findViewById(android.R.id.content))`
- **踩坑**：不要把底部 insets 单独加在 `layoutActions`（wrap_content 的线性容器）上，
  加了导航栏高度后它会被挤出父容器，内部按钮会被 layout 成 0x0。

### 2. 第一张卡片没按钮（第二张有、滑回第一张又有）
**原因**：ViewPager2 在 `adapter.submit()` 之前的初始 attach 阶段会回调
`onPageSelected(0)`，此时 `adapter.getItem(0) == null`，`renderActionsFor(0)`
走 `it == null` 分支把按钮区 `GONE` 了。而 `setCurrentItem(0)` 在已经是 0 时
**不会**再回调，所以按钮再也回不来。
**正解**：
- `onPageSelected` 里加 `if (adapter.getCount() == 0) return;`
- `applyLoaded` 里 `submit()` 之后**显式** `renderActionsFor(0)`

### 3. 聚合通知（GroupSummary）被重复列出
**原因**：一个 App 发多条通知时，系统会额外挂一条 `GroupSummary`（内容与明细重复）。
**正解**：
- daemon `/state` 增加 `groupKey` / `isGroupSummary` / `channelImportance` / `noisy` 字段
  - `getGroup()` 和 `isGroupSummary()` 都是 **@hide**，SDK 里找不到，必须反射
- App 侧：`isGroupSummary == true` 的一律不进决策流
- 顺带把 daemon 判定的常驻/系统类（ongoing / FOREGROUND_SERVICE / transport / service /
  android / systemui）也用 `noisy` 字段过滤，App 端 `isPersistent()` 里读它

### 4. 卡片下方加「通知渠道 + 禁用开关」/ 历史移出设置 / 待办自动恢复
- `activity_main.xml` 在卡片区与按钮区之间插入 `rowChannel`：
  「通知渠道」小标签 + 渠道名 + 状态文字 + `SwitchCompat`
- 开关直接读写渠道 importance（用 `DaemonClient.muteChannel/unmuteChannel`）
- 顶栏加「历史记录」按钮（`btnHistory`），设置页删掉「通知历史」「待办清单」
- **待办恢复**：`loadNotifications` 里 `db.queryTodos()` → `NotifItem.fromDecision()`
  插到队列最前面。这类条目标记 `fromTodo=true`，所有决策动作都不再调用 daemon
  （原通知已不存在），只记录决策 + `db.markTodoPendingByKey()` 清 pending
- 待办卡片没有渠道可操作，渠道行显示「来自待办（渠道名）」且开关 `setEnabled(false)`

### 关键坑：按钮 ChildCount 正常但子 View 全是 0x0（按钮"隐形"）
这是本轮最难查的 bug，症状极具迷惑性：
- `rowPrimary.getChildCount() == 2`、`rowPrimary.getWidth() == 1102`（正常）
- 但 `getChildAt(0).getWidth() == 0`、`getHeight() == 0`
- LayoutParams 完全正确（539x169）
- 屏幕上就是什么都看不见

**根因**：在 `addPrimary`/`addSecondary` 里**每加一个按钮就调一次 `requestLayout()`**，
连续的 requestLayout 与 ViewGroup 内部布局状态不同步，导致子 View 永久停在 0x0。

**正解**：
- 不要在 addView 后逐个 requestLayout
- 所有按钮加完后，在 `renderActionsFor` 末尾统一 post 一次：
  ```java
  layoutActions.post(() -> {
      layoutActions.requestLayout();
      rowPrimary.requestLayout();
      rowSecondary.requestLayout();
  });
  ```

**排查手段**（很有用，记下来）：把尺寸信息写到 `tvProgressSub` 之类的可见 TextView 上，
比看 logcat 可靠得多 —— 这台设备上 Operit 自己的日志会把 logcat 刷爆，
`logcat -s TAG` 和 `--pid` 都经常抓不到 App 的输出。

### 顺带修的
- `HistoryActivity` 一直没在 AndroidManifest 注册（之前只能从设置页按钮进，移掉按钮后暴露了）
- Switch 的 `channelBinding` 守门标志：`setChecked(x)` 在值相同时**不会**回调监听，
  导致 `channelBinding` 永久停在 true、后续真实点击全被挡掉。
  改为只在「值确实要变」时才加 guard。
- daemon jar 版本同步：`ModuleInstaller.syncJarIfNeeded()`，用「字节长度 + 累加校验」
  做指纹存进模块的 `version` 文件；App 启动时比对，不一致就覆盖并重启 daemon。
  （否则 App 升级后模块里跑的还是旧 daemon，新接口字段一直缺失）

## 第三轮修复（2026-10-19）

### 1. 点「立即阅读」没反应（B 站那条）
**排查结论**：不是 App 的问题，daemon 的 `/click` 是有效的。
直接测 `/click/0|com.eg.android.AlipayGphone|...` → 支付宝成功跳到 XRiverActivity。

但 **B 站那条是常驻播放通知**（`ongoing=true, category=transport`，
标题"大佬警告！失业潮快来了…"，正文"落叶在放假·后台听视频省流量"），
它的 `contentIntent` 指向 App 内部的播放页，从外部进程 send 后系统不切前台（或指向的服务不可见）。
这类通知本来就没有"跳出去看"的意义，属于通知本身的特性。

**同时做的加固**（即使真正失败也能给用户反馈）：
- daemon `click()` 改为多级尝试：API34+ 用 `ActivityOptions.setPendingIntentBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOWED)`；
  API31~33 反射 `setPendingIntentBackgroundActivityLaunchAllowed(true)`；
  最后兜底 `pi.send(ctx, 0, null, null, null, null, null)`。全部失败才返回 error。
- App `doReadNow()`：跳转后**延迟 2.5s** 再推进卡片（立刻 advance 会让本界面重排把目标 App 压回去），
  并用 `isSelfForeground()` 校验——若 2.5s 后自己仍在前台，弹提示
  「这条通知本身无法跳转（可能是常驻/播放类通知）」，而不是让用户以为点了没反应。

### 2. 加载过程显示 Loading
之前 daemon 启动/同步 jar 的几秒空窗期会先显示「0 / 0」甚至误显示「读取失败」。
**做法**：
- 新增 `showLoading(msg)` / `showLoadingForce(msg)`，复用 `layoutEmpty` 容器（图标换成 ◌，标题写"加载中/正在启动守护进程…/正在同步守护进程…/正在部署守护进程…"）
- 启动流程 `ensureDaemonThenLoad()` 一开始就 showLoading
- **踩坑**：`ensureDaemonThenLoad` 里有几个 `ui.post`，可能晚于 `applyLoaded` 执行，
  导致界面被盖回"加载中"且永远出不来。加 `readyOnce` 标志：
  `applyLoaded` 置 true，之后所有 `showLoading` 直接 return；
  用户主动刷新走 `showLoadingForce`（不受该标志限制）。
- 顺带加 `ModuleInstaller.needsJarSync()`：先判断再决定要不要显示"同步中"，
  避免每次启动都闪一下同步提示。


## 第六轮：五个交互 Bug 修复

### 1. 待办重复累积（点一次变两条、三次变三条）
**根因**：`doLater()` 走 `insertDecision()`，对同一条待办再点一次就插入新行，
旧行 `todo_pending` 已清零但记录仍在 → 列表按 key 还原时出现多条。
**修法**（双保险）：
- `NchatDb.upsertTodo()` 改为**事务内先 delete 再 insert**：
  `DELETE FROM decisions WHERE notif_key=? AND type IN (2,3)` 然后插入一条。
  无论历史上有多少条同 key 行，一次点击后都只剩一条。
- `recordDecision()` 加兜底：`decisionRowId<=0` 但 `hasHistory(key)` 为真时，
  若 `todoPending` 则走 `upsertTodo`，避免绕过 upsert 的路径继续累积。
- 启动时 `dedupeTodos()` 一次性清理旧版本遗留的重复行（保留每个 key 最新一条）。
**实测**：同一条通知连续三次「稍后读」，DB 里 pending 恒为 1 条（id 只从 37→38→39 变化）。

### 2. 待办增加计划时间
- DB v2：`decisions` 加 `plan_at INTEGER DEFAULT 0`，`VERSION=1→2`，`onUpgrade` 用 try/catch ALTER。
- `queryTodos(now)`：`plan_at IS NULL OR plan_at=0 OR plan_at<=now`，未到点不返回。
- `doLater()` 先弹 `AlertDialog` 单选 8 个预设：
  现在就办 / 今天 12:00 / 今天 18:00 / 今天 20:00 / 今天晚上 / 明天 09:00 / 明天 20:00 / 本周末。
- `planTimeOf()`：过了该时刻的「今天 X」顺延到明天；「今天晚上」=20:00（已过则 +2h）；周末=下周六 10:00。
- 待办卡片渠道行右侧显示「计划 20:00」。
**实测**：选「今天 20:00」→ `plan_at=1792411200000`（20:00:00 整）；
12:40 重进 App 列表 45→44，该条未出现。

### 3. 每次进 App 刷新列表
`onResume()` 里 `if (readyOnce) loadNotifications(false);`——
跳过首次 onCreate（那时 `ensureDaemonThenLoad` 正在跑），此后每次回前台都重拉。

### 4. 底部「屏蔽渠道」按钮多余
`renderActionsFor()` 的四种分类分支全部移除该按钮，渠道开关已在卡片上方承担此职责。
（`doMute`/`doMuteConfirm` 目前无调用方，保留未删。）

### 5. 处理完最后一条后渠道行残留 → 页面层级重构
**根因**：`rowChannel` 与 `layoutActions` 是根 LinearLayout 下的**两个并列兄弟节点**，
散落在代码各处的 `setVisibility` 调用很容易只改其一，出现「卡片没了渠道行还在」。
**重构**：新建 `@+id/layoutBottom` 垂直容器把两者包进去，统一由两个方法控制：
```java
showBottom()  // layoutBottom + layoutActions 一起 VISIBLE，并 post requestLayout
hideBottom()  // layoutBottom + layoutActions + rowChannel 一起 GONE
```
`renderActionsFor(pos)` 中 `it==null` 和 `showEmpty()` 都调 `hideActionArea()`（=hideBottom）。

**这轮踩的三个坑**：
1. **自递归 StackOverflow**：用脚本批量把 `layoutActions.setVisibility(View.GONE)`
   替换成 `hideBottom()` 时，把 `hideBottom()` 方法体内部那一行也替换了 → 无限递归崩溃。
   教训：批量替换后必须 grep 方法体确认没有自引用。
2. **`showBottom()` 只恢复父容器**：`hideBottom()` 会把子容器 `layoutActions` 置 GONE，
   而 `showBottom()` 早期只设了 `layoutBottom` VISIBLE → 结果只有渠道行、按钮区空白。
   必须在 `showBottom()` 里把 `layoutActions` 一并恢复。
3. **VISIBLE 后需二次 requestLayout**：`layoutBottom` 从 GONE 转 VISIBLE 的那一帧，
   子容器可能没重新测量，按钮仍为 0x0。`showBottom()` 里 `post` 一次
   对所有相关容器 `requestLayout()` 解决。

### 6. 常驻/播放类通知过滤
`NotifItem.isPersistent()` 增加：`ongoing && 渠道名匹配 播放|媒体|音频|视频|music|media|playback|player`
（`MEDIA_CHANNEL` Pattern，CASE_INSENSITIVE）。
这类通知点「立即阅读」只会回到播放页或无反应，直接不进决策流。


## 第七轮：卡片瘦身 + 通知漏显示

### 1. 缩小卡片高度、三个按钮各占一行

**卡片过高**：`item_card.xml` 根布局是 `LinearLayout` + `layout_height="match_parent"`，
正文 `svBody` 用 `layout_weight="1"` 吃光剩余空间，卡片永远顶满整个 ViewPager。

- 直接改 `wrap_content` 会崩：`IllegalStateException: Pages must fill the whole ViewPager2`。
  **ViewPager2 强制要求每个 page 的根 View 高度是 match_parent**。
- 正确做法：外层加一层 `FrameLayout`（match_parent，无背景）满足 ViewPager2，
  内层卡片 `LinearLayout` 用 `wrap_content` + `layout_gravity="center_vertical"`，
  卡片自然收缩并垂直居中。
- 正文限高：新建 `com.nchat.widget.MaxHeightScrollView`，
  重写 `onMeasure()` 把自身高度夹在 `maxHeight`（150dp）以内。
  **注意 `android:maxHeight` 对 ScrollView 完全不生效**（不参与测量），必须自定义 View。

**按钮各占一行**：
- `rowPrimary` / `rowSecondary` 在 XML 里仍是 `orientation="horizontal"`，
  按钮宽度算的是整行（1104px），横向并排放不下 → 第二个按钮被挤出容器、看不见。
- 改成 `orientation="vertical"` 即可。`makeBtn()` 里宽度公式同步改为整行宽度：
  `widthPixels - 18dp*2`，高度 46dp（主）/42dp（次），间距用 `topMargin`。

**排查过程踩的坑**：
1. `fix_rows.py` 的字符串替换静默失效（文件里有额外的 `layout_marginTop="8dp"` 和空行，
   匹配串对不上），导致第一轮白改。**脚本替换必须校验"替换前后是否真的变了"**，
   或者用 `index()` 定位区间再改，不要依赖整段精确匹配。
2. 为了看 `rowPrimary.getChildCount()` 加过把调试串写进 `tvProgressSub` 的输出，
   确认 `P2/S1 p0=1104x149 p1=1104x149 s0=1104x136` —— 按钮都建出来了、尺寸也对，
   但 `rowPrimary` 高度只有 171px（< 2×149），这才定位到是横向排列把它们挤没了。

### 2. 通知栏有一堆、App 却说全处理完

两个独立原因叠加：

**(a) `hasDecision()` 判据错误（主因）**
`loadNotifications()` 里原本是 `if (db.hasDecision(it.key)) continue;`——
只要这条通知在 DB 里有记录就永远跳过。
但通知可能处理完仍留在通知栏（cancel 竞态失败 / App 重新推同一个 key），
于是它既在通知栏里活着，又被 App 永久屏蔽。
**实测：45 条通知里 18 条被这条规则挡掉，只剩 5 条可见。**
改为按「是否挂着未完成待办」判断：
```java
Set<String> pendingKeys = db.queryTodosAll()...   // 新增 queryTodosAll()，不带时间过滤
if (pendingKeys.contains(it.key)) continue;       // 只有待办交给下面的恢复逻辑
```
`raw` 本身就是通知栏的实时快照，里面出现的都还活着，不该再按历史记录屏蔽。

**(b) `isNoisy()` 一刀切 ongoing**
daemon 里原本 `if (sbn.isOngoing()) return true;`——
大量 App 的普通推送也带 ongoing 标记（14/45），全被当噪音丢掉。
改为保守判据：系统进程（android / systemui）、`CATEGORY_TRANSPORT`、`CATEGORY_SERVICE`、
以及明确的系统组件包白名单 `NOISY_PKGS`（misound / mirror / mi_connect_service /
milink.service / tsmclient / securitycenter / aicr）。
`NotifItem.isPersistent()` 同步放宽：不再看 `flags & 0x2 / 0x40`，
只保留 transport、media 渠道 + ongoing、空骨架、以及 daemon 的 noisy 标记。

**效果**：45 条原始通知 → 20 条待处理（原来只有 5 条）。

### 3. 插曲：HyperOS 通知栏卡死
调试过程中反复 `input keyevent 26`（电源键睡眠/唤醒）导致
`NotificationShade` 窗口卡在 `mViewVisibility=VISIBLE` 抢焦点，
`cmd statusbar collapse` / `CLOSE_SYSTEM_DIALOGS` / `pkill systemui` 全部无效，
`am start` 也只回 warning。
**恢复方法**：`am start -W -n com.nchat/.ui.MainActivity` 触发 COLD 冷启动，
同时 `settings put system screen_brightness 200` 提亮，即可夺回焦点。
教训：不要用电源键做唤醒循环，改用 `am start -W` 直接冷启动验证。


## 第八轮：按钮结构统一 + 布局去抖动 + GitHub

### 1. 补上「打开 / 去处理」按钮
原来只有 `INFO` 和 `MARKETING` 分支有打开按钮，`TOOL` 和 `default` 分支都没有。
遇到工具类通知（如充电、系统设置类）想直接跳去 App 处理，无路可走。

重构成**统一的按钮结构**，每个分类都保证三个按钮：

| 位置 | 有 contentIntent | 无 contentIntent（待办还原 / 通知无可跳转目标） |
|---|---|---|
| 第 1 个 | 立即阅读 / 立即去办 / 去处理 / 打开通知 | 已完成（TOOL/OTHER）或 标记已读 |
| 第 2 个 | 稍后读 / 稍后办 | 同左 |
| 第 3 个 | 不感兴趣 | 同左 |

`canOpen = it.hasIntent && !it.fromTodo` —— 从待办还原的条目原通知已不在通知栏，
跳转必然失败，所以退化为主操作按钮。

### 2. 「不感兴趣」按钮间距不一致
`rowSecondary` 在 XML 里带了 `android:layout_marginTop="8dp"`，
而 `makeBtn()` 内部又给每个按钮加了 `topMargin = 7dp`。
两者叠加 → 第 3 个按钮与上面的间距是 15dp，而 1→2 之间只有 7dp。

**修法**：去掉容器上的 `marginTop`，间距统一由 `makeBtn` 的 `topMargin` 管理。
实测三个按钮 y 坐标：1976-2125、2147-2296、2318-2454，间距均为 22px。

### 3. 底部提示条高度不固定导致按钮抖动
`tvAiStatus` 原本是 `wrap_content` + `visibility="gone"`。
代码里在 AI 分析开始/结束时会 `setVisibility(VISIBLE/GONE)` 切换，
高度在 0 和 ~40px 之间跳变，导致上方按钮整体上下移动。

**修法**：
- 布局改为固定 `layout_height="22dp"`，去掉 `visibility="gone"`，永远占位
- 代码里所有 `setVisibility(VISIBLE/GONE)` 全部删掉，改为 `setText("...")` / `setText("")`
- 实测 `tvAiStatus` 稳定占据 2487-2559，高度 72px 恒定

### 4. GitHub 仓库
- https://github.com/bookgeek99/Nchat （公开）
- 本地 `/root/projects/nchat`，remote 为不含 token 的干净地址
- 首次提交 `7b09f2f`
- `.gitignore` 排除：build 产物、`android/app/src/main/assets/nchatd.jar`（由 buildall.sh 生成）、
  `android/keystore/`（含签名密码）、`ht/` `st/` `httptest/`（临时调试目录）
- 提交前用 `git diff --cached --name-only | grep -E '\.jks|\.keystore|build/|\.apk|\.jar'` 检查过，
  确认无敏感文件；推送后又用 GitHub API 拉 trees 复查一遍

**推送注意**：
- 本机 `socks5://127.0.0.1:1080` 代理当前**未运行**，GitHub API 直连可用（HTTP 200）。
  之前记忆里的代理配置这次不适用，先 `ss -ltnp | grep 1080` 确认再决定要不要走代理。
- 创建仓库：`POST https://api.github.com/user/repos`，HTTP 201 即成功
- 推送：`git push https://bookgeek99:TOKEN@github.com/bookgeek99/Nchat.git main`
  （token 只用在命令行，绝不写进 remote）

## 构建命令
```bash
./buildall.sh
# = javac+d8 产 daemon jar → 拷进 assets → gradle assembleDebug
#   → /sdcard/Download/Nchat-1.0.apk
```
安装：先 `cp /sdcard/Download/Nchat-1.0.apk /data/local/tmp/nchat.apk` 再 `pm install -r`
（直接装 /sdcard 路径会报 fuse 权限错误）。
签名：/root/projects/nchat/android/keystore/nchat.jks（storepass/keypass=nchat123，alias=nchat）
