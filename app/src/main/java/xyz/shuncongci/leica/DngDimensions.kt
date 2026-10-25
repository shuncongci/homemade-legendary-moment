package xyz.shuncongci.leica

import java.io.EOFException
import java.io.InputStream
import java.util.ArrayDeque

/** Dimension-only classic TIFF/DNG reader. Never reads a thumbnail or decodes sensor pixels.
 * The input is the immutable import copy, not the provider's filename or MIME declaration.
 * Offsets, IFDs, entries and value counts are bounded; malformed TIFF fails closed.
 */
internal object DngDimensions {
    data class Primary(val width:Int,val height:Int,val fullSizes:Set<Pair<Int,Int>>) {
        fun requireFull(width:Int,height:Int) {
            require(width to height in fullSizes) {
                "系统只提供了 ${width}×${height} 像素，DNG主图为 ${this.width}×${this.height}；不能把内嵌预览当作原图。请先在RAW软件中输出全尺寸JPEG/PNG。"
            }
        }
    }
    private data class Entry(val type:Int,val count:Long,val inline:ByteArray)
    private data class Image(val width:Int,val height:Int,val sizes:Set<Pair<Int,Int>>)

    fun read(open:()->InputStream):Primary? {
        val prefix=open().use { input ->
            val b=ByteArray(4);var n=0
            while(n<b.size){val got=input.read(b,n,b.size-n);if(got<0)break;if(got==0){val v=input.read();if(v<0)break;b[n++]=v.toByte()}else n+=got}
            b.copyOf(n)
        }
        if(prefix.size<4)return null
        val little=prefix[0]==73.toByte() && prefix[1]==73.toByte()
        val big=prefix[0]==77.toByte() && prefix[1]==77.toByte()
        if(!little && !big)return null
        fun number(b:ByteArray,at:Int,n:Int):Long {
            var value=0L
            for(i in 0 until n){val j=if(little)at+n-1-i else at+i;value=(value shl 8) or (b[j].toLong() and 255)}
            return value
        }
        val magic=number(prefix,2,2)
        require(magic==42L){"暂不支持此TIFF/RAW结构；不会改用内嵌预览"}
        fun at(offset:Long,length:Int):ByteArray {
            require(offset in 0..0xffffffffL && length in 1..12292 && offset+length<=0x100000000L){"RAW元数据偏移无效"}
            return open().use { input ->
                var left=offset
                while(left>0){val skipped=input.skip(left);if(skipped>0)left-=skipped else {if(input.read()<0)throw EOFException("RAW元数据已截断");left--}}
                val out=ByteArray(length);var n=0
                while(n<length){val got=input.read(out,n,length-n);if(got<0)throw EOFException("RAW元数据已截断");if(got==0){val v=input.read();if(v<0)throw EOFException("RAW元数据已截断");out[n++]=v.toByte()}else n+=got}
                out
            }
        }
        fun values(e:Entry,counts:IntRange,types:Set<Int>):List<Long> {
            require(e.count in counts.first.toLong()..counts.last.toLong() && e.type in types){"RAW尺寸标签类型或数量无效"}
            val unit=when(e.type){1->1;3->2;4,13->4;5->8;else->error("RAW标签类型无效")}
            val size=e.count.toInt()*unit
            val data=if(size<=4)e.inline else at(number(e.inline,0,4),size)
            return List(e.count.toInt()){i->
                if(e.type==5){val numerator=number(data,i*8,4);val denominator=number(data,i*8+4,4)
                    require(denominator>0 && numerator%denominator==0L){"不支持非整数RAW裁剪尺寸；不会改用预览"};numerator/denominator
                }else number(data,i*unit,unit)
            }
        }
        val queue=ArrayDeque<Long>();queue.add(number(at(4,4),0,4))
        val seen=HashSet<Long>();val images=ArrayList<Image>();var dng=false;var entries=0
        while(queue.isNotEmpty()){
            val offset=queue.removeFirst();if(offset==0L)continue
            require(offset>=8 && seen.add(offset) && seen.size<=64){"RAW目录循环或数量超限"}
            val count=number(at(offset,2),0,2).toInt();entries+=count
            require(count<=1024 && entries<=8192){"RAW目录标签数量超限"}
            val table=at(offset+2,count*12+4);val tags=HashMap<Int,Entry>()
            val wanted=setOf(254,256,257,262,330,50706,50720,50829)
            for(i in 0 until count){val p=i*12;val tag=number(table,p,2).toInt()
                if(tag in wanted){require(tag !in tags){"RAW目录存在重复尺寸标签"};tags[tag]=Entry(number(table,p+2,2).toInt(),number(table,p+4,4),table.copyOfRange(p+8,p+12))}
            }
            tags[50706]?.let{val version=values(it,4..4,setOf(1));require(version[0]>0){"DNG版本无效"};dng=true}
            tags[330]?.let{values(it,1..64,setOf(4,13)).forEach{child->queue.add(child)}}
            queue.add(number(table,count*12,4))
            fun scalar(tag:Int,default:Long=0)=tags[tag]?.let{values(it,1..1,setOf(3,4))[0]}?:default
            val subfile=scalar(254);val photo=scalar(262)
            if(subfile and 5L==0L && photo in setOf(32803L,34892L)){
                val w=scalar(256);val h=scalar(257)
                require(w in 1..Int.MAX_VALUE.toLong() && h in 1..Int.MAX_VALUE.toLong()){"RAW主图尺寸无效"}
                val sizes=linkedSetOf(w.toInt() to h.toInt())
                tags[50829]?.let{entry->
                    val a=values(entry,4..4,setOf(3,4));val top=a[0];val left=a[1];val bottom=a[2];val right=a[3]
                    require(top<bottom && left<right && bottom<=h && right<=w){"RAW有效像素区域无效"}
                    sizes.add((right-left).toInt() to (bottom-top).toInt())
                }
                tags[50720]?.let{entry->
                    val c=values(entry,2..2,setOf(3,4,5));require(c[0] in 1..w && c[1] in 1..h){"RAW默认裁剪尺寸无效"}
                    sizes.add(c[0].toInt() to c[1].toInt())
                }
                images.add(Image(w.toInt(),h.toInt(),sizes))
            }
        }
        if(!dng)return null
        val main=images.maxByOrNull{it.width.toLong()*it.height}
            ?:throw IllegalArgumentException("无法确认DNG主图像素；不会使用内嵌预览，请先输出全尺寸JPEG/PNG")
        return Primary(main.width,main.height,main.sizes)
    }
}

/** Platform decoders may round sampled dimensions either way, but may not return a small preview. */
internal object PhotoPixelContract {
    fun decoded(width:Int,height:Int,sample:Int,actualW:Int,actualH:Int) {
        require(width>0 && height>0 && sample>0 && sample and (sample-1)==0){"照片尺寸或采样参数无效"}
        require(actualW in maxOf(1,width/sample)..width && actualH in maxOf(1,height/sample)..height) {
            "解码只返回 ${actualW}×${actualH}，与 ${width}×${height} 原图的采样 $sample 不符；不会放大预览代替原像素"
        }
    }
}
