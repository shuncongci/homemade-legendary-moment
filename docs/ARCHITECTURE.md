# 实现说明

这份说明介绍我发布的 2.1.24 源码中的处理链路、工作尺寸和已知限制，供阅读或修改代码时参考。

主要代码在 app/src/main/java/xyz/shuncongci/leica。界面入口是 LeicaActivity.kt；LeicaEngine.kt 负责模型会话和处理链路。PhotoFlow.kt、PhotoWorkflow.kt 管理当前照片、处理方式和请求状态，界面操作不应直接绕过这些状态。

调色和景深

LeicaStyle.kt 定义冷调与暖调的稳定 ID、显示名称、ONNX 路径及 SHA-256。两份模型均输入等比例长边 1024 的 RGB，输出低频色彩。LabFusion.kt 把它与原图细节融合。开启景深时，先从原图获得相对深度，再结合焦点、光圈和 ARNet 渲染；深度不是米制距离，效果也不是某台相机镜头的物理标定。

NativeDepthMath.kt 将深度分析长边限制到 1220，并按 14 的倍数补边。GaicComposition.kt 负责构图评分，分析长边同样上限 1220。GaicCompositionMath.kt 和 CompositionGeometry.kt 等文件处理候选裁框与坐标。应用已有成片的裁框只记录选择，保存时裁切，不再跑一次调色或虚化。

只有包含虚化的四种流程显示原图像素、长边 2048 或 1024 的尺寸选择。PhotoWorkflow.allowsOutputSelection 与 output 统一控制选择器和输出策略；切换到非虚化时忽略但不清空先前的虚化档位。

非虚化使用 LeicaSizes.colorSize，保留来源像素到 50,000,000 像素，超过上限才等比缩小，小图不放大。调色仍按所选模式执行，GAN 分析长边仍固定为 1024；原图像素不表示所有模型都按原图尺寸推理。不要把模型工作尺寸、界面预览和最终输出混为一谈。

显式重生成调色时，先把完整场景限制在 5000 万像素内，再应用裁框；仅构图或导出已有结果时按裁后可用像素和 5000 万上限计划，不秘密重跑调色或虚化，也不放大已有低分辨率结果。输出仍使用现有 8 位 PNG 链路，本次不增加 HDR、高位深或广色域保留能力。

PhotoFlow.parametersExpanded 管理参数展开状态。generated 只在真实结果呈现并提交后收起，失败、取消和单纯预览不收起；photoLoaded 重新展开。进入构图时临时展开，应用后收起，取消/返回恢复进入前状态。应用构图仍只记录裁框，不自动保存。

PhotoFlow.style 管理当前选择；PhotoWorkflow.allowsColorSelection 控制风格按钮可见性。选择保存为稳定 ID，不使用枚举序号。切换不会自动推理，生成/保存等忙碌期间禁止切换；旧结果保留，参数标记待重新显影。生成捕获不可变风格快照，CompositionAppearance 保存结果实际使用的风格。切换不清空深度或主体缓存，保存旧结果不重跑模型。每次生成只加载选中的模型，模型按 SHA 校验并在推理后释放。

主体保护

此功能默认关闭。SubjectProtection.kt 对每个保护点分别运行 SAM 和 ViTMatte。主体分析长边上限为 2048，小图不放大。SAM 的输入仍固定为 1024×1024，输出 256×256 的三个候选 logits 及分数；选中的结果再放到主体分析尺寸。

SubjectProtectionMath.kt 生成 trimap。半径随分析尺寸缩放，1220 长边时为 5，2048 长边时为 8。已知前景和背景最后被约束为 1 和 0，ViTMatte 估计未知边界。

SubjectMatteTiles.kt 对补边后像素数不超过 1248×928 的图使用整图推理。更大的图使用最大 1024×1024 的块，起点间距不超过 768，内部边缘使用 96 像素羽化；只运行含未知边界的块。分块节省峰值内存，但不保证与任意整图推理结果完全一致。

SubjectPointCache.kt 保存当前照片已完成的逐点 alpha。增加或移动点只计算缺失项，删除、重排及开关可复用已完成结果。部分任务失败时保留已完成点，但不会把未完成的一组标为就绪。换照片或清空会重置缓存。

多个 alpha 先取逐像素最大值，再由 SubjectSelectionMath.joinContacts 处理相触主体的内部接缝。这个步骤只提高局部 alpha，不改逐点缓存。它是启发式处理，细小真实空隙仍可能被误填，不能承诺所有背景缝和外轮廓都绝对不变。

边缘、取消和资源

SoftBokehEdges.kt、BackgroundPlate.kt、EdgeDecontamination.kt、SharpSubjectEdges.kt 等文件参与景深边缘处理，高光相关计算分布在 Highlight 与 Bokeh 开头的类中。修改这些算法时应同时看对应测试，不要只调某一张照片的常量。

Cancel、ActiveNativeRun 和 HardwareSession 管理取消与原生资源。旧请求不能覆盖新照片或新保护点结果；取消后也必须关闭 tensor、结果和 session。保持 CPU 精度策略及模型哈希检查，不要用静默降低分辨率来掩盖内存失败。

测试范围

verifyLeicaMath 运行不依赖 Android 设备的 JUnit 测试。我随源码提供的 leica 测试数据由程序生成，沿用旧文件名仅为兼容测试引用；其中 expected 图不是神经网络推理结果。其余历史数值夹具原样保留。

这个源码版本包含 887 项 JVM 测试；ColorStyleTest 新增23项，覆盖模式ID、模型对应、切换、忙碌拒绝、取消失败、缓存和结果快照。PhotoOutputPolicyTest 的26项测试继续保留，覆盖尺寸选择可见性策略、5000 万像素上限、小图不放大、PNG 原像素导出和参数收起状态。测试结果只覆盖这些输入和检查项；不等于 Android 界面或 5000 万像素真机推理验收。我没有对这个源码版本重新做真机运行验证，只重新转换核验了两份自有调色模型，没有重新导出其他第三方模型。真机内存、速度、多点交互、取消、保存及实际照片效果需要另行检查。
