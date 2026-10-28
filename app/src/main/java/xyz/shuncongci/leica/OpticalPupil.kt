package xyz.shuncongci.leica

import kotlin.math.*

/** Mild field-dependent pupil approximation. Tangential major axis, radial compression;
 * not a fitted prescription of a specific lens. This kernel preserves its supplied
 * long-axis radius; HighlightScatter applies the highlight-only enlargement. */
object OpticalPupil {
    const val MIN_RADIAL_RATIO=.82f
    const val GRID_INTERVALS=4
    data class Shape(val ux:Double,val uy:Double,val radialRatio:Double)
    fun shape(x:Double,y:Double,w:Int,h:Int):Shape {
        require(w>0 && h>0 && x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0)
        val dx=(x-.5)*(w-1);val dy=(y-.5)*(h-1);val r=hypot(dx,dy)
        val bound=hypot((w-1)/2.0,(h-1)/2.0)
        val t=if(bound==0.0)0.0 else (r/bound).coerceIn(0.0,1.0)
        val smooth=t*t*(3-2*t)
        return Shape(if(r==0.0)1.0 else dx/r,if(r==0.0)0.0 else dy/r,1-(1-MIN_RADIAL_RATIO)*smooth)
    }
    data class Node(val x:Double,val y:Double,val wx:FloatArray,val wy:FloatArray,val x0:Int,val x1:Int)
    internal fun nodes(w:Int,h:Int):List<Node> {
        fun axis(size:Int):List<Pair<Double,FloatArray>> {
            if(size==1)return listOf(.5 to floatArrayOf(1f))
            return (0..GRID_INTERVALS).map{n-> n.toDouble()/GRID_INTERVALS to FloatArray(size){i->
                max(0.0,1-abs(i.toDouble()/(size-1)*GRID_INTERVALS-n)).toFloat()
            }}
        }
        return axis(h).flatMap{(y,wy)->axis(w).map{(x,wx)->Node(x,y,wx,wy,wx.indexOfFirst{it>0f},wx.indexOfLast{it>0f})}}
    }
    internal fun nodes(region:BokehRegion):List<Node> = nodes(region.fullW,region.fullH).mapNotNull{n->
        val wx=n.wx.copyOfRange(region.box.left,region.box.right);val wy=n.wy.copyOfRange(region.box.top,region.box.bottom)
        val lo=wx.indexOfFirst{it>0f};val hi=wx.indexOfLast{it>0f}
        if(lo<0 || wy.none{it>0f})null else Node(n.x,n.y,wx,wy,lo,hi)
    }
    internal fun kernel(radius:Int,aperture:Float,shape:Shape,cancel:Cancel):LegendaryMath.Kernel {
        cancel.check();require(radius>=0);FullFrameOptics.checkAperture(aperture)
        if(radius==0 || shape.radialRatio==1.0)return LegendaryMath.kernel(radius,aperture)
        val half=radius+1;val w=half*2+1;val data=FloatArray(w*w)
        val round=when(aperture){.95f->1.0;1.8f->.85;2f->.8;2.8f->.65;4f->.45;else->.25}
        val inner=radius*(round+(1-round)*cos(PI/9));val allowance=sqrt(.5)/shape.radialRatio
        val extra=1/shape.radialRatio-1;val period=2*PI/9
        fun inside(dx:Double,dy:Double):Boolean {
            val radial=(dx*shape.ux+dy*shape.uy)*extra
            val x=dx+radial*shape.ux;val y=dy+radial*shape.uy
            // At full aperture the boundary is a circle in pupil coordinates;
            // atan/cos terms multiply by zero. Same hypot comparison, no approximation.
            if(round==1.0)return hypot(x,y)<=radius
            val theta=atan2(y,x);val sector=((theta+PI/9)%period+period)%period-PI/9
            return hypot(x,y)<=radius*(round+(1-round)*cos(PI/9)/cos(sector))
        }
        for(y in 0 until w){cancel.check();for(x in 0 until w){
            val dx=(x-half).toDouble();val dy=(y-half).toDouble();val radial=(dx*shape.ux+dy*shape.uy)*extra
            val distance=hypot(dx+radial*shape.ux,dy+radial*shape.uy)
            if(distance<inner-allowance){data[y*w+x]=1f;continue}
            if(distance>radius+allowance)continue
            var covered=0
            for(yy in 0..7)for(xx in 0..7)if(inside(dx+(xx+.5)/8-.5,dy+(yy+.5)/8-.5))covered++
            data[y*w+x]=covered/64f
        }}
        val sum=data.sumOf{it.toDouble()};check(sum>0)
        for(i in data.indices)data[i]=(data[i]/sum).toFloat()
        return LegendaryMath.Kernel(half,data)
    }
}
