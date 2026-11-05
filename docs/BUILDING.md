# 构建指南 · 3.0.0 公开版

## 环境

| 组件 | 版本 |
|---|---|
| JDK | 17（较新兼容 JDK 以实际工具链验证为准） |
| Android SDK / targetSdk | 35 |
| 最低 Android | API 26 |
| Android Gradle Plugin / Kotlin / Gradle | 8.7.3 / 2.0.21 / 8.9 |
| NDK | 27.2.12479018 |
| CMake | 3.22.1 |

在 Android Studio 的 SDK Manager 安装平台、Build Tools、NDK 和 CMake。SDK 路径放入本地 `local.properties`，或设置 `ANDROID_HOME`；不要提交个人路径文件。仓库保留镜像与官方依赖源，按网络情况调整顺序。首次构建需要网络，缓存和 SDK 完整后才能离线构建。

## 常用命令

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:verifyLeicaMath
.\gradlew.bat :app:compileReleaseKotlin :app:compileDebugAndroidTestKotlin :app:assembleRelease
python tools/check_models.py --bundled-only
python tools/check_public_source.py
```

macOS / Linux 使用 `./gradlew`，首次运行 `chmod +x gradlew`。标准 `:app:testDebugUnitTest` 仍可使用；`verifyLeicaMath` 用 JUnitCore 运行登记的测试类，适合测试 worker 遇到路径问题的环境。设备测试源码仅编译不代表已经在手机执行，实际验证见 [验证状态](VALIDATION.md)。

默认输出：`app/build/outputs/apk/debug/app-debug.apk` 和 `app/build/outputs/apk/release/app-release-unsigned.apk`。Debug 使用本机调试签名；Release 开启 R8/资源裁剪，**默认未配置发布签名**。

公开版与私有 V3 使用同一应用 ID `xyz.shuncongci.leica.v3`，不同签名不能直接覆盖。需要并存时，在自己的本地构建配置中选择独立应用 ID；不要直接卸载丢失数据。个人签名应使用自己的 keystore，密钥、密码、`.env` 不得提交，也不改变个人非商业许可范围。

## 资源与依赖

随仓库提供两份冷暖 ONNX 和两份公开 LUT。纯 LUT 照片调色、视频调色无需第三方权重。景深需要深度模型；构图和主体保护分别还需 GAIC、SAM/ViTMatte。ARNet 仅保留给历史接口/诊断，不是当前分层虚化的必需权重。详见 [模型与 LUT](MODELS.md)。缺权重仍可编译，不代表相应功能能运行。

ONNX Runtime 默认从 Maven 取得 `com.microsoft.onnxruntime:onnxruntime-android:1.28.0`。不随源码附带 AAR，离线环境可显式覆盖同一版本：

```powershell
.\gradlew.bat --offline -PonnxruntimeAar="/absolute/path/onnxruntime-android-1.28.0.aar" :app:assembleRelease
```

已登记官方 AAR 的 SHA-256：`f351a0638696f54b35184290dbc001d66daae17281ad0b548d2c70347d53b8a9`。本地覆盖不是替换运行时版本的兼容性保证。

## Windows 原生工具路径

本版本新增普通 C++ 视频 LUT 库，不使用私有构建/保护脚本。若 Windows CMake 遇到非 ASCII 路径问题，可在本机为**同一份目录**建立 ASCII 路径联接，将 SDK、项目、原生 staging、构建输出和 TEMP/TMP 置于可用的 ASCII 路径；不要复制生产工程或修改共享 SDK。可选 Gradle 参数：

- `-PnativeSourceDir=/absolute/ascii/path/to/cpp`：CMake 源目录，默认 `app/src/main/cpp`。
- `-PnativeBuildStagingDir=/absolute/ascii/path/to/cxx`：原生 staging。
- `-PbuildOutputRoot=/absolute/path/to/build`：隔离构建输出。
- `--project-cache-dir /absolute/path/to/cache`：隔离项目缓存。

这些是本机路径参数，不应把某位维护者的机器路径写入仓库。CMake、NDK 和编译器仍使用上表版本；不以禁用原生构建或删除功能让检查通过。

## 测试数据

`leica/` RGB 测试资源由程序生成，Pillow/OpenCV 提供重采样和 LAB 参考，不是私人样片或模型质量证明。必要时使用 `tools/generate_test_fixtures.py` 重建，并检查所有差异。历史 `legendary/`、`highlight-v4/`、`exact-radiance*` 是已公开的小尺寸数值回归资源，名称不代表私有色彩功能。它们不是重新训练数据，也不是手机效果验收。

新增公开 LUT 测试核对实际资源、节点范围、哈希、破损/取消与照片尺寸/源像素保护；通用 `PureLutTest` 仍使用独立合成节点。视频时间戳、轨道、杜比兼容门禁、音频头时长兼容与导出提交测试使用合成结构/回调，不删除真实照片。不要改旧期望或删测试来掩盖真实失败。

历史测试数字归入 [版本记录](CHANGELOG.md)，不再混入本构建指南的当前状态。
