package xyz.shuncongci.leica

import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.RandomAccessFile

/** Rechecks ONLY an already admitted new arrival. Bounded write-settling retries never scan/backfill old work. */
object ExternalPhotoInput {
    fun completeJpeg(file:File):Boolean=RandomAccessFile(file,"r").use{f->
        if(f.length()<4)return@use false
        if(f.readUnsignedShort()!=0xffd8)return@use false
        f.seek(f.length()-2);f.readUnsignedShort()==0xffd9
    }
    fun stage(directory:File,initial:ExternalPhoto,cancel:Cancel,observe:()->ExternalPhoto?,open:(ExternalPhoto)->InputStream,
              onWait:()->Unit={},waitStable:(Cancel)->Unit={c->repeat(6){c.check();Thread.sleep(500)};c.check()}):File {
        var expected=initial
        var needsWait=false
        repeat(5){
            cancel.check()
            val before=observe()?:throw FileNotFoundException("新增照片已移除，未处理")
            require(before.key==initial.key){"来源身份改变，未处理"}
            if(needsWait || !expected.sameBytes(before)){
                onWait();waitStable(cancel)
                val stable=observe()?:throw FileNotFoundException("新增照片已移除，未处理")
                if(!before.sameBytes(stable)){expected=stable;needsWait=true;return@repeat}
                expected=stable
            }else expected=before
            if(expected.pending || expected.size<=0){needsWait=true;return@repeat}
            val source=PhotoCopy.stage(directory,{open(expected)},cancel::check)
            var keep=false
            try{
                val after=observe()
                val jpeg=expected.name.endsWith(".jpg",true)||expected.name.endsWith(".jpeg",true)
                if(source.length()==expected.size && after!=null && expected.sameBytes(after) && (!jpeg || completeJpeg(source))){cancel.check();keep=true;return source}
                if(after!=null)expected=after
                needsWait=true
            }finally{if(!keep)source.delete()}
        }
        error("相机文件仍未写完或JPEG不完整，已停止本张；没有处理其他旧照片")
    }
}
