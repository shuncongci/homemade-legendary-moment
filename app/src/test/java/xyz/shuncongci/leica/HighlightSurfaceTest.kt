package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*

/** Regression scenes are synthetic, not claims of semantic emitter recognition. */
class HighlightSurfaceTest {
    private fun scene(w:Int=161,h:Int=121, pixel:(Int,Int)->Int):Planes = Planes(w,h).apply {
        for(y in 0 until h)for(x in 0 until w)for(c in 0..2)plane(c)[y*w+x]=pixel(x,y).toByte()
    }
    private fun noLight(p:Planes) { val (base,high)=LegendaryMath.split(p,Cancel());assertTrue("diffuse surface became a light",high.data.all{it==0f});for(c in 0..2)for(i in 0 until p.size)assertEquals((p.plane(c)[i].toInt() and 255)/255f,base.data[c*p.size+i],0f) }
    @Test fun texturedBrightSurfaceIsNotManySmallLamps(){noLight(scene{x,y->if(x%14 in 6..8 && y%14 in 6..8)220 else 170})}
    @Test fun flatWhiteIsNotALamp(){noLight(scene{_,_->220})}
    @Test fun flatSaturatedSurfaceIsNotALamp(){noLight(scene{_,_->255})}
    @Test fun broadBrightSubjectIsNotALamp(){noLight(scene{x,y->if(x in 35..125 && y in 25..95)220 else 30})}
    @Test fun widePuddleReflectionIsNotALamp(){noLight(scene{x,y->if(((x-80)/55.0).pow(2)+((y-65)/20.0).pow(2)<1)230 else 35})}
    @Test fun longThinReflectionIsNotALamp(){noLight(scene{x,y->if(x in 15..145 && y in 60..62)245 else 25})}
    @Test fun smoothBrightGradientIsNotALamp(){noLight(scene{x,_->160+x/2})}
    @Test fun brightSurfaceSkipsOpticalCompositor(){val p=scene{_,_->220};var calls=0;val out=LegendaryMath.renderCoc(p,Frame(161,121,1,FloatArray(p.size){4f}),.95f,Cancel(),highlightRenderer={a,_,_,_->calls++;a}){a,_->a};assertEquals(0,calls);for(c in 0..2)assertArrayEquals(p.plane(c),out.plane(c))}
    @Test fun compactWhiteLampSurvives(){val p=scene{x,y->if(x in 78..82 && y in 58..62)245 else 20};assertTrue(LegendaryMath.split(p,Cancel()).second.data.sum()>1f)}
    @Test fun separatedLampsSurvive(){val p=scene{x,y->if(y in 58..62 && (x in 38..42 || x in 118..122))220 else 20};val hi=LegendaryMath.split(p,Cancel()).second;assertTrue(hi.data[60*161+40]>0f);assertTrue(hi.data[60*161+120]>0f)}
    @Test fun cornerLampSurvives(){val p=scene{x,y->if(x<3 && y<3)240 else 20};assertTrue(LegendaryMath.split(p,Cancel()).second.data.sum()>0f)}
    @Test fun compactLampBesideLargeSubjectSurvives(){val p=scene{x,y->when{ x in 15..65 && y in 25..95->230;x in 118..122 && y in 58..62->240;else->20}};val h=LegendaryMath.split(p,Cancel()).second;assertEquals(0f,h.data[60*161+30],0f);assertTrue(h.data[60*161+120]>0f)}
    @Test fun sourceAndCocAreNotMutated(){val p=scene{x,y->if(x in 78..82 && y in 58..62)220 else 20};val before=(0..2).map{p.plane(it).copyOf()};LegendaryMath.split(p,Cancel());for(c in 0..2)assertArrayEquals(before[c],p.plane(c))}
}

object HighlightFinalAudit {
    @JvmStatic fun main(args:Array<String>) {
        val w=513;val h=385;val n=w*h
        val p=Planes(w,h,ByteArray(n){220.toByte()},ByteArray(n){220.toByte()},ByteArray(n){220.toByte()})
        val split=LegendaryMath.split(p,Cancel())
        println("flat220 highPixels="+(0 until n).count{split.second.data[it]>0f}+" linearEnergy="+split.second.data.sum())
        val c=Frame(w,h,1,FloatArray(n){i->(i%w)/(w-1f)*14f-7f})
        for(kind in listOf("sparse","dense")) {
            val hi=Frame(w,h,3,FloatArray(3*n){i->if(kind=="dense" || (i%n%w in 251..261 && i%n/w in 187..197)) .3f else 0f})
            var last:Frame?=null
            fun run():Double {val t=System.nanoTime();last=HighlightScatter.optical(hi,c,.95f,Cancel());return (System.nanoTime()-t)/1e6}
            run();val times=List(3){run()};println("$kind timesMs=$times medianMs=${times.sorted()[1]} checksum=${last!!.data.sum()}")
        }
        if(args.isNotEmpty()) {
            val dir=java.io.File(args[0]);dir.mkdirs()
            val scene=Planes(161,121)
            for(y in 0 until 121)for(x in 0 until 161)for(ch in 0..2)scene.plane(ch)[y*161+x]=(if(x in 15..65 && y in 25..95)230 else if(x in 118..122 && y in 58..62)240 else 20).toByte()
            val hi=LegendaryMath.split(scene,Cancel()).second
            println("mixed subjectHigh=${hi.data[60*161+30]} lampHigh=${hi.data[60*161+120]}")
            fun save(name:String,p:Planes){java.io.File(dir,name).outputStream().use { out -> out.write("P6\n${p.width} ${p.height}\n255\n".toByteArray());for(i in 0 until p.size)for(ch in 0..2)out.write(p.plane(ch)[i].toInt() and 255)}}
            save("scene.png",scene)
            val coc=Frame(161,121,1,FloatArray(scene.size){4f})
            save("render.png",LegendaryMath.renderCoc(scene,coc,.95f,Cancel()){a,_->a})
        }
    }
}
