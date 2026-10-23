# CarLink Lab 0.18.0：高德独立导航

## 在现有设备上使用

新版可覆盖安装，保留原签名与应用数据。本次 APK 针对当前小米 14 Ultra 的 ARM64 架构构建；Windows 电脑接收器继续使用此前的版本。

1. 手机打开“车辆连接”→“高德独立导航”。首次使用请阅读高德 SDK 说明，自行决定是否启用。
2. 电脑模拟车机选择手机，点击“连接 APP”，即可看到独立地图与胎压侧栏。
3. 手机填写城市和目的地关键词，点击“搜索”，在结果中选择地点，再点“开始导航到选中地点”。真实驾车导航需要精确定位权限。
4. “模拟导航测试（北京示例）”使用固定起终点，不使用实时定位，画面明确显示“模拟导航”。它验证 SDK 算路、导航信息更新和独立视频链路。
5. 手机可返回桌面；独立画面不依赖手机屏幕共享。熄屏传输已在当前手机验证，真实 GPS 的后台持续定位还需授权和路测。
6. “停止导航”结束导航、定位和播报并保留地图。“恢复胎压仪表”结束导航并切回原仪表。

没有车辆数据时胎压显示 `--`。导航语音由手机输出；车机音频通道、触摸反控、语音搜索和多路线选择尚未实现。真实 GPS 路测尚未完成，不将固定北京路线测试视作实际行驶验证。

## 渲染与生命周期

导航由前台服务持有，手机 Activity 只负责搜索、目的地选择和用户授权。地图视图渲染到应用自己的私有 VirtualDisplay / Presentation，通过 SurfaceTexture 和 EGL 按车机协商分辨率缩放，再交给 H.264 Surface 编码器。

私有显示使用 OWN_CONTENT_ONLY，不调用 MediaProjection，不读取其他手机应用画面。高分辨率地图渲染后再缩放，可避免高密度手机 SDK 在 800×480 车机上的文字和图标过大。

车机暂停视频时释放显示和编码器，导航引擎继续运行；恢复视频时重建独立显示。切换到胎压或屏幕共享、停止服务时会停止导航。实际 GPS 导航启动前，服务增加 location 前台类型。

## 本地 Key 与构建

Key 保存在 Git 忽略的 `local.properties`，不会写入版本控制中的源码：

```properties
amap.api.key=YOUR_ANDROID_KEY
```

构建时注入清单的 `com.amap.api.v2.apikey`。未配置 Key 的源码仍能构建，导航入口会提示缺少配置。安装包需要携带 Android Key，包名和签名 SHA-1 应继续匹配高德控制台。

- 包名：`io.github.carlinklab`
- 当前签名 SHA-1：`D6:02:31:BB:3C:CE:9E:4A:23:EB:50:02:71:64:39:31:F6:FB:B2:58`
- SDK Maven 依赖锁定为 `com.amap.api:navi-3dmap-location-search:11.3.100_3dmap11.3.100_loc11.3.000_sea9.8.1`。

Windows PowerShell：

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
.\gradlew.bat assembleDebugAndroidTest '-PauditRunner=io.github.carlinklab.NavigationAuditInstrumentation'
```

## 测试记录（2026-10-18）

- 构建、5 项 H.264 单元测试和 Lint 通过；Lint 无错误，207 条警告。
- 小米 14 Ultra 成功覆盖安装，用户自行确认 SDK 说明后初始化。
- 高德地点搜索返回成功码 1000，固定北京关键词返回 5 个结果。
- 固定北京驾车路线规划成功，SDK 启动模拟导航（类型 2），真实地图、蓝色路线、转向图标、距离与剩余时间可见。
- 800×480 / 15 fps 电脑接收链路通过：返回手机桌面、手机休眠后继续显示，时钟持续变化，视频暂停后恢复。
- 系统 `dumpsys media_projection` 为 null。
- 回调回归：SDK 可返回 requestId=0 的成功结果；不能将它误当成不匹配请求丢弃。取消后的回调不应重新启动导航，上一条人工算路完成前不并发提交新的人工算路。
- 实时定位权限、真实 GPS 行驶、真实车机硬件解码、车机音频和触摸尚未验证。

导航 SDK 自动检查是 `app/src/androidTest/.../NavigationAuditInstrumentation.java`，仅读取用户已确认的隐私开关，不替用户授予隐私或定位许可。`hold_ms` 参数可在成功后维持模拟会话供接收器检查。

官方资料：

- https://developer.amap.com/api/android-navi-sdk/summary/
- https://developer.amap.com/api/android-navi-sdk/download/
- https://developer.android.com/reference/android/hardware/display/DisplayManager

## 2026-10-23 源码整理验证

- 当前源码版本 0.19.0 / versionCode 30。
- Windows JDK 17：assembleDebug、testDebugUnitTest、lintDebug 通过，5 项单元测试通过，Lint 无错误。
- Windows 接收器编译通过，10 项协议检查通过。
- 本轮未连接手机或真实车机；上述检查不扩大 2026-10-18 的设备验证范围。
