package xyz.shuncongci.leica

import kotlin.math.*
import xyz.shuncongci.leica.CompositionGeometry.Crop

/** Display-only mesh. Include ALL proposal edges once so pieces retain identity when reversing. */
object CompositionTileMath {
    const val GRID_EDGE=22
    data class Piece(val box:NativeImageMath.Box,val parent:Int,val cx:Float,val cy:Float)
    data class Mesh(val width:Int,val height:Int,val pieces:List<Piece>,val crops:List<Crop>)
    fun mesh(w:Int,h:Int,proposals:List<Crop>):Mesh {
        require(w>0 && h>0 && proposals.size in 1..4)
        val (gx,gy)=DepthVisualMath.grid(w,h,GRID_EDGE)
        val nx=gx.coerceAtMost(w);val ny=gy.coerceAtMost(h)
        val baseX=(0..nx).map{(it.toDouble()*w/nx).roundToInt()}
        val baseY=(0..ny).map{(it.toDouble()*h/ny).roundToInt()}
        val crops=proposals.map{CompositionGeometry.snapped(it,w,h)}
        val boxes=crops.map{CompositionGeometry.pixels(it,w,h)}
        val xs=(baseX+boxes.flatMap{listOf(it.left,it.right)}).distinct().sorted()
        val ys=(baseY+boxes.flatMap{listOf(it.top,it.bottom)}).distinct().sorted()
        val pieces=ArrayList<Piece>((xs.size-1)*(ys.size-1))
        for(y in 0 until ys.lastIndex)for(x in 0 until xs.lastIndex){
            val bx=baseX.indexOfLast{it<=xs[x]}.coerceAtMost(nx-1)
            val by=baseY.indexOfLast{it<=ys[y]}.coerceAtMost(ny-1)
            pieces.add(Piece(NativeImageMath.Box(xs[x],ys[y],xs[x+1],ys[y+1]),by*nx+bx,
                (baseX[bx]+baseX[bx+1])/(2f*w),(baseY[by]+baseY[by+1])/(2f*h)))
        }
        check(pieces.size<=900)
        return Mesh(w,h,pieces,crops)
    }
    fun kept(piece:Piece,crop:NativeImageMath.Box)=piece.box.let{it.left>=crop.left && it.top>=crop.top && it.right<=crop.right && it.bottom<=crop.bottom}
    fun ease(t:Float)=CompositionMotion.ease(t).toFloat()
    fun lerp(a:Float,b:Float,t:Float)=a+(b-a)*t
    fun phase(index:Int)=((index*73+19)%997)/997f
    /** Artistic displacement, NOT predicted depth, detection, inference progress or metres. */
    fun wave(index:Int,seconds:Float):Float {
        val p=phase(index)*(2*PI).toFloat()
        return .19f*sin(seconds*1.55f+p)+.085f*cos(seconds*.9f+p*2.3f)
    }
}

/** Deterministic, Android-free timeline. Retargeting snapshots the current frame, never queues. */
class CompositionTileMotion(w:Int,h:Int) {
    enum class Mode { ANALYSING, REVEALING, SWITCHING, READY }
    data class Camera(val yaw:Float=0f,val pitch:Float=0f,val lift:Float=0f,val crop:Crop=CompositionGeometry.FULL)
    companion object { const val SWITCH_MS=1450f;const val REVEAL_MS=650f }
    var mesh=CompositionTileMath.mesh(w,h,listOf(CompositionGeometry.FULL));private set
    var departures=FloatArray(mesh.pieces.size);private set
    var mode=Mode.READY;private set
    var camera=Camera();private set
    var selected=0;private set
    var seconds=0f;private set
    private var elapsed=0f
    private var startCamera=Camera()
    private var from=departures.copyOf();private var target=departures.copyOf()
    private var orbitYaw=.46f;private var orbitPitch=-.22f
    val crop get()=mesh.crops[selected]
    val needsFrames get()=mode!=Mode.READY
    val canOrbit get()=mode==Mode.ANALYSING && camera.lift>.02f
    fun beginAnalysis(){
        mode=Mode.ANALYSING;selected=0;elapsed=0f;seconds=0f;camera=Camera()
        mesh=CompositionTileMath.mesh(mesh.width,mesh.height,listOf(CompositionGeometry.FULL))
        departures=FloatArray(mesh.pieces.size);target=departures.copyOf();from=departures.copyOf()
    }
    fun reveal(proposals:List<Crop>,index:Int=0){
        require(index in proposals.indices)
        // All new subpieces of an old grid cell share the same Z-wave; rebuilding cannot shuffle it.
        mesh=CompositionTileMath.mesh(mesh.width,mesh.height,proposals)
        departures=FloatArray(mesh.pieces.size);selected=index;begin(Mode.REVEALING)
    }
    fun select(index:Int){
        require(index in mesh.crops.indices)
        if(selected==index && mode!=Mode.ANALYSING)return
        camera=Camera(crop=camera.crop)
        selected=index;begin(Mode.SWITCHING)
    }
    private fun begin(next:Mode){
        startCamera=camera;from=departures.copyOf()
        val box=CompositionGeometry.pixels(crop,mesh.width,mesh.height)
        target=FloatArray(mesh.pieces.size){if(CompositionTileMath.kept(mesh.pieces[it],box))0f else 1f}
        elapsed=0f;mode=next
    }
    fun settle(){
        val box=CompositionGeometry.pixels(crop,mesh.width,mesh.height)
        for(i in departures.indices)departures[i]=if(CompositionTileMath.kept(mesh.pieces[i],box))0f else 1f
        mode=Mode.READY;camera=Camera(crop=crop);elapsed=0f
    }
    fun drag(dx:Float,dy:Float){
        require(dx.isFinite() && dy.isFinite())
        if(!canOrbit)return
        orbitYaw=(orbitYaw+dx).coerceIn(-.85f,.85f)
        orbitPitch=(orbitPitch+dy).coerceIn(-.55f,.55f)
        update()
    }
    fun advance(ms:Float){
        require(ms.isFinite() && ms>=0)
        if(!needsFrames)return
        elapsed+=ms;seconds+=ms/1000f;update()
    }
    private fun update(){
        when(mode){
            Mode.ANALYSING->{val t=CompositionTileMath.ease(elapsed/700f);camera=Camera(orbitYaw*t,orbitPitch*t,t)}
            Mode.REVEALING->{
                val t=CompositionTileMath.ease(elapsed/REVEAL_MS)
                camera=Camera(startCamera.yaw*(1-t),startCamera.pitch*(1-t),startCamera.lift*(1-t),CompositionMotion.mix(startCamera.crop,crop,t))
                for(i in departures.indices)departures[i]=CompositionTileMath.lerp(from[i],target[i],t)
                if(elapsed>=REVEAL_MS)settle()
            }
            Mode.SWITCHING->{
                val p=(elapsed/SWITCH_MS).coerceIn(0f,1f)
                val flight=CompositionTileMath.ease(p)
                // Stay on the photo plane throughout: no opening orbit or return-to-3D phase.
                camera=Camera(crop=CompositionMotion.mix(startCamera.crop,crop,p))
                // One shared flight phase: outgoing AND returning pieces move simultaneously.
                for(i in departures.indices)departures[i]=CompositionTileMath.lerp(from[i],target[i],flight)
                if(elapsed>=SWITCH_MS)settle()
            }
            Mode.READY->Unit
        }
    }

    /** Four screen corners + centre depth + opacity. No allocation, export or bitmap mutation. */
    fun project(index:Int,viewW:Int,viewH:Int,out:FloatArray){
        require(viewW>0 && viewH>0 && out.size>=10)
        val p=mesh.pieces[index];val box=p.box;val gone=departures[index]
        val long=max(mesh.width,mesh.height).toFloat()
        val ax=mesh.width/long;val ay=mesh.height/long
        val c=camera
        val scale=min(viewW/(ax*c.crop.w.toFloat()),viewH/(ay*c.crop.h.toFloat()))*(.94f-.18f*c.lift)
        val focusX=(((c.crop.l+c.crop.r)/2)-.5).toFloat()*ax
        val focusY=(((c.crop.t+c.crop.b)/2)-.5).toFloat()*ay
        val cx=(box.left+box.right)/(2f*mesh.width);val cy=(box.top+box.bottom)/(2f*mesh.height)
        val seed=CompositionTileMath.phase(p.parent)
        var vx=(p.cx-.5f)*ax;var vy=(p.cy-.5f)*ay
        val len=hypot(vx,vy)
        if(len>.001f){vx/=len;vy/=len}else{vx=cos(seed*6.283185f);vy=sin(seed*6.283185f)}
        val travel=gone*(.58f+.22f*seed)
        val tx=(cx-.5f)*ax+vx*travel
        val ty=(cy-.5f)*ay+vy*travel
        val flat=c.lift==0f
        val tz=if(flat)0f else CompositionTileMath.wave(p.parent,seconds)*c.lift+gone*(.48f+.3f*seed)
        val turn=(seed-.5f)*gone
        val ry=if(flat)0f else turn*1.2f;val rx=if(flat)0f else turn*.7f;val rz=turn*.55f
        val cosY=cos(ry);val sinY=sin(ry);val cosX=cos(rx);val sinX=sin(rx);val cosZ=cos(rz);val sinZ=sin(rz)
        val cosYaw=cos(c.yaw);val sinYaw=sin(c.yaw);val cosPitch=cos(c.pitch);val sinPitch=sin(c.pitch)
        val shrink=(1-.025f*c.lift)*(1-.12f*gone)
        var depthSum=0f
        for(k in 0..3){
            val sx=if(k==0 || k==3)box.left.toFloat()/mesh.width else box.right.toFloat()/mesh.width
            val sy=if(k<2)box.top.toFloat()/mesh.height else box.bottom.toFloat()/mesh.height
            val x=(sx-cx)*ax*shrink;val y=(sy-cy)*ay*shrink
            val x1=x*cosY;val z1=-x*sinY
            val y1=y*cosX-z1*sinX;val z2=y*sinX+z1*cosX
            val xx=x1*cosZ-y1*sinZ+tx;val yy=x1*sinZ+y1*cosZ+ty;val zz=z2+tz
            val eyeX=xx*cosYaw+zz*sinYaw;val z3=-xx*sinYaw+zz*cosYaw
            val eyeY=yy*cosPitch-z3*sinPitch;val depth=yy*sinPitch+z3*cosPitch
            val perspective=3.8f/(3.8f-depth)
            out[k*2]=viewW/2f+(eyeX*perspective-focusX)*scale
            out[k*2+1]=viewH/2f+(eyeY*perspective-focusY)*scale
            depthSum+=depth
        }
        out[8]=depthSum*.25f;out[9]=(1-gone).coerceIn(0f,1f)
    }
}
