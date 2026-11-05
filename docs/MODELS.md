# 模型与 LUT 资源

App 从本地 assets 读取资源，不在运行时下载。仓库随附两份冷暖 ONNX 与两份公开 LUT；第三方权重不附带，需按各自许可准备。下文保留历史转换接口和来源，当前是否必需按用途区分。

先根据用途准备，不必一次下载全部：

冷暖照片调色使用随附 ONNX；照片徕卡一瞬和两种视频调色使用随附 LUT，无需额外权重。当前分层景深虚化需要 Depth Anything V2 Small，不依赖 ARNet；ARNet 条目仅为保留的历史接口/诊断。智能构图需要 GAIC MobileNetV2 reddim16；主体保护还需要 SAM 2.1 Tiny 和 ViTMatte Small。

自有模型

两种独立调色模式，均为 epoch6 的 G_AB 方向：

| 模式 | 来源系列 | 工程文件 |
|---|---|---|
| 徕卡冷调 | M9/M10系列4.0 | leica-cool-epoch6.fp16.onnx |
| 徕卡暖调 | M9系列3.0 | leica-warm-epoch6.fp16.onnx |

两份原 checkpoint 都叫 cloud_epoch6.pth，但权重不同，不可混用。只随源码提供当前运行所需的两份 ONNX，不提供原 checkpoint、训练集或其他轮次。旧 epoch11 ONNX 已从当前文件树移除，不改写历史提交。

models.json 和 LeicaStyle.kt 保存两份模型的 SHA-256、来源 checkpoint 哈希及对应关系。不能只凭文件名替换或删除哈希检查。两份模型分别严格加载 G_AB，并通过32组桌面 PyTorch/ONNX FP32与FP16转换比较；另以当前Kotlin重采样/LAB融合完成8组桌面图片联调。转换一致性不是相机标定、审美评分或手机性能验收。

许可：个人非商业使用许可协议（../LICENSE）。模型架构中如涉及第三方材料，仍以对应声明为准。

内部 FP16、外部 FP32；输入 input、输出 output，NCHW RGB，数值约定为 [-1, 1]。Android 使用等比例长边 1024 的模型尺寸，随后与原图细节融合。

第三方模型

以下链接指向第三方项目的上游来源，不是我提供的转换权重下载页。上游 .pth、.pt、.safetensors 不能改后缀直接当 ONNX 用，一般模型库中的同名 ONNX 也不保证具有相同接口。

Depth Anything V2 Small

仓库：https://github.com/DepthAnything/Depth-Anything-V2

已使用代码提交：a561b849ebae10a6f5ef49e26c83cbbcd36c71bf。

权重仓库：https://huggingface.co/depth-anything/Depth-Anything-V2-Small

原检查点：depth_anything_v2_vits.pth。

工程文件：legendary-1.0-depth-native-chunk64.fp32.onnx。

许可：上游对 Small 模型标注 Apache-2.0，不能把这个结论套用到 Base/Large 等其他型号。

转换差异：动态高宽、14 倍数输入、FP32、位置编码动态缩放；全局注意力只按 query 分成 64 个一组，保留全部 key，不是窗口注意力。

接口：input 为 [1,3,H,W]，output 为 [1,H,W]。输入是 RGB ImageNet 归一化值，输出是相对深度，不是米制距离。

BokehMe ARNet

仓库：https://github.com/JuewenPeng/BokehMe

已使用提交：8b3ed556dc146207c9bb39fccd078a113f8d1bd8。

原权重入口：仓库 checkpoints/arnet.pth；不要用 iunet.pth 替代。

工程文件：legendary-1.0-arnet.fp16.onnx。

上游仓库代码许可证：Apache-2.0；具体检查点及相关数据权利应结合上游声明确认。

转换差异：取神经渲染器的 RGB 输出，固定上游 gamma 参数为 0.3，保留偶数尺寸重采样和像素重排；内部 FP16、外部 FP32。

接口：image 为 [1,3,H,W]，defocus 为 [1,1,H,W]，output 为 [1,3,H,W]。image 使用 [0,1]，defocus 使用 App 计算的有符号归一化离焦量。

GAIC MobileNetV2，reddim16

仓库：https://github.com/bo-zhang-cs/GAIC-Pytorch

已使用提交：877d6a325943c0a098324dc6d4a975ff4097c3f6。

原权重：按上游 README 的预训练模型链接获取 GAIC_pretrained_models.zip，选择其中的 GAIC-mobilenetv2-reddim16.pth。不在这里转存外链压缩包。

工程文件：gaic-mobilenetv2-reddim16.fp32.onnx。

代码为 MIT；现有检查点压缩包没有独立的权重许可说明，再分发授权仍待确认。不把代码 MIT 解释为所有权重、训练数据均已获授权。

转换差异：FP32，动态高宽和裁框数；保留 RoI/RoD 的 10×10 采样后 9×9 聚合、RoI +1、边缘外推和内部遮罩，不用标准 RoiAlign 冒充。

接口：image 为 [1,3,H,W]，boxes 为 [N,4] 的像素坐标 x1,y1,x2,y2，scores 为 [N]。输入 RGB ImageNet 归一化；App 分析长边上限 1220，与上游演示的预处理不同，不套用论文精度或速度。

SAM 2.1 Hiera Tiny

仓库：https://github.com/facebookresearch/sam2

权重入口：上游 README 的 SAM 2.1 Tiny 检查点 sam2.1_hiera_tiny.pt。

配置：configs/sam2.1/sam2.1_hiera_t.yaml。

工程文件：subject-sam2.1-tiny.fp32.onnx。

许可：上游声明代码和 SAM 2 检查点为 Apache-2.0。

转换差异：只导出图像和单个正向点击；保留三个候选及分数，不包含视频记忆。输入尺寸固定为 1024×1024，主体分析上限 2048 不代表 SAM 输入变成 2048。

接口：image 为 [1,3,1024,1024]，RGB ImageNet 归一化；point 为 [1,1,2] 的 1024 坐标系位置；输出 masks 为 [1,3,256,256] 的 logits、scores 为 [1,3]。

ViTMatte Small，Composition-1k

仓库：https://github.com/hustvl/ViTMatte

权重与配置：https://huggingface.co/hustvl/vitmatte-small-composition-1k

原权重：model.safetensors，同时需要对应配置文件。

工程文件：subject-vitmatte-small.fp32.onnx。

模型卡标注 Apache-2.0，副本保存在 app/src/main/assets/subject-licenses/。

转换差异：动态高宽，FP32，opset 17；接口 pixel_values 为 [1,4,H,W]，前三通道为归一化 RGB，第四通道为 trimap（0、128/255、1），输出 alpha 为 [1,1,H,W]。H/W 使用 32 倍数补边。

App 对大图只推理含未知边界的块并重叠融合，不是把整张 2048 图直接交给模型一次推理。

转换、放置和核对

models.json 记录两份随附 ONNX、五份未附带的第三方/历史 ONNX 及两份公开 LUT 的字节数、SHA-256和来源。把自行获得或转换的 ONNX 按清单中的文件名放置后运行：

```bash
python tools/check_models.py
# 只核对随源码带的模型：
python tools/check_models.py --bundled-only
```

SAM/ViTMatte 的转换入口见 tools/export_subject.py；其他三个模型的转换适配见 tools/model_export/。工具不自动下载权重，不会把输出自动写进 assets，也不会修改 Android 端的模型哈希。使用前先安装工具依赖，并取得上游代码和有权使用的检查点，参见转换工具说明（../tools/model_export/README.md）。

我已检查这些脚本的语法、命令行入口及缺少权重时的拒绝行为。GAIC 还通过了两组小尺寸合成输入的实际转换与 PyTorch/ONNX 比较。其余模型没有用这些整理后的脚本重新完成全流程转换。我不保证不同工具链导出的文件与清单逐字节相同。

导出工具链版本不同，即使输出数值相近，ONNX 的文件哈希也可能不同。本项目不提供一个可保证从任意上游版本重建相同字节的下载命令。若重新导出后哈希不同，先检查接口、数值和许可，再同步 models.json 和下列源码中的对应常量：

LeicaStyle.kt：冷调、暖调；LeicaEngine.kt：深度、ARNet。

GaicComposition.kt：GAIC。

SubjectProtection.kt：SAM、ViTMatte。

不要只改文件名、删除校验或降低精度来让模型“通过”。新导出的模型需要独立做 PyTorch/ONNX 一致性及真机检查，JVM 单元测试不负责验证模型推理。

## 两份公开 LUT（3.0.0）

| 风格 | 文件（相对 assets） | 字节数 | SHA-256 |
|---|---|---:|---|
| 徕卡一瞬 | `looks/leica-instant-v2.bin` | 6591012 | `87d1b46ae221b272227f845f2a7a984357b0d4056a79a2570b84c1271e3f9dcf` |
| 徕卡N瞬 | `looks/leica-n-instant.bin` | 6591012 | `72d3e0d901aba3560738486a2fa2139a44e53b966e5d0b35ac0ca33d6fb16ff9` |

徕卡一瞬同时用于照片和视频；N瞬只用于视频。这是作者此次指定公开的两种色彩资源，沿用现有个人非商业许可，不构成任何相机品牌官方授权，也不扩大第三方权利。

格式：`PLUT0001` + big-endian int32边长65 + RGB Float64节点，红坐标最快变化；节点均为有限[0,1]值。文件是明文公开资源，不是ONNX，也不是传奇专属LUT。消费端固定校验长度、头部和SHA；照片PureLut及视频原生消费者另检查节点范围。

`python tools/check_models.py --bundled-only` 同时检查两份模型和两份LUT。替换资源时不能只改名称/哈希来跳过数值验证；重新导出仍需核对色彩、精度、连续性及真机表现。
