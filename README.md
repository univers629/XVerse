# XVerse

<div align="center">

一个为 x.com 打造的 Android WebView 增强壳 —— 广告剥离 · 一键下载 · 浏览历史。

纯客户端壳，**不代理流量、不改写数据、不绕过登录**；所有内容均来自你自己的登录会话。

![Platform](https://img.shields.io/badge/platform-Android%2012%2B-3ddc84)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7f52ff)
![Material3](https://img.shields.io/badge/UI-Material%203-0F1419)
![License](https://img.shields.io/badge/license-MIT-blue)

</div>

## 功能

- **广告剥离**：5 层防御（CSS 隐藏 → DOM 监听 → 响应拦截 → GraphQL 强信号 → 徽标文本扫描），只删除广告条目，不触碰你的数据
- **一键下载**：推文页右下角浮出「↓」按钮，图片/视频直存系统相册（`Pictures/XVerse`、`Movies/XVerse`），相册立即可见
- **浏览历史**：停留超 3s 或点击详情自动记录，本地缩略图离线显示，点击回跳帖子整页
- **本地过滤规则**：内置规则 + 用户自建，支持热更新
- **纯净内核**：无后台抓取、无流量代理、无数据上报

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 | Kotlin 2.0 · Compose 编译器插件 |
| UI | Jetpack Compose + Material 3 |
| WebView | AndroidX WebKit（内核增强） |
| 数据库 | Room 2.8（历史 / 下载 / 过滤规则） |
| 后台下载 | WorkManager + OkHttp 断点续传 |
| 媒体落盘 | MediaStore（系统相册）· DocumentFile（SAF 兜底） |
| 偏好 | DataStore |

## 项目结构

```
app/src/main/
├── assets/scripts/filter/   # 广告剥离 5 层过滤脚本（CSS/Mutation/Redux/响应拦截）
├── java/com/xverse/app/
│   ├── core/
│   │   ├── auth/            # 登录态与 Cookie 主线程安全封装
│   │   ├── data/            # Room 实体 + DAO + 仓库
│   │   ├── download/        # 解析、下载器、WorkManager 调度
│   │   ├── log/             # 应用内日志存储
│   │   ├── util/            # 缩略图缓存、UI 主线程执行器
│   │   └── webview/         # WebView 封装、JS 注入、Bridge 桥接
│   ├── di/                  # ServiceLocator 依赖组装
│   └── ui/                  # 首页 / 历史 / 下载 / 日志 / 设置（Compose）
└── res/
```

## 构建

```bash
# 环境：JDK 17+、Android SDK 36+、已连接 Android 12+ 设备
./gradlew.bat :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 合规说明

本项目是个人使用的 x.com 客户端壳，与 X Corp. 无任何关联。登录、数据与账号均由推特官方服务提供；应用仅缓存你自己浏览过的页面与媒体，不做任何批量抓取或数据外传。

## License

MIT
