# ScrcpyLite

面向 2015-2016 年代老安卓设备（如华为 MediaPad T2, Android 6.0）的极简 scrcpy 客户端。
目标是把「投屏画面 + 触控/按键回控」这一核心功能做到**最流畅**。

## 与 ScrcpyForAndroid 的区别

| | ScrcpyForAndroid | ScrcpyLite |
|---|---|---|
| UI 框架 | Jetpack Compose 1.12 + Material3 + miuix | 经典 View + SurfaceView |
| 依赖 | 数十个 androidx/third-party | **零第三方依赖**（仅 Kotlin 标准库）|
| 视频渲染 | Compose 里的播放组件 | **MediaCodec 硬解 → SurfaceView 直出**（零拷贝 GPU 合成路径）|
| minSdk | 23（移植版）| 23 |
| 功能 | 全功能 | 仅核心：投屏 + 触控 + 滚动 + 返回/桌面/多任务/旋转 |

在 msm8916 这类老芯片上，Compose 每帧绘制开销巨大（实测掉帧率 78%）；
本应用走传统硬件合成路径，是 2015 年本地视频播放器同款方案。

## 协议实现（自研，参考 Genymobile/scrcpy v2.7 服务端源码）

- 应用内 ADB 客户端: CNXN/AUTH(RSA-2048 签名 + Android 公钥格式)/OPEN/WRTE/OKAY/CLSE 多路复用
- scrcpy-server v2.7: `exec:` 通道推送 (`cat >`) + `app_process` 启动, `localabstract:scrcpy_<scid>` 双流
- 视频流: 12 字节帧头 (u64 pts|flags + u32 len), config 包 → CODEC_CONFIG
- 控制流: 触控 (type=2)/滚动 (type=3)/按键 (type=0)/返回或亮屏 (type=4)/旋转 (type=11), 大端序

## 使用

1. 被控设备开启网络 ADB（无线调试或 `adb tcpip 5555`）
2. 平板打开 ScrcpyLite → 输入 IP:端口 → 连接并投屏
3. 首次连接在被控设备上允许调试授权弹窗
4. 双指拖动 = 滚动; 底部悬浮键 = 返回/桌面/多任务/旋转/退出; 音量键映射到被控设备

## 构建

GitHub Actions 自动构建签名 APK（无密钥时自动生成临时 keystore），或本地:

```
gradle :app:assembleRelease
```
