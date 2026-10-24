package xyz.shuncongci.leica

import kotlin.math.*

/** Presentation only: split real alpha into foreground/background, never edit the depth cache. */
object SubjectDepthMotion {
    data class Data(val alpha:LegendaryMath.Frame,val foreground:DepthVisualMath.Tiles,
                    val background:DepthVisualMath.Tiles,val coverage:FloatArray,val focus:Float)
    const val DURATION_MS=1200L
    fun z(depth:Float)=(depth-.5f)*.95f
    fun movedZ(depth:Float,focus:Float,progress:Float)=when{progress>=1f->z(focus);progress<=0f->z(depth);else->ProgressiveDepth.blend(z(depth),z(focus),progress)}
    fun create(depth:LegendaryMath.Frame,alpha:LegendaryMath.Frame,focus:Float,cancel:Cancel):Data {
        cancel.check();require(depth.channels==1 && alpha.channels==1 && focus.isFinite() && focus in 0f..1f)
        require(depth.data.all{it.isFinite() && it in 0f..1f} && alpha.data.all{it.isFinite() && it in 0f..1f})
        val (cols,rows)=DepthVisualMath.spatialGrid(alpha.w,alpha.h)
        val fg=FloatArray(cols*rows);val bg=FloatArray(cols*rows);val coverage=FloatArray(cols*rows)
        for(y in 0 until rows){cancel.check();for(x in 0 until cols){
            val x0=x*alpha.w/cols;val x1=max(x0+1,(x+1)*alpha.w/cols).coerceAtMost(alpha.w)
            val y0=y*alpha.h/rows;val y1=max(y0+1,(y+1)*alpha.h/rows).coerceAtMost(alpha.h)
            var fs=0.0;var bs=0.0;var mass=0.0;var total=0.0;var count=0
            for(yy in y0 until y1)for(xx in x0 until x1){
                val a=alpha.data[yy*alpha.w+xx].toDouble();val d=FullFrameOptics.sample(depth,xx,yy,alpha.w,alpha.h).toDouble()
                mass+=a;fs+=a*d;bs+=(1-a)*d;total+=d;count++
            }
            val i=y*cols+x;val mean=total/count
            fg[i]=(if(mass>1e-8)fs/mass else mean).toFloat()
            bg[i]=(if(count-mass>1e-8)bs/(count-mass) else mean).toFloat();coverage[i]=(mass/count).toFloat()
        }}
        cancel.check();return Data(alpha,DepthVisualMath.Tiles(cols,rows,fg),DepthVisualMath.Tiles(cols,rows,bg),coverage,focus)
    }
    /** Complementary alpha textures; no duplicate opaque subject left at the old depth. */
    fun layerPixels(pixels:IntArray,w:Int,h:Int,alpha:LegendaryMath.Frame,foreground:Boolean):IntArray {
        require(pixels.size==w*h)
        return IntArray(pixels.size){i->
            val a=FullFrameOptics.sample(alpha,i%w,i/w,w,h).coerceIn(0f,1f)
            val src=pixels[i];val fa=((src ushr 24)*a).roundToInt()
            val opacity=if(foreground)fa else (src ushr 24)-fa
            (opacity shl 24) or (src and 0xffffff)
        }
    }
}
