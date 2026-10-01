# Architecture

## 主要模块

### CarLifeBridgeService

前台服务，负责：

- 维持 CarLife 本地 TCP 监听。
- 维持 ELM327 连接。
- 持有 WiFi 和 CPU WakeLock。
- 将 TPMS 数据传给 WebView 渲染器。
- 将 H.264 帧发送到车机视频通道。

### CarLifeClient

监听以下本机端口：

~~~text
7240  control
8240  video
9240  media
9241  TTS
9242  VR
9340  touch
~~~

实现与目标车机的初始握手、视频编码初始化和 H.264 帧发送。

### TucsonTpmsDataSource

运行独立工作线程：

- 连接 WiFi ELM327。
- 初始化 ISO 15765 CAN 参数。
- 轮询 TPMS Mode 21。
- 轮询标准 OBD-II 燃油 PID。
- 计算本次消耗、平均油耗和消费金额。
- 输出 TpmsSnapshot。

### WebViewDashboardRenderer

- 在主线程加载 app/src/main/assets/tpms_template.html。
- 使用 800x480 CSS 视口。
- 通过 window.updateDashboard(JSON) 注入数据。
- 将 WebView 绘制到 Bitmap。
- 无数据或过期数据时显示 N/A。

### H264FrameEncoder

- 从 WebView 渲染器复制 Bitmap。
- 转换为 YUV420。
- 使用 MediaCodec 编码 H.264。
- 通过 CarLifeClient 发送视频帧。

## 数据模型

TpmsSnapshot 包含：

- 四个轮胎压力
- 四个轮胎温度
- 更新时间与 stale 状态
- 车速
- 燃油流量
- 瞬时和平均油耗
- 本次燃油消耗
- 当前油价
- 当前消费
- 燃油数据是否可用

## 线程模型

- Android 主线程：WebView、服务生命周期、通知。
- TPMS 工作线程：Socket、ELM327 命令、解析和累计。
- H.264 调度线程：Bitmap 复制、YUV 转换和 MediaCodec。
- CarLife 通道线程：监听、握手、视频发送。

WebView 只能由主线程创建和绘制，因此视频启动和停止会 post 到主线程。
