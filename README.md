# XVerse

<div align="center">

<img src="docs/icon.png" width="128" height="128" alt="XVerse 图标" />

一个为 x.com 打造的 Android WebView 增强壳 —— 广告过滤 · 一键下载 · 扩展加载 · 浏览历史。

纯客户端壳，**不代理流量、不改写数据、不绕过登录**；内容均来自 X 官方登录会话。

![Platform](https://img.shields.io/badge/platform-Android%2012%2B-3ddc84)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7f52ff)
![Material3](https://img.shields.io/badge/UI-Material%203-0F1419)
![License](https://img.shields.io/badge/license-MIT-blue)

</div>

## 功能

- **广告剥离**：两种过滤方式可选 ——「占位 + 可点击验证」：CSS 注入 → MutationObserver，命中广告保留占位并显示提示卡片，点击卡片可查看原推文（防误屏蔽），再点隐藏恢复屏蔽；「完全不加载」：GraphQL 数据层直接剔除广告，广告根本不进 DOM。可另过滤带字幕（CC）视频（独立开关，热更新生效）
- **一键下载**：顶栏下载按钮，推文页列出图片/视频/动图，直存系统相册（`Pictures/XVerse`、`Movies/XVerse`）；下载列表带格式徽标（GIF / 视频 / 图片）、缩略图，支持断点续传
- **扩展加载**：导入 Chrome 扩展（.crx）与油猴用户脚本（.user.js），支持内容脚本、样式、配置页与 storage.local；来源分组展示
- **浏览历史**：停留超 1.5s 或点击详情自动记录，缩略图离线显示，点击回跳帖子
- **本地过滤规则**：添加屏蔽词与自定义 CSS，热更新生效
- **纯净内核**：不代理流量、不上报数据

## 技术栈

| 层 | 选型 |
|---|---|
| 构建 | AGP 9.1.1 · Kotlin 2.2.10 · KSP |
| UI | Jetpack Compose + Material 3 |
| WebView | Android WebView + AndroidX WebKit |
| 数据库 | Room 2.8（历史 / 下载 / 过滤规则 / 扩展） |
| 后台下载 | WorkManager + OkHttp（HTTP/1.1 兼容 twimg CDN） |
| 媒体落盘 | MediaStore（系统相册）· SAF（DocumentFile） |
| 扩展 | CRX/UserScript 解析 · chrome 兼容层 · 资源拦截 |
| 偏好 | DataStore |

## 项目结构

```
XVerse/
├── app/
│   ├── src/main/
│   │   ├── assets/
│   │   │   ├── extensions/           # 预置依赖（用户脚本 @require 的 JSZip 等）
│   │   │   └── scripts/filter/       # 广告过滤脚本（CSS / Strip / Mutation）
│   │   ├── java/com/xverse/app/
│   │   │   ├── core/
│   │   │   │   ├── auth/             # 登录态、Cookie 主线程封装
│   │   │   │   ├── data/             # Room：实体 / DAO / 仓库
│   │   │   │   ├── download/         # 媒体解析、下载器、WorkManager 调度
│   │   │   │   ├── extensions/       # CRX/UserScript 解析、运行时注入、chrome 兼容层
│   │   │   │   ├── log/              # 应用内日志
│   │   │   │   ├── util/             # 常量、缩略图缓存、UI 主线程执行器
│   │   │   │   └── webview/          # WebView 封装、JS 注入、Bridge 桥接
│   │   │   ├── di/                   # 手动 DI 组装（ServiceLocator）
│   │   │   └── ui/                   # browser / extensions / history / download / settings / logs / common / navigation / theme
│   │   └── res/
│   │       ├── drawable/             # 品牌徽标（Chrome/Edge/油猴）、通知图标
│   │       └── mipmap-*/             # 应用图标（小蓝鸟 + Web 字样，adaptive + legacy 全密度）
│   └── build.gradle.kts
├── build.gradle.kts                   # AGP / Kotlin / KSP 版本声明
├── settings.gradle.kts
├── gradle/wrapper/                    # Gradle Wrapper
└── .gitignore
```

## 构建

```bash
# 环境：JDK 17+、Android SDK 37、已连接 Android 12+ 设备
./gradlew.bat :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 合规说明

本项目是个人使用的 x.com 客户端壳，与 X Corp. 无任何关联。登录、数据与账号均由 X 官方服务提供；应用仅缓存浏览过的页面与媒体，不做批量抓取或数据外传。

## License

本项目采用 [MIT](LICENSE) 许可证。
