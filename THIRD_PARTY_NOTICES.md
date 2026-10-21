# 第三方说明

本项目原创部分使用根目录 LICENSE 中的个人非商业协议。下面的第三方材料保留各自许可，本项目协议不覆盖或限制它们已有的独立权利。这里列的是工程直接使用或适配的主要材料，不替代依赖包随附的完整声明。

Android 与构建依赖

AndroidX、Material Components for Android、Kotlin 与 kotlinx.coroutines 主要采用 Apache-2.0；coreLibraryDesugaring 使用的 desugar_jdk_libs 基于 OpenJDK，含 GPLv2 with Classpath Exception 及其他相应声明。JUnit 4.13.2 为 EPL-1.0，Hamcrest 为 BSD 类许可。具体传递依赖和版本应以 Gradle 实际解析结果及原包声明为准，测试和构建工具不等同全部随 APK 分发。

Gradle Wrapper 的 gradlew.bat 保留原作者版权和 Apache-2.0 头部，gradle-wrapper.jar 使用 Gradle 原许可。新增的简易 gradlew 是本项目启动脚本，不是上游完整的跨平台启动器。

ONNX Runtime Android 1.28.0 来自 Microsoft 的官方 Maven 包，采用 MIT。原许可和第三方声明保存在 app/src/main/assets/runtime-notices/ORT-1.28.0-LICENSE.txt 和 ORT-1.28.0-ThirdPartyNotices.txt。AAR 由构建工具下载，不随源码提供。

模型和适配

Depth Anything V2：作者团队 Depth Anything，仓库 https://github.com/DepthAnything/Depth-Anything-V2 。本项目使用 Small；上游对这一型号标注 Apache-2.0，其他型号许可可能不同。本地导出适配包括动态位置编码、动态高宽和 query 分组的精确全局注意力，仍保留全部 key。没有提供第三方权重。

BokehMe：作者 Juewen Peng 等，仓库 https://github.com/JuewenPeng/BokehMe 。仓库代码采用 Apache-2.0。导出只适配 ARNet RGB 输出，固定 gamma 为 0.3，不是完整 BokehMe 管线，不表示完成镜头物理标定。权重及训练数据仍应核对上游具体条件，没有提供权重。

GAIC：Copyright (c) 2022 bo-zhang，仓库 https://github.com/bo-zhang-cs/GAIC-Pytorch 。代码采用 MIT，许可全文保存在 app/src/main/assets/runtime-notices/GAIC-code-MIT-LICENSE.txt。tools/model_export/gaic_mobile_port.py 是对应结构与 RoI/RoD 采样的 PyTorch 移植，保留 MIT。Kotlin 侧预处理和裁框工作流是本项目实现。外链预训练检查点的独立再分发许可尚未确认，代码 MIT 不等于全部权重已获授权；没有提供权重。

SAM 2.1：Copyright (c) Meta Platforms, Inc. and affiliates. 仓库 https://github.com/facebookresearch/sam2 。上游声明代码和 SAM 2 检查点采用 Apache-2.0。本地适配为图像单正向点击、多候选输出的 FP32 ONNX，不包含视频记忆和 CUDA 后处理。没有提供权重或演示照片。

ViTMatte：作者 Jingfeng Yao、Xinggang Wang、Shusheng Yang、Baoyuan Wang。仓库 https://github.com/hustvl/ViTMatte ，模型卡 https://huggingface.co/hustvl/vitmatte-small-composition-1k 。所用模型卡标注 Apache-2.0；Transformers 实现由 Hugging Face 团队提供，采用 Apache-2.0。导出适配为动态高宽 FP32 ONNX，Android 侧另做 trimap 约束和大图分块。没有提供权重。

Apache-2.0 全文和原始 ViTMatte 模型卡保存在 app/src/main/assets/subject-licenses/。模型卡按上游原样保留，其中格式、插图链接和引用不是本项目新增宣传材料。各模型检查点与转换文件哈希见 models.json。

M9 epoch11 由作者提供，当前 ONNX 随源码保留，按根目录个人非商业协议使用。这里没有公开训练集，也没有因此对训练素材、模型结构或第三方权利作全面担保。

Python 工具

模型工具使用 PyTorch、torchvision、Transformers、ONNX、ONNX Runtime、NumPy、Hydra 和 onnxconverter-common；合成夹具工具还使用 Pillow、OpenCV。它们由使用者自行安装，不把环境和依赖源码打包到本目录。各自许可随其软件包，转换后发布时仍需检查相应声明。

新增的转换入口取代了旧开发机专用脚本，去掉了固定磁盘路径、照片验证输入和自动拷贝资产。它们不下载权重、不改 Android 哈希，不保证产生与历史记录完全相同的 ONNX 字节。
