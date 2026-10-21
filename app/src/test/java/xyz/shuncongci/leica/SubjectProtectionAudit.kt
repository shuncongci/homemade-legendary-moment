package xyz.shuncongci.leica

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Desktop audit adapter. Uses the compiled production math, never Android stubs or a reimplementation. */
object SubjectProtectionAudit {
    @JvmStatic fun main(args:Array<String>){
        val mode=args[0];val dir=File(args[1]);val w=args[2].toInt();val h=args[3].toInt();val c=Cancel()
        fun read(name:String):FloatArray {val bytes=File(dir,name).readBytes();val fb=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();return FloatArray(fb.remaining()).also{fb.get(it)}}
        fun write(name:String,data:FloatArray){val b=ByteBuffer.allocate(data.size*4).order(ByteOrder.LITTLE_ENDIAN);b.asFloatBuffer().put(data);File(dir,name).writeBytes(b.array())}
        val rgb=LegendaryMath.Frame(w,h,3,read("rgb.bin"))
        when(mode){
            "prepare"->{write("sam-input.bin",SubjectProtectionMath.samInput(rgb,c))}
            "refine"->{val scores=read("scores.bin");val best=scores.indices.maxBy{scores[it]};val low=read("masks.bin");val resized=LegendaryMath.resize(LegendaryMath.Frame(256,256,1,low.copyOfRange(best*65536,(best+1)*65536)),w,h,cancel=c)
                for(i in resized.data.indices)resized.data[i]=if(resized.data[i]>0)1f else 0f
                write("segmentation.bin",resized.data);val tri=SubjectProtectionMath.trimap(resized,c);write("trimap.bin",tri)
                write("matte-input.bin",SubjectProtectionMath.matteInput(rgb,tri,c).data);File(dir,"selected.txt").writeText(best.toString())
            }
            "finish"->{val pw=(w+31)/32*32;val ph=(h+31)/32*32;val alpha=SubjectProtectionMath.unpad(LegendaryMath.Frame(pw,ph,1,read("alpha-padded.bin")),w,h,c);val tri=read("trimap.bin")
                for(i in alpha.data.indices){if(tri[i]==1f)alpha.data[i]=1f else if(tri[i]==0f)alpha.data[i]=0f};write("alpha.bin",alpha.data)
                val sharp=LegendaryMath.planes(rgb);val blurred=LegendaryMath.planes(LegendaryMath.Frame(w,h,3,read("blurred.bin")))
                val result=SubjectProtectionMath.composite(sharp,blurred,alpha,c);write("protected.bin",LegendaryMath.rgb(result).data)
                var checked=0;for(i in alpha.data.indices)if(alpha.data[i]==1f){for(ch in 0..2)check(sharp.plane(ch)[i]==result.plane(ch)[i]);checked++};check(checked>0)
                File(dir,"opaque-checked.txt").writeText(checked.toString())
            }
            else->error("unknown audit stage")
        }
    }
}
