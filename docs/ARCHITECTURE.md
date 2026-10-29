# 实现说明

这份说明介绍我发布的 2.8.3 源码中的处理链路、工作尺寸和已知限制，供阅读或修改代码时参考。

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

SubjectPointCache.kt 保存当前照片已完成的逐点 alpha。增加或移动点只计算缺失项，删除、重排及开关可复用已完成结果。部分任务失败时保留已完成点，但不会把未完成的一组标为就绪。换照片或清空会重置缓存。2.5.2 将显式选择变更与只读展示刷新分开：选择身份包含焦点与分析分辨率，选点集合也必须匹配；过时刷新不能裁掉新选择的缓存。每点先持久提交再对外宣告完成，恢复从已校验 blob 读取，而不是复用 UI 完成计数。EditSession.finishSubjects 只在当前 job、身份和选点集合仍一致且全点完成时条件提交；展示失败不会撤销已持久完成点。实际页面返回及进程恢复行为仍需真机确认。

多个 alpha 先取逐像素最大值，再由 SubjectSelectionMath.joinContacts 处理相触主体的内部接缝。这个步骤只提高局部 alpha，不改逐点缓存。它是启发式处理，细小真实空隙仍可能被误填，不能承诺所有背景缝和外轮廓都绝对不变。

边缘、取消和资源

SoftBokehEdges.kt、BackgroundPlate.kt、EdgeDecontamination.kt、SharpSubjectEdges.kt 等文件参与景深边缘处理，高光相关计算分布在 Highlight 与 Bokeh 开头的类中。修改这些算法时应同时看对应测试，不要只调某一张照片的常量。

Cancel、ActiveNativeRun 和 HardwareSession 管理取消与原生资源。旧请求不能覆盖新照片或新保护点结果；取消后也必须关闭 tensor、结果和 session。保持 CPU 精度策略及模型哈希检查，不要用静默降低分辨率来掩盖内存失败。

HDR、空间与恢复

HdrAutoSource 在 Android 14+ 检测增益图；HdrSource 当前只接收 SDR 基底 Ultra HDR JPEG，不把 HEIF、PQ、HLG 当作可互换输入。HdrMath、HdrRecipe、HdrResultStore 管理配对结果。未编辑的几何导出可使用来源增益图；调色/虚化后的结果通过 HdrPairExport 重算相对于实际 JPEG SDR 基底的增益图。UltraHdrContainer 写容器，HdrExportPolicy 校验编码回读尺寸和参数，检查失败不发布。HdrMemory 在各阶段检查预算；OOM、取消和 I/O 失败必须传播，不能伪装成 SDR 成功。原始文件编码、任意色域和全部手机 HDR 显示效果不在此契约中。

HdrPreview 只从具有真实增益图的来源或 HDR 成片 companion 加载显示内容，EXIF 方向、裁框和拷贝必须保留增益图，否则报错；它不是最终显影数据。CompareView 的 HDR 一侧禁止 SDR 原像素细节瓦片覆盖，深度/热力视图不冒充 HDR。窗口只在 RESUMED 且当前可见内容为 HDR 时请求 HDR，恢复后排队重查、暂停立即关闭请求。Android 14+ 与支持 HDR 的显示链路是实际显示前提，不支持的屏幕明确按 SDR 显示。

HdrDevelopMode 显式区分 HDR 与 SDR。HDR 输入并列两个显影按钮，导入/导出各占原权重一半；普通 SDR 与仅构图保持原布局。选择写入 renderKey、成片元数据和编辑器状态；切换只清中间渲染缓存，保留原片与上次完整成片。旧 HDR 会话未记录选择时默认 HDR，来源非 HDR 时恢复为 SDR，不恢复隐式 AUTO。HDR 显影/保存失败不自动转 SDR，SDR 显影不能复用 HDR 成片。

2.5.2 的非虚化 HDR 通过 HdrReconstruction.renderRows、HdrPixels / HdrFloatFile、HdrResultStore.readRows 交接无损 Float32 文件，来源采样使用有界行缓存，读取使用有界 64 KiB 缓冲；不是把整幅数组换成同尺寸 direct/mmap。增益计算读取实际 JPEG 基底的最多 16 行，压缩中间数据使用私有临时文件；裁框使用线性光行视图并保持原 Double 积分顺序。检查点的字节、校验、原子提交和所有权规则保留。文件交接增加缓存空间与读写，空间不足按 I/O 失败处理，不转成 SDR 成功。兼容 Frame 接口及完整 HDR 虚化仍有整幅分配，不代表全路径内存已经解决。

近原尺寸重建只复用少量精确来源行；LegendaryMath 预计算插值轴与权重，字节线性值精确查表，连续值回到原公式。FusionFir 保留原有限脉冲响应核和逐像素浮点累加顺序，用有界条带/行环替代整幅差值浮点图；光学辅助前缀也按条带复用。这些实现不改变模型/LUT、现有输出尺寸策略或 HDR 精度，不承诺完整虚化在任意设备都有同样收益。

DepthStageView 与 SpaceCamera、SpaceMesh、SpaceParallax 管理空间视图。SpaceFullscreen 临时复用同一视图而不复制照片、不启动新推理；SpaceGyro 仅在启用且前台有焦点时提供姿态，关闭/暂停时解除驱动。空间预览不改变导出成片。

EditSession 与 LatestCheckpoint 保存本机最近一张照片的状态。使用带校验的 schema 2 清单、不可变 blob、先数据后清单的原子提交；重叠 Activity 使用目录锁协调。取消或崩溃不得发布半成品，损坏记录不自动删除。恢复存储不是加密备份或历史相册；来源 URI 的读取权和模型资产仍须有效。

导入与通用查表

PhotoPixels 统一检查尺寸和系统解码结果；DngDimensions 读取主图元数据，拒绝仅解码出内嵌预览的 DNG。RegionPhoto 保留普通格式的区域解码，无法区域解码时按需使用全像素 bitmap；内存不足不会自动降分辨率。导入的可失败检查先于 EditSession 提交，提交后 UI 与持久来源在不可取消段内对齐。真实 DNG 兼容性仍取决于系统，不等于通用 RAW 开发器。

PureLut.kt 提供无 Android 依赖的通用三线性查表：PLUT0001、big-endian int32 网格边长、RGB float64 节点，红坐标变化最快；边长 2–65，节点须为有限 [0,1]，文件长度和 SHA-256 必须匹配。输入输出为编码 SDR sRGB 字节；输出尺寸不变，无自动曝光、模型、空间蒙版或细节融合。合成测试独立构造节点，不包含成品风格数据。公开版没有把这一通用模块接到冷暖路径，也不附带私有 LUT。

渲染检查点键包含版本、稳定风格 ID 与资源哈希。键变化只清除未完成渲染缓存，不偷偷覆盖或重新生成已保存成片。

测试范围

verifyLeicaMath 运行不依赖 Android 设备的 JUnit 测试。我随源码提供的 leica 测试数据由程序生成，沿用旧文件名仅为兼容测试引用；其中 expected 图不是神经网络推理结果。其余历史数值夹具原样保留。

公开 2.8.3（versionCode 81）通过 2350 项 JVM 测试、Release Kotlin 和 AndroidTest Kotlin 编译及未签名 Release 构建；原2325项保留，新增25项HDR提亮回归。AndroidTest仅编译，未运行手机；使用官方ONNX Runtime 1.28.0 AAR显式离线覆盖。另有监听按钮置顶静态门禁。公开版仍不包含私有风格的72项测试，不与私有包2422项混用；未操作用户照片，真实界面/相册/HDR亮度仍待真机验收。

## 2.6.0 外接、元数据与呈现

ExternalModeActivity / ExternalModeService 是独立的用户启动入口；ExternalArrivalGate 维护基线与稳定门禁，ExternalFolders 使用行 ID 游标、已知项跳过和单张定位，通知与闲置兜底共用定时器。ExternalQueueStore 是发布日志，不是历史相册待办；不会在重启后重排旧照片。ExternalPhotoInput、ExternalPhotoExport、ExternalOriginalGuard 和 ExternalSourceDeletion 固定来源/输出身份及指纹，成功发布并回读后才按用户策略尝试处置。

CaptureExif / CaptureMetadataContainer 按容器写入本张来源的拍摄 EXIF/GPS 并核验，不搬用样例信息。LeicaBadgeExport 仅对已编码 JPEG 注入候选 ExifIFD 0x88B0 标志；主 JPEG 和增益 JPEG 不重新编码，不保证厂家相册识别。

EditorPresentation / EditorLayout 用真实视图滑动与裁剪维护布局；DevelopView 在真实成片就绪后保持最后一帧并连续缩放/裁剪交接，不把 HDR 照片截图为 SDR 做转场。DiagnosticsPanel / LiveDiagnostics 提供指标卡片与按需详情；RuntimeMonitor 提供可选静音参数通知，编辑与外接偏好独立。

这些变化不加入私有风格路径，冷暖两份模型和其哈希保持不变。使用和权限边界见 [外接与编辑说明](EXTERNAL-MODE.md)。

## 2.8 同步补充

ExternalDeletionConsent 将删除授权版本与元数据完整性分开：旧授权回到保留原片，重新同意后以 verified 回读结果进入后续删除检查，而不是伪造 complete。ExternalMetadataTransfer 仍如实记录完整性，原片与成片指纹、发布、取消、系统授权及停止/重启保护保留。

同步 2.6.0 之后的仅新增输入稳定性、PNG 同步、JPEG/EXIF/XMP 与源增益归档兼容修复；相册选择器权限分流、忙碌选图保留、完整组/区域检查点、远焦 d² 映射和逐点主体约束，以及调色到虚化的连续呈现。检查点不是任意指令续算；性能取决于输入和设备，不承诺每场景更快。

## 2.8.1 手动编辑保存修复

手动编辑保存时，越界或截断的 Interop 目录不再阻断保存：保留边界检查，只跳过该目录的成片链接并提示用户。原图不改不删，原始元数据字节仍保存在经过回读核验的归档中；归档不保证其他相册可读取。其他目录、冲突、未知类型和真实 I/O、权限、取消仍严格处理。外接和删除策略不变，不改照片像素、调色、虚化、尺寸、精度或 HDR。

## 2.8.3 监听置顶与HDR提亮

外接模式“开始监听”“停止监听”移到标题下、说明与权限之前，仅移动原有控件；启停回调、就绪和权限检查、按钮可用状态、扫描/队列/原片处置不变。同步2.8.2的新HDR成片1.25倍线性RGB增益（约+0.32EV）：SDR底图、调色/虚化、尺寸、精度和质量不变；原始检查点不曝光，最终交接仅应用一次，裁剪/缩放/恢复/重复保存不叠加。须重新HDR显影，旧成片和仅构图不额外提亮；越界/取消/I/O仍失败，不截断或因提亮溢出转SDR。实际屏幕亮度不保证增加25%，本次不是恢复2.6.0的整套虚化观感，也不是对“第二次导出缺元数据”问题的修复。

布局静态回归：`python tools/check_external_controls.py`。
