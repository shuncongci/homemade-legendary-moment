package xyz.shuncongci.leica

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Sequential frames with bounded parallel row grading. No realtime clock, dropped/repeated frames, Surface
 * implicit tone mapping, Bitmap, reduced-size preview, network, or plaintext LUT file.
 */
internal object VideoEngine {
    data class Source(val file:File,val track:Int,val format:MediaFormat,val w:Int,val h:Int,
        val color:VideoContract.Color,val points:LongArray,val durationUs:Long,val audio:List<Int>,val sarW:Int,val sarH:Int,
        val videoCount:Int,val rotation:Int,val dolby:VideoDolby.Config?=null)
    data class Result(val file:File,val source:Source,val color:VideoColor,val metadata:VideoMetadata,val frames:Int,val elapsedMs:Long)
    private fun MediaFormat.int(key:String,default:Int=0)=if(containsKey(key))getInteger(key) else default
    private fun MediaFormat.long(key:String,default:Long=0)=if(containsKey(key))getLong(key) else default
    private fun MediaFormat.bytes(key:String):ByteArray {val b=getByteBuffer(key)?.duplicate()?:error("缺少视频编码配置");return ByteArray(b.remaining()).also{b.get(it)}}
    private fun extractor(file:File)=MediaExtractor().also{try{it.setDataSource(file.absolutePath)}catch(t:Throwable){it.release();throw t}}
    fun import(context:Context,uri:Uri,dir:File,cancel:Cancel):File {
        require(dir.isDirectory || dir.mkdirs());val file=File(dir,"input.bin");require(!file.exists())
        try {context.contentResolver.openInputStream(uri).use{input->requireNotNull(input){"无法读取所选视频"};FileOutputStream(file).use{out->val buffer=ByteArray(256*1024);while(true){cancel.check();val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n)};out.fd.sync()}};require(file.length()>0);cancel.check();return file}
        catch(t:Throwable){file.delete();throw t}
    }
    private fun inventory(file:File,ex:MediaExtractor,cancel:Cancel):VideoTracks.Inventory {
        require(ex.trackCount in 0..64){"系统轨道数量超出安全容量"}
        val tracks=(0 until ex.trackCount).map{i->
            cancel.check();val f=ex.getTrackFormat(i)
            VideoTracks.Track(i,f.int("track-id",-1),f.getString(MediaFormat.KEY_MIME).orEmpty(),
                f.int(MediaFormat.KEY_WIDTH),f.int(MediaFormat.KEY_HEIGHT),f.long(MediaFormat.KEY_DURATION),f.int(MediaFormat.KEY_ROTATION,-1))
        }
        var issue=""
        val headers=try{VideoTracks.headers(file,cancel::check)}catch(e:IllegalArgumentException){issue=e.message.orEmpty();null}catch(e:IllegalStateException){if(e is java.util.concurrent.CancellationException)throw e;issue=e.message.orEmpty();null}catch(e:NoSuchElementException){issue="轨道头缺少必需字段：${e.message.orEmpty()}";null}
        return VideoTracks.Inventory(tracks,headers,issue)
    }
    fun catalog(file:File,cancel:Cancel):VideoTracks.Inventory {
        val ex=extractor(file);try{return inventory(file,ex,cancel)}finally{ex.release()}
    }
    private fun hevcBaseFormat(original:MediaFormat,base:VideoDolby.Base,chosen:VideoTracks.Track,metadata:VideoMetadata):MediaFormat {
        val color=VideoDolby.color(base,chosen.width,chosen.height)
        if(original.containsKey("csd-0"))VideoDolby.requireSameConfig(original.bytes("csd-0"),base.csd)
        metadata.note(base.config.notice)
        // Fresh plain-HEVC format: no Dolby profile, RPU, static HDR statistics or dynamic keys.
        return MediaFormat.createVideoFormat("video/hevc",chosen.width,chosen.height).apply{
            colorFields(this,color);setByteBuffer("csd-0",ByteBuffer.wrap(base.csd))
            setLong(MediaFormat.KEY_DURATION,chosen.durationUs);setInteger(MediaFormat.KEY_ROTATION,chosen.rotation)
            setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            if(original.containsKey(MediaFormat.KEY_BIT_RATE))setInteger(MediaFormat.KEY_BIT_RATE,original.getInteger(MediaFormat.KEY_BIT_RATE))
            // Keep the existing container SAR handling; do not turn non-square pixels square.
            for(key in listOf("sar-width","sar-height"))if(original.containsKey(key))setInteger(key,original.getInteger(key))
        }
    }
    fun inspect(file:File,metadata:VideoMetadata,cancel:Cancel,selectedTrack:Int?=null):Source {
        val ex=extractor(file)
        try {
            val catalog=inventory(file,ex,cancel);metadata.note(catalog.summary)
            val chosen=catalog.choose(selectedTrack);val track=chosen.index
            val dolby=if(catalog.hasDolby)requireNotNull(catalog.dolbyBase)else null
            val original=ex.getTrackFormat(track);val f=if(dolby==null)original else hevcBaseFormat(original,dolby,chosen,metadata)
            metadata.note("本次处理：${chosen.label}")
            if(catalog.processingVideos.size>1)metadata.warn("按你的选择仅导出第${track+1}条独立视频画面；其余视频画面轨不合并、不作为本次成片。音轨仍逐一复制与回读，原文件不变。")
            val mime=f.getString(MediaFormat.KEY_MIME);require(mime in setOf("video/avc","video/hevc")){"当前只支持AVC/HEVC；不会将不支持的HDR编码自动转为SDR"}
            val w=f.getInteger(MediaFormat.KEY_WIDTH);val h=f.getInteger(MediaFormat.KEY_HEIGHT);require(w>0 && h>0 && w%2==0 && h%2==0){"原始尺寸不受当前4:2:0编解码链支持；不会缩图"}
            val csd=f.bytes("csd-0");val config=VideoBitstream.config(csd,mime=="video/hevc");val bits=config.bits
            require(config.width==w && config.height==h){"码流与容器的显示尺寸冲突；不会静默裁切"}
            val transfer=when(f.int(MediaFormat.KEY_COLOR_TRANSFER)){MediaFormat.COLOR_TRANSFER_ST2084->16;MediaFormat.COLOR_TRANSFER_HLG->18;MediaFormat.COLOR_TRANSFER_SDR_VIDEO->1;else->error("缺少可确认的传递曲线；不能猜测或将HDR当SDR")}
            val standard=f.int(MediaFormat.KEY_COLOR_STANDARD);val range=f.int(MediaFormat.KEY_COLOR_RANGE)
            require(standard in setOf(MediaFormat.COLOR_STANDARD_BT709,MediaFormat.COLOR_STANDARD_BT2020) && range in setOf(MediaFormat.COLOR_RANGE_FULL,MediaFormat.COLOR_RANGE_LIMITED)){"无法确认原始色域或量化范围"}
            val color=VideoContract.Color(if(standard==MediaFormat.COLOR_STANDARD_BT2020)9 else 1,transfer,if(standard==MediaFormat.COLOR_STANDARD_BT2020)9 else 1,range==MediaFormat.COLOR_RANGE_FULL,bits).also{it.validate()}
            validateConfig(config,color)
            val sarW=f.int("sar-width",config.sarW);val sarH=f.int("sar-height",config.sarH);require(sarW>0 && sarH>0)
            if(color.hdr)require(Build.VERSION.SDK_INT>=33){"原生10bit视频处理需要Android 13及P010编解码器；不会降为8bit或SDR"}
            val audio=(0 until ex.trackCount).filter{ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true}
            if(ex.trackCount!=catalog.videos.size+audio.size)metadata.warn("存在字幕、时间码或附加轨道，将尽力迁移可支持信息；不兼容的附加信息会具体提示，完整视频仍可导出。")
            ex.selectTrack(track);val points=ArrayList<Long>();val buffer=ByteBuffer.allocateDirect(VideoIso.MAX_SAMPLE)
            while(ex.sampleTime>=0){cancel.check();require(points.size<VideoIso.MAX_SAMPLES){"视频帧数超出当前有界处理容量"};require(ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED==0){"不支持受保护的加密视频"};require(ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME==0){"不支持分段帧，不能擅自丢帧"};buffer.clear();val n=ex.readSampleData(buffer,0);require(n>0);if(dolby!=null)VideoDolby.cleanSample(buffer,n,csd,cancel::check)else if(VideoBitstream.sample(buffer,n,mime=="video/hevc",csd))metadata.warn("原片含SEI附加信息；调色后不沿用旧逐帧HDR分析及亮度统计。画面按原传递曲线和位深重编码。");points+=ex.sampleTime;if(!ex.advance())break}
            points.sort();require(points.isNotEmpty() && points.first()>=0 && points.zipWithNext().all{(a,b)->b>a}){"帧时间戳重复或异常，不能抽帧处理"}
            val duration=f.long(MediaFormat.KEY_DURATION);require(duration>points.last()){"缺少有效末帧时长，不能改变原帧时序"}
            metadata.note("元数据按可读取内容尽力保留；不承诺全部厂商字段均可识别。编码器专有信息不会盲目复制到新画面。")
            return Source(file,track,f,w,h,color,points.toLongArray(),duration,audio,sarW,sarH,catalog.processingVideos.size,chosen.rotation,dolby?.config)
        }finally{ex.release()}
    }
    private fun validateConfig(c:VideoBitstream.Config,color:VideoContract.Color) {
        require(c.bits==color.bits && (c.primaries==null || c.primaries==color.primaries) && (c.transfer==null || c.transfer==color.transfer) && (c.matrix==null || c.matrix==color.matrix) && (c.full==null || c.full==color.fullRange)){"码流与系统色彩描述冲突或暂不支持；不能降低位深或猜测色彩"}
    }
    private fun colorFormat(color:VideoContract.Color)=if(color.bits==10)MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    private fun colorFields(f:MediaFormat,c:VideoContract.Color) {
        f.setInteger(MediaFormat.KEY_COLOR_STANDARD,if(c.hdr)MediaFormat.COLOR_STANDARD_BT2020 else MediaFormat.COLOR_STANDARD_BT709)
        f.setInteger(MediaFormat.KEY_COLOR_TRANSFER,when(c.transfer){16->MediaFormat.COLOR_TRANSFER_ST2084;18->MediaFormat.COLOR_TRANSFER_HLG;else->MediaFormat.COLOR_TRANSFER_SDR_VIDEO})
        f.setInteger(MediaFormat.KEY_COLOR_RANGE,if(c.fullRange)MediaFormat.COLOR_RANGE_FULL else MediaFormat.COLOR_RANGE_LIMITED)
    }
    private fun checkColor(f:MediaFormat,s:Source,encoded:Boolean) {
        val w=f.int(MediaFormat.KEY_WIDTH);val h=f.int(MediaFormat.KEY_HEIGHT)
        val cw=if(f.containsKey("crop-right"))f.getInteger("crop-right")-f.int("crop-left")+1 else w
        val ch=if(f.containsKey("crop-bottom"))f.getInteger("crop-bottom")-f.int("crop-top")+1 else h
        require(cw==s.w && ch==s.h){"编解码器改变了原始尺寸，已停止"}
        val expected=MediaFormat();colorFields(expected,s.color)
        for(k in listOf(MediaFormat.KEY_COLOR_STANDARD,MediaFormat.KEY_COLOR_TRANSFER,MediaFormat.KEY_COLOR_RANGE))require(f.containsKey(k) && f.getInteger(k)==expected.getInteger(k)){"编解码器未确认原始色彩：$k；不做静默降级"}
        if(encoded){val c=VideoBitstream.config(f.bytes("csd-0"),f.getString(MediaFormat.KEY_MIME)=="video/hevc");validateConfig(c,s.color);require(c.width==s.w && c.height==s.h && c.sarW.toLong()*s.sarH==c.sarH.toLong()*s.sarW){"输出码流的尺寸或像素比例改变"}}
    }
    private fun imageBuffers(image:Image,s:Source):Pair<List<ByteBuffer>,IntArray> {
        require(image.format==if(s.color.bits==10)ImageFormat.YCBCR_P010 else ImageFormat.YUV_420_888){"编解码器未提供原位深Image；不会使用8bit替代"}
        val crop=image.cropRect;require(crop.width()==s.w && crop.height()==s.h && crop.left%2==0 && crop.top%2==0 && image.planes.size==3){"实际帧裁切或平面布局不能原样处理"}
        val layout=IntArray(6);val buffers=image.planes.mapIndexed{i,p->
            val b=p.buffer.duplicate();val shift=(if(i==0)crop.top else crop.top/2).toLong()*p.rowStride+(if(i==0)crop.left else crop.left/2).toLong()*p.pixelStride
            require(shift>=0 && shift<=b.remaining());b.position(b.position()+shift.toInt());layout[i*2]=p.rowStride;layout[i*2+1]=p.pixelStride;b.slice()
        };return buffers to layout
    }
    fun process(context:Context,s:Source,look:VideoColor,dir:File,metadata:VideoMetadata,cancel:Cancel,progress:(String)->Unit):Result {
        val started=SystemClock.elapsedRealtime();val system=File(dir,"system-mux.mp4");require(!system.exists());val output=File(dir,"encoded.mp4");require(!output.exists());val copy=File(dir,"metadata.mp4");require(!copy.exists());val partial=File(dir,"descriptive.mp4");require(!partial.exists())
        val mime=s.format.getString(MediaFormat.KEY_MIME)!!;val imageFormat=colorFormat(s.color)
        val rate=if(s.points.size>1)(1_000_000.0*(s.points.size-1)/(s.points.last()-s.points.first())).coerceAtLeast(1.0) else 30.0
        val request=MediaFormat.createVideoFormat(mime,s.w,s.h).apply{
            colorFields(this,s.color);setInteger(MediaFormat.KEY_COLOR_FORMAT,imageFormat);setFloat(MediaFormat.KEY_FRAME_RATE,rate.toFloat());setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);setInteger("max-bframes",0);setInteger("sar-width",s.sarW);setInteger("sar-height",s.sarH)
            setInteger(MediaFormat.KEY_PROFILE,if(mime=="video/hevc")if(s.color.hdr)MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 else MediaCodecInfo.CodecProfileLevel.HEVCProfileMain else MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
        }
        val candidates=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter{it.isEncoder && it.supportedTypes.any{t->t.equals(mime,true)}}
        val codec=candidates.firstOrNull{info->val caps=info.getCapabilitiesForType(mime);imageFormat in caps.colorFormats && caps.videoCapabilities.isSizeSupported(s.w,s.h) && caps.profileLevels.any{it.profile==request.getInteger(MediaFormat.KEY_PROFILE)}}?:error("设备没有支持原尺寸及${s.color.bits}bit Image输入的编码器；未降级")
        val caps=codec.getCapabilitiesForType(mime);val suggested=maxOf(s.format.int(MediaFormat.KEY_BIT_RATE).toLong(),(s.w.toDouble()*s.h*rate*.45).toLong())
        val bitrate=suggested.coerceIn(caps.videoCapabilities.bitrateRange.lower.toLong(),caps.videoCapabilities.bitrateRange.upper.toLong()).toInt()
        request.setInteger(MediaFormat.KEY_BIT_RATE,bitrate)
        if(caps.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR))request.setInteger(MediaFormat.KEY_BITRATE_MODE,MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        val workers=VideoFrameWorkers()
        val ex=extractor(s.file);var encoder:MediaCodec?=null;var decoder:MediaCodec?=null;var muxer:MediaMuxer?=null;var handle=0L;var muxStarted=false
        try {
            ex.selectTrack(s.track);val enc=MediaCodec.createByCodecName(codec.name).also{encoder=it};enc.configure(request,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);enc.start()
            val dec=MediaCodec.createDecoderByType(mime).also{decoder=it};val input=s.format
            input.setInteger(MediaFormat.KEY_COLOR_FORMAT,imageFormat);input.setInteger(MediaFormat.KEY_ROTATION,0);input.setInteger("allow-frame-drop",0)
            dec.configure(input,null,null,0);dec.start()
            handle=VideoLutNative.open(context,look,cancel)
            val mux=MediaMuxer(system.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also{muxer=it}
            // Rotation belongs to the explicitly selected stream, not a retriever's default stream.
            mux.setOrientationHint(s.rotation)
            metadata.attempt("拍摄位置读取"){val retriever=MediaMetadataRetriever();try{retriever.setDataSource(s.file.absolutePath);retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let{location->val m=Regex("^([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?).*$").matchEntire(location)?:error("无法解析位置格式");mux.setLocation(m.groupValues[1].toFloat(),m.groupValues[2].toFloat())}}finally{retriever.release()}}
            val audioOut=s.audio.associateWith{mux.addTrack(ex.getTrackFormat(it))}
            val witness=VideoMuxExact.Witness(mime=="video/hevc")
            var videoTrack=-1;var fed=false;var decoded=false;var encoded=false;var rendered=0;val actual=ArrayList<Long>();val info=MediaCodec.BufferInfo();var lastAction=SystemClock.elapsedRealtime()
            fun touched(){lastAction=SystemClock.elapsedRealtime()}
            fun guard(){cancel.check();check(SystemClock.elapsedRealtime()-lastAction<60_000){"编解码器等待超时，未发布半成品"}}
            fun drain(wait:Long=0):Boolean {
                val index=enc.dequeueOutputBuffer(info,wait)
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){require(!muxStarted);val format=enc.outputFormat;checkColor(format,s,true);videoTrack=mux.addTrack(format);mux.start();muxStarted=true;touched();return true}
                if(index<0)return false
                try {
                    if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0){require(muxStarted && actual.size<s.points.size && info.presentationTimeUs==s.points[actual.size]){"编码器改变了帧顺序或时间戳，未发布"};val b=enc.getOutputBuffer(index)?:error("编码输出为空");witness.add(b,info.offset,info.size,info.presentationTimeUs,info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME!=0);mux.writeSampleData(videoTrack,b,info);actual+=info.presentationTimeUs}
                    if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0)encoded=true
                    touched();return true
                }finally{enc.releaseOutputBuffer(index,false)}
            }
            while(!encoded){
                guard();while(drain()){}
                if(!fed){val index=dec.dequeueInputBuffer(0);if(index>=0){val b=dec.getInputBuffer(index)?:error("解码输入为空");b.clear();val n=ex.readSampleData(b,0);if(n<0){dec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);fed=true}else{require(n<=b.capacity() && ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED==0);val fedSize=if(s.dolby==null)n else VideoDolby.cleanSample(b,n,s.format.bytes("csd-0"),cancel::check);dec.queueInputBuffer(index,0,fedSize,ex.sampleTime,0);ex.advance()};touched()}}
                if(!decoded){val di=MediaCodec.BufferInfo();val index=dec.dequeueOutputBuffer(di,10_000)
                    if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){checkColor(dec.outputFormat,s,false);touched()}
                    if(index>=0){try {
                        if(di.size>0 && di.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0){
                            require(rendered<s.points.size && di.presentationTimeUs==s.points[rendered]){"解码器改变帧数或原始时间戳"}
                            var ei=enc.dequeueInputBuffer(0);while(ei<0){guard();drain(10_000);ei=enc.dequeueInputBuffer(0)}
                            val source=dec.getOutputImage(index)?:error("设备解码器不提供原位深Image")
                            try {val target=enc.getInputImage(ei)?:error("设备编码器不提供原位深Image")
                                try {val (src,sl)=imageBuffers(source,s);val(dst,dl)=imageBuffers(target,s);val buffers=(src+dst).toTypedArray();val layout=sl+dl
                                    val frameHandle=handle
                                    // Distinct Java buffer views/layout per lane; shared bytes have even, disjoint row ownership.
                                    val lanes=VideoFrameWorkers.partition(s.h).map{buffers.map{it.duplicate()}.toTypedArray() to layout.copyOf()}
                                    workers.frame(s.h,cancel::check){lane,row,n->
                                        val (views,spec)=lanes[lane]
                                        VideoLutNative.render(frameHandle,views,spec,s.w,s.h,row,n,s.color.bits,s.color.transfer,s.color.fullRange)
                                    } // joins ALL native calls even on cancellation/failure before either Image closes
                                }finally{target.close()}
                            }finally{source.close()}
                            val size=enc.getInputBuffer(ei)?.capacity()?:error("编码输入缓冲区不可访问")
                            require(size.toLong()>=s.w.toLong()*s.h*3/(if(s.color.bits==10)1 else 2))
                            enc.queueInputBuffer(ei,0,size,di.presentationTimeUs,0);rendered++;progress("最多8线程逐帧调色 · $rendered/${s.points.size} · ${s.w}×${s.h} · ${s.color.label}")
                        }
                        if(di.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0){require(rendered==s.points.size){"原帧未全部解码"};var ei=enc.dequeueInputBuffer(0);while(ei<0){guard();drain(10_000);ei=enc.dequeueInputBuffer(0)};enc.queueInputBuffer(ei,0,0,s.durationUs,MediaCodec.BUFFER_FLAG_END_OF_STREAM);decoded=true};touched()
                    }finally{dec.releaseOutputBuffer(index,false)}}
                }else drain(10_000)
            }
            VideoContract.verifyFrames(s.points,actual);require(muxStarted)
            val end=MediaCodec.BufferInfo().apply{set(0,0,s.durationUs,MediaCodec.BUFFER_FLAG_END_OF_STREAM)};mux.writeSampleData(videoTrack,ByteBuffer.allocateDirect(0),end)
            progress("保留音轨与核验封装")
            for((track,dst) in audioOut){val audio=extractor(s.file);try{audio.selectTrack(track);val b=ByteBuffer.allocateDirect(VideoIso.MAX_SAMPLE);val ai=MediaCodec.BufferInfo();while(audio.sampleTime>=0){cancel.check();b.clear();val n=audio.readSampleData(b,0);if(n<0)break;require(audio.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED==0);ai.set(0,n,audio.sampleTime,if(audio.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC!=0)MediaCodec.BUFFER_FLAG_KEY_FRAME else 0);mux.writeSampleData(dst,b,ai);if(!audio.advance())break}}finally{audio.release()}}
            mux.stop();mux.release();muxer=null
            progress("逐样本核对编码内容并精确封装原帧时间戳")
            VideoMuxExact.write(readMovie(system,metadata,cancel),output,s.points,s.durationUs,witness.finish(),cancel::check)
            verify(s,output,cancel,metadata)
            progress("尽力保留拍摄信息")
            val sourceMovie=if(s.videoCount==1 && s.dolby==null)metadata.attempt("原容器元数据读取"){readMovie(s.file,metadata,cancel)} else null
            var result=output
            if(sourceMovie!=null){val migrated=metadata.attempt("原容器元数据迁移"){VideoIso.rewrite(sourceMovie,readMovie(output,metadata,cancel),copy,cancel::check);verify(s,copy,cancel,metadata);copy}
                if(migrated!=null){result=migrated;metadata.note("原容器拍摄描述、音轨和可支持附加轨道已迁移并回读核验；旧HDR亮度统计不复制。")}else copy.delete()}
            if(result===output){val salvaged=metadata.attempt("逐项拍摄信息迁移"){VideoIso.salvage(s.file,output,partial,metadata,cancel::check);verify(s,partial,cancel,metadata);partial};if(salvaged!=null)result=salvaged else partial.delete()}
            // The chosen complete file is checked outside optional-metadata error handling.
            verify(s,result,cancel,metadata)
            cancel.check();system.delete();return Result(result,s,look,metadata,actual.size,SystemClock.elapsedRealtime()-started)
        }catch(t:Throwable){system.delete();output.delete();copy.delete();partial.delete();throw t}
        finally {
            try{workers.close()}finally{
                try{if(handle!=0L)VideoLutNative.close(handle)}finally{
                    try{decoder?.release()}finally{try{encoder?.release()}finally{try{muxer?.release()}finally{ex.release()}}}
                }
            }
        }
    }
    private fun readMovie(file:File,metadata:VideoMetadata,cancel:Cancel)=VideoIso.readCompatible(file,cancel::check).also{it.warnings.forEach(metadata::warn)}
    private fun verify(source:Source,file:File,cancel:Cancel,metadata:VideoMetadata) {
        if(source.dolby!=null)VideoDolby.verifyOutput(file,cancel::check)
        val ex=extractor(file)
        try {
            val v=(0 until ex.trackCount).single{ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true};val format=ex.getTrackFormat(v);require(format.int(MediaFormat.KEY_ROTATION)==source.rotation){"输出画面方向与所选轨道不一致，未发布"};checkColor(format,source,true);ex.selectTrack(v)
            val actual=ArrayList<Long>();val buffer=ByteBuffer.allocateDirect(VideoIso.MAX_SAMPLE);val csd=format.bytes("csd-0");val hevc=format.getString(MediaFormat.KEY_MIME)=="video/hevc"
            while(ex.sampleTime>=0){cancel.check();require(actual.size<VideoIso.MAX_SAMPLES);buffer.clear();val size=ex.readSampleData(buffer,0);require(size>0 && ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED==0){"输出样本不可完整读取"};VideoBitstream.sample(buffer,size,hevc,csd);actual+=ex.sampleTime;if(!ex.advance())break}
            val movie=readMovie(file,metadata,cancel)
            VideoMuxTiming.verifyMovie(source.points,actual,movie,source.durationUs,format.long(MediaFormat.KEY_DURATION))
            val audio=(0 until ex.trackCount).filter{ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true};require(audio.size==source.audio.size){"音轨数量发生变化"}
            for(i in audio.indices)require(trackHash(source.file,source.audio[i],cancel).contentEquals(trackHash(file,audio[i],cancel))){"音轨或音轨时序未完整保留"}
        }finally{ex.release()}
    }
    private fun trackHash(file:File,track:Int,cancel:Cancel):ByteArray {
        val ex=extractor(file);val hash=MessageDigest.getInstance("SHA-256");val b=ByteBuffer.allocateDirect(VideoIso.MAX_SAMPLE)
        try{ex.selectTrack(track);while(ex.sampleTime>=0){cancel.check();b.clear();val n=ex.readSampleData(b,0);require(n>0);hash.update(ByteBuffer.allocate(12).putLong(ex.sampleTime).putInt(n).array());b.position(0);b.limit(n);hash.update(b);if(!ex.advance())break};return hash.digest()}finally{ex.release()}
    }
}
