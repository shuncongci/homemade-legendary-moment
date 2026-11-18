# 桌面版使用的资源

桌面源码由本项目的本地工作台整理而来：保留照片、视频、对比和导出的公共链路，去掉未公开的色彩及需要另行清权的模型功能。Windows 和 macOS 共用这一份源码。原创部分按根目录 [LICENSE](../LICENSE) 提供。

## 色彩文件

直接读取现有 Android 目录中的文件，不复制一份到桌面目录，也不联网下载：

- `app/src/main/assets/looks/leica-instant-v2.bin` 与 `leica-n-instant.bin`：作者已公开的 PLUT0001 LUT。
- `app/src/main/assets/leica/leica-cool-epoch6.fp16.onnx` 与 `leica-warm-epoch6.fp16.onnx`：作者已公开的两份冷暖模型，桌面仅用 CPU ONNX Runtime 处理照片。

固定尺寸及 SHA-256 与根目录 [models.json](../models.json) 一致。第三方权重、私有色彩资源和保护加载实现没有加入桌面公开版。

## 运行依赖

Python、NumPy、Pillow、OpenCV、ONNX Runtime、psutil 由使用者安装，不随桌面源码分发。依赖版本见 `requirements.txt`；许可和原声明以所安装软件包为准。NumPy 和 psutil 使用 BSD 类许可，Pillow 使用 HPND，OpenCV 主项目使用 Apache-2.0，ONNX Runtime 使用 MIT。原生 wheel 中还可能有按各自条款发布的第三方库。

FFmpeg 是可选的本机命令，桌面目录不附带 FFmpeg 二进制或链接库。可选的 `imagecodecs` / `libultrahdr` 由使用者另行安装。如果要重新打包这些依赖，请按对应构建的实际许可证和声明核查再分发义务。手机端的说明见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。
