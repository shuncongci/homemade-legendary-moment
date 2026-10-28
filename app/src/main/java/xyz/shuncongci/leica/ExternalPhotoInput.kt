package xyz.shuncongci.leica

import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/** Rechecks ONLY an already admitted new arrival. Bounded retries never scan/backfill old work. */
object ExternalPhotoInput {
    fun completeJpeg(file:File):Boolean=JpegCompletion.primaryEnd(file)!=null
    private data class Copy(val photo:ExternalPhoto,val bytes:Long,val hash:String)
    fun stage(directory:File,initial:ExternalPhoto,cancel:Cancel,observe:()->ExternalPhoto?,open:(ExternalPhoto)->InputStream,
              onWait:()->Unit={},waitStable:(Cancel)->Unit={c->repeat(6){c.check();Thread.sleep(500)};c.check()}):File {
        fun current():ExternalPhoto {
            val p=observe()?:throw FileNotFoundException("新增照片已移除，未处理")
            require(p.key==initial.key && p.uri==initial.uri && p.name==initial.name && p.created==initial.created){"来源身份改变，未处理"}
            return p
        }
        var expected=initial;var needsWait=false;var previous:Copy?=null
        var reason="尚未取得稳定的本张新照片"
        repeat(5){
            cancel.check();val before=current()
            if(needsWait || !expected.sameBytes(before)){
                onWait();waitStable(cancel);cancel.check();val stable=current()
                if(!before.sameBytes(stable)){expected=stable;previous=null;needsWait=true;reason="文件大小、修改时间或待写入状态仍在变化";return@repeat}
                expected=stable
            }else expected=before
            if(expected.pending || expected.size<=0){needsWait=true;previous=null;reason="提供器尚未给出非空、非待写入的文件";return@repeat}
            val source=PhotoCopy.stage(directory,{open(expected)},cancel::check)
            var keep=false
            try{
                val after=current();val bytes=source.length()
                val jpeg=expected.name.endsWith(".jpg",true)||expected.name.endsWith(".jpeg",true)
                val end=if(jpeg)JpegCompletion.primaryEnd(source,cancel)else bytes
                when {
                    !expected.sameBytes(after)->{previous=null;reason="复制前后大小、修改时间或待写入状态发生变化"}
                    end==null->{previous=null;reason="JPEG主图的段、扫描或结束标记不完整"}
                    bytes<expected.size->{previous=null;reason="本次读取${bytes}字节，小于提供器声明的${expected.size}字节"}
                    bytes==expected.size && end==bytes->{cancel.check();keep=true;return source}
                    !jpeg->{previous=null;reason="本次读取大小与提供器声明不一致"}
                    else->{
                        // OEM SIZE may lag behind the readable original. Never ignore it on one read:
                        // require two full, identical private copies, separated by the settling interval.
                        // The complete main JPEG is parsed above; all trailing bytes are kept, not stripped.
                        val hash=ExternalOriginalGuard.digest(source.inputStream()){cancel.check()};val last=previous
                        if(last!=null && last.photo.sameBytes(expected) && last.bytes==bytes && last.hash==hash){cancel.check();keep=true;return source}
                        previous=Copy(expected,bytes,hash);reason="核对JPEG附加数据或滞后大小：等待第二次完整字节指纹一致"
                    }
                }
                expected=after;needsWait=true
            }finally{if(!keep)source.delete()}
        }
        error("本张新增照片完整性核对未通过（$reason），已停止本张；原片保留，没有处理其他旧照片")
    }
}
