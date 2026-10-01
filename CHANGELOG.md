# Changelog

## 0.13.2

- 修复 APK 内 WebView 视口配置导致的车机画面放大和裁切。
- 强制 800x480 CSS 视口，使手机导出画面与批准稿一致。
- 修复 ELM327 MAF/015E 成功后未标记燃油数据有效的错误。
- TPMS、燃油和无数据状态统一支持 N/A。
- 保留胎压正常、偏高偏低、过低的分级颜色。

## 0.13.0

- 将已确认的 TPMS HTML 页面作为 APK 内唯一仪表画面来源。
- 使用 WebView 渲染并通过 JavaScript 注入实时 TPMS、燃油和欢迎标题。
- 新增前台服务、WiFi WakeLock 与 H.264 视频通道。

## 0.1.0 - 0.12.0

- 建立 Android 音频和 MediaSession 基线。
- 加入 CarLife 端口监听与协议握手实验。
- 加入 WiFi ELM327 TPMS 读取。
- 加入燃油消耗、油价查询和仪表预览。
