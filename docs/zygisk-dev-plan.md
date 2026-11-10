# Zygisk 迁移开发计划

设计与依据见[技术文档](zygisk-migration.md)。本文只讲做什么、按什么顺序、怎样算完成。

原则：**先验证风险最高的原生加载器，再动现有代码**。阶段 1 上机通过之前，不改现有 Hook 代码和界面。

## 工作方式

- 仓库：`origin` 是 `msnazhao/fcmfix-coloros`（`ByteJuno/fcmfix-coloros` 的 fork），`upstream` 是原版。迁移工作在 `zygisk` 分支。
- **本机不编译**。推送后由 GitHub Actions 的 `Zygisk module` 工作流构建，产物是可刷入的模块 zip；
  现有的 `Android CI` 工作流只在 `master` 和 PR 上运行。
- 本机只需要 `git`、`gh` 和 `adb`（`C:\dev\android\platform-tools`）。需要别的工具时也装到 `C:\dev`。
- 取产物：`gh run download <run-id> -R msnazhao/fcmfix-coloros -n fcmfix-coloros-zygisk`。

## 阶段总览

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 0 | 决策与基线记录 | 决策已定；基线记录待上机 |
| 1 | 原生加载器验证 | **已通过**：日志确认两个进程注入、开机时序和回调链顺序 |
| 2 | Hook 后端与生命周期切换 | 代码已写（Fix 类原样运行在新后端上），待上机对照基线 |
| 3 | 配置与界面 | 代码已写，待上机 |
| 4 | NeoZygisk 专项（含晚加载） | 未开始 |
| 5 | 打包、文档、发布 | 未开始 |

阶段 3 和 4 互不依赖，可以并行。

## 阶段 0：决策与基线记录

已定：只适配 NeoZygisk；支持晚加载；不保留 LSPosed 入口；APK 放进 zip 由脚本安装；旧配置不导入；电池进程名是 `com.oplus.battery`。

还需要在现有版本 + Vector 的基线机上记录，作为之后每个阶段的对照：

- 状态页“复制结果”的全文（两个进程各自的生效、不适用、失配列表）；
- 一次强停回归的 `fcmfix` 日志：`am force-stop` 后发 FCM，记下哪些 bypass 行出现；
- Vector、NeoZygisk、root 方案各自的版本。

## 阶段 1：原生加载器验证

回答一个问题：这套引擎脱离 Vector 后，能不能在基线机上工作。不接入现有 Hook 代码，不改变任何系统行为。

已经写好的内容（`zygisk/`）：

- Zygisk 入口、libart 符号解析、内嵌 dex、LSPlant + Dobby 静态链接；
- Java 侧 `Hooks`（回调链、去优化）和 `ZygiskEntry`（探针）；
- 模块 zip 骨架：`module.prop`、`sepolicy.rule`、`customize.sh`、两层启动保护；
- 构建脚本和 Actions 工作流。

上机步骤：

1. 在 Vector 中停用现有的 FCMFix（避免两边同时 Hook），保留 NeoZygisk；
2. 刷入 Actions 产出的 zip，重启；
3. `adb logcat -s fcmfix-zygisk fcmfix` 查看日志。

完成标准：

| 检查 | 期望的日志或现象 |
| --- | --- |
| system_server 注入 | `fcmfix-zygisk: injected into system_server` |
| 开机时序 | `[zygisk] system_server probe: hooked=1 deoptimized=1`（数字 ≥ 1） |
| Hook 生效、回调链顺序正确 | 每次广播依次出现 `probe outer before`、`probe inner`、`probe outer after`（只打印前 3 次） |
| 电池进程注入 | `[zygisk] app process=com.oplus.battery`，随后 `battery probe: hooked=N`，两个 Manager 类都是 `true` |
| 没有权限问题 | 没有与本模块相关的 `avc: denied`，没有 `Accessing hidden` 拒绝 |
| 其他进程不受影响 | 随便开几个普通应用，`/proc/<pid>/maps` 里没有 `zygisk-module` |
| 系统正常 | 开机、解锁、收发广播无异常，无 system_server 重启 |
| 启动保护 | 见下 |
| 卸载干净 | 移除模块并重启后，上述日志都不再出现 |

启动保护的验证需要一个故意崩溃的构建，放在阶段 1 的最后做：确认连续失败后模块被停用、手机能进系统。

阶段 1 不通过就停下来重新评估，不进入阶段 2。

> **实际结果**（`PJE110_16.0.2.400` + NeoZygisk）：`injected into system_server`，`probe: hooked=1 deoptimized=1`，
> 每次广播依次出现 `outer before → inner → outer after`；电池进程注入成功，`battery probe: hooked=12`，
> 两个 Manager 类都能找到。启动保护的故意崩溃验证还没有做。

### 阶段 2、3 的实现与阶段 1 之后的差异

- 现有的 Fix 类没有改动 Hook 逻辑，只换了后端：`XposedBridge.hookMethod/deoptimize` → `zygisk.Hooks`。
- Gradle 拆成 `:hook`（运行在 system_server 和电池进程里的代码，加上与应用共用的协议类）和 `:app`。
  模块 zip 里的 `hook.dex` 由 `zygisk/build.sh` 直接编译 `hook/` 的源码得到，没有经过 R8。
- Context 不再靠 Hook `attachBaseContext`：system_server 里等 `activity`、`package`、`user`、`window` 服务都注册后取
  `ActivityThread.getSystemContext()`，电池进程取 `currentApplication()`，都在单独的线程上轮询，最长 5 分钟。
- 配置由应用保存，经带发送方身份的 `.update.config` 广播整份推给 system_server，校验后原子写入
  `/data/system/fcmfix/config.properties`。应用每次启动都会再推一次（仅限已经保存过配置的情况）。
- 状态页“LSPosed”“作用域”两行换成“Zygisk 模块”，显示版本，版本不一致时报警告，晚加载时给提示。
- 电池进程的 Hook 在 `LoadedApk.createOrUpdateClassLoaderLocked` 之后安装，用的是 `com.oplus.battery` 的类加载器。

## 阶段 2：Hook 后端与生命周期切换

- 拆出 `:hook`（纯 Java）：移入 `xposed/`、`util/`、适配层；去掉对 androidx 的依赖（`IceboxUtils` 里的 `ContextCompat`、`RequiresPermission`）；
  产出 `hook.dex`，不混淆、不删减；
- `XposedBridge.hookMethod / deoptimize` 的后端换成 `zygisk.Hooks`；删除 `invokeOriginalMethod`、`XC_MethodReplacement`；
- `ZygiskEntry` 的探针换成各 Fix 类，安装顺序与 `XposedMain` 相同；删除 `XposedMain`、`META-INF/xposed/`、`io.github.libxposed:api`；
- Context 改为显式获取，删除 `initContext` 里的 `attachBaseContext` Hook；
- 单元测试：`HookInvocationTest` 保持通过；给回调链补一条“同一方法两次注册，各执行一次、顺序正确、互不吞异常”的测试。

配置在这一阶段先用临时办法（读一个调试文件），不阻塞验证。

完成标准：

- 状态查询应答里，两个进程的生效、不适用列表与阶段 0 的记录**逐项一致**，没有新增失配；
- 强停回归通过，bypass 日志行与基线一致；
- 技术文档第 7 节列出的短方法，命中情况与 Vector 下相同；不同的逐个给出处理（去优化调用方或换 Hook 点）。

## 阶段 3：配置与界面（代码已写）

- system_server：`.update.config` 接收完整配置，校验后原子写入 `/data/system/fcmfix/config.properties`，开机读取；
  `XposedModule.onUpdateConfig` 改为读这个文件；电池进程不再加载配置；
- 应用：`ConfigStore` 改为本地 `SharedPreferences` + 推送；启动时推一次；
- `ModuleConnection` 重写为基于状态查询；`MainViewModel` 去掉 `XposedService`、`requestScope`；
- `StatusReport`：“LSPosed”“作用域”两行换成“Zygisk 模块”，加版本一致性检查和晚加载提示；删除 `RowAction.RequestScope`；
- 更新受影响的测试和样例：`StatusReportTest`、`ScreenModelTest`、`Samples`、`ModuleConfigTest`、`UiSmokeTest`、截图测试；
- 去掉 `io.github.libxposed:interface`、`service` 依赖和对应的 ProGuard 规则。

完成标准：

- 改配置后不重启即生效，重启后仍在；
- 清除应用数据后，system_server 仍按落盘的配置工作，应用再次打开能读回并显示；
- 伪造发送方的 `.update.config` 被拒绝；
- 未刷模块时界面显示“未激活”，不崩溃。

## 阶段 4：NeoZygisk 专项

- **晚加载**：识别 `runtime_flags & (1 << 30)`，走直接安装路径；`OplusProxyWakeLock` 实例从 `sInstance` 取；状态应答标出晚加载和电池侧是否加载；
- **DenyList**：把电池应用设为“卸载模块”，确认仍能注入和工作；
- **system_server 重启**：杀掉 system_server 后，Hook 重新安装，配置从磁盘恢复；
- **重复注入保护**：同时在 Vector 中启用旧版时，新入口检测到后放弃并记日志。

| 场景 | 期望 |
| --- | --- |
| 正常开机 | 全部 Hook 生效，与基线一致 |
| `ksud late-load` | system_server 的 Hook 全部安装，状态页标明晚加载，无崩溃；强停回归通过 |
| 晚加载后强停电池应用 | 新的电池进程被注入，电池侧 Hook 生效 |
| 电池应用在 DenyList | 与正常开机相同 |
| system_server 被杀后重启 | 自动恢复 |
| 旧版仍在 Vector 中启用 | 只有一方安装 Hook，有日志说明 |

完成标准：上表全部符合期望。

## 阶段 5：打包、文档、发布

- 工作流：构建 APK 并签名后放进 zip；`module.prop` 加 `updateJson`；`uninstall.sh` 清理 `/data/system/fcmfix/` 并卸载应用；
- 合并 `Android CI` 和 `Zygisk module` 两个工作流；发布工作流产出 zip 和校验和；
- `collect-report.sh`：加上模块目录内容、NeoZygisk 版本、启动保护计数；
- 文档：README（安装步骤改为刷入模块，风险与恢复改写）、`how-it-works.md`、`development.md`、`report-issue.md`、issue 模板；
  `firmware-notes.md` 增加 `PJE110_16.0.2.400`；`NOTICE` 加 LSPlant、Dobby、xz-embedded；
- 发布说明：主版本号加一；写明需要先在 Vector / LSPosed 中停用并卸载旧版、配置需要重新勾选、LSPosed 模块仓库不再更新。

完成标准：全新环境按 README 一步步操作能装好并通过强停回归；先出 `-rc` 版本收集反馈。

## 注意事项

- **每次刷入原生代码前先想清楚怎么恢复**：基线机上保留 `adb` 可用的停用手段（`touch /data/adb/modules/fcmfix_coloros/disable`）。
- **不要在阶段 2 顺手改 Hook 逻辑**。对照基线时任何差异都应该只来自后端，否则无法定位。
- **原生层出错一律退回“什么都不做”**，不能带着半初始化的状态继续。
- **`sepolicy.rule` 不能有分号**，注释里也不行。
- **模块脚本必须是 LF 行尾**：`.gitattributes` 已强制，构建脚本也会检查。
- **升级 LSPlant 或 Dobby 的提交前先在基线机回归**，它们跟随 ART 内部结构，Android 版本一变就可能失效。
- **不要复制 Vector 的 GPL 代码**，只参考做法。
