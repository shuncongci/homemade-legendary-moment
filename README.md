# 传奇一瞬

在自己的设备上处理照片和视频。Android 版是原生应用；Windows 和 macOS 版在浏览器里打开本机工作台。显影会生成新文件，不覆盖原片。

## Android

仓库中的手机源码是 **3.0.0（104）**，最低支持 Android 8.0。照片可用徕卡一瞬、冷调、暖调，视频可用徕卡一瞬、N 瞬。景深、构图和主体保护要另行准备模型，见[模型清单](docs/MODELS.md)。

按 [Android 构建指南](docs/BUILDING.md)准备 JDK 和 Android SDK。在仓库根目录运行 `./gradlew :app:assembleDebug`（Windows 用 `gradlew.bat :app:assembleDebug`）。自行构建的 APK 与私有成品签名不同，不能直接覆盖安装；不要为此卸载原应用。

使用说明：[照片与外接](docs/EXTERNAL-MODE.md) · [视频与 HDR](docs/VIDEO.md) · [版本记录](docs/CHANGELOG.md)

## Windows 与 macOS

适用于 Windows x64 和 Apple 芯片 Mac。先[下载整个仓库](https://github.com/shuncongci/homemade-legendary-moment/archive/refs/heads/main.zip)，再按[桌面启动说明](desktop/README.md)准备 Python 3.12 环境。启动后打开 `http://127.0.0.1:8876/`，导入素材、挑选色彩、点「显影」，对比后导出。

桌面公开版用 CPU，不需要 NVIDIA 显卡，也不需要编译 Android 应用。准备好依赖即可离线运行。视频会重新编码；如需保留音轨，按桌面说明配置 FFmpeg。本仓库不附桌面安装包。

## 目录与许可

- `app/`：Android 应用，Gradle 入口仍在仓库根目录。
- `desktop/`：Windows 和 macOS 共用的桌面源码。

本仓库沿用[个人非商业使用许可](LICENSE)，是源码公开版，不是 OSI 定义的开源项目。项目名「传奇一瞬」不表示同名私有色彩已公开；这里不包含该私有色彩、未公开权重或签名材料。第三方材料按各自许可使用，见 [Android 声明](THIRD_PARTY_NOTICES.md)和[桌面资源说明](desktop/THIRD_PARTY.md)。项目与所提到的相机品牌无官方关系。
