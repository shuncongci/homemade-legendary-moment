package xyz.shuncongci.leica

import java.io.*
import xyz.shuncongci.leica.LegendaryMath.Frame

/** Immutable lossless working pair owned by exactly one rendered Result. Never rereads source or models. */
object HdrResultStore {
    fun pairFile(result:File)=File(result.path+".hdr-pair")
    fun jpegFile(result:File)=File(result.path+".hdr.jpg")
    fun remove(result:File){pairFile(result).delete();jpegFile(result).delete()}
    fun write(file:File,sdr:Planes,hdr:Frame,cancel:Cancel){
        require(sdr.width==hdr.w && sdr.height==hdr.h && hdr.channels==3)
        HdrMath.checked(hdr)
        require(checkNotNull(file.parentFile).usableSpace>=16L+15L*sdr.size+16*HdrMemory.MIB){"HDR成片缓存空间不足"}
        try{DataOutputStream(BufferedOutputStream(file.outputStream(),65536)).use{out->
            out.writeInt(0x48445231);out.writeInt(hdr.w);out.writeInt(hdr.h)
            for(c in 0..2){cancel.check();out.write(sdr.plane(c))}
            for(i in hdr.data.indices){if(i%hdr.w==0)cancel.check();out.writeFloat(hdr.data[i])}
            cancel.check()
        }}catch(t:Throwable){file.delete();throw t}
    }
    fun read(file:File,w:Int,h:Int,cancel:Cancel):Pair<Planes,Frame>{
        require(w>0 && h>0 && w.toLong()*h*3<=Int.MAX_VALUE)
        check(file.length()==12L+15L*w*h){"HDR成片缓存缺失或长度错误，请重新生成"}
        return DataInputStream(BufferedInputStream(file.inputStream(),65536)).use{input->
            check(input.readInt()==0x48445231 && input.readInt()==w && input.readInt()==h){"HDR成片缓存不匹配"}
            val sdr=Planes(w,h);for(c in 0..2){cancel.check();input.readFully(sdr.plane(c))}
            val values=FloatArray(w*h*3);for(i in values.indices){if(i%w==0)cancel.check();values[i]=input.readFloat()}
            cancel.check();sdr to HdrMath.checked(Frame(w,h,3,values))
        }
    }
}
