package xyz.shuncongci.leica

import xyz.shuncongci.leica.LegendaryMath.Point

/** Screen-space handle hit testing, source-space deltas. No model/UI mutation until release. */
class PointDrag {
    data class Handle(val point:Point,val subjectIndex:Int=-1)
    data class Change(val handle:Handle,val point:Point)
    var handle:Handle?=null;private set
    var preview:Point?=null;private set
    private var downX=0f;private var downY=0f;private var w=1f;private var h=1f;private var slop=0f
    private var moved=false
    val active get()=handle!=null
    fun begin(handles:List<Handle>,x:Float,y:Float,left:Float,top:Float,width:Float,height:Float,radius:Float,slop:Float):Boolean {
        cancel();require(width>0 && height>0 && radius>0 && slop>=0)
        val hit=handles.map{it to ((left+width*it.point.x-x)*(left+width*it.point.x-x)+(top+height*it.point.y-y)*(top+height*it.point.y-y))}
            .filter{it.second<=radius*radius}.minByOrNull{it.second}?.first?:return false
        handle=hit;preview=hit.point;downX=x;downY=y;w=width;h=height;this.slop=slop;return true
    }
    fun move(x:Float,y:Float){
        val point=handle?.point?:return;val dx=x-downX;val dy=y-downY
        if(dx*dx+dy*dy>slop*slop)moved=true
        if(moved)preview=Point((point.x+dx/w).coerceIn(0f,1f),(point.y+dy/h).coerceIn(0f,1f))
    }
    fun display(point:Point,subjectIndex:Int=-1):Point=if(handle?.subjectIndex==subjectIndex)preview?:point else point
    fun finish():Change? {
        val at=handle;val point=preview
        val change=if(moved && at!=null && point!=null && at.point!=point)Change(at,point)else null
        cancel();return change
    }
    fun cancel(){handle=null;preview=null;moved=false}
}
