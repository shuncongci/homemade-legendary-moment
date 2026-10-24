package xyz.shuncongci.leica

import kotlin.math.*

/** Relative fused orientation, not raw angular-velocity integration or magnetic azimuth.
 * Android rotation vectors are device->world quaternions (x,y,z,w); heading never enters UI.
 * Only reference-local screen X/Y rotations drive a small display-only camera overlay. */
class SpaceParallax {
    companion object {
        const val MAX_YAW=.24f
        const val MAX_PITCH=.18f
        const val GAIN=.65
        const val DEAD_ZONE=.004
        const val SMOOTH_SECONDS=.09
        const val MAX_GAP_NS=500_000_000L
    }
    private data class Q(val x:Double,val y:Double,val z:Double,val w:Double)
    private var reference:Q?=null
    private var lastAt=-1L
    private var rotation=-1
    var enabled=false;private set
    var yaw=0f;private set
    var pitch=0f;private set
    fun start(){enabled=true;lastAt=-1L;rotation=-1;recenter()}
    fun stop(){enabled=false;lastAt=-1L;rotation=-1;recenter()}
    /** The next valid sample becomes neutral; old queued timestamps are still rejected. */
    fun recenter(){reference=null;yaw=0f;pitch=0f}
    private fun quaternion(v:FloatArray):Q? {
        if(v.size<3 || !(0 until min(4,v.size)).all{v[it].isFinite()})return null
        val x=v[0].toDouble();val y=v[1].toDouble();val z=v[2].toDouble()
        val xyz=x*x+y*y+z*z
        if(v.size==3 && xyz>1.0001)return null
        val w=if(v.size>=4)v[3].toDouble() else sqrt(max(0.0,1.0-xyz))
        val norm=sqrt(xyz+w*w);if(!norm.isFinite() || norm<1e-6)return null
        return Q(x/norm,y/norm,z/norm,w/norm)
    }
    /** rotation=Surface.ROTATION_0..270 (0..3), timestamp in monotonic nanoseconds.
     * false means ignored without mutation; true includes the neutral reference sample. */
    fun sample(values:FloatArray,at:Long,screenRotation:Int):Boolean {
        if(!enabled || at<0 || at<=lastAt || screenRotation !in 0..3)return false
        val q=quaternion(values)?:return false
        val oldAt=lastAt;lastAt=at
        if(reference==null || rotation!=screenRotation || (oldAt>=0 && at-oldAt>MAX_GAP_NS)){
            reference=q;rotation=screenRotation;yaw=0f;pitch=0f;return true
        }
        val r=checkNotNull(reference)
        // conjugate(reference) * current, in the axes of the original held phone.
        var x=r.w*q.x-r.x*q.w-r.y*q.z+r.z*q.y
        var y=r.w*q.y+r.x*q.z-r.y*q.w-r.z*q.x
        var z=r.w*q.z-r.x*q.y+r.y*q.x-r.z*q.w
        var w=r.w*q.w+r.x*q.x+r.y*q.y+r.z*q.z
        if(w<0){x=-x;y=-y;z=-z;w=-w} // q and -q are the same pose; shortest rotation
        val length=sqrt(x*x+y*y+z*z)
        val scale=if(length<1e-10)2.0 else 2*atan2(length,w)/length
        val rx=x*scale;val ry=y*scale
        // Same screen axes as Android remapCoordinateSystem(X,Y), (Y,-X), (-X,-Y), (-Y,X).
        val screenX=when(screenRotation){0->rx;1->ry;2->-rx;else->-ry}
        val screenY=when(screenRotation){0->ry;1->-rx;2->-ry;else->rx}
        fun target(angle:Double,limit:Float):Double {
            val clean=if(abs(angle)<=DEAD_ZONE)0.0 else sign(angle)*(abs(angle)-DEAD_ZONE)
            return (-clean*GAIN).coerceIn(-limit.toDouble(),limit.toDouble())
        }
        val alpha=1-exp(-(at-oldAt)/1e9/SMOOTH_SECONDS)
        yaw=(yaw+(target(-screenY,MAX_YAW)-yaw)*alpha).toFloat()
        pitch=(pitch+(target(screenX,MAX_PITCH)-pitch)*alpha).toFloat()
        if(abs(yaw)<.00001f)yaw=0f;if(abs(pitch)<.00001f)pitch=0f
        return true
    }
}
