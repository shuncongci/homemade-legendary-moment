package xyz.shuncongci.leica

import java.io.File

/** Container framing, not an entropy decoder. Finds the PRIMARY image's real EOI through segment
 * lengths and byte-stuffed scans; APP thumbnails cannot masquerade as a completed image. Bytes after
 * EOI (gain JPEG / Motion Photo / vendor trailer) remain untouched. Android still decodes the photo. */
internal object JpegCompletion {
    fun primaryEnd(file:File,cancel:Cancel=Cancel()):Long?=file.inputStream().buffered(65536).use{input->primaryEnd(input,file.length(),cancel)}
    internal fun primaryEnd(input:java.io.InputStream,length:Long,cancel:Cancel):Long? {
        var position=0L
        fun byte():Int {if(position and 65535L==0L)cancel.check();val v=input.read();if(v>=0)position++;return v}
        fun skip(n:Int):Boolean {
            if(n<0 || n.toLong()>length-position)return false
            var left=n.toLong()
            while(left>0){cancel.check();val count=input.skip(left);if(count>0){position+=count;left-=count}else{if(byte()<0)return false;left--}}
            return true
        }
        if(byte()!=255 || byte()!=216)return null
        var frame=false;var scan=false;var entropy=false;var scanData=false
        while(true){
            cancel.check();var marker:Int
            if(entropy){
                // FF00 is a literal data byte; restart markers stay inside this scan.
                do {
                    var b=byte();while(b>=0 && b!=255){scanData=true;b=byte()};if(b<0)return null
                    do{marker=byte()}while(marker==255)
                    if(marker<0)return null
                    if(marker==0)scanData=true
                }while(marker==0 || marker in 0xd0..0xd7)
                if(!scanData)return null
                entropy=false
            }else{
                if(byte()!=255)return null
                do{marker=byte()}while(marker==255)
                if(marker<0)return null
            }
            if(marker==0xd9)return if(frame && scan)position else null
            if(marker==1)continue // TEM, standalone
            if(marker !in 0xc0..0xfe || marker in 0xd0..0xd8)return null
            val hi=byte();val lo=byte();if(hi<0 || lo<0)return null
            val size=(hi shl 8)+lo-2;if(size<0 || size.toLong()>length-position)return null
            val sof=marker in 0xc0..0xcf && marker !in setOf(0xc4,0xc8,0xcc)
            when {
                sof->{
                    if(frame || size<6)return null
                    val precision=byte();val h=(byte() shl 8)+byte();val w=(byte() shl 8)+byte();val components=byte()
                    if(precision !in 1..16 || w<=0 || h<=0 || components !in 1..4 || size!=6+components*3)return null
                    if(!skip(size-6))return null;frame=true
                }
                marker==0xda->{
                    if(!frame || size<4)return null
                    val components=byte();if(components !in 1..4 || size!=4+components*2)return null
                    if(!skip(size-1))return null;scan=true;entropy=true;scanData=false
                }
                else->if(!skip(size))return null
            }
        }
        @Suppress("UNREACHABLE_CODE") return null
    }
}
