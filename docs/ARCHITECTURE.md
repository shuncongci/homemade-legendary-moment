# 实现说明

这份说明描述 3.0.0 公开版。历史变更归入 CHANGELOG，当前执行结果见 VALIDATION；不包含传奇专属色彩实现。

主要代码在 app/src/main/java/xyz/shuncongci/leica。界面入口是 LeicaActivity.kt；LeicaEngine.kt 负责模型会话和处理链路。PhotoFlow.kt、PhotoWorkflow.kt 管理当前照片、处理方式和请求状态，界面操作不应直接绕过这些状态。

调色和景深

LeicaStyle.kt 定义三种照片风格的稳定 ID、路径和 SHA-256。冷暖两份 ONNX 按长边 1024 分析，由 LabFusion 融合原图细节；徕卡一瞬使用 PureLut 直接查表，不走 GAN 或细节融合。开启景深后，原图相对深度配合焦点、光圈及 FourTileBokeh 分层光学渲染；ARNet 只保留历史接口/诊断，不是当前基础虚化入口。深度不是米制距离，效果不代表某台相机的物理标定。

NativeDepthMath.kt 将深度分析长边限制到 1220，并按 14 的倍数补边。GaicComposition.kt 负责构图评分，分析长边同样上限 1220。GaicCompositionMath.kt 和 CompositionGeometry.kt 等文件处理候选裁框与坐标。应用已有成片的裁框只记录选择，保存时裁切，不再跑一次调色或虚化。

只有包含虚化的四种流程显示原图≤1250万、长边 2048 或 1024 的尺寸选择。PhotoWorkflow.allowsOutputSelection 与 output 统一控制选择器和输出策略；切换到非虚化时忽略但不清空先前的虚化档位。

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

HdrDevelopMode 显式区分 HDR 与 SDR。HDR 输入通过 SDR/HDR 分段开关选择下次显影；普通 SDR 与仅构图保持对应操作布局。选择写入 renderKey、成片元数据和编辑器状态；切换只清中间渲染缓存，保留原片与上次完整成片。旧 HDR 会话未记录选择时默认 HDR，来源非 HDR 时恢复为 SDR，不恢复隐式 AUTO。HDR 显影/保存失败不自动转 SDR，SDR 显影不能复用 HDR 成片。

2.5.2 的非虚化 HDR 通过 HdrReconstruction.renderRows、HdrPixels / HdrFloatFile、HdrResultStore.readRows 交接无损 Float32 文件，来源采样使用有界行缓存，读取使用有界 64 KiB 缓冲；不是把整幅数组换成同尺寸 direct/mmap。增益计算读取实际 JPEG 基底的最多 16 行，压缩中间数据使用私有临时文件；裁框使用线性光行视图并保持原 Double 积分顺序。检查点的字节、校验、原子提交和所有权规则保留。文件交接增加缓存空间与读写，空间不足按 I/O 失败处理，不转成 SDR 成功。兼容 Frame 接口及完整 HDR 虚化仍有整幅分配，不代表全路径内存已经解决。

近原尺寸重建只复用少量精确来源行；LegendaryMath 预计算插值轴与权重，字节线性值精确查表，连续值回到原公式。FusionFir 保留原有限脉冲响应核和逐像素浮点累加顺序，用有界条带/行环替代整幅差值浮点图；光学辅助前缀也按条带复用。这些实现不改变模型/LUT、现有输出尺寸策略或 HDR 精度，不承诺完整虚化在任意设备都有同样收益。

DepthStageView 与 SpaceCamera、SpaceMesh、SpaceParallax 管理空间视图。SpaceFullscreen 临时复用同一视图而不复制照片、不启动新推理；SpaceGyro 仅在启用且前台有焦点时提供姿态，关闭/暂停时解除驱动。空间预览不改变导出成片。

EditSession 与 LatestCheckpoint 保存本机最近一张照片的状态。使用带校验的 schema 2 清单、不可变 blob、先数据后清单的原子提交；重叠 Activity 使用目录锁协调。取消或崩溃不得发布半成品，损坏记录不自动删除。恢复存储不是加密备份或历史相册；来源 URI 的读取权和模型资产仍须有效。

导入与通用查表

PhotoPixels 统一检查尺寸和系统解码结果；DngDimensions 读取主图元数据，拒绝仅解码出内嵌预览的 DNG。RegionPhoto 保留普通格式的区域解码，无法区域解码时按需使用全像素 bitmap；内存不足不会自动降分辨率。导入的可失败检查先于 EditSession 提交，提交后 UI 与持久来源在不可取消段内对齐。真实 DNG 兼容性仍取决于系统，不等于通用 RAW 开发器。

PureLut.kt 提供无 Android 依赖的通用三线性查表：PLUT0001、big-endian int32 网格边长、RGB float64 节点，红坐标变化最快；边长 2–65，节点须为有限 [0,1]，文件长度和 SHA-256 必须匹配。输入输出为编码 SDR sRGB 字节；输出尺寸不变，无自动曝光、模型、空间蒙版或细节融合。合成测试独立构造节点，不包含成品风格数据。公开版只把它接到照片徕卡一瞬，不改变冷暖模型路径；两份公开 LUT 为明文资源，不附传奇专属 LUT。

渲染检查点键包含版本、稳定风格 ID 与资源哈希。键变化只清除未完成渲染缓存，不偷偷覆盖或重新生成已保存成片。

## 视频链路

VideoActivity 管理导入、轨道确认、暂停首帧、播放和导出；VideoTaskGate 拒绝陈旧任务交接。VideoContract 为两种视频色彩记录资源路径和哈希，VideoLutNative / ColorLutFiles 从普通 assets 验证读取，不依赖私有加载器。

CMake 构建 `videolut`，通过 JNI 注册 create/render/close；video_math.h 完成 LUT、sRGB/Rec.709/Rec.2020 与 PQ/HLG 转换及 YUV/P010 行处理。VideoFrameWorkers 按偶数行分割同一帧，最多 8 线程并行；帧序、PTS 和编码交接仍串行。HDR 使用有符号高光/宽色域残差扩展，不做逐帧自动曝光或静默 SDR 回退。

VideoTracks / VideoIso 解析实际轨道、样本与编辑列表；多独立画面需要明确选择，不把相同系统 ID 当作基础层身份的充分证明。VideoDolby / VideoBitstream 仅放行可核验的 Profile 8 兼容基础层，去掉旧杜比 RPU/配置后输出普通 HEVC，不生成新杜比动态元数据。

VideoMuxTiming / VideoMuxExact 绑定编码样本、顺序、PTS 与末帧时长；音轨 mdhd 头时长差异可告警继续，但实际样本、时间戳、合法编辑、边界和最终回读仍受保护。VideoMetadata 的可选字段失败记录告警，不掩盖编解码/权限/发布失败。VideoExportCommit 在完整文件写入、同步与 SHA 回读后发布；失败清理本次半成品，原片和上次完整成片不被替换。

## 工作台、外接与隐私

EditorWorkspacePolicy、WorkspacePanels、FullscreenOverlay 等负责覆盖面板、真实入口几何、返回/生命周期及减少动画策略，不用 SDR 截图冒充 HDR 成片。ExternalModeActivity / ExternalModeService 由用户主动开启，ExternalArrivalGate 建立基线并等待新片稳定；停止/重启不补历史。

ExternalDeletionConsent 的危险确认与元数据完整性分开记录。明确同意后可尝试处置附加数据未完整转存的原片，但仍必须满足成片发布、回读、源/成片指纹和系统授权；默认保留，不补删旧照片。用户操作和设备验收见 EXTERNAL-MODE / RELEASE。

最近照片检查点、处理元数据和导出可能包含私人像素或原片 GPS；它们不是公开仓库内容，不应上传。`LegendaryMath` 等历史通用名称及合成夹具不等于私有“传奇一瞬”色彩实现。当前公开枚举没有该风格项。

## 测试边界

verifyLeicaMath 运行登记的 JVM 测试，AndroidTest 需另行在设备执行。合成结构、桌面原生调用、编译和构建不能代替 Android MediaCodec、显示、系统授权、相册、50MP 或长片音画同步验收。当前执行证据单独维护在 [VALIDATION.md](VALIDATION.md)。
