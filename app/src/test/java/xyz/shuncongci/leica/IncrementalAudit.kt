package xyz.shuncongci.leica

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import xyz.shuncongci.leica.LegendaryMath.Frame
import xyz.shuncongci.leica.LegendaryMath.Point

/** Desktop bridge for real per-point ONNX tensors and production incremental math. */
object IncrementalAudit {
    private fun read(f:File):FloatArray{val b=ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN);return FloatArray(b.remaining()/4){b.float}}
    private fun write(f:File,a:FloatArray){val b=ByteBuffer.allocate(a.size*4).order(ByteOrder.LITTLE_ENDIAN);b.asFloatBuffer().put(a);f.writeBytes(b.array())}
    @JvmStatic fun main(args:Array<String>){
        val mode=args[0];val dir=File(args[1]).apply{mkdirs()};val c=Cancel()
        if(mode=="optics"){optics(dir,c);return}
        val w=args[2].toInt();val h=args[3].toInt();val points=read(File(dir,"points.bin")).toList().chunked(2).map{Point(it[0],it[1])}
        val rgb=Frame(w,h,3,read(File(dir,"rgb.bin")))
        when(mode){
            "prepare"->write(File(dir,"sam-input.bin"),SubjectProtectionMath.samInput(rgb,c))
            "matte"->{
                val p=points.single();val mask=SubjectSelectionMath.select(read(File(dir,"masks.bin")),read(File(dir,"scores.bin")),p,w,h,c)
                write(File(dir,"selected.bin"),mask.data)
                val t=SubjectProtectionMath.trimap(mask,c);write(File(dir,"trimap.bin"),t)
                write(File(dir,"matte-input.bin"),SubjectProtectionMath.matteInput(rgb,t,c).data)
            }
            "finish"->{
                val a=SubjectSelectionMath.finish(Frame((w+31)/32*32,(h+31)/32*32,1,read(File(dir,"alpha-padded.bin"))),w,h,read(File(dir,"trimap.bin")),points,c)
                write(File(dir,"alpha.bin"),a.data)
            }
            "cache"->{
                val cache=SubjectPointCache();val calls=ArrayList<Int>();val counts=ArrayList<Int>()
                val masks=points.indices.map{Frame(w,h,1,read(File(dir,"point-$it/alpha.bin")))}
                val selections=listOf(points.take(2),points,points.filterIndexed{i,_->i!=1},points.take(1),points.reversed())
                selections.forEachIndexed{i,selection->
                    val before=calls.size
                    val alpha=cache.resolve(true,"audit",selection,c){missing,commit->missing.forEach{p->val at=points.indexOf(p);calls.add(at);commit(p,masks[at])}}!!
                    write(File(dir,"union-$i.bin"),alpha.data);counts.add(calls.size-before)
                    SubjectSelectionMath.requireCovered(alpha,selection)
                }
                check(counts==listOf(2,1,0,0,0));check(calls.sorted()==points.indices.toList())
                check(cache.resolve(false,"other",emptyList(),c){_,_->error("disabled") }==null)
                val alpha=cache.resolve(true,"audit",points,c){_,_->error("off/on recompute")}!!
                val sharp=LegendaryMath.planes(rgb);val out=SubjectProtectionMath.composite(sharp,Planes(w,h),alpha,c)
                write(File(dir,"protected.bin"),LegendaryMath.rgb(out).data)
                val opaque=alpha.data.count{it==1f};check(opaque>0)
                for(i in alpha.data.indices)if(alpha.data[i]==1f)for(ch in 0..2)check(sharp.plane(ch)[i]==out.plane(ch)[i])
                File(dir,"cache.json").writeText("{\"callsPerEdit\":${counts},\"eachPointComputedOnce\":true,\"offOnNoInference\":true,\"opaquePixels\":$opaque}")
            }
            else->error("Unknown audit mode")
        }
    }
    private fun optics(dir:File,c:Cancel){
        val w=769;val h=513;val r=12f;val n=w*h;check(r<=FullFrameOptics.maxRadius(w,h))
        val high=Frame(w,h,3,FloatArray(n*3));val spots=ArrayList<Pair<Int,Int>>()
        for(y in 0..4)for(x in 0..6){val sx=48+x*112;val sy=48+y*104;spots.add(sx to sy);for(ch in 0..2)high.data[ch*n+sy*w+sx]=.8f}
        val coc=Frame(w,h,1,FloatArray(n){r})
        write(File(dir,"before.bin"),HighlightScatter.opticalAtScale(high,coc,.95f,c,1.5f).data)
        write(File(dir,"after.bin"),HighlightScatter.optical(high,coc,.95f,c).data)
        val warm=Planes(w,h)
        for((sx,sy) in spots)for(y in sy-2..sy+2)for(x in sx-2..sx+2){val i=y*w+x;warm.p0[i]=130.toByte();warm.p1[i]=95;warm.p2[i]=15}
        val split=LegendaryMath.split(warm,c);write(File(dir,"warm-source.bin"),LegendaryMath.rgb(warm).data)
        write(File(dir,"warm-high.bin"),split.second.data);write(File(dir,"warm-bokeh.bin"),HighlightScatter.optical(split.second,coc,.95f,c).data)
        check(split.second.data.any{it>0f});check(warm.p0.all{(it.toInt() and 255)<=136})
        File(dir,"optics.json").writeText("{\"w\":$w,\"h\":$h,\"radiusBefore\":18,\"radiusAfter\":36,\"radiusScaleVs211\":2,\"gainUnchanged\":true,\"spots\":[${spots.joinToString(","){"[${it.first},${it.second}]"}}]}")
    }
}
