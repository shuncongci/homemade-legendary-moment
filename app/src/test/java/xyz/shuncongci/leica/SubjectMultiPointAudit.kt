package xyz.shuncongci.leica

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import xyz.shuncongci.leica.LegendaryMath.Frame
import xyz.shuncongci.leica.LegendaryMath.Point

/** Desktop integration adapter: invokes production selection, union, matte and optics.
 * Test source only, never packaged. Network/Android gestures are not exercised. */
object SubjectMultiPointAudit {
    private fun read(f:File):FloatArray {val b=ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN);return FloatArray(b.remaining()/4){b.float}}
    private fun write(f:File,a:FloatArray){val b=ByteBuffer.allocate(a.size*4).order(ByteOrder.LITTLE_ENDIAN);b.asFloatBuffer().put(a);f.writeBytes(b.array())}
    @JvmStatic fun main(args:Array<String>){
        val mode=args[0];val dir=File(args[1]).apply{mkdirs()};val c=Cancel()
        if(mode=="optics"){optics(dir,c);return}
        val w=args[2].toInt();val h=args[3].toInt()
        val rgb=Frame(w,h,3,read(File(dir,"rgb.bin")))
        val points=read(File(dir,"points.bin")).toList().chunked(2).map{Point(it[0],it[1])}
        when(mode){
            "prepare"->write(File(dir,"sam-input.bin"),SubjectProtectionMath.samInput(rgb,c))
            "union"->{
                val mask=SubjectSelectionMath.union(points,w,h,c){p,at,_->
                    SubjectSelectionMath.select(read(File(dir,"masks-$at.bin")),read(File(dir,"scores-$at.bin")),p,w,h,c).also{write(File(dir,"selected-$at.bin"),it.data)}
                }
                write(File(dir,"union.bin"),mask.data)
                val tri=SubjectProtectionMath.trimap(mask,c);write(File(dir,"trimap.bin"),tri)
                write(File(dir,"matte-input.bin"),SubjectProtectionMath.matteInput(rgb,tri,c).data)
            }
            "finish"->{
                val alpha=SubjectSelectionMath.finish(Frame((w+31)/32*32,(h+31)/32*32,1,read(File(dir,"alpha-padded.bin"))),w,h,read(File(dir,"trimap.bin")),points,c)
                write(File(dir,"alpha.bin"),alpha.data)
                val sharp=LegendaryMath.planes(rgb)
                val result=SubjectProtectionMath.composite(sharp,LegendaryMath.planes(Frame(w,h,3,read(File(dir,"blurred.bin")))),alpha,c)
                write(File(dir,"protected.bin"),LegendaryMath.rgb(result).data)
                var checked=0;for(i in alpha.data.indices)if(alpha.data[i]==1f){for(ch in 0..2)check(sharp.plane(ch)[i]==result.plane(ch)[i]);checked++};check(checked>0)
                val cache=SubjectMaskCache();var calls=0
                check(cache.get(true,"audit",points,c){calls++;alpha}===alpha)
                check(cache.get(false,"audit",points,c){error("disabled inference")}==null)
                check(cache.get(true,"audit",points.reversed(),c){error("recomputed")}===alpha);check(calls==1)
                File(dir,"finish.json").writeText("{\"opaquePixels\":$checked,\"allPointsCovered\":true,\"offOnCacheCalls\":$calls}")
                motion(dir,alpha,c)
            }
            else->error("Unknown audit mode")
        }
    }
    private fun motion(dir:File,alpha:Frame,c:Cancel){
        val w=alpha.w;val h=alpha.h;val focus=.35f
        val depth=Frame(w,h,1,FloatArray(w*h){.1f+.8f*(it%w)/(w-1)})
        val d=SubjectDepthMotion.create(depth,alpha,focus,c);val n=d.coverage.size;val cols=d.foreground.cols;val rows=d.foreground.rows
        write(File(dir,"foreground.bin"),d.foreground.depth);write(File(dir,"background.bin"),d.background.depth);write(File(dir,"coverage.bin"),d.coverage)
        val out=FloatArray(161*2*n*4*3);var at=0;val p=FloatArray(3);val frames=ArrayList<String>()
        for(f in 0..160){val t=min(1f,f/120f);val yaw=if(f<=120).38f else -.8f+(f-121)*1.6f/39;val pitch=if(f<=120)-.14f else .4f*sin((f-121)*PI.toFloat()/39)
            for(layer in 0..1)for(i in 0 until n){val z=if(layer==0)SubjectDepthMotion.z(d.background.depth[i]) else SubjectDepthMotion.movedZ(d.foreground.depth[i],focus,t)
                for(k in 0..3){val dx=if(k==0 || k==3)-.5f else .5f;val dy=if(k<2)-.5f else .5f
                    val x=((i%cols+.5f+dx*.993f)/cols-.5f)*w/max(w,h).toFloat();val y=((i/cols+.5f+dy*.993f)/rows-.5f)*h/max(w,h).toFloat()
                    DepthVisualMath.project(x,y,z,yaw,pitch,p);p.forEach{check(it.isFinite());out[at++]=it}
                }
            };frames.add("{\"progress\":$t,\"yaw\":$yaw,\"pitch\":$pitch}")
        }
        write(File(dir,"projection.bin"),out)
        File(dir,"motion.json").writeText("{\"cols\":$cols,\"rows\":$rows,\"w\":$w,\"h\":$h,\"focus\":$focus,\"frames\":[${frames.joinToString(",")}]}")
    }
    private fun optics(dir:File,c:Cancel){
        val w=769;val h=513;val r=18f;check(r<=FullFrameOptics.maxRadius(w,h));val n=w*h
        val high=Frame(w,h,3,FloatArray(n*3));val spots=ArrayList<Pair<Int,Int>>()
        for(y in 0..4)for(x in 0..6){val sx=48+x*112;val sy=48+y*104;spots.add(sx to sy);for(ch in 0..2)high.data[ch*n+sy*w+sx]=.8f}
        val coc=Frame(w,h,1,FloatArray(n){r});val before=HighlightScatter.opticalAtScale(high,coc,.95f,c,1f);val after=HighlightScatter.optical(high,coc,.95f,c)
        write(File(dir,"before.bin"),before.data);write(File(dir,"after.bin"),after.data)
        File(dir,"optics.json").writeText("{\"w\":$w,\"h\":$h,\"radiusBefore\":$r,\"radiusAfter\":${r*HighlightScatter.RADIUS_SCALE},\"aperture\":0.95,\"gainUnchanged\":true,\"spots\":[${spots.joinToString(","){"[${it.first},${it.second}]"}}]}")
    }
}
