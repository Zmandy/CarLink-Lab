# Contributing

感谢参与 CarLink Lab。

## 开发环境

- JDK 17
- Android SDK Platform 35
- Android 8.0 或更高设备
- 建议使用自有车辆或可恢复的测试环境

## 开发流程

1. Fork 仓库并创建功能分支。
2. 保持改动聚焦，不提交 APK、签名文件、设备日志或本地路径。
3. 修改后至少运行：

~~~powershell
.\gradlew.bat assembleDebug
~~~

4. 对协议修改附上脱敏后的原始请求、响应和触发步骤。
5. Pull Request 中说明测试设备、Android 版本、ELM327 型号和车机版本。

## 代码要求

- Java 代码使用 4 空格缩进。
- 不使用硬编码 token、密码或私有地址。
- 网络和 OBD 失败必须可恢复，不能阻塞主线程。
- UI 改动需保留无数据 N/A 状态。
- 不在驾驶状态下进行实验。

## 提交协议日志

提交前删除：

- VIN
- 车牌
- 手机号
- 地理位置
- WiFi 名称和密码
- 公网 IP
- 账号和 token
- 未经授权的第三方客户端数据

只提交完成复现所需的最小日志。
