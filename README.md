# Gemini Pro

面向 Android 的 Google AI Studio WebView 客户端，基于 [RxNaison/Gemini-Pro](https://github.com/RxNaison/Gemini-Pro) 开发。

直接使用 AI Studio 网页及 Google 账号，不需要在应用中配置 API Key。模型、额度和付费状态由 Google AI Studio 提供。

## 使用

1. 首次使用时，先在 Chrome 中登录 [Google AI Studio](https://aistudio.google.com/)，按网页提示完成 Google Drive 授权。
2. 打开应用并登录 Google 账号。Chrome 与应用的登录状态不共享，网页要求时仍需登录。
3. 点击底部角落的菜单按钮，或从侧边滑入打开工具菜单；菜单位置可以切换左右。
4. 从顶部中间向下拖动超过阈值再松手，刷新当前页面。

应用会记住最近访问的 AI Studio / Flow 页面地址；这不包含未发送输入，也不替代 Google 的聊天保存功能。Google 限制的登录或授权步骤仍需在支持的浏览器中完成。

## 功能

- 复制 Mermaid 或 HTML 后显示预览入口，支持 Markdown 代码围栏与说明文字中的代码块。
- 保存剪贴板文本，长按图片下载，开启视频选择模式后长按视频下载。
- 分享页面、复制链接、浏览器打开、前进与刷新。
- 访问 Google Docs 和 Google Flow，按需开启屏幕常亮。
- 简体中文与英文界面，跟随系统语言。

## 本分支改进

- 摄像头和麦克风按需申请：校验网站来源并展示网站授权提示，不在启动时集中申请权限。
- 下载桥接使用限定 HTTPS 来源的 Web Message，仅接受主框架消息与用户发起的下载。
- Blob 下载以 48 KiB 分块传输，在后台写入临时文件，核对长度后保存；支持取消及失败清理，不再禁用 Blob 释放。
- Android 10 及以上使用 MediaStore 保存；Android 9 及以下在下载时申请存储权限。
- 断网重试返回出错页面；刷新错误页也会重试原地址。
- 补齐剪贴板监听与 WebView 清理，避免旧预览计时器提前隐藏新提示。
- 更新来源指向本仓库。

Blob / data 下载暂设 **256 MiB** 上限，一次传输一个文件，页面导航会取消未完成的传输。需要支持 Web Message Listener 的 Android System WebView；过旧版本会提示更新。普通 HTTPS 下载交给系统下载管理器。

Mermaid 运行库目前从 jsDelivr 加载，需要网络。应用界面的中文翻译不改变 Google 网页本身的语言。

## 构建与验证

使用 JDK 21、Android Studio 以及项目指定的 Android SDK / Gradle 版本：

Debug 测试包使用独立包名，可与上游原版共存；不会读取原版的登录状态或设置。

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
node --test tests/blob-download.test.mjs
```

测试覆盖精确来源匹配、Blob 来源限制、代码围栏提取、Mermaid 文本转义，以及下载的分块完整性、大小限制和取消清理。摄像头、Google 登录、系统文件选择与媒体下载仍需 Android 实机验证。

## 许可证

[Apache License 2.0](LICENSE)，保留上游版权与许可证。
