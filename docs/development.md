# 开发说明

面向修改代码或发布版本的人。模块的功能与 Hook 点见[工作原理](how-it-works.md)。

## 推送信任边界

- 广播入口读取 Binder 真实 UID，核对 GMS、精确 RECEIVE action、明确且一致的目标包、
  允许列表。只在一个入口安装信任作用域，避免 AMS → BroadcastController 重复建立窗口。
- 不向 Intent extras 写入信任标记。线程内作用域保存目标和发送 UID；嵌套入口（包括
  空 Intent、不可信请求）遮蔽外层信任，正常返回、提前返回、异常均清理并恢复外层状态。
- 异步队列路径读取框架 BroadcastRecord.callingUid；服务路径按已核实的完整方法签名读取
  callingUid，并交叉核对 ServiceRecord.appInfo / 分类目标包。不以清除身份后的 Binder UID
  或字符串 `bsgcm` 单独作为凭据。未知签名不启用对应服务 Hook，并记录日志。
- Firebase 应用自拉起服务只在自己的有效窗口内放行，不能用自发 RECEIVE 建立窗口。
  ColorOS 的 `com.google.android.gms.gcm.ACTION_TASK_READY` 仅在已核验 GMS 的 `bsgcm`
  bind 分支接受，不全局认作 FCM 广播。
- 单 UID 20 秒投递窗口，单调时钟、最大到期时间语义；到期查询与续期串行化，避免删除新窗口。
  不永久放开应用，也不全局关闭 Hans。UID 解析限进程所属用户，不跨用户套用窗口。
- Ice Box 激活使用单工作线程、最多 8 个排队任务，尽力而为：不异步重放系统原方法，
  外部 SDK 挂起不阻塞原广播线程，本条消息可能丢失。
- 每组 Hook 经 `OplusHooks.runHook` 安装并登记结果，模块界面通过有序广播 `.query.status` 查询（与配置刷新相同的发送方身份校验）。
  一组 Hook 一个都没挂上时必须抛出异常，否则会被记为生效。仅对已核实不存在该限制的固件变体使用
  `NotApplicable`，独立记录为 skipped 并在自查显示“不适用”；未知类、未知签名和安装错误仍为失败。
  判定条件见[固件核对记录](firmware-notes.md#什么时候记为不适用)。
- Hook 适配层每次注册只执行自己的回调。回调故障记录日志并保留框架原调用/异常；清理在
  finally 中执行。
- 配置用一次 getAll 读取构造不可变快照，后台串行重载、一次发布；损坏配置保留上一份快照。
  Android 14+ 配置刷新核对发送包和 UID；旧 Android 不开放无身份校验的刷新入口，需重启。
- 通知 Hook 按已知完整签名匹配并校验实参；OTA 单点失配回退系统原行为。

## 诊断日志

- 日志只写入 logcat（标签 `fcmfix`），不再转发到 GMS：转发依赖 GMS 作用域，而模块不使用该作用域。
- 限频：完全相同的诊断文本 30 秒最多输出一次；每进程每 5 秒最多 32 条；缓存最多 128 个键；单条最多 2048 字符。
  下一条输出附带 `suppressed N diagnostic lines since last output`，这是被合并的日志行数，不是丢失的推送数。
- 初始化日志、解冻失败和找不到 Hook 点等关键错误走本地非限频路径。

## 构建与验证

本地使用 JDK 21、Android SDK 36：

```text
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
```

不要删除失败的测试或关闭 lint 来规避问题，也不新增 lint baseline。

Hook 部分（`XposedMain`、`xposed`、`util`、`libxposed`）用 Java，运行在 system_server 和电池进程中；
应用界面用 Kotlin + Jetpack Compose（Material 3），在 `ui` 包中。Release 用 R8 压缩，`proguard-rules.pro`
保留 Hook 部分的全部类和成员，不改名、不删除，只压缩界面和依赖库。

界面测试分两种：

- `UiSmokeTest`：Robolectric（legacy 图形模式）组合每个页面并点击主要操作，随 `testDebugUnitTest` 在任何机器上运行；
- `screenshots/ScreenshotTest`：把每个页面渲染成 PNG，需要 Robolectric 原生图形（x86_64 Linux 或 macOS），
  只在加 `-Pscreenshots` 时运行，输出到 `app/build/outputs/roborazzi/`。CI 的 `screenshots` 任务会上传这些截图；
  `emulator` 任务把 Release 包装到模拟器上逐页打开并截图（`.github/scripts/emulator-smoke.sh`），应用崩溃时失败。

Hook 尽量挂在 Binder 入口、跨 jar 调用的服务方法或体积较大的方法上：同一 dex 内被调用的短方法（getter、私有小方法）
可能被 ART 内联，Hook 不会触发。

## 发布

- 版本号是 `<上游主版本>.<上游次版本>.<本项目修订号>`，修订号从 0 到 999 递增，例如上游 13.2 之上的第一版是 `13.2.0`，之后 `13.2.1`……；
  上游升到 13.3 时从 `13.3.0` 重新开始。`app/build.gradle` 的 `versionName` 是唯一的来源，模块和应用共用它。
- `versionCode` 只能递增，每次发布都要加 1：设备上已安装的应用不会被更低的 versionCode 覆盖，root 管理器也靠它判断有没有新版本。
- 改 `versionName` 时同一提交里写好 `docs/releases/<versionName>.md`，改完删掉上一版的说明；历史说明都在 GitHub Release 中。
- 推送到 `zygisk` 分支或 PR 触发 `Zygisk module` 工作流：编译 Hook dex 和注入库，运行 `hook`、`app` 的单元测试，打包带签名应用的模块 zip，
  作为产物（14 天）上传。
- 推送 `v*` tag 触发 `Tagged release`：校验 `versionName` 形如 `13.2.0`、tag 等于 `v` + `versionName`、发布说明存在，
  然后构建并上传到 GitHub Release：`FCMFix-Zygisk-<版本>.zip`、`update.json`、`SHA256SUMS`。缺少签名密钥或 zip 里没有应用时失败，
  不会发布一个没有应用的模块。tag 含 `-rc` 时标为预发布。
- `update.json` 由工作流生成，内容是 `version`、`versionCode`、`zipUrl`、`changelog`。模块的 `module.prop` 里
  `updateJson` 指向 `releases/latest/download/update.json`，所以 root 管理器总是读到最新的正式版（预发布不算）。
- 签名密钥来自仓库 Secrets：`KEYSTORE`（base64）、`KEYSTORE_PASSWORD`、`KEY_ALIAS`。

## root 管理器里的两个按钮

- Action 按钮运行模块里的 `action.sh`：检查模块是否启用、应用是否安装且版本一致，
  并读取 system_server 在 `/data/system/fcmfix/run.properties` 留下的本次开机记录（版本、是否晚加载、生效与失配的 Hook 数量）。
  记录里的 pid 与当前 system_server 不同，说明本次启动模块没有加载。输出一行一项，用 ✅ ⚠️ ❌ 标记；
  Zygisk 实现只有不是 NeoZygisk 时才显示“未验证”，不计入异常。
- 模块名下面的说明行不写介绍，写状态，格式参考 bindhosts，例如 `状态：运行中 ✅ | Hook 14 项`。
  `service.sh` 在开机后等到 system_server 写好记录再更新一次，Action 按钮每次运行也会更新；`common.sh` 是两者共用的逻辑。
  更新的只是 `module.prop` 的 `description` 一行。
- 升级检查：`module.prop` 的 `updateJson` 指向最新正式版 Release 里的 `update.json`，root 管理器打开模块页时读取它，
  `versionCode` 比已安装的大就提示更新。所以每次发布 `versionCode` 都必须加 1。
- 配置按钮（WebUI，`webroot/index.html`）调用 root 管理器的 `ksu.exec` 执行 `am start` 打开应用。
