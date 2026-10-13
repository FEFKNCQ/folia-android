# Folia 安卓版 (folia-android)

> 📱 **Folia 音乐播放器的安卓移植版** —— 基于 [folia-major 0.7.18](https://github.com/chthollyphile/folia-major) 源码移植，内置 Node 后端，无需电脑、无需任何外部服务器，手机上独立运行。

**作者：不出名的TiMing（[Timingyyds](https://github.com/Timingyyds)）**

**当前版本：v1.3.0**（同步上游 0.7.18）

---

## ✨ 这是什么

这是把 Folia（一个桌面端音乐播放器）完整移植到安卓的项目：

- **完整 0.7.18 源码**：桌面版的所有音源（网易云 / 酷狗 / QQ 音乐 / 波点 / Navidrome）与界面全部保留
- **内置 Node 后端**：通过 [nodejs-mobile](https://github.com/nodejs-mobile/nodejs-mobile) 在手机本地跑起全部音源 API（端口 3000/3001/3002/3100），**不需要 adb、不需要电脑、不需要任何外部服务器**
- **Capacitor 壳**：安卓 WebView 承载界面，沉浸式全屏、横竖屏自适应
- **本地音乐导入**：通过安卓 SAF（存储访问框架）选择文件夹，扫描本地音频进曲库
- **扫码登录**：网易云 / 酷狗 / QQ 音乐都支持手机上直接扫码登录

## 🎵 安卓专属功能

- **媒体通知**：播放时通知栏显示封面 / 歌名 / 歌手，进度条实时走动、可拖动跳转，上一首/播放暂停/下一首按钮直接控制
- **后台播放不中断**：前台服务保活，退到桌面、锁屏音乐持续播放
- **播放不被其他应用打断**：开关位于 播放动画设置 → 第 2 个面板 → 音频增益下方，开游戏/看视频音乐不停
- **登录态持久化**：网易云 / 酷狗 / 波点 / QQ 的会话落盘保存，重启与升级 App 都不丢失
- **通知权限自动请求**：Android 13+ 首次启动自动弹出授权框
- **触摸设备优化**：隐藏键盘快捷键提示、空闲自动隐藏播放条

## 📥 安装

1. 打开本仓库的 [**Releases**](https://github.com/Timingyyds/folia-android/releases) 页面
2. 下载最新的 `Folia-Android-1.3.0.apk`
3. 手机上安装（需允许"安装未知来源应用"）
4. 首次启动在弹框中**允许发送通知**（媒体通知需要）

> APK 约 360MB：内置了三个 ABI 的 Node 运行库和完整的音源后端。

## 🔨 自己构建

```bash
npm install
npm run build:capacitor     # 构建前端
npx cap sync android        # 同步进安卓工程
cd android && ./gradlew assembleDebug
```

要求：Node.js 20+、JDK 17+、Android SDK（compileSdk 36）。

## 🧩 架构说明

```
WebView（React + 0.7.18 界面）
   │  http://127.0.0.1:<port>
Node 运行时（nodejs-mobile，跑在手机本地）
   ├── 3000  网易云 API
   ├── 3001  酷狗 API
   ├── 3002  QQ 音乐 API（会话已落盘持久化）
   ├── 3100  波点 API
   └── 3101  本地音乐文件服务（SAF 导入）
```

## ⚠️ 已知限制

- 部分 Serverless 接口（`/api/*`：歌词代理、AI 主题生成等）在安卓端尚未落地
- QQ 扫码登录会被 QQ 风控在凭据校验阶段拒绝（upstream-rejected）——扫码同意后上游要求安全验证，当前流程未实现该环节，属上游协议限制
- 酷狗 VIP 歌曲对非会员账号返回"已下架"，为上游版权限制

## 🙏 致谢

- 上游项目：[folia-major](https://github.com/chthollyphile/folia-major) —— 本仓库基于其 0.7.18 版源码移植，请同时支持原作者
- [nodejs-mobile](https://github.com/nodejs-mobile/nodejs-mobile)
- 各音源 API 项目：NeteaseCloudMusicApi / KuGouMusicApi / qq-music-api / bodian-music-api

## 📄 免责声明

本项目仅供学习交流使用，请于下载后 24 小时内自行删除。音乐版权归原作者所有。
