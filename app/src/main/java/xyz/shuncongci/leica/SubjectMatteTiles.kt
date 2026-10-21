package xyz.shuncongci.leica

import kotlin.math.ceil
import kotlin.math.min

/** 2.1.21: ViTMatte at the 2048 subject-analysis size without whole-frame global attention.
 *
 * Desktop CPU probe (synthetic input): whole-frame 928×1248 peaked about +1.0 GiB, 1536×2048 about
 * +7.3 GiB. Frames whose padded pixel count stays within the old 1248×928 budget still run as one
 * whole pass (identical to 2.1.20). Larger frames run fixed 1024×1024 tiles (32-aligned, step ≤768,
 * so overlap ≥256 > 2×FEATHER), blended with linear feathering at interior tile edges.
 *
 * Only tiles containing at least one unknown trimap pixel run. Every tile touching an unknown pixel
 * therefore runs, so an unknown pixel's blend never depends on which far tiles were skipped. Pixels
 * covered by no running tile are known and keep the trimap value; finish() forces known 0/1 anyway.
 * Resource failures propagate; there is no silent fallback to a smaller analysis size. */
object SubjectMatteTiles {
    const val TILE=1024
    const val FEATHER=96
    const val MAX_STEP=768
    const val SINGLE_PASS_PIXELS=1248*928
    const val UNKNOWN=128f/255f
    data class Tile(val x:Int,val y:Int,val w:Int,val h:Int)

    fun singlePass(w:Int,h:Int)=w.toLong()*h<=SINGLE_PASS_PIXELS

    /** Evenly spread 32-aligned starts; first 0, last exactly length-tile. */
    fun starts(length:Int,tile:Int):IntArray {
        require(length>0 && tile in 1..length && length%32==0 && tile%32==0)
        if(tile==length)return intArrayOf(0)
        val last=length-tile;val n=ceil(last.toDouble()/MAX_STEP).toInt()+1
        return IntArray(n){i->if(i==n-1)last else (last.toLong()*i/(n-1)).toInt()/32*32}
    }

    fun plan(input:LegendaryMath.Frame,cancel:Cancel):List<Tile> {
        require(input.channels==4 && input.w%32==0 && input.h%32==0 && input.w>0 && input.h>0)
        val w=input.w;val h=input.h
        if(singlePass(w,h))return listOf(Tile(0,0,w,h))
        val tw=min(TILE,w);val th=min(TILE,h);val off=3*input.size
        val cw=w/32;val cells=BooleanArray(cw*(h/32))
        for(y in 0 until h){if(y and 31==0)cancel.check();val row=off+y*w;val cr=(y/32)*cw
            for(x in 0 until w)if(input.data[row+x]==UNKNOWN)cells[cr+x/32]=true}
        val out=ArrayList<Tile>()
        for(ty in starts(h,th))for(tx in starts(w,tw)){
            var any=false
            scan@ for(cy in ty/32 until (ty+th)/32)for(cx in tx/32 until (tx+tw)/32)if(cells[cy*cw+cx]){any=true;break@scan}
            if(any)out+=Tile(tx,ty,tw,th)
        }
        cancel.check();return out
    }

    /** Linear ramp only toward interior tile edges; always >0 inside the tile. */
    fun weight(u:Int,start:Int,tile:Int,length:Int):Float {
        var k=1f
        if(start>0)k=min(k,(u+.5f)/FEATHER)
        if(start+tile<length)k=min(k,(tile-u-.5f)/FEATHER)
        return k
    }

    fun matte(input:LegendaryMath.Frame,cancel:Cancel,infer:(LegendaryMath.Frame)->LegendaryMath.Frame):LegendaryMath.Frame {
        val tiles=plan(input,cancel);val w=input.w;val h=input.h;val size=w*h
        fun checked(out:LegendaryMath.Frame,tw:Int,th:Int):LegendaryMath.Frame {
            require(out.channels==1 && out.w==tw && out.h==th && out.data.size==tw*th){"主体边缘模型输出尺寸异常"}
            return out
        }
        if(tiles.size==1 && tiles[0].w==w && tiles[0].h==h){cancel.check();val out=checked(infer(input),w,h);cancel.check();return out}
        val acc=FloatArray(size);val total=FloatArray(size)
        for(t in tiles){
            cancel.check();val n=t.w*t.h;val crop=FloatArray(4*n)
            for(c in 0..3)for(y in 0 until t.h)System.arraycopy(input.data,c*size+(t.y+y)*w+t.x,crop,c*n+y*t.w,t.w)
            val a=checked(infer(LegendaryMath.Frame(t.w,t.h,4,crop)),t.w,t.h)
            cancel.check()
            for(y in 0 until t.h){val wy=weight(y,t.y,t.h,h);val row=(t.y+y)*w+t.x
                for(x in 0 until t.w){val k=wy*weight(x,t.x,t.w,w);acc[row+x]+=k*a.data[y*t.w+x];total[row+x]+=k}}
        }
        cancel.check();val off=3*size
        return LegendaryMath.Frame(w,h,1,FloatArray(size){i->if(total[i]>0f)acc[i]/total[i] else input.data[off+i]})
    }
}
