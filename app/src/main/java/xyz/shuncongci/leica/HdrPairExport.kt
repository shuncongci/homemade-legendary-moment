package xyz.shuncongci.leica

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import xyz.shuncongci.leica.LegendaryMath.Frame

@RequiresApi(34)
object HdrPairExport {
    fun planes(bitmap:Bitmap):Planes {
        val result=Planes(bitmap.width,bitmap.height);val row=IntArray(bitmap.width)
        for(y in 0 until bitmap.height){bitmap.getPixels(row,0,bitmap.width,0,y,bitmap.width,1);result.setFromArgbRows(y,1,row)};return result
    }
    private fun jpeg(p:Planes,quality:Int):ByteArray {
        val b=Bitmap.createBitmap(p.width,p.height,Bitmap.Config.ARGB_8888)
        try{b.setHasAlpha(false);val row=IntArray(p.width);for(y in 0 until p.height){p.toArgbRows(y,1,row);b.setPixels(row,0,p.width,0,y,p.width,1)}
            val bytes=ByteArrayOutputStream();check(b.compress(Bitmap.CompressFormat.JPEG,quality,bytes));return bytes.toByteArray()
        }finally{b.recycle()}
    }
    /** Uses existing SDR area policy and the identical rounded crop coordinates for float HDR area integration. */
    fun crop(sdr:Planes,hdr:Frame,crop:CompositionGeometry.Crop,size:Pair<Int,Int>,cancel:Cancel):Pair<Planes,Frame> {
        val target=Planes(size.first,size.second)
        CompositionExport.rows(sdr.width,sdr.height,crop,size.first,size.second,cancel,read={box->
            IntArray(box.w*box.h){i->val at=(box.top+i/box.w)*sdr.width+box.left+i%box.w
                0xff000000.toInt()or((sdr.p0[at].toInt()and 255)shl 16)or((sdr.p1[at].toInt()and 255)shl 8)or(sdr.p2[at].toInt()and 255)}
        }){y,p,offset->target.setFromArgbRows(y,1,if(offset==0)p else p.copyOfRange(offset,offset+size.first))}
        return target to HdrMath.geometry(hdr,crop,size.first,size.second,cancel)
    }
    /** Gain is derived against the JPEG-decoded SDR bytes, not the uncompressed image. */
    internal fun encode(target:File,sdr:Planes,hdr:Frame,cancel:Cancel):HdrExportPolicy.Metadata {
        cancel.check();val base=jpeg(sdr,95)
        val decoded=checkNotNull(BitmapFactory.decodeByteArray(base,0,base.size))
        val decodedBase=try{planes(decoded)}finally{decoded.recycle()}
        val map=HdrMath.encode(decodedBase,hdr,cancel)
        val gm=Bitmap.createBitmap(map.w,map.h,Bitmap.Config.ARGB_8888)
        val gainBytes=try{
            gm.setHasAlpha(false);gm.setPixels(map.pixels,0,map.w,0,0,map.w,map.h)
            ByteArrayOutputStream().also{check(gm.compress(Bitmap.CompressFormat.JPEG,100,it))}.toByteArray()
        }finally{gm.recycle()}
        cancel.check();target.outputStream().use{UltraHdrContainer.write(it,base,gainBytes,map)}
        return HdrExportPolicy.Metadata(sdr.width,sdr.height,map.w,map.h,
            List(3){map.minimum}+List(3){map.maximum}+List(3){1f}+List(6){HdrMath.EPSILON}+listOf(1f,map.display))
    }
    internal fun verify(context:Context,uri:Uri,expected:HdrExportPolicy.Metadata,target:Frame,cancel:Cancel):HdrMath.Error {
        HdrPhotoExport34.verify(context,uri,expected,cancel)
        val decoded=context.contentResolver.openInputStream(uri)!!.use{checkNotNull(BitmapFactory.decodeStream(it))}
        try{
            val actual=HdrSource.bitmap(decoded,1,target.w,target.h,cancel,context.cacheDir).hdr
            val error=HdrMath.error(target,actual);HdrMath.accept(error);return error
        }finally{decoded.recycle()}
    }
    fun save(context:Context,sdr:Planes,hdr:Frame,crop:CompositionGeometry.Crop,size:Pair<Int,Int>,cancel:Cancel):String {
        val temp=File.createTempFile("hdr-pair-",".jpg",context.cacheDir)
        try{
            try{
                val (base,target)=crop(sdr,hdr,crop,size,cancel)
                encode(temp,base,target,cancel)
            }catch(t:Throwable){if(!HdrAutoPolicy.canFallback(t))throw t;cancel.check();throw HdrUnsupportedException(t)}
            return publish(context,temp,cancel)
        }finally{temp.delete()}
    }
    /** Publish a previously encoded immutable JPEG. No source decode, model call or numeric readback. */
    fun publish(context:Context,file:File,cancel:Cancel):String {
        check(file.isFile && file.length()>0){"HDR成片缓存缺失，请重新生成"};cancel.check()
        val bytes=file.length();val name="传奇一瞬-HDR-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.jpg"
        val cr=context.contentResolver
        val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,name);put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/传奇一瞬");put(MediaStore.Images.Media.IS_PENDING,1)}
        ExportCommit.run(cancel,
            create={cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:error("无法创建HDR相册文件")},
            write={pending->(cr.openOutputStream(pending)?:error("无法写入HDR")).use{out->file.inputStream().use{stream->
                val buffer=ByteArray(65536);var written=0L
                while(true){cancel.check();val n=stream.read(buffer);if(n<0)break;out.write(buffer,0,n);written+=n}
                check(written==bytes){"HDR缓存长度改变"}
            }}},
            publish={pending->values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);check(cr.update(pending,values,null,null)>0){"HDR发布失败"}},
            rollback={pending->check(cr.delete(pending,null,null)>0){"HDR半成品回滚失败"}})
        return "Pictures/传奇一瞬/$name"
    }
}
