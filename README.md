# XVerse

<div align="center">

<img src="docs/icon.png" width="128" height="128" alt="XVerse 图标" />

一个为 x.com 打造的 Android WebView 增强壳 —— 广告过滤 · 高级搜索 · 一键下载 · 扩展加载 · 浏览历史 · 全量多语言。

纯客户端壳，**不代理流量、不改写数据、不绕过登录**；内容均来自 X 官方登录会话。

![Platform](https://img.shields.io/badge/platform-Android%2012%2B-3ddc84)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7f52ff)
![Material3](https://img.shields.io/badge/UI-Material%203-0F1419)
![License](https://img.shields.io/badge/license-MIT-blue)

</div>

## 功能

- **多语言国际化 (i18n)**：支持 English、简体中文、日本語，全界面组件、下拉弹窗、Toast 提示、系统通知与运行日志支持实时动态热切换与语言隔离
- **广告与内容过滤**：支持占位验证或数据层剔除两种模式，可过滤带字幕视频；内置规则、扩展规则包、关键词和自定义 CSS 均可热更新
- **高级搜索**：原生搜索面板组合关键词、账号、时间、互动量、媒体、位置等 X 搜索运算符，并保存本地历史与收藏
- **一键下载**：识别推文图片、视频和动图，流式写入 MediaStore 或用户选择的 SAF 目录，支持后台任务、暂停、恢复与系统查看器打开
- **扩展加载**：导入 `.crx`、`.zip` 和 `.user.js`，支持内容脚本、样式、配置页、`storage.local`、扩展过滤包及常用 GM API
- **账号与历史**：使用 X 官方 Cookie 会话识别账号，按账号隔离浏览历史；详情页自动记录并缓存缩略图
- **安全与存储加固**：会话凭据经 Android KeyStore 硬件级 AES-GCM 加密，扩展解压具备 Zip Slip 路径穿越与 Zip 炸弹防御，遵循 Android 10+ Scoped Storage 分区存储
- **界面体验**：Material 3、系统/自定义 Monet 配色、紧凑顶栏与短时转场动画
- **纯净内核**：纯客户端实现，不代理流量，不采集或上报用户数据

## 局限性与已知不支持项 (Limitations & TODO)

- [ ] ❌ **不支持通行密钥 (Passkey) 登录**：受限于 Android 系统 WebView 容器对 WebAuthn / Passkey 的通道限制，无法在应用内直接使用通行密钥登录，推荐使用账号密码/双重验证码登录或系统浏览器跳转。
- [ ] ❌ **扩展支持的局限性**：
  - ❌ 不支持带有常驻后台生命周期（MV3 Service Worker / MV2 Background Page）的复杂扩展；
  - ❌ 不支持依赖 Chrome 特有桌面私有 API（如 `chrome.tabs.*`、`chrome.windows.*`、`chrome.webRequestBlocking` 等）的桌面专属扩展；
  - ❌ 过滤类扩展仅支持通过原生解析引擎提取静态与声明式规则，无法在后台运行复杂的拦截脚本。

## 技术栈

| 层 | 选型 |
|---|---|
| 构建 | AGP 9.3.1 · Gradle 9.7.0 · Kotlin 2.4.0 · KSP 2.3.10 |
| UI | Jetpack Compose + Material 3 |
| WebView | Android WebView + AndroidX WebKit |
| 数据库 | Room 2.8（历史 / 下载 / 过滤规则 / 扩展） |
| 后台任务 | WorkManager + OkHttp（流式下载，兼容 twimg CDN） |
| 媒体落盘 | MediaStore（系统相册）· SAF（DocumentFile） |
| 扩展 | CRX/UserScript 解析 · chrome/GM 兼容层 · 资源拦截 |
| 偏好 | DataStore |

首次安装时，“隐藏网页内 X 底栏”、“自定义主题色”、“AdGuard 集成规则”和“过滤 AI 生成内容”默认关闭；其余设置页开关默认开启。升级安装会保留已有偏好。

## 设计与生命周期

- `XVerseApp` 持有唯一 `ServiceLocator`，ViewModel 通过 factory 注入依赖，不依赖全局静态 Context
- WebView 仅由界面层持有；ViewModel 使用弱引用，离开组合时停止加载、移除 Bridge、释放 client 并显式调用 `destroy()` 销毁 WebView 与释放 native 资源
- 页面脚本的 observer、interval 与重试任务会在 `pagehide` 时回收；媒体解析、广告日志、应用日志和扩展分块会话均设置容量上限
- 页面命令使用有界 Channel，冷启动与连续点击不会因订阅时序而静默丢失
- Release 默认启用 R8 代码压缩和资源收缩，并保留必要的 JavaScript Bridge 接口

## 项目结构

```text
XVerse/
├── app/
│   ├── src/main/
│   │   ├── assets/
│   │   │   ├── extensions/           # 预置脚本依赖
│   │   │   └── scripts/filter/       # 广告过滤脚本
│   │   ├── java/com/xverse/app/
│   │   │   ├── core/
│   │   │   │   ├── auth/             # Cookie 会话与本地账号仓库 (KeyStore 加密)
│   │   │   │   ├── data/             # Room 实体、DAO 与仓库
│   │   │   │   ├── download/         # 媒体解析、下载器与后台任务
│   │   │   │   ├── extensions/       # 扩展解析、运行时与兼容层
│   │   │   │   ├── log/              # 有界应用日志与动态多语言本地化引擎
│   │   │   │   ├── search/           # X 搜索语法、历史与收藏
│   │   │   │   ├── util/             # 常量、缩略图缓存、动态 Context 语言解析器
│   │   │   │   └── webview/          # WebView、脚本注入与 Bridge
│   │   │   ├── di/                   # 手动依赖组装
│   │   │   └── ui/                   # Compose 页面、导航、弹窗与主题
│   │   └── res/                       # 图标、主题、多语言 (values, values-zh, values-ja) 与备份规则
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/wrapper/
```

## 构建与检查

```powershell
# 环境：JDK 17+、Android SDK 37；运行要求 Android 12+
.\gradlew.bat :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 提交前检查
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Release 输出位于 `app/build/outputs/apk/release/`。仓库不保存构建产物、签名文件或真实账号数据；正式发布前请自行配置签名。

## 数据与备份

浏览历史、下载记录、过滤规则、扩展和偏好均保存在设备本地。Android 备份规则会排除账号 Cookie 仓库等会话数据，避免登录态随系统备份迁移。

## 合规说明

本项目是个人使用的 x.com 客户端壳，与 X Corp. 无任何关联。登录、内容与账号数据均由 X 官方服务提供；应用只在本地保存必要状态和用户主动下载的媒体，不做批量抓取或数据外传。使用扩展与自定义规则时，请自行确认来源与权限。

## License

本项目采用 [MIT](LICENSE) 许可证。
