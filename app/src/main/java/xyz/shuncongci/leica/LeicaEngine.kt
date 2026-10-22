package xyz.shuncongci.leica

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Original depth (optional) -> GAN LAB detail fusion (all sizes) -> manual ARNet/aperture bokeh. */
class LeicaEngine(private val context: Context, private val diagnostic: ((String,LegendaryMath.Frame)->Unit)? = null,
    private val depthBusy:(Boolean)->Unit = {}, private val depthReady:(DepthVisualMath.Data)->Unit = {},
    private val depthPreview:(DepthVisualMath.Data)->Unit = {}, private val resultDirectory:File=File(context.cacheDir,"leica"),
    private val subjectBusy:(Boolean)->Unit = {}, private val subjectReady:(SubjectDepthMotion.Data,Boolean)->Unit = {_,_->}) : AutoCloseable {
    class Result(
        val style: LeicaStyle, val file: File, val width: Int, val height: Int,
        val original: Bitmap, val result: Bitmap,
        val inferMs: Long, val totalMs: Long, val downscaled: Boolean, val fused: Boolean,
        val rendered: Boolean = false, val reason: String = "", val hardwareSummary: String = "", val stageSummary:String = "", val subjectProtected:Boolean=false,
    )

    val memory = PhotoMemory(context)
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment().also{it.setTelemetry(false)}
    private var session: HardwareSession? = null
    private val hardwareReport=HardwareReport()
    private var sessionStyle: LeicaStyle? = null
    private val activeRun = ActiveNativeRun<OrtSession.RunOptions>()

    private val cacheDir = resultDirectory
    // Accessed only on legendary-worker; imported photo URIs identify immutable local copies.
    private val depthCache = PhotoDepthCache()
    private var visualCache:DepthVisualMath.Data?=null
    private val subjectCache=SubjectPointCache()
    fun completedSubjects(uri:Uri,points:List<LegendaryMath.Point>)=subjectCache.completed(uri.toString(),points)
    fun cachedSubjects(uri:Uri,points:List<LegendaryMath.Point>,cancel:Cancel)=subjectCache.cached(uri.toString(),points,cancel)
    private var subjectVisual:SubjectDepthMotion.Data?=null
    fun clearSubjectCache(){subjectCache.clear();subjectVisual=null}
    /** Cached geometry only: removal must not decode or execute any model. */
    fun cachedSubjectMotion(uri:Uri,points:List<LegendaryMath.Point>,point:LegendaryMath.Point?,cancel:Cancel):SubjectDepthMotion.Data? {
        val key=uri.toString()
        if(point==null || !subjectCache.contains(key,points) || !depthCache.contains(key))return null
        val map=depthCache.get(key,cancel){error("cached geometry must not infer depth")}
        val focus=LegendaryMath.focus(map,point)?:return null
        val alpha=subjectCache.cached(key,points,cancel)?:return null
        return subjectVisual?.takeIf{it.alpha===alpha && it.focus==focus}
            ?:SubjectDepthMotion.create(map,alpha,focus,cancel).also{subjectVisual=it}
    }
    fun clearDepthCache() { depthCache.clear();visualCache=null;clearSubjectCache() }
    data class FocusPreview(val overlay:FocusPreviewMath.Overlay?,val reusedDepth:Boolean,val subjectOverlay:IntArray?=null,val subjectProtected:Boolean=false,val completedSubjects:List<LegendaryMath.Point> = emptyList())
    private fun subjectMask(enabled:Boolean,uri:Uri,w:Int,h:Int,points:List<LegendaryMath.Point>,depth:LegendaryMath.Frame,focus:Float,cancel:Cancel,progress:(String)->Unit,onCommitted:(List<LegendaryMath.Point>)->Unit = {}):LegendaryMath.Frame? {
        // Disabled never decodes, loads, infers, applies or publishes the retained mask.
        if(!enabled)return null
        val reused=subjectCache.contains(uri.toString(),points)
        if(!reused)subjectBusy(true)
        try {
            val alpha=checkNotNull(subjectCache.resolve(true,uri.toString(),points,cancel){missing,commit->
                val size=SubjectProtectionMath.analysisSize(w,h)
                progress("主体保护 · 已缓存${points.distinct().size-missing.size}点，仅计算${missing.size}点 · 分析图 ${size.first}×${size.second}")
                val rgb=LegendaryMath.rgb(decodeWorking(uri,cancel,BokehOutput.ORIGINAL,size).first)
                SubjectProtection(env,::modelPath,cacheDir,hardwareReport,activeRun).computePoints(rgb,missing,cancel,progress){point,mask->
                    commit(point,mask);onCommitted(completedSubjects(uri,points))
                }
            })
            onCommitted(completedSubjects(uri,points))
            val data=subjectVisual?.takeIf{it.alpha===alpha && it.focus==focus}
                ?: SubjectDepthMotion.create(depth,alpha,focus,cancel).also{subjectVisual=it}
            cancel.check();subjectReady(data,reused)
            progress(if(reused)"主体保护 · 复用同图整组${points.size}点蒙版 · 无需重复推理" else "主体保护 · ${points.size}点统一完成 · 主体拉至焦平面")
            return alpha
        }finally{if(!reused)subjectBusy(false)}
    }

    private fun analyzedDepth(uri:Uri,w:Int,h:Int,cancel:Cancel,progress:(String)->Unit):LegendaryMath.Frame {
        val key=uri.toString()
        if(depthCache.contains(key))progress("复用本张照片完整深度 · 无需重复推理")
        val computing=!depthCache.contains(key)
        if(computing){visualCache=null;depthBusy(true)}
        try {
            val map=depthCache.get(key,cancel){
                ProgressiveDepth.run(cancel,coarse={
                    val (cw,ch)=ProgressiveDepth.size(w,h)
                    progress("粗深度预览 · 单精度 · ${cw}×${ch} · 仅用于空间预览")
                    coarseDepth(uri,cw,ch,cancel)
                },publish={coarse->
                    val data=DepthVisualMath.create(coarse,cancel)
                    cancel.check();depthPreview(data)
                },full={
                    val (aw,ah)=NativeDepthMath.analysisSize(w,h)
                    val (dw,dh)=NativeDepthMath.paddedSize(aw,ah)
                    progress("完整深度计算 · 单精度 · ${aw}×${ah} → 补边 ${dw}×${dh} · 可拖动预览")
                    depth(uri,w,h,cancel)
                })
            }
            if(visualCache==null)visualCache=DepthVisualMath.create(map,cancel)
            cancel.check();depthReady(checkNotNull(visualCache));return map
        } finally { if(computing)depthBusy(false) }
    }
    fun focusPreview(uri:Uri,point:LegendaryMath.Point,aperture:Float,output:BokehOutput,
                     cancel:Cancel,subjectEnabled:Boolean=false,subjectPoints:List<LegendaryMath.Point> = emptyList(),onSubjectCommitted:(List<LegendaryMath.Point>)->Unit = {},progress:(String)->Unit):FocusPreview {
        cancel.check();cacheDir.mkdirs()
        val (sw,sh)=bounds(uri);val exif=orientation(uri)
        val (w,h)=NativeImageMath.size(sw,sh,exif)
        val reused=depthCache.contains(uri.toString())
        val map=analyzedDepth(uri,w,h,cancel,progress)
        val focus=LegendaryMath.focus(map,point) ?: return FocusPreview(null,reused)
        val alpha=subjectMask(subjectEnabled,uri,w,h,subjectPoints,map,focus,cancel,progress,onSubjectCommitted)
        progress("焦内范围计算 · 仅显示，不写入成片")
        val (ow,oh)=LeicaSizes.workingSize(w,h,output)
        val overlay=FocusPreviewMath.create(map,point,aperture,ow,oh,cancel)
        cancel.check();return FocusPreview(overlay,reused,if(alpha!=null && overlay!=null)SubjectProtectionMath.overlay(alpha,overlay.width,overlay.height,cancel)else null,alpha!=null,completedSubjects(uri,subjectPoints))
    }

    fun inspectDepth(uri:Uri,cancel:Cancel,progress:(String)->Unit) {
        val (sw,sh)=bounds(uri);val (w,h)=NativeImageMath.size(sw,sh,orientation(uri))
        analyzedDepth(uri,w,h,cancel,progress)
    }
    /** Temporary rendered PNG files; never touches the user source/gallery. */
    fun clearCache(keep: File? = null) {
        cacheDir.listFiles()?.filter { it != keep }?.forEach { it.delete() }
    }

    private fun orientation(uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: 1
    } catch (_: Exception) { 1 }

    private fun bounds(uri: Uri): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // inJustDecodeBounds always makes decodeStream return null; only a null stream means "cannot open".
        val stream = context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("无法打开这张照片")
        stream.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0 || o.outHeight <= 0) throw IllegalArgumentException("不支持的照片格式")
        return o.outWidth to o.outHeight
    }

    private fun decode(uri: Uri, sample: Int): Bitmap {
        val o = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            ?: throw IllegalArgumentException("无法解码这张照片")
    }

    /** Small, orientation-corrected preview for the picker step. */
    fun loadPreview(uri: Uri): Bitmap {
        val (w, h) = bounds(uri)
        var sample = 1
        while (max(w, h) / (sample * 2) >= LeicaSizes.PREVIEW_MAX_SIDE) sample *= 2
        val bmp = decode(uri, sample)
        val m = Matrix()
        when (orientation(uri)) {
            2 -> m.setScale(-1f, 1f); 3 -> m.setRotate(180f); 4 -> m.setScale(1f, -1f)
            5 -> { m.setRotate(90f); m.postScale(-1f, 1f) }; 6 -> m.setRotate(90f)
            7 -> { m.setRotate(-90f); m.postScale(-1f, 1f) }; 8 -> m.setRotate(-90f)
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
    }

    /** Automatic color-only photo: native pixels up to 50 MP; larger sources keep their ratio. */
    private fun decodeOriginal(uri: Uri, cancel: Cancel): Pair<Planes,Boolean> {
        val (sw,sh)=bounds(uri);val exif=orientation(uri)
        val ow=if(exif in 5..8)sh else sw;val oh=if(exif in 5..8)sw else sh
        val (tw,th)=LeicaSizes.colorSize(ow,oh)
        cancel.check();val bmp=decode(uri,LeicaSizes.colorSample(sw,sh))
        val pixels=try {
            val p=Planes(bmp.width,bmp.height);val row=IntArray(bmp.width)
            for(y in 0 until bmp.height){cancel.check();bmp.getPixels(row,0,bmp.width,0,y,bmp.width,1);p.setFromArgbRows(y,1,row)}
            p
        } finally { bmp.recycle() }
        cancel.check();val oriented=pixels.exifTransposed(exif)
        val out=if(oriented.width==tw && oriented.height==th)oriented else Resample.resize(oriented,tw,th,Resample.Filter.BICUBIC,cancel)
        return out to (tw!=ow || th!=oh)
    }
    /** User-selected work photo, EXIF corrected. Original stays read-only. */
    private fun decodeWorking(uri: Uri,cancel: Cancel,output:BokehOutput,target:Pair<Int,Int>?=null): Pair<Planes,Boolean> {
        val (sw,sh)=bounds(uri);val exif=orientation(uri)
        val (ow,oh)=NativeImageMath.size(sw,sh,exif)
        val (tw,th)=target?:LeicaSizes.workingSize(ow,oh,output)
        require(tw in 1..ow && th in 1..oh){"输出尺寸超过来源像素"}
        var sample=1;while(ow/(sample*2)>=tw && oh/(sample*2)>=th)sample*=2
        cancel.check();val bmp=decode(uri,sample)
        val pixels=try {
            val p=Planes(bmp.width,bmp.height);val row=IntArray(bmp.width)
            for(y in 0 until bmp.height){cancel.check();bmp.getPixels(row,0,bmp.width,0,y,bmp.width,1);p.setFromArgbRows(y,1,row)}
            p
        } finally { bmp.recycle() }
        cancel.check();val oriented=pixels.exifTransposed(exif)
        return Resample.resize(oriented,tw,th,Resample.Filter.BICUBIC,cancel) to (tw!=ow || th!=oh)
    }
    /** Direct source-to-GAN decode: metadata-sized sampling, EXIF, then exact 1024-long-edge resize. */
    private fun decodeGanInput(uri: Uri,cancel: Cancel): Planes {
        val (sw,sh)=bounds(uri);val exif=orientation(uri)
        val (mw,mh)=LeicaSizes.modelSize(if(exif in 5..8)sh else sw,if(exif in 5..8)sw else sh)
        cancel.check();val bmp=decode(uri,LeicaSizes.ganSample(sw,sh))
        val pixels=try {
            val p=Planes(bmp.width,bmp.height);val row=IntArray(bmp.width)
            for(y in 0 until bmp.height){cancel.check();bmp.getPixels(row,0,bmp.width,0,y,bmp.width,1);p.setFromArgbRows(y,1,row)}
            p
        } finally { bmp.recycle() }
        cancel.check();val oriented=pixels.exifTransposed(exif)
        return Resample.resize(oriented,mw,mh,Resample.Filter.BILINEAR,cancel)
    }
    private fun analyzeColor(uri: Uri, style: LeicaStyle, cancel: Cancel): Pair<Planes,Long> {
        val input=decodeGanInput(uri,cancel)
        closeSession();val model=session(style);val start=SystemClock.elapsedRealtime()
        return try { infer(model,input,cancel) to (SystemClock.elapsedRealtime()-start) } finally { closeSession() }
    }

    private fun toBitmap(p: Planes): Bitmap {
        val bmp = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
        val rows = max(1, 1_000_000 / p.width)
        val buf = IntArray(rows * p.width)
        var y = 0
        while (y < p.height) {
            val n = min(rows, p.height - y)
            p.toArgbRows(y, n, buf)
            bmp.setPixels(buf, 0, p.width, 0, y, p.width, n)
            y += n
        }
        return bmp
    }

    private fun preview(p: Planes): Bitmap {
        val (pw, ph) = LeicaSizes.previewSize(p.width, p.height)
        return toBitmap(if (pw == p.width && ph == p.height) p else Resample.resize(p, pw, ph, Resample.Filter.BILINEAR))
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun modelPath(asset: String, expected: String): String {
        val dir=File(context.filesDir,"legendary-models").apply{mkdirs()}
        val file=File(dir,"$expected.onnx")
        fun hash(f: File): String {
            val md=MessageDigest.getInstance("SHA-256");val buf=ByteArray(64*1024)
            f.inputStream().use{input->while(true){val n=input.read(buf);if(n<0)break;md.update(buf,0,n)}}
            return md.digest().joinToString(""){"%02x".format(it)}
        }
        if(file.exists() && hash(file)==expected)return file.absolutePath
        val temp=File(dir,"$expected.tmp")
        try {
            context.assets.open(asset).use{input->temp.outputStream().use{out->input.copyTo(out,64*1024)}}
            require(hash(temp)==expected){"内置模型校验失败，请重新安装应用"}
            if(file.exists() && !file.delete())throw IllegalStateException("无法替换内置模型缓存")
            check(temp.renameTo(file)){"无法写入内置模型缓存"}
            return file.absolutePath
        } finally { temp.delete() }
    }
    /** Options live as long as the session; actual per-size assignment is profiled. */
    private fun session(style: LeicaStyle): HardwareSession {
        session?.let{if(sessionStyle==style)return it}
        closeSession()
        session=HardwareSession(env,modelPath(style.asset,style.sha256),"调色",cacheDir,hardwareReport)
        sessionStyle=style
        return session!!
    }
    private fun closeSession(){val old=session;session=null;sessionStyle=null;old?.close()}

    /** Normalisation identical to ToTensor + Normalize(0.5, 0.5); output mapping identical to ToPILImage. */
    private fun infer(s: HardwareSession, p: Planes, cancel: Cancel): Planes {
        val w = p.width
        val h = p.height
        require(max(w,h)==LeicaSizes.MODEL_LONG_EDGE) { "色彩模型输入必须为长边1024" }
        val hw = p.size
        val input = java.nio.FloatBuffer.wrap(FloatArray(3 * hw))
        for (c in 0 until 3) {
            val a = p.plane(c)
            for (i in 0 until hw) input.put(c * hw + i, ((a[i].toInt() and 0xFF) / 255f - 0.5f) / 0.5f)
        }
        val ro = OrtSession.RunOptions()
        activeRun.attach(ro)
        try {
            OnnxTensor.createTensor(env, input, longArrayOf(1, 3, h.toLong(), w.toLong())).use { t ->
                s.run(w,h,mapOf("input" to t),ro){cancel.check()}.use { r ->
                    val out = r.get(0) as OnnxTensor
                    val shape = out.info.shape
                    if (!shape.contentEquals(longArrayOf(1, 3, h.toLong(), w.toLong()))) {
                        throw IllegalStateException("模型输出尺寸异常: ${shape.joinToString("x")}")
                    }
                    val fb = out.floatBuffer
                    val o = Planes(w, h)
                    for (c in 0 until 3) {
                        val dst = o.plane(c)
                        for (i in 0 until hw) {
                            val value = fb.get(c * hw + i)
                            check(value.isFinite()) { "调色模型输出包含非有限值" }
                            val x = (value * 0.5f + 0.5f).coerceIn(0f, 1f)
                            dst[i] = (x * 255f).toInt().toByte()
                        }
                    }
                    return o
                }
            }
        } finally {
            activeRun.release(ro)
        }
    }

    /** Asks a running ONNX inference to stop early (used when the screen closes). */
    fun cancelRun() {
        try { activeRun.cancel { it.setTerminate(true) } } catch (e: Exception) { propagateFatalCause(e) }
    }

    private val BOKEH_ASSET="leica/legendary-1.0-arnet.fp16.onnx"
    private val BOKEH_SHA="a3aa5fa6a0818f36dbfa4be4dd5471d874cea5981357b7aeb28919954e70d05c"
    private val DEPTH_ASSET="leica/legendary-1.0-depth-native-chunk64.fp32.onnx"
    private val DEPTH_SHA="eeb3e43a7ac9f11a720f5d20a69a18ed95332456700717e7f460fb4600a05eb5"
    private fun checkedSession(asset:String,hash:String):HardwareSession {
        val label=if(asset==DEPTH_ASSET)"深度" else "虚化"
        return HardwareSession(env,modelPath(asset,hash),label,cacheDir,hardwareReport,
            forceCpu=asset==DEPTH_ASSET,preserveGeluPrimitives=asset==DEPTH_ASSET)
    }
    private fun tensor(frame: LegendaryMath.Frame): OnnxTensor {
        // ORT owns its native copy and releases it on tensor.close(); no extra direct-buffer clone.
        val buf=java.nio.FloatBuffer.wrap(frame.data)
        return OnnxTensor.createTensor(env,buf,longArrayOf(1,frame.channels.toLong(),frame.h.toLong(),frame.w.toLong()))
    }
    private fun runFrames(s: HardwareSession, inputs: Map<String,LegendaryMath.Frame>, channels: Int, cancel: Cancel): LegendaryMath.Frame {
        val tensors=linkedMapOf<String,OnnxTensor>();val ro=OrtSession.RunOptions();activeRun.attach(ro)
        try {
            inputs.forEach{(name,frame)->tensors[name]=tensor(frame)}
            val frame=inputs.values.first()
            require(inputs.values.all{it.w==frame.w && it.h==frame.h})
            s.run(frame.w,frame.h,tensors,ro){cancel.check()}.use { result ->
                val output=result.get(0) as OnnxTensor;val shape=output.info.shape
                val h=shape[shape.size-2].toInt();val w=shape.last().toInt()
                require(output.info.numElements==w.toLong()*h*channels) { "模型输出尺寸异常" }
                val a=FloatArray(w*h*channels);output.floatBuffer.get(a);require(a.all { it.isFinite() })
                return LegendaryMath.Frame(w,h,channels,a)
            }
        } finally { try { activeRun.release(ro) } finally { closeNativeResources(tensors.values) } }
    }
    /** Decode full source pixels independently of selected render/output dimensions. */
    private fun nativeDepthInput(uri:Uri,cancel:Cancel):LegendaryMath.Frame {
        cancel.check();val bmp=decode(uri,1)
        val pixels=try {
            val p=Planes(bmp.width,bmp.height);val row=IntArray(bmp.width)
            for(y in 0 until bmp.height){cancel.check();bmp.getPixels(row,0,bmp.width,0,y,bmp.width,1);p.setFromArgbRows(y,1,row)}
            p
        } finally {bmp.recycle()}
        return NativeDepthMath.input(pixels.exifTransposed(orientation(uri)),cancel)
    }
    /** Sampled decode for the disposable preview; final inference still decodes the original. */
    private fun coarseDepth(uri:Uri,w:Int,h:Int,cancel:Cancel):LegendaryMath.Frame {
        val (sw,sh)=bounds(uri);val exif=orientation(uri)
        val tw=if(exif in 5..8)h else w;val th=if(exif in 5..8)w else h
        var sample=1
        while(sw/(sample*2)>=tw && sh/(sample*2)>=th)sample*=2
        cancel.check();val bmp=decode(uri,sample)
        val pixels=try {
            val p=Planes(bmp.width,bmp.height);val row=IntArray(bmp.width)
            for(y in 0 until bmp.height){cancel.check();bmp.getPixels(row,0,bmp.width,0,y,bmp.width,1);p.setFromArgbRows(y,1,row)}
            p
        } finally {bmp.recycle()}
        val oriented=pixels.exifTransposed(exif)
        val input=NativeDepthMath.input(Resample.resize(oriented,w,h,Resample.Filter.BICUBIC,cancel),cancel)
        val raw=checkedSession(DEPTH_ASSET,DEPTH_SHA).use{s->runFrames(s,mapOf("input" to input),1,cancel)}
        cancel.check()
        return LegendaryMath.normalize(NativeDepthMath.crop(raw,w,h,cancel))
    }
    private fun depth(uri:Uri,width:Int,height:Int,cancel:Cancel):LegendaryMath.Frame {
        val input=nativeDepthInput(uri,cancel)
        diagnostic?.invoke("depth-input",input)
        cancel.check()
        val raw=checkedSession(DEPTH_ASSET,DEPTH_SHA).use { s ->
            runFrames(s,mapOf("input" to input),1,cancel)
        }
        cancel.check()
        val (aw,ah)=NativeDepthMath.analysisSize(width,height)
        diagnostic?.invoke("depth-raw",raw)
        return LegendaryMath.normalize(NativeDepthMath.crop(raw,aw,ah,cancel)).also { diagnostic?.invoke("depth-normalized",it) }
    }

    /** Fast-version pipeline; only bokeh output size and UI progress are extended. */
    fun process(uri: Uri, style: LeicaStyle, cancel: Cancel, blurEnabled: Boolean = false, point: LegendaryMath.Point? = null, aperture: Float = 2.8f, outputSize:BokehOutput=BokehOutput.SIZE_2048, colorEnabled:Boolean=true, renderSize:Pair<Int,Int>?=null, onVisual:((DevelopEvent)->Unit)?=null, subjectEnabled:Boolean=false, subjectPoints:List<LegendaryMath.Point> = emptyList(), progress: (String) -> Unit): Result {
        val processingMode=ProcessingMode.from(colorEnabled,blurEnabled)
        val outputLabel=if(blurEnabled)outputSize.label else "原图像素（最高5000万）"
        require(!blurEnabled || point != null) { "请先在原照片上点击焦点" }
        FullFrameOptics.checkAperture(aperture)
        val timing=StageTiming{SystemClock.elapsedRealtime()}
        fun stage(message: String){timing.start(message.substringBefore(" · ").substringAfter(' '));memory.stage(message);progress(message);cancel.check()}
        val t0=SystemClock.elapsedRealtime();hardwareReport.reset();cacheDir.mkdirs()
        val resultFile=File(cacheDir,"result-${java.util.UUID.randomUUID()}.png")
        try {
            // The original/GAN byte planes belong only to this helper stack; release them before ARNet.
            data class Prepared(val colored: Planes,val depth: LegendaryMath.Frame?,val focus: Float?,val inferMs: Long,val downscaled: Boolean,val subject:LegendaryMath.Frame?)
            fun prepare(): Prepared {
                stage("1/7 读取 · $outputLabel · 中央处理器")
                cancel.check();val (sw,sh)=bounds(uri);val exif=orientation(uri)
                val sourceWidth=if(exif in 5..8)sh else sw;val sourceHeight=if(exif in 5..8)sw else sh
                val requested=renderSize?:LeicaSizes.processingSize(sourceWidth,sourceHeight,blurEnabled,outputSize)
                require(requested.first in 1..sourceWidth && requested.second in 1..sourceHeight){"输出尺寸超过来源像素"}
                val (w,h)=if(blurEnabled)requested else LeicaSizes.colorSize(requested.first,requested.second)
                val depthMap=if(blurEnabled){
                    analyzedDepth(uri,sourceWidth,sourceHeight,cancel){stage("2/7 $it")}
                }else null
                val selectedFocus=depthMap?.let{LegendaryMath.focus(it,point!!)}
                val alpha=if(blurEnabled && selectedFocus!=null)subjectMask(subjectEnabled,uri,sourceWidth,sourceHeight,subjectPoints,depthMap!!,selectedFocus,cancel,progress={stage("2/7 $it")})else null
                // Model sessions and masks are complete before color/ARNet starts.
                val work=if(blurEnabled)decodeWorking(uri,cancel,outputSize,w to h) else null
                return processingMode.selectColorPath(color={
                    val (mw,mh)=LeicaSizes.modelSize(sourceWidth,sourceHeight)
                    onVisual?.invoke(DevelopEvent.ColorStart(blurEnabled))
                    stage("3/7 上色 · ${style.label} · ${mw}×$mh · 执行中")
                    val (gan,inferMs)=analyzeColor(uri,style,cancel)
                    stage("4/7 融合 · ${w}×$h · 中央处理器")
                    // Pure color loads full source only after GAN input/tensors/session are released.
                    val (original,downscaled)=work ?: decodeWorking(uri,cancel,outputSize,w to h)
                    check(original.width==w && original.height==h)
                    val colored=Fusion.fuse(original,Resample.resize(gan,w,h,Resample.Filter.BICUBIC,cancel),cancel)
                    onVisual?.let{send->
                        cancel.check();val event=DevelopEvent.ColorReady(preview(colored))
                        try{send(event)}catch(e:Exception){event.dispose();throw e}
                    }
                    Prepared(colored,depthMap,selectedFocus,inferMs,downscaled,alpha)
                },original={
                    stage("3/7 仅虚化 · 跳过调色模型与色彩融合")
                    val (original,downscaled)=checkNotNull(work)
                    check(original.width==w && original.height==h)
                    stage("4/7 保留原照片色彩 · ${w}×$h · 中央处理器")
                    Prepared(original,depthMap,selectedFocus,0L,downscaled,alpha)
                })
            }
            val prepared=prepare();val colored=prepared.colored;val depthMap=prepared.depth
            val selectedFocus=prepared.focus;val inferMs=prepared.inferMs;val w=colored.width;val h=colored.height
            val rendered=blurEnabled && selectedFocus!=null
            if(diagnostic!=null){
                diagnostic.invoke("colored",LegendaryMath.rgb(colored))
                selectedFocus?.let{diagnostic.invoke("focus",LegendaryMath.Frame(1,1,1,floatArrayOf(it)))}
            }
            var diagnosticPass=0;var visualPass=0
            // GAN inference is finished. Do not retain its ORT session alongside ARNet.
            closeSession()
            val output=if(rendered){
                onVisual?.let{send->
                    DevelopMath.focusTiles(depthMap!!,point!!,aperture,w,h,cancel,subjectMask=prepared.subject)?.let{tiles->
                        cancel.check();send(DevelopEvent.BokehStart(point,tiles))
                    }
                }
                stage("5/7 虚化准备 · ${w}×$h · 中央处理器")
                checkedSession(BOKEH_ASSET,BOKEH_SHA).use { bokeh ->
                    LegendaryMath.render(colored,depthMap!!,selectedFocus!!,aperture,cancel,subjectMask=prepared.subject,progress={sub->
                        when {
                            sub.contains("高光散景合成")->stage("6/7 高光合成 · ${w}×$h · 中央处理器")
                            sub.contains("RGB成片转换")->stage("6/7 颜色转换 · ${w}×$h · 中央处理器")
                            else->stage("5/7 虚化准备 · ${w}×$h · 中央处理器")
                        }
                    },onPass=onVisual?.let{send->{frame:LegendaryMath.Frame->
                        cancel.check();val (pw,ph)=LeicaSizes.previewSize(frame.w,frame.h)
                        val small=LegendaryMath.resize(frame,pw,ph,cancel=cancel)
                        val event=DevelopEvent.BokehPass(++visualPass,preview(LegendaryMath.planes(small)))
                        try{cancel.check();send(event)}catch(e:Exception){event.dispose();throw e}
                        stage("6/7 虚化尺度回传 · 第${visualPass}次 · 中间预览，尚未完成高光合成")
                    }}){image,defocus ->
                        cancel.check();stage("6/7 虚化模型 · ${image.w}×${image.h} · 执行中")
                        val pass=++diagnosticPass
                        diagnostic?.invoke("arnet-$pass-image",image)
                        diagnostic?.invoke("arnet-$pass-defocus",defocus)
                        runFrames(bokeh,mapOf("image" to image,"defocus" to defocus),3,cancel).also { diagnostic?.invoke("arnet-$pass-output",it) }
                    }
                }
            } else colored
            cancel.check();stage("7/7 成片 · ${w}×$h · 中央处理器")
            val row=ByteArray(w*3)
            NativePng(resultFile.outputStream(),w,h).use { png ->
                for(y in 0 until h){cancel.check();for(x in 0 until w){val i=y*w+x;val at=x*3;row[at]=output.p0[i];row[at+1]=output.p1[i];row[at+2]=output.p2[i]};png.row(row)}
            }
            cancel.check()
            val reason=(if(colorEnabled)"${style.label} · " else "") + if(!blurEnabled)"仅调色 · ${w}×$h · $outputLabel" else if(!rendered)(if(colorEnabled)"焦点深度不可靠；已上色但未虚化，请在目标内部重新点选。" else "焦点深度不可靠；未调色且未虚化，请在目标内部重新点选。") else "${processingMode.label} · ${outputSize.label} · 光圈 $aperture · ${if(prepared.subject!=null)"主体保护 · 背景虚化" else "整图虚化"}"
            val origPreview=loadPreview(uri);val resultPreview=loadPreview(Uri.fromFile(resultFile))
            timing.finish()
            return Result(style,resultFile,w,h,origPreview,resultPreview,inferMs,SystemClock.elapsedRealtime()-t0,prepared.downscaled,colorEnabled,rendered,reason,hardwareReport.summary(),timing.summary(),prepared.subject!=null && rendered)
        }catch(t:Exception){resultFile.delete();throw t}
        finally{closeSession()}
    }

    /** Copies the cached result into the gallery (Pictures/传奇一瞬). Never overwrites: MediaStore renames duplicates. */
    fun save(r: Result): String {
        val name = "传奇一瞬1.0-" + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()) + ".png"
        val cr = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/传奇一瞬")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法写入相册")
            try {
                (cr.openOutputStream(uri) ?: throw IllegalStateException("无法写入相册")).use { out ->
                    r.file.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                cr.update(uri, values, null, null)
            } catch (e: Exception) {
                cr.delete(uri, null, null)
                throw e
            }
            return "相册 Pictures/传奇一瞬/$name"
        }
        // Android 8–9: app-specific pictures folder, no storage permission needed.
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: throw IllegalStateException("存储不可用")
        dir.mkdirs()
        var target = File(dir, name)
        var i = 1
        while (target.exists()) target = File(dir, name.removeSuffix(".png") + "-${i++}.png")
        r.file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("image/png"), null)
        return target.absolutePath
    }

    override fun close() {
        clearDepthCache()
        closeSession()
    }
}
