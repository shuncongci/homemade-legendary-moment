# 构建

以下步骤用于构建我发布的源码版本。源码不包含我的签名密钥，也不附带第三方模型权重；模型准备方法见模型说明（MODELS.md）。

环境

JDK 17。

Android SDK Platform 35，Build Tools 35.0.0（Gradle 可提示安装所需组件）。

Android Gradle Plugin 8.7.3、Kotlin 2.0.21、Gradle 8.9，版本在工程中固定。

运行设备至少 Android 8.0（API 26）。

用 Android Studio 打开本目录，等待 Gradle 同步。也可设置 JAVA_HOME、ANDROID_HOME 后使用命令行。local.properties 是每台电脑自己的 SDK 路径，不应从别人的工程照抄，也不要提交。

仓库保留了原工程的镜像源与官方回退源；如果所在网络不适用，可在 settings.gradle.kts 调整仓库顺序。首次构建需要网络，依赖准备齐全后才可使用 --offline。

调试与测试

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:verifyLeicaMath
```

macOS/Linux 用 ./gradlew（首次执行 chmod +x gradlew）。标准测试入口 :app:testDebugUnitTest 也保留；verifyLeicaMath 直接用 JUnitCore 运行同一套断言，适合 Gradle 测试进程遇到路径问题的环境。

本次 2.5.1 公开树已通过 1302 项 JVM 测试，并构建 Debug、未签名 Release 与 AndroidTest APK；设备测试尚未运行。需要只编译设备诊断包时可执行 `:app:assembleDebugAndroidTest`，不要把生成测试 APK 当作测试已经执行。

默认输出为 app/build/outputs/apk/debug/app-debug.apk。Debug 使用你电脑上的 Android 调试密钥签名，不使用我的发布签名。如果与已安装版本的签名不同，不能直接覆盖安装。卸载可能丢失应用数据，处理签名冲突前请先确认备份和数据保留方式。

缺少第三方模型不会阻止编译，但调用相应功能会失败。模型准备步骤见 MODELS.md。不要把“编译成功”当作“完整推理已可用”。

Release

```powershell
.\gradlew.bat :app:assembleRelease
```

Release 开启 R8 和资源裁剪，默认不配置签名，输出未签名 APK。我不随源码提供签名材料，因此自行构建的安装包不能使用我的发布签名。

需要个人签名时，在本地配置自己的 keystore 或使用 Android Studio 的签名向导。密钥、密码、keystore.properties、.env 等都不要提交。配置自己的签名不会改变本项目“限个人非商业使用”的许可范围，也不会补齐第三方权重授权。

依赖

ONNX Runtime 使用 Maven Central 的 com.microsoft.onnxruntime:onnxruntime-android:1.28.0。源码没有自带 AAR，也不包括早期 QNN/双运行时试验包。

离线环境可先自行取得同版本官方 AAR，再显式指定：

```powershell
.\gradlew.bat --offline -PonnxruntimeAar="/your/path/onnxruntime-android-1.28.0.aar" :app:assembleDebug
```

该 AAR 的已核对 SHA-256 为 f351a0638696f54b35184290dbc001d66daae17281ad0b548d2c70347d53b8a9。本地覆盖是为离线构建预留的入口，不是更换运行时版本的兼容性保证。

合成测试数据

工程已附带运行测试所需的数据。leica/ 的旧照片夹具被替换为程序生成的 RGB 渐变、结构和色偏；Pillow 计算重采样参考，OpenCV 计算 LAB 和融合参考，不调用待测 Kotlin 算法生成期望值。

需要重建这一组数据时：

```bash
python -m pip install -r tools/requirements-fixtures.txt
python tools/generate_test_fixtures.py
```

保留的 legendary/ 和 highlight-v4/ 为历史小尺寸数值回归夹具。它们的输入已作独立合成特征检查，但这里没有收录完整的原始生成脚本；不要把这些参考数据当成真实照片效果或模型输出的证明。名称中的旧风格名仅为兼容历史测试资源引用。

PureLutTest 在内存中构造身份表、通道交换和多线性函数节点；不需要下载 LUT 或照片。DngDimensionsTest 使用合成 TIFF/DNG 元数据，PhotoImportCommitTest 检查提交边界；这些不能替代真实相机 DNG 和系统 codec 验证。AndroidTest 的 `raw-import` 诊断只创建合成 GIF/PNG/JPEG，不是 RAW 画质测试。

把验证输出放到工程外

通常不需要此参数。如果希望将构建产物和项目缓存放在源码目录外，可指定：

```powershell
.\gradlew.bat --project-cache-dir ../local-check/gradle-cache -PbuildOutputRoot="/absolute/path/to/local-check/build" :app:verifyLeicaMath
```

建议使用绝对路径；生成的 APK、日志和缓存不属于公开源码清单。.gitignore 已排除常规构建目录，但发布前仍应检查目录内容，不能仅依赖忽略规则保护凭据。
