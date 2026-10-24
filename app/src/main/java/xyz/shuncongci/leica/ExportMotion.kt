package xyz.shuncongci.leica

import kotlin.math.max
import kotlin.math.min

/** Pure motion/coordinate contract shared by native UI and JVM tests. No clamped spring or second-window offsets. */
object ExportMotion {
    data class Rect(val left:Float,val top:Float,val right:Float,val bottom:Float){
        init{require(listOf(left,top,right,bottom).all{it.isFinite()} && right>left && bottom>top)}
        val width get()=right-left;val height get()=bottom-top
        fun offset(x:Float,y:Float)=Rect(left+x,top+y,right+x,bottom+y)
    }
    /** View's matrix followed by its layout offset minus its parent's scroll. */
    data class Step(val a:Float=1f,val b:Float=0f,val c:Float=0f,val d:Float=0f,val e:Float=1f,val f:Float=0f,
                    val x:Float=0f,val y:Float=0f)
    fun map(rect:Rect,steps:List<Step>):Rect {
        val points=arrayOf(floatArrayOf(rect.left,rect.top),floatArrayOf(rect.right,rect.top),floatArrayOf(rect.right,rect.bottom),floatArrayOf(rect.left,rect.bottom))
        for(s in steps)for(p in points){val x=p[0];val y=p[1];p[0]=s.a*x+s.b*y+s.c+s.x;p[1]=s.d*x+s.e*y+s.f+s.y}
        return Rect(points.minOf{it[0]},points.minOf{it[1]},points.maxOf{it[0]},points.maxOf{it[1]})
    }
    /** Solve x(u)=time, then evaluate y(u); not the common mistake of using u=time. */
    fun bezier(time:Float,x1:Double,y1:Double,x2:Double,y2:Double):Float {
        val t=time.coerceIn(0f,1f);if(t==0f || t==1f)return t
        fun curve(u:Double,p:Double,q:Double):Double {val v=1-u;return 3*v*v*u*p+3*v*u*u*q+u*u*u}
        var low=0.0;var high=1.0
        repeat(28){val u=(low+high)/2;if(curve(u,x1,x2)<t)low=u else high=u}
        return curve((low+high)/2,y1,y2).toFloat().coerceIn(0f,1f)
    }
    fun openEase(time:Float)=bezier(time,.2,0.0,0.0,1.0)
    fun closeEase(time:Float)=bezier(time,.4,0.0,.8,1.0)
    fun mix(from:Rect,to:Rect,progress:Float):Rect {
        if(progress<=0)return from;if(progress>=1)return to
        fun m(a:Float,b:Float)=a+(b-a)*progress
        return Rect(m(from.left,to.left),m(from.top,to.top),m(from.right,to.right),m(from.bottom,to.bottom))
    }
    fun smooth(value:Float):Float {val x=value.coerceIn(0f,1f);return x*x*(3-2*x)}
    fun content(progress:Float,index:Int):Float {val start=.1f+index.coerceIn(0,10)*.035f;return smooth((progress-start)/(1-start))}
    fun ghost(progress:Float)=1-smooth(progress/.22f)

    enum class Phase { NEW, OPENING, OPEN, CLOSING, DISMISSED }
    data class Run(val generation:Int,val from:Float,val to:Float){
        val opening get()=to>from
        fun ease(t:Float)=if(opening)openEase(t)else closeEase(t)
        fun value(t:Float)=from+(to-from)*ease(t)
    }
    class State {
        var phase=Phase.NEW;private set
        var progress=0f;private set
        private var generation=0;private var active:Run?=null
        fun open():Run {check(phase==Phase.NEW);phase=Phase.OPENING;return Run(++generation,progress,1f).also{active=it}}
        fun close():Run? {if(phase==Phase.CLOSING || phase==Phase.DISMISSED)return null;phase=Phase.CLOSING;return Run(++generation,progress,0f).also{active=it}}
        fun accept(run:Run,value:Float):Boolean {
            if(active!=run || phase==Phase.DISMISSED)return false
            progress=value.coerceIn(min(run.from,run.to),max(run.from,run.to));return true
        }
        fun complete(run:Run):Boolean {
            if(active!=run || phase==Phase.DISMISSED)return false
            progress=run.to;phase=if(run.to==1f)Phase.OPEN else Phase.DISMISSED;active=null;return true
        }
        fun dismiss(){generation++;active=null;phase=Phase.DISMISSED}
    }
}
