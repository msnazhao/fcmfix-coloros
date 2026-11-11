# FCMFix-Zygisk

修复**国行 ColorOS 16 / 17** 拦截 Google FCM 推送的 **NeoZygisk 模块**：应用被划掉或被系统停止后，仍能及时收到推送。
需要 **Root（KernelSU / APatch）+ Zygisk 实现**。已验证的是 NeoZygisk；模块只用 Zygisk 模块接口，理论上兼容其他 Zygisk 实现，
但**没有验证过**，换用其他实现时请在 Action 或应用的“状态”页确认模块真的加载了。不再需要 LSPosed。

基于上游 [ByteJuno/fcmfix-coloros](https://github.com/ByteJuno/fcmfix-coloros)（其基础为 [kooritea/fcmfix](https://github.com/kooritea/fcmfix) 53 版），
把注入方式从 LSPosed 改为 NeoZygisk。已在一加 15（PLK110）上验证，其他 ColorOS 16 / 17 机型可能可用，但未经同等级验证。
不是 ColorOS 国行请用上游版本。

版本号 `13.2.0`：前两段是对应的上游版本（13.2），最后一段是本项目自己的修订号（0 到 999）。

## 安装

1. 从 [Release](https://github.com/msnazhao/fcmfix-coloros/releases/latest) 下载 `FCMFix-Zygisk-<版本>.zip`，在 root 管理器里刷入，然后重启手机。应用随模块自动安装。
2. 在 root 管理器的模块页：**Action 按钮**检查模块、应用和 Hook 的运行状态；**配置按钮**直接打开 FCMFix-Zygisk 应用。
3. 在应用里放行需要推送的应用，或开启“自动放行包含 FCM 的应用”，并确认这些应用的通知权限已开启。

升级：root 管理器会读取模块的更新地址，有新版本时直接提示；也可以在应用的“设置 → 关于 → 检查更新”打开下载页。
不要同时启用 LSPosed 版的 FCMFix。从 LSPosed 版（包括旧包名 `com.fcmfix.coloros`、`com.kooritea.fcmfix.op15`）迁移时，请先停用并卸载旧版。

问题反馈：[Issues](https://github.com/msnazhao/fcmfix-coloros/issues/new/choose)。

## 能解决什么

- 划掉后台、被系统清理或处于“已停止”状态的应用，FCM 仍能唤醒它并显示通知；
- 推送到达后，应用不会马上被再次冻结或断网，能及时拉取消息内容；
- GMS、Play 商店等 Google 核心服务不再被禁止联网，熄屏、Doze 和“睡眠待机优化”期间保持连接。

## 不能解决什么

- GMS 连不上 FCM 服务器：常见于 DNS 污染或 5228–5230 端口被封，需要自行解决网络（[判断方法](docs/report-issue.md#fcm-断开网络问题还是系统限制)）；
- 应用不使用 FCM、服务器没有发送消息，或 Google Play 服务未登录、被禁用；
- 系统整机断网，例如深度睡眠直接关闭 Wi‑Fi / 移动数据；
- 工作资料、应用分身和多用户下的推送（未承诺支持）。

本模块不安装或替代 GMS，也不是推送服务器、代理或保活工具。

## 风险与隐私

- 这是 system_server 级别的 Hook，安装前请保留恢复手段：模块自带启动保护（连续两次开机失败会自动停用自己），
  也可以在 `/data/adb/modules/fcmfix_coloros/` 下新建 `disable` 文件后重启。
  OTA 后 Hook 可能与固件不匹配，此时保持系统原行为，并在“状态”页显示失配；
- APK 不申请 `INTERNET` 权限，不经过任何服务器中转；导出的报告只保存在本机，由你决定是否分享。

## 文档

- [遇到问题](docs/report-issue.md)：自查、导出报告、常见问题
- [工作原理](docs/how-it-works.md)：ColorOS 的限制位置、模块改了什么、与上游的区别
- [固件核对记录](docs/firmware-notes.md)：各固件上的 Hook 点与实测
- [开发说明](docs/development.md)：信任边界、构建与发布

## 许可证

本项目和上游 fcmfix 都以 [MIT](LICENSE) 授权，第三方组件见 [NOTICE](NOTICE)。
