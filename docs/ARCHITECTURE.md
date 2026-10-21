# 实现说明

这份说明介绍我发布的 2.1.22 源码中的处理链路、工作尺寸和已知限制，供阅读或修改代码时参考。

主要代码在 app/src/main/java/xyz/shuncongci/leica。界面入口是 LeicaActivity.kt；LeicaEngine.kt 负责模型会话和处理链路。PhotoFlow.kt、PhotoWorkflow.kt 管理当前照片、处理方式和请求状态，界面操作不应直接绕过这些状态。

调色和景深

M9 模型输入等比例长边 1024 的 RGB，输出低频色彩。LabFusion.kt 把它与原图细节融合。开启景深时，先从原图获得相对深度，再结合焦点、光圈和 ARNet 渲染；深度不是米制距离，效果也不是某台相机镜头的物理标定。

NativeDepthMath.kt 将深度分析长边限制到 1220，并按 14 的倍数补边。GaicComposition.kt 负责构图评分，分析长边同样上限 1220。GaicCompositionMath.kt 和 CompositionGeometry.kt 等文件处理候选裁框与坐标。应用已有成片的裁框只记录选择，保存时裁切，不再跑一次调色或虚化。

导出可以选择原图像素、长边 2048 或 1024。原图像素选项不表示所有模型都按原图尺寸推理。不要把模型工作尺寸、界面预览和最终输出混为一谈。

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

这个源码版本已通过 838 项 JVM 测试，但结果只覆盖测试中的输入和检查项。我没有对这个源码版本重新做真机运行验证，也没有重新导出全部模型。真机内存、速度、多点交互、取消、保存及实际照片效果需要另行检查。
