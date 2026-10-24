package xyz.shuncongci.leica

import kotlin.math.*

object DepthVisualMath {
    // Display-only density, shared by coarse/full relief and subject layers. No model size change.
    const val SPACE_GRID_EDGE=48
    fun spatialGrid(w:Int,h:Int):Pair<Int,Int> {
        val (cols,rows)=grid(w,h,SPACE_GRID_EDGE)
        return cols.coerceAtMost(w) to rows.coerceAtMost(h)
    }
    data class Tiles(val cols:Int,val rows:Int,val depth:FloatArray)
    data class Data(val w:Int,val h:Int,val gray:IntArray,val thermal:IntArray,val tiles:Tiles)
    private val palette=intArrayOf(0x101025,0x312366,0x842C7F,0xD44B53,0xF69355,0xFFE6AE)
    fun heat(d:Float):Int {
        require(d.isFinite() && d in 0f..1f)
        val t=d*(palette.size-1);val i=t.toInt().coerceAtMost(palette.size-2);val f=t-i
        fun channel(shift:Int)=(((palette[i] shr shift) and 255)*(1-f)+((palette[i+1] shr shift) and 255)*f).roundToInt()
        return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
    fun grid(w:Int,h:Int,long:Int):Pair<Int,Int> {
        require(w>0 && h>0 && long>0)
        return if(w>=h)long to max(1,(long*h.toDouble()/w).roundToInt()) else max(1,(long*w.toDouble()/h).roundToInt()) to long
    }
    fun create(d:LegendaryMath.Frame,cancel:Cancel):Data {
        cancel.check();require(d.channels==1 && d.data.all{it.isFinite() && it in 0f..1f})
        val scale=min(1.0,960.0/max(d.w,d.h));val w=max(1,(d.w*scale).roundToInt());val h=max(1,(d.h*scale).roundToInt())
        val gray=IntArray(w*h);val thermal=IntArray(w*h)
        for(y in 0 until h){cancel.check();for(x in 0 until w){
            val v=FullFrameOptics.sample(d,x,y,w,h).coerceIn(0f,1f);val n=(v*255).roundToInt()
            gray[y*w+x]=(255 shl 24) or (n shl 16) or (n shl 8) or n;thermal[y*w+x]=heat(v)
        }}
        val (cols,rows)=spatialGrid(d.w,d.h);val depths=FloatArray(cols*rows)
        for(y in 0 until rows){cancel.check();for(x in 0 until cols){
            val x0=x*d.w/cols;val x1=max(x0+1,(x+1)*d.w/cols).coerceAtMost(d.w)
            val y0=y*d.h/rows;val y1=max(y0+1,(y+1)*d.h/rows).coerceAtMost(d.h)
            var sum=0.0;var n=0
            for(yy in y0 until y1)for(xx in x0 until x1){sum+=d.data[yy*d.w+xx];n++}
            depths[y*cols+x]=(sum/n).toFloat()
        }}
        cancel.check();return Data(w,h,gray,thermal,Tiles(cols,rows,depths))
    }
    fun phase(index:Int):Float=((index*73+19)%997)/997f
    fun loadingZ(index:Int,time:Float):Float {
        val p=phase(index)*2*PI
        return (.30*sin(time*2*PI+p)+.16*cos(time*4*PI+p*3)).toFloat()
    }
    /** Reused by the dense relief: four trig evaluations per frame, not per tile corner. */
    class Projector {
        private var cy=1f;private var sy=0f;private var cp=1f;private var sp=0f
        fun setAngles(yaw:Float,pitch:Float){cy=cos(yaw);sy=sin(yaw);cp=cos(pitch);sp=sin(pitch)}
        fun project(x:Float,y:Float,z:Float,out:FloatArray,offset:Int=0)=
            projectCamera(x,y,z,cy,sy,cp,sp,out,offset)
    }
    fun project(x:Float,y:Float,z:Float,yaw:Float,pitch:Float,out:FloatArray,offset:Int=0) =
        projectCamera(x,y,z,cos(yaw),sin(yaw),cos(pitch),sin(pitch),out,offset)
    private fun projectCamera(x:Float,y:Float,z:Float,cy:Float,sy:Float,cp:Float,sp:Float,out:FloatArray,offset:Int) {
        val xx=x*cy+z*sy;val zz=-x*sy+z*cy;val yy=y*cp-zz*sp;val depth=y*sp+zz*cp
        val perspective=3.8f/(3.8f-depth)
        out[offset]=xx*perspective;out[offset+1]=yy*perspective;out[offset+2]=depth
    }
}
