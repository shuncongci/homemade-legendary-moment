package xyz.shuncongci.leica

import kotlin.math.*

/** Display-only discontinuous mesh. Source geometry/UV never recomputed on animation frames.
 * Sorted triangles retain painter order; no per-tile Android Matrix, clip or Canvas save. */
class SpaceMesh(val width:Int,val height:Int,val cols:Int,val rows:Int,val layers:Int) {
    val count=cols*rows*layers
    val vertices=FloatArray(count*8)
    val tex=FloatArray(count*8)
    val indices=ShortArray(count*6)
    val lines=FloatArray(cols*rows*16)
    val order=IntArray(count){it}
    private val scratch=IntArray(count)
    private val world=FloatArray(count*8)
    private val uv=FloatArray(count*8)
    private val quads=FloatArray(count*8)
    private val keys=FloatArray(count)
    private val lastZ=FloatArray(count){Float.NaN}
    private val projector=DepthVisualMath.Projector()
    private val point=FloatArray(3)
    private var ly=Float.NaN;private var lp=Float.NaN
    private var lu=Float.NaN;private var lx=Float.NaN;private var lc=Float.NaN
    var projections=0;private set
    var lineCount=0;private set
    init {
        require(width>0 && height>0 && cols>0 && rows>0 && layers in 1..2 && count*4<32768)
        val long=max(width,height).toFloat();val ax=width/long;val ay=height/long
        for(t in 0 until count){val i=t%(cols*rows);val x=i%cols;val y=i/cols
            val cx=(x+.5f)/cols;val cy=(y+.5f)/rows
            for(k in 0..3){val sx=(x+if(k==1 || k==2)1 else 0).toFloat()/cols
                val sy=(y+if(k>=2)1 else 0).toFloat()/rows;val at=t*8+k*2
                world[at]=((sx-cx)*.993f+cx-.5f)*ax
                world[at+1]=((sy-cy)*.993f+cy-.5f)*ay
                uv[at]=sx*width;uv[at+1]=sy*height+if(t>=cols*rows)height else 0
            }
            val base=t*4;val off=t*6
            intArrayOf(0,1,2,0,2,3).forEachIndexed{k,v->indices[off+k]=(base+v).toShort()}
        }
    }
    fun project(yaw:Float,pitch:Float,unit:Float,cx:Float,cy:Float,z:FloatArray):Boolean {
        require(z.size==count && z.all{it.isFinite()})
        if(yaw==ly && pitch==lp && unit==lu && cx==lx && cy==lc && z.contentEquals(lastZ))return false
        ly=yaw;lp=pitch;lu=unit;lx=cx;lc=cy;z.copyInto(lastZ);projections++
        projector.setAngles(yaw,pitch)
        for(t in 0 until count)for(k in 0..3){val at=t*8+k*2
            projector.project(world[at],world[at+1],z[t],point)
            quads[at]=cx+point[0]*unit;quads[at+1]=cy+point[1]*unit
            if(k==0)keys[t]=point[2]
        }
        // Stable primitive merge: no boxed comparator array or per-frame allocation.
        var span=1
        while(span<count){var lo=0
            while(lo<count){val mid=min(lo+span,count);val end=min(lo+2*span,count)
                var a=lo;var b=mid;var dst=lo
                while(a<mid || b<end){scratch[dst++]=if(b>=end || (a<mid && keys[order[a]]<=keys[order[b]]))order[a++] else order[b++]}
                lo=end
            }
            scratch.copyInto(order);span*=2
        }
        lineCount=0
        for(slot in 0 until count){val t=order[slot];quads.copyInto(vertices,slot*8,t*8,t*8+8);uv.copyInto(tex,slot*8,t*8,t*8+8)
            if(t<cols*rows)for(k in 0..3){val next=(k+1)%4
                lines[lineCount++]=quads[t*8+k*2];lines[lineCount++]=quads[t*8+k*2+1]
                lines[lineCount++]=quads[t*8+next*2];lines[lineCount++]=quads[t*8+next*2+1]
            }
        }
        return true
    }
    fun depthKey(tile:Int)=keys[tile]
}
