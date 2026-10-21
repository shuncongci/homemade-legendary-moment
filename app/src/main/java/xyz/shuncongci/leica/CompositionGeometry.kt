package xyz.shuncongci.leica

import kotlin.math.*

/** Coordinates are always in the EXIF-upright source, never the displayed view or depth map. */
object CompositionGeometry {
    const val ANALYSIS_EDGE=1220
    data class Point(val x:Double,val y:Double) {
        init { require(x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0) }
    }
    data class Crop(val l:Double,val t:Double,val r:Double,val b:Double) {
        init { require(listOf(l,t,r,b).all{it.isFinite()} && l>=0 && t>=0 && r<=1 && b<=1 && r>l && b>t) }
        val w get()=r-l;val h get()=b-t;val area get()=w*h
        fun contains(p:Point,margin:Double=0.0)=p.x>=l+margin*w && p.x<=r-margin*w && p.y>=t+margin*h && p.y<=b-margin*h
    }
    val FULL=Crop(0.0,0.0,1.0,1.0)
    fun analysisSize(w:Int,h:Int):Pair<Int,Int> {
        require(w>0 && h>0)
        val scale=min(1.0,ANALYSIS_EDGE.toDouble()/max(w,h))
        return max(1,(w*scale).roundToInt()) to max(1,(h*scale).roundToInt())
    }
    /** Decode at least the requested detail, then downsample to the exact analysis budget. */
    fun sample(w:Int,h:Int):Int {
        val (tw,th)=analysisSize(w,h);var s=1
        while(w/(s*2)>=tw && h/(s*2)>=th)s*=2
        return s
    }
    fun fit(w:Int,h:Int,ratio:Double,scale:Double=1.0,cx:Double=.5,cy:Double=.5):Crop {
        require(w>0 && h>0 && ratio.isFinite() && ratio>0 && scale>0 && scale<=1)
        val q=ratio*h/w;val cw=min(1.0,q)*scale;val ch=min(1.0,1/q)*scale
        return placed(cw,ch,cx-cw/2,cy-ch/2)
    }
    fun placed(w:Double,h:Double,l:Double,t:Double):Crop {
        require(w>0 && h>0 && w<=1 && h<=1 && l.isFinite() && t.isFinite())
        val x=l.coerceIn(0.0,1-w);val y=t.coerceIn(0.0,1-h)
        return Crop(x,y,min(1.0,x+w),min(1.0,y+h))
    }
    fun move(c:Crop,dx:Double,dy:Double)=placed(c.w,c.h,c.l+dx,c.t+dy)
    fun zoom(c:Crop,factor:Double):Crop {
        require(factor.isFinite() && factor>0)
        val f=factor.coerceIn(max(.03/c.w,.03/c.h).coerceAtMost(1.0),min(1/c.w,1/c.h))
        return placed(c.w*f,c.h*f,(c.l+c.r-c.w*f)/2,(c.t+c.b-c.h*f)/2)
    }
    /** Half-open source pixel box. Round edges once; preview and export use this exact box. */
    fun pixels(c:Crop,w:Int,h:Int):NativeImageMath.Box {
        require(w>0 && h>0)
        val l=(c.l*w).roundToInt().coerceIn(0,w-1);val t=(c.t*h).roundToInt().coerceIn(0,h-1)
        return NativeImageMath.Box(l,t,(c.r*w).roundToInt().coerceIn(l+1,w),(c.b*h).roundToInt().coerceIn(t+1,h))
    }
    fun snapped(c:Crop,w:Int,h:Int):Crop {val b=pixels(c,w,h);return Crop(b.left.toDouble()/w,b.top.toDouble()/h,b.right.toDouble()/w,b.bottom.toDouble()/h)}
    fun iou(a:Crop,b:Crop):Double {val v=max(0.0,min(a.r,b.r)-max(a.l,b.l))*max(0.0,min(a.b,b.b)-max(a.t,b.t));return v/(a.area+b.area-v)}
}

/** Only an explicit apply commits a recipe; every subsequent edit invalidates saving. */
class CompositionRecipe {
    var draft=CompositionGeometry.FULL;private set
    var applied:CompositionGeometry.Crop?=null;private set
    private val history=java.util.ArrayDeque<CompositionGeometry.Crop>()
    val canUndo get()=history.isNotEmpty()
    val canSave get()=applied!=null && applied==draft
    fun edit(c:CompositionGeometry.Crop) {if(c==draft)return;history.addLast(draft);if(history.size>40)history.removeFirst();draft=c;applied=null}
    fun undo(){if(history.isNotEmpty()){draft=history.removeLast();applied=null}}
    fun reset()=edit(CompositionGeometry.FULL)
    fun resumeEditing(){applied=null}
    fun apply(w:Int,h:Int):CompositionGeometry.Crop {draft=CompositionGeometry.snapped(draft,w,h);applied=draft;return draft}
    fun restore(d:CompositionGeometry.Crop,a:CompositionGeometry.Crop?){draft=d;applied=if(a==d)a else null;history.clear()}
}
