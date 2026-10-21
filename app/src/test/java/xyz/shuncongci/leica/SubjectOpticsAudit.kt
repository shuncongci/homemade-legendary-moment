package xyz.shuncongci.leica

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Desktop-only diagnostics call the compiled production math; not included in the APK. */
object SubjectOpticsAudit {
    private fun read(file:File):FloatArray {val b=ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN);return FloatArray(b.remaining()/4){b.float}}
    private fun write(file:File,data:FloatArray){val b=ByteBuffer.allocate(data.size*4).order(ByteOrder.LITTLE_ENDIAN);data.forEach{b.putFloat(it)};file.writeBytes(b.array())}
    @JvmStatic fun main(args:Array<String>){
        val out=File(args[0]).apply{mkdirs()};val source=File(args[1]);val w=args[2].toInt();val h=args[3].toInt()
        val alpha=LegendaryMath.Frame(w,h,1,read(File(source,"alpha.bin")))
        // Deliberately synthetic depth isolates the surface-flattening behavior. The alpha is real ONNX output.
        val depth=LegendaryMath.Frame(w,h,1,FloatArray(w*h){i->(.12f+.65f*(i%w)/(w-1).coerceAtLeast(1)+.14f*(i/w)/(h-1).coerceAtLeast(1)).coerceIn(0f,1f)})
        val point=LegendaryMath.Point(.78f,.47f);val focus=checkNotNull(LegendaryMath.focus(depth,point))
        val data=SubjectDepthMotion.create(depth,alpha,focus,Cancel());val cols=data.foreground.cols;val rows=data.foreground.rows
        write(File(out,"foreground.bin"),data.foreground.depth);write(File(out,"background.bin"),data.background.depth);write(File(out,"coverage.bin"),data.coverage)
        val result=ArrayList<Float>();val frames=ArrayList<String>();val p=FloatArray(3)
        for(frame in 0..160){
            val t=min(1f,frame/120f);val yaw=if(frame<=120).38f else -.8f+(frame-121)*1.6f/39
            val pitch=if(frame<=120)-.14f else .4f*sin((frame-121)*PI.toFloat()/39)
            for(layer in 0..1)for(i in 0 until cols*rows){
                val z=if(layer==0)SubjectDepthMotion.z(data.background.depth[i]) else SubjectDepthMotion.movedZ(data.foreground.depth[i],focus,t)
                for(k in 0..3){val dx=if(k==0 || k==3)-.5f else .5f;val dy=if(k<2)-.5f else .5f
                    val x=((i%cols+.5f+dx*.993f)/cols-.5f)*w/max(w,h).toFloat();val y=((i/cols+.5f+dy*.993f)/rows-.5f)*h/max(w,h).toFloat()
                    DepthVisualMath.project(x,y,z,yaw,pitch,p);p.forEach{check(it.isFinite());result.add(it)}
                }
            };frames.add("{\"progress\":$t,\"yaw\":$yaw,\"pitch\":$pitch}")
        }
        write(File(out,"projection.bin"),result.toFloatArray())
        val meta="{\"cols\":$cols,\"rows\":$rows,\"w\":$w,\"h\":$h,\"focus\":$focus,\"frames\":[${frames.joinToString(",")}],\"depth\":\"synthetic\",\"alpha\":\"packaged-ONNX previously verified\"}"
        File(out,"motion.json").writeText(meta)
        val bw=769;val bh=513;val high=LegendaryMath.Frame(bw,bh,3,FloatArray(bw*bh*3));val spots=ArrayList<Pair<Int,Int>>()
        for(y in 0..4)for(x in 0..6){val sx=48+x*112;val sy=48+y*104;spots.add(sx to sy);for(c in 0..2)high.data[c*high.size+sy*bw+sx]=.8f}
        val coc=LegendaryMath.Frame(bw,bh,1,FloatArray(bw*bh){18f});val before=System.nanoTime()
        val optical=HighlightScatter.optical(high,coc,.95f,Cancel());val elapsed=(System.nanoTime()-before)/1e9
        val old=HighlightScatter.scatter(high,coc,.95f,Cancel())
        write(File(out,"optical.bin"),optical.data);write(File(out,"uniform.bin"),old.data)
        val checks=spots.map{(x,y)->var sum=0.0;var xx=0.0;var yy=0.0;var xy=0.0
            for(dy in -23..23)for(dx in -23..23){val a=optical.data[(y+dy)*bw+x+dx].toDouble();sum+=a;xx+=a*dx*dx;yy+=a*dy*dy;xy+=a*dx*dy}
            val theta=.5*atan2(2*xy,xx-yy);val delta=sqrt((xx-yy).pow(2)+4*xy*xy);val ratio=sqrt((xx+yy+delta)/(xx+yy-delta))
            check(abs(sum-.8)<.012){"energy $x,$y: $sum"}
            "{\"x\":$x,\"y\":$y,\"energy\":$sum,\"axisRatio\":$ratio,\"angle\":$theta}"
        }
        File(out,"optical.json").writeText("{\"w\":$bw,\"h\":$bh,\"radius\":18,\"aperture\":0.95,\"desktopSeconds\":$elapsed,\"spots\":[${checks.joinToString(",")}]}")
        println("AUDIT VERIFIED ${frames.size} projection frames, ${checks.size} optical spots, ${elapsed}s desktop")
    }
}
