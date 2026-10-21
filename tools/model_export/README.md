# 模型转换工具

我随源码提供这些模型转换脚本，供准备 App 所需的 ONNX 模型时参考。脚本不包含上游仓库或权重，不自动下载，不自动复制到 Android assets，也不修改源码中的模型哈希。

请使用 Python 3.11，在独立虚拟环境安装本目录的 requirements.txt，再按上游要求安装各仓库依赖。这里列出的是参考依赖版本，不是适用于所有系统的完整锁文件；SAM 的上游安装可能还需要额外依赖。

我已检查脚本语法、--help，以及缺少参数或权重时的拒绝行为。GAIC 已完成实际导出，并通过两组小尺寸合成输入的 PyTorch/ONNX 比较；其他大模型尚未用这些脚本重新完成全流程转换和数值检查。完整运行成功后，脚本才会写出对应的 export-report.json。没有报告不能视为转换验证通过。不同环境可能得到不同的 ONNX 哈希，脚本不会自动更新 App 中允许加载的模型哈希。

获取上游代码

Depth Anything V2 和 BokehMe 请按模型说明（../../docs/MODELS.md）记录的提交准备。SAM 2 需要支持 SAM 2.1 的上游代码和 configs/sam2.1/sam2.1_hiera_t.yaml 配置。我没有在清单中记录 SAM 仓库的精确提交，因此不承诺逐字节复现。ViTMatte 使用指定模型仓库中的 config.json 和 model.safetensors，传给脚本的路径必须是本地目录。

检查点请从各上游 README 或模型仓库获取。根目录 models.json 记录了我使用的原文件哈希，脚本会先核对。GAIC 的权重授权仍需确认，能用工具读取不代表可以再分发。

深度、虚化和构图

以下命令中的 /path/ 是占位路径，需要替换成你本机的实际路径。输出目录必须是新目录，不能指向 Android assets。参数 stage 分别为 depth、bokeh、gaic。

```bash
python tools/model_export/export_models.py depth --repo /path/Depth-Anything-V2 --checkpoint /path/depth_anything_v2_vits.pth --output-dir /path/exported-depth
python tools/model_export/export_models.py bokeh --repo /path/BokehMe --checkpoint /path/arnet.pth --output-dir /path/exported-bokeh
python tools/model_export/export_models.py gaic --checkpoint /path/GAIC-mobilenetv2-reddim16.pth --output-dir /path/exported-gaic
```

深度是 FP32、opset 17 和每组 64 个 query 的精确全局注意力。虚化先导出 FP32，再转换为内部 FP16、外部 FP32，并和上游 gamma=0.3 的 RGB 输出比较。GAIC 使用本目录 MIT 移植代码，保留其特定采样，不用标准 RoiAlign 替代。内置数值检查使用合成输入，不能替代真实照片和真机验证。

主体模型

```bash
python tools/export_subject.py sam --sam-repo /path/sam2 --checkpoint /path/sam2.1_hiera_tiny.pt --output-dir /path/exported-sam
python tools/export_subject.py matte --model-dir /path/vitmatte-small-composition-1k --output-dir /path/exported-matte
```

SAM 只支持一个正向点并输出三个候选，ViTMatte 为动态高宽、四通道输入。两者均保持 FP32。ViTMatte 的内置随机尺寸核验较小，尚需自行增加 Android 分块实际尺寸核验。SAM 导出需要较多内存，失败时不要把半成品当成可用模型。

使用输出

先检查报告、ONNX 输入输出和精度，再对实际照片做独立数值与设备验证。若哈希与记录一致，可按 docs/MODELS.md 放置；若不一致，不要删除运行时校验，应先审查原因，确认新模型后同时更新 models.json 和相应 Kotlin 常量。

我已随源码提供可用的 M9 ONNX，构建时不需要重新转换。这里不提供 M9 的训练工程、训练数据和检查点转换流程。
