# 实现说明

这份说明介绍我发布的 2.5.1 源码中的处理链路、工作尺寸和已知限制，供阅读或修改代码时参考。

主要代码在 app/src/main/java/xyz/shuncongci/leica。界面入口是 LeicaActivity.kt；LeicaEngine.kt 负责模型会话和处理链路。PhotoFlow.kt、PhotoWorkflow.kt 管理当前照片、处理方式和请求状态，界面操作不应直接绕过这些状态。

调色和景深

LeicaStyle.kt 定义冷调与暖调的稳定 ID、显示名称、ONNX 路径及 SHA-256。两份模型均输入等比例长边 1024 的 RGB，输出低频色彩。LabFusion.kt 把它与原图细节融合。开启景深时，先从原图获得相对深度，再结合焦点、光圈和 ARNet 渲染；深度不是米制距离，效果也不是某台相机镜头的物理标定。

NativeDepthMath.kt 将深度分析长边限制到 1220，并按 14 的倍数补边。GaicComposition.kt 负责构图评分，分析长边同样上限 1220。GaicCompositionMath.kt 和 CompositionGeometry.kt 等文件处理候选裁框与坐标。应用已有成片的裁框只记录选择，保存时裁切，不再跑一次调色或虚化。

只有包含虚化的四种流程显示原图像素、长边 2048 或 1024 的尺寸选择。PhotoWorkflow.allowsOutputSelection 与 output 统一控制选择器和输出策略；切换到非虚化时忽略但不清空先前的虚化档位。

非虚化使用 LeicaSizes.colorSize，保留来源像素到 50,000,000 像素，超过上限才等比缩小，小图不放大。调色仍按所选模式执行，GAN 分析长边仍固定为 1024；原图像素不表示所有模型都按原图尺寸推理。不要把模型工作尺寸、界面预览和最终输出混为一谈。

显式重生成调色时，先把完整场景限制在 5000 万像素内，再应用裁框；仅构图或导出已有结果时按裁后可用像素和 5000 万上限计划，不秘密重跑调色或虚化，也不放大已有低分辨率结果。SDR 主链路仍为 8 位 RGB，导出编码由 PhotoExport、ExportFormat 和 ExportCommit 管理；JPEG/WebP 是重新编码，不代表原文件无损保留。HDR 另走下述受限配对路径。

PhotoFlow.parametersExpanded 管理参数展开状态。generated 只在真实结果呈现并提交后收起，失败、取消和单纯预览不收起；photoLoaded 重新展开。进入构图时临时展开，应用后收起，取消/返回恢复进入前状态。应用构图仍只记录裁框，不自动保存。

PhotoFlow.style 管理当前选择；PhotoWorkflow.allowsColorSelection 控制风格按钮可见性。选择保存为稳定 ID，不使用枚举序号。切换不会自动推理，生成/保存等忙碌期间禁止切换；旧结果保留，参数标记待重新显影。生成捕获不可变风格快照，CompositionAppearance 保存结果实际使用的风格。切换不清空深度或主体缓存，保存旧结果不重跑模型。每次生成只加载选中的模型，模型按 SHA 校验并在推理后释放。

主体保护

此功能默认关闭。SubjectProtection.kt 对每个保护点分别运行 SAM 和 ViTMatte。SubjectResolution 提供 2048、1220、720 三种分析长边，默认 2048，小图不放大。SAM 的输入仍固定为 1024×1024，输出 256×256 的三个候选 logits 及分数；选中的结果再放到主体分析尺寸。

SubjectProtectionMath.kt 生成 trimap。半径随分析尺寸缩放，1220 长边时为 5，2048 长边时为 8。已知前景和背景最后被约束为 1 和 0，ViTMatte 估计未知边界。

SubjectMatteTiles.kt 对补边后像素数不超过 1248×928 的图使用整图推理。更大的图使用最大 1024×1024 的块，起点间距不超过 768，内部边缘使用 96 像素羽化；只运行含未知边界的块。分块节省峰值内存，但不保证与任意整图推理结果完全一致。

SubjectPointCache.kt 保存当前照片已完成的逐点 alpha。增加或移动点只计算缺失项，删除、重排及开关可复用已完成结果。部分任务失败时保留已完成点，但不会把未完成的一组标为就绪。换照片或清空会重置缓存。

多个 alpha 先取逐像素最大值，再由 SubjectSelectionMath.joinContacts 处理相触主体的内部接缝。这个步骤只提高局部 alpha，不改逐点缓存。它是启发式处理，细小真实空隙仍可能被误填，不能承诺所有背景缝和外轮廓都绝对不变。

边缘、取消和资源

SoftBokehEdges.kt、BackgroundPlate.kt、EdgeDecontamination.kt、SharpSubjectEdges.kt 等文件参与景深边缘处理，高光相关计算分布在 Highlight 与 Bokeh 开头的类中。修改这些算法时应同时看对应测试，不要只调某一张照片的常量。

Cancel、ActiveNativeRun 和 HardwareSession 管理取消与原生资源。旧请求不能覆盖新照片或新保护点结果；取消后也必须关闭 tensor、结果和 session。保持 CPU 精度策略及模型哈希检查，不要用静默降低分辨率来掩盖内存失败。

HDR、空间与恢复

HdrAutoSource 在 Android 14+ 检测增益图；HdrSource 当前只接收 SDR 基底 Ultra HDR JPEG，不把 HEIF、PQ、HLG 当作可互换输入。HdrMath、HdrRecipe、HdrResultStore 管理配对结果。未编辑的几何导出可使用来源增益图；调色/虚化后的结果通过 HdrPairExport 重算相对于实际 JPEG SDR 基底的增益图。UltraHdrContainer 写容器，HdrExportPolicy 校验编码回读尺寸和参数，检查失败不发布。HdrMemory 在各阶段检查预算；OOM、取消和 I/O 失败必须传播，不能伪装成 SDR 成功。原始文件编码、任意色域和全部手机 HDR 显示效果不在此契约中。

DepthStageView 与 SpaceCamera、SpaceMesh、SpaceParallax 管理空间视图。SpaceFullscreen 临时复用同一视图而不复制照片、不启动新推理；SpaceGyro 仅在启用且前台有焦点时提供姿态，关闭/暂停时解除驱动。空间预览不改变导出成片。

EditSession 与 LatestCheckpoint 保存本机最近一张照片的状态。使用带校验的 schema 2 清单、不可变 blob、先数据后清单的原子提交；重叠 Activity 使用目录锁协调。取消或崩溃不得发布半成品，损坏记录不自动删除。恢复存储不是加密备份或历史相册；来源 URI 的读取权和模型资产仍须有效。

导入与通用查表

PhotoPixels 统一检查尺寸和系统解码结果；DngDimensions 读取主图元数据，拒绝仅解码出内嵌预览的 DNG。RegionPhoto 保留普通格式的区域解码，无法区域解码时按需使用全像素 bitmap；内存不足不会自动降分辨率。导入的可失败检查先于 EditSession 提交，提交后 UI 与持久来源在不可取消段内对齐。真实 DNG 兼容性仍取决于系统，不等于通用 RAW 开发器。

PureLut.kt 提供无 Android 依赖的通用三线性查表：PLUT0001、big-endian int32 网格边长、RGB float64 节点，红坐标变化最快；边长 2–65，节点须为有限 [0,1]，文件长度和 SHA-256 必须匹配。输入输出为编码 SDR sRGB 字节；输出尺寸不变，无自动曝光、模型、空间蒙版或细节融合。合成测试独立构造节点，不包含成品风格数据。公开版没有把这一通用模块接到冷暖路径，也不附带私有 LUT。

渲染检查点键包含版本、稳定风格 ID 与资源哈希。键变化只清除未完成渲染缓存，不偷偷覆盖或重新生成已保存成片。

测试范围

verifyLeicaMath 运行不依赖 Android 设备的 JUnit 测试。我随源码提供的 leica 测试数据由程序生成，沿用旧文件名仅为兼容测试引用；其中 expected 图不是神经网络推理结果。其余历史数值夹具原样保留。

这个源码版本的 1302 项 JVM 测试已通过，覆盖数值算法、冷暖模式、尺寸和参数状态、SDR 导出策略、HDR 数学和发布边界、空间相机/网格以及检查点提交/损坏处理。原有合成夹具未替换，随附冷暖模型和哈希不变，本轮没有重新导出模型。Debug、未签名 Release、AndroidTest APK 已构建；AndroidTest 只完成编译打包，没有在设备运行。Android 界面、实际编解码/相册发布、显示、真实进程恢复、陀螺仪、峰值内存与耗时仍需另行验收。
