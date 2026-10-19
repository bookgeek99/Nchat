# Nchat — Android 通知管理

把通知栏的堆积通知变成一条条可决策的卡片：**打开 / 稍后办 / 不感兴趣**。
处理完直接从通知栏清除，不再堆积。

## 为什么需要 root

读取"全量活跃通知"（含其他 App 的通知）需要 `NotificationListenerService` 或系统级权限。
Nchat 采用**极薄 Magisk 模块**方式：模块本身只负责在开机时拉起一个 root daemon，
真正的工作都由这个 daemon 完成。App 通过本地 HTTP 与 daemon 通信。

## 架构

```
┌─────────────────┐   HTTP 127.0.0.1:18765   ┌──────────────────┐
│   Nchat App     │ ───────────────────────> │  NchatDaemon     │
│ (UI / 决策 /DB) │ <─────────────────────── │  (root, 常驻)    │
└─────────────────┘   /state /click /cancel └──────────────────┘
        │                                              │
        │ SQLite decisions                             │ StatusBarNotification
        ▼                                              ▼
   decisions 表                                 系统通知栏
```

- **App**：卡片流 UI、AI/规则分类、决策落库、待办恢复
- **daemon**：读取 `StatusBarNotification` 全量列表、触发 `contentIntent`、
  通过 `cancelNotificationsFromListener` 清除通知、读写渠道重要性
- **模块**：`/data/adb/modules/nchatd/`，systemless，只含启动脚本与 daemon jar

## 功能

- 通知卡片流，一条一条处理
- AI 分类（OpenAI 兼容接口，Key/Model 在 App 内配置），未配置时走本地规则
- 四种处理方式：**打开 / 稍后 / 已阅 / 不感兴趣**
  - 已阅 = 看到内容就够了，不用点进去，但也不是不感兴趣
- 待办支持**计划时间**（今晚 8 点、明天早上…），未到点不提示
- 通知渠道开关，直接关闭不想看的渠道
- 历史记录、聚合通知去重、常驻/媒体类通知自动过滤

## 统计

每次处理都会记一条样本，统计页展示：

- **决策分布**：已阅 / 已完成 / 不感兴趣 / 稍后 分开统计
- **渠道画像**：按「不感兴趣」占比排序，占比高的渠道就是该屏蔽的候选
- **活跃时段**：24 小时分布，找出通知高峰

「已阅」和「不感兴趣」刻意分开——前者说明内容有价值只是不用展开，
后者说明这个渠道在骚扰你。将来做智能判断时，一个是正样本一个是负样本。

## 构建

```bash
./buildall.sh
# javac + d8 打 daemon jar → 拷进 assets → gradle assembleDebug
# 产物：/sdcard/Download/Nchat-1.0.apk
```

依赖：JDK 17、Android SDK（默认 `/root/android-sdk`）、Gradle 8.1。

安装时注意：必须先拷到 `/data/local/tmp` 再装，直接装 `/sdcard` 路径会因 fuse 权限失败。

```bash
adb shell cp /sdcard/Download/Nchat-1.0.apk /data/local/tmp/nchat.apk
adb shell pm install -r /data/local/tmp/nchat.apk
```

**签名**：仓库不含 keystore（含密码）。首次构建需自备
`android/keystore/nchat.jks`，或修改 `build.gradle` 使用自己的签名配置。

## 目录

```
android/          Android App 源码
  app/src/main/java/com/nchat/
    ui/           界面（MainActivity / 卡片流 / 历史 / 统计 / 设置）
    data/         NchatDb（SQLite）
    classify/     RuleClassifier / AiClassifier
    net/          DaemonClient
    root/         Shell
    mod/          ModuleInstaller（部署 Magisk 模块）
    widget/       MaxHeightScrollView
daemon-src/       NchatDaemon.java（root 守护进程）
build.sh          daemon 编译脚本
buildall.sh       完整构建
NOTES.md          开发笔记与踩坑记录（很详细，值得看）
```

## 实现要点

几个绕不开的坑，都记在 `NOTES.md` 里：

- **ViewPager2 强制 page 撑满**：卡片根布局必须 `match_parent`，
  要卡片随内容收缩需外面套一层 FrameLayout
- **`android:maxHeight` 对 ScrollView 不生效**，得自己重写 `onMeasure`
- **`PendingIntent.send()` 不带 ActivityOptions** 会被后台启动限制静默拦截
- **`hideActionArea()` 自递归**：脚本批量替换把方法体内部也替换了
- 判断"通知是否已处理"不能只看 DB 记录，要看它是否还在通知栏里

## 许可证

个人项目，未指定许可证。
