package xyz.shuncongci.leica

import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Local synthetic diagnostic bridge, not packaged or a replacement for phone/model tests. */
object NaturalBokehAudit {
    @JvmStatic fun main(args:Array<String>){
        val dir=File(args[0]);dir.mkdirs()
        fun save(name:String,p:Planes){File(dir,name).outputStream().use{out->out.write("P6\n${p.width} ${p.height}\n255\n".toByteArray());for(i in 0 until p.size)for(ch in 0..2)out.write(p.plane(ch)[i].toInt() and 255)}}
        fun oldBlend(s:Planes,b:Planes,a:Frame)=Planes(s.width,s.height).also{o->for(ch in 0..2)for(i in 0 until s.size)o.plane(ch)[i]=Math.rint(((s.plane(ch)[i].toInt() and 255)*a.data[i]+(b.plane(ch)[i].toInt() and 255)*(1-a.data[i])).toDouble()).toInt().toByte()}
        fun edge(name:String,a:Frame){
            val s=Planes(a.w,a.h);val b=Planes(a.w,a.h);val expected=Planes(a.w,a.h)
            for(i in 0 until a.size)for(ch in 0..2){
                val fg=when(ch){0->190;1->115;else->65}+(i%a.w/3%2)*12
                val old=when(ch){0->35;1->55;else->75};val next=when(ch){0->115;1->125;else->135}
                s.plane(ch)[i]=(fg*a.data[i]+old*(1-a.data[i])).roundToInt().toByte()
                b.plane(ch)[i]=if(a.data[i]==0f)next.toByte() else s.plane(ch)[i]
                expected.plane(ch)[i]=(fg*a.data[i]+next*(1-a.data[i])).roundToInt().toByte()
            }
            val start=System.nanoTime();val actual=SubjectProtectionMath.composite(s,b,a,Cancel());val elapsed=(System.nanoTime()-start)/1e6
            val old=oldBlend(s,b,a)
            fun error(p:Planes):Double{var sum=0.0;var n=0;for(i in a.data.indices)if(a.data[i]>0f && a.data[i]<1f)for(ch in 0..2){sum+=abs((p.plane(ch)[i].toInt() and 255)-(expected.plane(ch)[i].toInt() and 255));n++};return sum/max(1,n)}
            var opaque=0;var bg=0
            for(i in a.data.indices){if(a.data[i]==1f){opaque++;for(ch in 0..2)check(actual.plane(ch)[i]==s.plane(ch)[i])};if(a.data[i]==0f){bg++;for(ch in 0..2)check(actual.plane(ch)[i]==b.plane(ch)[i])}}
            for((suffix,p) in listOf("input" to s,"old" to old,"new" to actual,"expected" to expected))save("$name-$suffix.ppm",p)
            val bytes=ByteBuffer.allocate(a.size*4).order(ByteOrder.LITTLE_ENDIAN);bytes.asFloatBuffer().put(a.data);File(dir,"$name-alpha.bin").writeBytes(bytes.array())
            println("edge $name width=${a.w} height=${a.h} opaque=$opaque background=$bg partial=${a.data.count{it>0f&&it<1f}} oldMAE=${error(old)} newMAE=${error(actual)} milliseconds=$elapsed")
        }
        val w=193;val h=145
        edge("synthetic",Frame(w,h,1,FloatArray(w*h){i->
            val x=i%w;val y=i/w
            val body=(45.5-hypot((x-96).toDouble(),(y-82).toDouble())).toFloat().coerceIn(0f,1f)
            val hair=if(y in 12..58){val center=94+9*sin(y*.09);(1.4-abs(x-center)).toFloat().coerceIn(0f,1f)}else 0f
            max(body,hair)
        }))
        if(args.size>1){val file=File(args[1]);val bytes=file.readBytes();val data=FloatArray(bytes.size/4);ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(data);edge("real-alpha",Frame(800,534,1,data))}
        val p=Planes(193,145)
        for(ch in 0..2)p.plane(ch).fill(20)
        for(y in 12..132 step 12)for(x in 12..180 step 12)for(ch in 0..2)p.plane(ch)[y*193+x]=180.toByte()
        for(y in 66..70)for(x in 92..96)for(ch in 0..2)p.plane(ch)[y*193+x]=245.toByte()
        val high=LegendaryMath.split(p,Cancel()).second
        var weak=0;var light=0;for(i in 0 until p.size)if(high.data[i]>0f){if((p.p0[i].toInt() and 255)==180)weak++ else light++}
        check(weak==0 && light>0)
        save("lights-input.ppm",p)
        save("lights-render.ppm",LegendaryMath.renderCoc(p,Frame(p.width,p.height,1,FloatArray(p.size){4f}),.95f,Cancel()){rgb,_->rgb})
        println("lights weakHighPixels=$weak strongHighPixels=$light")
    }
}
