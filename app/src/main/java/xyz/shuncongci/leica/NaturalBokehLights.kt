package xyz.shuncongci.leica

import java.io.File
import kotlin.math.*

/** Brightness-gated SDR light and signed HDR residuals share one physical transport.
 * Durable snapshots contain whole completed steps only; no row or layer is marked complete
 * before the group's files have been synced, hashed and committed atomically. */
internal object NaturalBokehLights {
    const val ID="natural-physical-signed-radiance-v1"
    const val BRIGHT_START=.60f
    const val BRIGHT_FULL=.85f
    private fun smooth(x:Float):Float{val t=x.coerceIn(0f,1f);return t*t*(3-2*t)}
    internal fun cap(peak:Float,old:Float):Float {
        require(peak.isFinite() && peak in 0f..1f && old.isFinite() && old>=0f)
        val confidence=smooth((peak-BRIGHT_START)/(BRIGHT_FULL-BRIGHT_START))
        return if(confidence==0f)max(.25f,peak) else max(old,peak-(peak-old)*confidence)
    }
    fun caps(p:Planes,cancel:Cancel,scratch:File):FloatArray {
        val out=BoundedHighlightExtraction.caps(p,cancel,scratch)
        for(i in out.indices){if(i%p.width==0)cancel.check();val peak=HdrMath.linearByte(maxOf(p.p0[i].toInt()and 255,p.p1[i].toInt()and 255,p.p2[i].toInt()and 255));out[i]=cap(peak,out[i])}
        return out
    }
    data class PairOfLights(val sdr:GlobalBokehLights.Light?,val hdrSigned:GlobalBokehLights.Light?)
    fun render(colored:Planes,p:BokehPreparation.Prepared,sharp:HdrPixels?,work:File,aperture:Float,
        cancel:Cancel,pupils:StreamingLightScatter.Pupils,progress:(String)->Unit,checkpoint:RenderStepCache?=null):PairOfLights {
        cancel.check();val w=colored.width;val h=colored.height
        val kinds=listOfNotNull(if(p.hasSdrLight)"sdr" else null,if(sharp!=null)"hdr" else null)
        if(kinds.isEmpty())return PairOfLights(null,null)
        val label=if(sharp==null)"SDR高光"else if(!p.hasSdrLight)"HDR光能"else "SDR/HDR光能"
        fun checked(saved:RenderStepCache.Snapshot?,names:Set<String>):Map<String,File>?=saved?.files?.also{files->
            require(files.keys==names){"光能恢复文件组不完整"}
            for(file in files.values){cancel.check();BokehFloatFile.open(file).use{require(it.w==w && it.h==h && it.channels==3){"光能恢复尺寸不匹配"}}}
        }
        val sourceNames=kinds.map{"${it}source"}.toSet()
        val savedSources=checked(checkpoint?.load("light-source",cancel),sourceNames)
        val lights=kinds.map{k->GlobalBokehLights.Light(savedSources?.getValue("${k}source")?:File(work,"natural-$k-source.f32"),File(work,"natural-$k-color.f32"),p.alpha?.let{File(work,"natural-$k-foreground.f32")})}
        val readers=ArrayList<BokehFloatFile>();val writers=ArrayList<BokehFloatFile>()
        fun closeWriters(){writers.forEach{it.sync();it.close()};writers.clear()}
        try {
            if(savedSources==null){
                progress("$label · 提取光能");cancel.check()
                val s=kinds.indexOf("sdr").takeIf{it>=0}?.let{BokehFloatFile.create(lights[it].source,w,h,3).also(writers::add)}
                val d=kinds.indexOf("hdr").takeIf{it>=0}?.let{BokehFloatFile.create(lights[it].source,w,h,3).also(writers::add)}
                BokehFloatFile.open(p.caps).use{caps->
                    val capRow=FloatArray(w);val sr=Array(3){FloatArray(w)};val dr=Array(3){FloatArray(w)};val pixel=FloatArray(3)
                    for(y in 0 until h){cancel.check();caps.row(0,y,0,w,capRow,cancel)
                        if(sharp!=null)for(c in 0..2)sharp.read(c,y,1,dr[c],cancel)
                        for(x in 0 until w){val i=y*w+x;LegendaryMath.basePixel(colored,i,capRow[x],pixel)
                            for(c in 0..2){val base=LegendaryMath.linearQuantized(pixel[c]);if(s!=null)sr[c][x]=max(0f,HdrMath.linearByte(colored.plane(c)[i].toInt()and 255)-base)
                                if(d!=null){HdrPixels.valid(dr[c][x]);dr[c][x]-=base}
                            }
                        }
                        for(c in 0..2){s?.putRow(c,y,0,w,sr[c],cancel);d?.putRow(c,y,0,w,dr[c],cancel)}
                    }
                }
                closeWriters()
                checkpoint?.saveFiles("light-source",kinds.indices.associate{"${kinds[it]}source" to lights[it].source},cancel)
            }else{progress("$label · 复用已保存光能提取");cancel.check()}
            val scatterNames=kinds.flatMap{listOfNotNull("${it}color",if(p.alpha!=null)"${it}front"else null)}.toSet()
            val savedScatter=checked(checkpoint?.load("light-scatter",cancel),scatterNames)
            val completed=if(savedScatter!=null){
                require(savedSources!=null){"全局散射缺少已保存光源"}
                progress("$label · 复用已保存全局散射");cancel.check()
                kinds.indices.map{i->GlobalBokehLights.Light(lights[i].source,savedScatter.getValue("${kinds[i]}color"),if(p.alpha==null)null else savedScatter.getValue("${kinds[i]}front"))}
            }else{
                val co=BokehFloatFile.open(p.coc).use{it.read(it.whole,cancel)}
                val input=lights.map{BokehFloatFile.open(it.source).also(readers::add)}
                val output=lights.map{BokehFloatFile.create(it.color,w,h,3).also(writers::add)}
                val foreground=if(p.alpha==null)null else lights.map{BokehFloatFile.create(checkNotNull(it.foreground),w,h,3).also(writers::add)}
                val minL=floor(co.data.minOrNull()!!).toInt();val maxL=ceil(co.data.maxOrNull()!!).toInt()
                progress("$label · 物理半径档位 ${maxL-minL+1}级");progress("$label · 共用光学散射");cancel.check()
                RadianceTransport.run(input,co,aperture,cancel,output,foreground,BooleanArray(lights.size){kinds[it]=="hdr"},pupils,
                    onProgress={n,total->progress("$label · 全局散射 $n/${total}行")})
                closeWriters()
                val files=linkedMapOf<String,File>()
                for(i in kinds.indices){files["${kinds[i]}color"]=lights[i].color;lights[i].foreground?.let{files["${kinds[i]}front"]=it}}
                checkpoint?.saveFiles("light-scatter",files,cancel)
                lights
            }
            cancel.check();return PairOfLights(kinds.indexOf("sdr").takeIf{it>=0}?.let{completed[it]},kinds.indexOf("hdr").takeIf{it>=0}?.let{completed[it]})
        } finally {readers.forEach{it.close()};writers.forEach{it.close()}}
    }
}
