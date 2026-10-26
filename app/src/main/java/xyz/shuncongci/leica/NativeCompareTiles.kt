package xyz.shuncongci.leica

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Preview-only detail: decode each side in its own resolution using the same viewport. */
class NativeCompareTiles(private val context:Context,private val ready:()->Unit) {
    private val executor=Executors.newSingleThreadExecutor{Thread(it,"legendary-preview-tiles")}
    private val main=Handler(Looper.getMainLooper())
    private var original:RegionPhoto?=null
    private var result:RegionPhoto?=null
    private var version=0
    private var closed=false
    private var loading=false
    private var lastKey=""
    var width=0;private set
    var height=0;private set
    private var resultWidth=0
    private var resultHeight=0
    private data class Part(val bitmap:Bitmap,val box:NativeImageMath.Box,val w:Int,val h:Int)
    private data class Tile(val original:Part?,val result:Part?)
    private var tile:Tile?=null
    private fun dispose(t:Tile?){t?.original?.bitmap?.recycle();t?.result?.bitmap?.recycle()}
    fun setSources(o:Uri?,r:Uri?){
        version++;val v=version;lastKey="";loading=false;width=0;height=0;resultWidth=0;resultHeight=0;dispose(tile);tile=null
        executor.execute{
            original?.close();result?.close();original=null;result=null
            try{if(o!=null)original=RegionPhoto(context,o);if(r!=null)result=RegionPhoto(context,r)
                val w=original?.width ?: result?.width ?: 0;val h=original?.height ?: result?.height ?: 0
                val rw=result?.width ?: 0;val rh=result?.height ?: 0
                main.post{if(!closed && v==version){width=w;height=h;resultWidth=rw;resultHeight=rh;ready()}}
            }catch(e:Exception){propagateFatalCause(e);android.util.Log.w("LegendaryPreview","Native preview unavailable",e)}
        }
    }
    fun request(dst:RectF,viewW:Int,viewH:Int,originalDetail:Boolean=true,resultDetail:Boolean=true){
        if(closed || loading || width<=0 || height<=0 || dst.width()<=0 || (!originalDetail && !resultDetail))return
        val d=PreviewViewport.Bounds(dst.left,dst.top,dst.right,dst.bottom)
        val w=width;val h=height;val rw=resultWidth;val rh=resultHeight
        val ob=if(originalDetail)PreviewRegions.visible(w,h,d,viewW,viewH)else null
        val rb=if(resultDetail)PreviewRegions.visible(rw,rh,d,viewW,viewH)else null
        if(ob==null && rb==null)return
        val os=if(ob!=null)PreviewRegions.sample(ob,w,d.w)else 1
        val rs=if(rb!=null)PreviewRegions.sample(rb,rw,d.w) else 1
        val key="$version:$ob:$os:$rb:$rs";if(key==lastKey)return
        val v=version;loading=true
        executor.execute{
            var o:Part?=null;var r:Part?=null
            try{
                if(ob!=null)original?.let{o=Part(it.bitmap(ob,os),ob,it.width,it.height)}
                if(rb!=null)result?.let{r=Part(it.bitmap(rb,rs),rb,it.width,it.height)}
            }catch(e:Exception){propagateFatalCause(e);android.util.Log.w("LegendaryPreview","Tile read failed",e)}
            val next=Tile(o,r)
            main.post{if(closed || v!=version){dispose(next)}else{loading=false;lastKey=key;dispose(tile);tile=next;ready()}}
        }
    }
    fun draw(canvas:Canvas,dst:RectF,isResult:Boolean,paint:Paint){
        val t=tile ?: return;val part=(if(isResult)t.result else t.original) ?: return
        if(part.bitmap.isRecycled)return
        val b=part.box
        val rect=RectF(dst.left+dst.width()*b.left/part.w,dst.top+dst.height()*b.top/part.h,dst.left+dst.width()*b.right/part.w,dst.top+dst.height()*b.bottom/part.h)
        canvas.drawBitmap(part.bitmap,null,rect,paint)
    }
    fun close(){closed=true;version++;dispose(tile);tile=null;executor.execute{original?.close();result?.close();original=null;result=null};executor.shutdown()}
}
