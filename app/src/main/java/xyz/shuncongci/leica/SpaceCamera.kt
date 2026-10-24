package xyz.shuncongci.leica

import kotlin.math.*

/** Display camera only: never enters depth cache, focus selection or image export. */
class SpaceCamera {
    companion object { const val MIN_ZOOM=.5f;const val MAX_ZOOM=4f;const val DEFAULT_YAW=.38f;const val DEFAULT_PITCH=-.14f }
    var yaw=DEFAULT_YAW;private set
    var pitch=DEFAULT_PITCH;private set
    var zoom=1f;private set
    var panX=0f;private set
    var panY=0f;private set
    var gyroMode=false;private set
    private var parallaxYaw=0f;private var parallaxPitch=0f
    /** Explicit mode transition, not sensor ACTIVE/PAUSED callbacks. Keep zoom, center pan. */
    fun setGyroMode(value:Boolean){
        if(gyroMode==value)return
        gyroMode=value;yaw=if(value)0f else DEFAULT_YAW;pitch=if(value)0f else DEFAULT_PITCH
        panX=0f;panY=0f;setParallax(0f,0f)
    }
    val displayYaw get()=(yaw+parallaxYaw).coerceIn(-.85f,.85f)
    val displayPitch get()=(pitch+parallaxPitch).coerceIn(-.55f,.55f)
    fun setParallax(yaw:Float,pitch:Float){
        require(yaw.isFinite() && pitch.isFinite())
        parallaxYaw=yaw.coerceIn(-SpaceParallax.MAX_YAW,SpaceParallax.MAX_YAW)
        parallaxPitch=pitch.coerceIn(-SpaceParallax.MAX_PITCH,SpaceParallax.MAX_PITCH)
    }
    fun reset(){yaw=if(gyroMode)0f else DEFAULT_YAW;pitch=if(gyroMode)0f else DEFAULT_PITCH;zoom=1f;panX=0f;panY=0f;setParallax(0f,0f)}
    fun rotate(dx:Float,dy:Float){
        require(dx.isFinite() && dy.isFinite())
        // Reserve the full, symmetric sensor range; combined clamp must not eat one side.
        val yLimit=if(gyroMode).85f-SpaceParallax.MAX_YAW else .85f
        val pLimit=if(gyroMode).55f-SpaceParallax.MAX_PITCH else .55f
        yaw=(yaw+dx*1.8f).coerceIn(-yLimit,yLimit);pitch=(pitch-dy).coerceIn(-pLimit,pLimit)
    }
    /** Focus coordinates are normalized to this viewport, not source-image coordinates. */
    fun transform(factor:Float,oldX:Float,oldY:Float,newX:Float,newY:Float){
        require(factor.isFinite() && factor>0f && listOf(oldX,oldY,newX,newY).all{it.isFinite()})
        val next=(zoom.toDouble()*factor).coerceIn(MIN_ZOOM.toDouble(),MAX_ZOOM.toDouble()).toFloat()
        val ratio=next/zoom;zoom=next
        val limit=min(1.5f,.45f*zoom)
        panX=((newX-.5f)+(panX-(oldX-.5f))*ratio).coerceIn(-limit,limit)
        panY=((newY-.5f)+(panY-(oldY-.5f))*ratio).coerceIn(-limit,limit)
    }
    fun zoomBy(factor:Float)=transform(factor,.5f,.5f,.5f,.5f)
}

/** MotionEvent-free gesture state; pointer rebase/cancel never applies an old delta. */
class SpaceTouch(private val camera:SpaceCamera,private val slop:Float,private val doubleSlop:Float,
                 private val doubleTimeout:Long=300L,private val onReset:()->Unit={}) {
    data class Finger(val id:Int,val x:Float,val y:Float)
    private enum class Mode { NONE, ORBIT, TRANSFORM, WAIT }
    private var mode=Mode.NONE
    private var first=-1;private var second=-1
    private var lastX=0f;private var lastY=0f;private var lastSpan=0f
    private var downX=0f;private var downY=0f;private var downAt=0L
    private var tap=false;private var orbitStarted=false;private var multi=false
    private var tapAt=-1L;private var tapX=0f;private var tapY=0f
    init { require(slop.isFinite() && slop>0 && doubleSlop.isFinite() && doubleSlop>=slop && doubleTimeout>0) }
    fun down(f:Finger,time:Long){
        mode=Mode.ORBIT;first=f.id;second=-1;downX=f.x;downY=f.y;lastX=f.x;lastY=f.y
        downAt=time;tap=true;orbitStarted=false;multi=false
    }
    fun rebase(points:List<Finger>){
        if(mode==Mode.NONE)return
        tap=false;tapAt=-1L
        if(points.size>=2){
            multi=true;mode=Mode.TRANSFORM;val a=points[0];val b=points[1];first=a.id;second=b.id
            lastX=(a.x+b.x)*.5f;lastY=(a.y+b.y)*.5f;lastSpan=hypot(a.x-b.x,a.y-b.y)
        }else mode=Mode.WAIT // a finger left after pinching must not suddenly rotate the photo
    }
    fun move(points:List<Finger>,w:Int,h:Int){
        if(w<=0 || h<=0 || mode==Mode.NONE || mode==Mode.WAIT)return
        if(mode==Mode.ORBIT){
            if(points.size!=1){rebase(points);return}
            val f=points.firstOrNull{it.id==first}?:run{rebase(points);return}
            if(!orbitStarted && hypot(f.x-downX,f.y-downY)>slop){orbitStarted=true;tap=false;tapAt=-1L}
            if(orbitStarted)camera.rotate((f.x-lastX)/w,(f.y-lastY)/h)
            // Until crossing slop retain the DOWN position, so the first real drag is continuous.
            if(orbitStarted){lastX=f.x;lastY=f.y}
        }else{
            val a=points.firstOrNull{it.id==first};val b=points.firstOrNull{it.id==second}
            if(a==null || b==null){rebase(points);return}
            val x=(a.x+b.x)*.5f;val y=(a.y+b.y)*.5f;val span=hypot(a.x-b.x,a.y-b.y)
            val factor=if(span>=slop && lastSpan>=slop)span/lastSpan else 1f
            camera.transform(factor,lastX/w,lastY/h,x/w,y/h);lastX=x;lastY=y;lastSpan=span
        }
    }
    /** Returns a real click; multi-touch/drag/long press never masquerade as a tap. */
    fun up(f:Finger,time:Long):Boolean {
        val clicked=mode==Mode.ORBIT && tap && !multi && f.id==first && time-downAt in 0L..250L && hypot(f.x-downX,f.y-downY)<=slop
        if(clicked){
            if(tapAt>=0 && time-tapAt in 0L..doubleTimeout && hypot(f.x-tapX,f.y-tapY)<=doubleSlop){camera.reset();onReset();tapAt=-1L}
            else {tapAt=time;tapX=f.x;tapY=f.y}
        }else tapAt=-1L
        mode=Mode.NONE;tap=false;return clicked
    }
    fun cancel(){mode=Mode.NONE;tap=false;multi=false;tapAt=-1L;first=-1;second=-1}
}
