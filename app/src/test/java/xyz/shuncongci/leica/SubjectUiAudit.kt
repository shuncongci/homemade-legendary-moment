package xyz.shuncongci.leica
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import xyz.shuncongci.leica.LegendaryMath.Frame
import xyz.shuncongci.leica.LegendaryMath.Point

/** Local CPU diagnostic: same production PSF, conservative-support off/on; no model timing claims. */
object SubjectUiAudit {
    private fun write(f:File,a:FloatArray){val b=ByteBuffer.allocate(a.size*4).order(ByteOrder.LITTLE_ENDIAN);b.asFloatBuffer().put(a);f.writeBytes(b.array())}
    private fun read(f:File):FloatArray{val b=ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN);return FloatArray(b.remaining()/4){b.float}}
    private fun save(dir:File,name:String,p:Planes)=write(File(dir,"$name.bin"),LegendaryMath.rgb(p).data)
    @JvmStatic fun main(args:Array<String>){
        val dir=File(args[0]).apply{mkdirs()};val w=289;val h=193;val n=w*h;val cancel=Cancel()
        val sharp=Planes(w,h);val alpha=Frame(w,h,1,FloatArray(n));val high=Frame(w,h,3,FloatArray(n*3))
        for(y in 0 until h)for(x in 0 until w){val i=y*w+x;val a=(min(min(x-99,221-x),min(y-29,164-y))/3f).coerceIn(0f,1f);alpha.data[i]=a
            sharp.p0[i]=(if(a>0)75+(x/5+y/5)%2*40 else 24).toByte();sharp.p1[i]=(if(a>0)90+(x/5+y/5)%2*35 else 28).toByte();sharp.p2[i]=(if(a>0)105+(x/5+y/5)%2*30 else 34).toByte()
            if((x-91)*(x-91)+(y-96)*(y-96)<=25){high.data[i]=.7f;high.data[n+i]=.4f;high.data[2*n+i]=.07f}
        }
        val coc=Frame(w,h,1,FloatArray(n){6f*(1-alpha.data[it])})
        val weak=HighlightScatter.opticalLayers(high,coc,.95f,cancel)
        val boosted=HighlightScatter.opticalLayers(BokehRadiance.boost(high,coc,cancel),coc,.95f,cancel)
        val far=HighlightScatter.opticalLayers(BokehRadiance.boost(high,coc,cancel),Frame(w,h,1,FloatArray(n){-coc.data[it]}),.95f,cancel)
        val old=BokehRadiance.protect(sharp,sharp,alpha,weak.foreground,cancel)
        val out=BokehRadiance.protect(sharp,sharp,alpha,boosted.foreground,cancel)
        val behind=BokehRadiance.protect(sharp,sharp,alpha,far.foreground,cancel)
        val at=96*w+106;check(alpha.data[at]==1f && boosted.foreground.data[at]>0f)
        check((out.p0[at].toInt() and 255)>(sharp.p0[at].toInt() and 255));check(far.foreground.data.all{it==0f})
        for(ch in 0..2)check(sharp.plane(ch).contentEquals(behind.plane(ch)))
        val ratio=boosted.color.data.sumOf{it.toDouble()}/weak.color.data.sumOf{it.toDouble()};check(abs(ratio-4)<1e-6)
        for((name,p) in listOf("sharp" to sharp,"weak-near" to old,"boosted-near" to out,"far-protected" to behind))save(dir,name,p)
        write(File(dir,"alpha.bin"),alpha.data);write(File(dir,"light-before.bin"),weak.color.data);write(File(dir,"light-after.bin"),boosted.color.data)
        var realLift="null"
        if(args.size==4){val src=File(args[1]);val rw=args[2].toInt();val rh=args[3].toInt();val mask=Frame(rw,rh,1,read(File(src,"union-1.bin")));val d=Frame(rw,rh,1,FloatArray(rw*rh){.1f+.8f*(it%rw)/(rw-1)})
            val a=DevelopMath.focusTiles(d,Point(.2f,.5f),.95f,2048,1365,cancel)!!;val b=DevelopMath.focusTiles(d,Point(.2f,.5f),.95f,2048,1365,cancel,mask)!!
            val changed=a.values.indices.count{b.values[it]>a.values[it]+.01f};check(changed>0)
            write(File(dir,"lift-optical.bin"),a.values);write(File(dir,"lift-protected.bin"),b.values)
            realLift="{\"cols\":${b.cols},\"rows\":${b.rows},\"raisedTiles\":$changed,\"alphaSource\":\"existing real ONNX per-point union\",\"depth\":\"synthetic gradient, not Android depth inference\"}"
        }
        File(dir,"visual.json").writeText("{\"w\":$w,\"h\":$h,\"linearEnergyRatio\":$ratio,\"opaqueOverlapRGBBefore\":[${(0..2).joinToString{(sharp.plane(it)[at].toInt() and 255).toString()}}],\"opaqueOverlapRGBAfter\":[${(0..2).joinToString{(out.plane(it)[at].toInt() and 255).toString()}}],\"farForegroundIsZero\":true,\"farProtectedByteExact\":true,\"realAlphaLift\":$realLift}")
        val cases=listOf(Triple("sparse-2048",2048,1365),Triple("edge-mixed-1024",1024,683),Triple("dense-769",769,513))
        val results=cases.map{(name,bw,bh)->
            val bn=bw*bh;val bhigh=Frame(bw,bh,3,FloatArray(bn*3));val bcoc=Frame(bw,bh,1,FloatArray(bn){if(name.startsWith("edge")){if(it%bw<bw/3)4f else if(it%bw<2*bw/3)0f else -4f}else 6f})
            for(y in 0 until bh)for(x in 0 until bw)if(name.startsWith("dense") || (if(name.startsWith("edge"))x<5 && y<5 else abs(x-bw/2)<4 && abs(y-bh/2)<4)){val i=y*bw+x;for(ch in 0..2)bhigh.data[ch*bn+i]=.5f/(ch+1)}
            val base=HighlightScatter.opticalAtScale(bhigh,bcoc,.95f,cancel,3f,pruneDark=false);val fast=HighlightScatter.opticalAtScale(bhigh,bcoc,.95f,cancel,3f,pruneDark=true)
            val err=base.data.indices.maxOf{abs(base.data[it]-fast.data[it])};check(err==0f)
            val before=ArrayList<Double>();val after=ArrayList<Double>()
            fun measure(prune:Boolean):Double{val t=System.nanoTime();val o=HighlightScatter.opticalAtScale(bhigh,bcoc,.95f,cancel,3f,pruneDark=prune);check(o.data[bn/2].isFinite());return (System.nanoTime()-t)/1e6}
            repeat(3){if(it%2==0){before.add(measure(false));after.add(measure(true))}else{after.add(measure(true));before.add(measure(false))}}
            val bm=before.sorted()[1];val am=after.sorted()[1];println("$name baseline=${bm}ms optimized=${am}ms speedup=${bm/am} maxError=$err")
            "{\"name\":\"$name\",\"w\":$bw,\"h\":$bh,\"baselineMs\":$before,\"optimizedMs\":$after,\"baselineMedianMs\":$bm,\"optimizedMedianMs\":$am,\"speedup\":${bm/am},\"maxAbsError\":$err}"
        }
        File(dir,"performance.json").writeText("{\"device\":\"desktop JVM CPU\",\"timingScope\":\"highlight optical compositor only, allocation included; no ONNX/Android/end-to-end timing\",\"warmupPairs\":1,\"repeats\":3,\"cases\":[${results.joinToString(",")}]}")
    }
}
