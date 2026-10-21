package xyz.shuncongci.leica

import java.io.File
import xyz.shuncongci.leica.LegendaryMath.Frame

/** Local analytic audit only. No user image, Android runtime, or model inference. */
object ReflectionEdgeAudit {
    @JvmStatic fun main(args:Array<String>){
        val dir=File(args.single());dir.mkdirs()
        ReflectionEdgeTest().gradientError(false,211,File(dir,"gradient"))
        ReflectionEdgeTest().gradientError(true,211,File(dir,"mirror"))
        val w=129;val h=97;val p=Planes(w,h)
        for(c in 0..2)p.plane(c).fill(25)
        for(y in 45..51)for(x in 61..67){val i=y*w+x;p.p0[i]=235.toByte();p.p1[i]=190.toByte();p.p2[i]=105}
        val mask=Frame(w,h,1,FloatArray(w*h){.8f});val depth=Frame(w,h,1,FloatArray(w*h){1f})
        val coc=SubjectProtectionMath.protectedCoc(FullFrameOptics.coc(depth,0f,.95f,w,h,Cancel()),mask,Cancel())
        var foreground:Frame?=null
        val raw=LegendaryMath.renderCoc(p,coc,.95f,Cancel(),highlightBoost=1f,onForeground={foreground=it},infer={rgb,_->rgb})
        val old=BokehRadiance.protect(p,raw,mask,checkNotNull(foreground),Cancel())
        val next=LegendaryMath.render(p,depth,0f,.95f,Cancel(),subjectMask=mask,infer={rgb,_->rgb})
        val expected=SubjectProtectionMath.composite(p,raw,mask,Cancel());val i=48*w+64
        fun color(q:Planes)=(0..2).joinToString(","){(q.plane(it)[i].toInt() and 255).toString()}
        val high=LegendaryMath.split(p,Cancel()).second
        val c=Frame(w,h,1,FloatArray(w*h){3f})
        val source=high.data.sumOf{it.toDouble()};val oldEnergy=BokehRadiance.boost(high,c,Cancel(),4f).data.sumOf{it.toDouble()};val nextEnergy=BokehRadiance.boost(high,c,Cancel()).data.sumOf{it.toDouble()}
        check(source>0 && source==nextEnergy && oldEnergy==4*source)
        check((0..2).all{old.plane(it)[i]!=expected.plane(it)[i] && next.plane(it)[i]==expected.plane(it)[i]})
        File(dir,"radiance.json").writeText("""{"oldDefaultGain":4,"newDefaultGain":${BokehRadiance.DEFAULT_GAIN},"sourceEnergy":$source,"oldPreparedEnergy":$oldEnergy,"newPreparedEnergy":$nextEnergy,"selfReflectionOld":[${color(old)}],"selfReflectionNew":[${color(next)}],"selfReflectionExpected":[${color(expected)}],"phoneRuntimeVerified":false,"userOriginalReplayed":false}""")
        println("reflection-edge audit complete: $dir")
    }
}
