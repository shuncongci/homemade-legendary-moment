package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.zip.InflaterInputStream
import java.util.zip.CRC32
import kotlin.math.abs

class NativeImageMathTest {
    @Test fun wholeBokehDoesNotSplitAboveFormerTileBoundary(){
        val w=1105;val h=49;val p=Planes.fromArgb(IntArray(w*h){0xff202020.toInt()},w,h)
        val coc=LegendaryMath.Frame(w,h,1,FloatArray(w*h){2f});var calls=0
        val out=LegendaryMath.renderCoc(p,coc,2.8f,Cancel()){image,defocus ->
            calls++;assertEquals(w,image.w);assertEquals(h,image.h);assertEquals(w,defocus.w);assertEquals(h,defocus.h);image
        }
        assertEquals(1,calls);assertEquals(w,out.width);assertEquals(h,out.height)
    }
    @Test fun allExifOrientationsReadTheCorrectUserPixels(){
        val p=Planes.fromArgb(IntArray(7*5){0xff000000.toInt() or (it*913)},7,5)
        for(o in 1..8){val upright=p.exifTransposed(o);assertEquals(upright.width to upright.height,NativeImageMath.size(7,5,o))
            for(y in 0 until upright.height)for(x in 0 until upright.width){val (sx,sy)=NativeImageMath.encoded(x,y,7,5,o);for(c in 0..2)assertEquals(p.plane(c)[sy*7+sx],upright.plane(c)[y*upright.width+x])}
            val full=NativeImageMath.encodedBox(NativeImageMath.Box(0,0,upright.width,upright.height),7,5,o);assertEquals(NativeImageMath.Box(0,0,7,5),full)
        }
    }
    @Test fun globalDepthAndGanCoordinatesDoNotChangeAcrossTileEdges(){
        val f=LegendaryMath.Frame(5,7,3,FloatArray(5*7*3){it/105f})
        for(cubic in listOf(false,true)){val whole=LegendaryMath.resize(f,93,121,cubic=cubic)
            val b=NativeImageMath.Box(31,43,70,96);val region=NativeImageMath.region(f,93,121,b,cubic)
            for(c in 0..2)for(y in 0 until b.h)for(x in 0 until b.w)assertEquals(whole.data[c*whole.size+(b.top+y)*93+b.left+x],region.data[c*region.size+y*b.w+x],1e-6f)
        }
    }
    @Test fun losslessOutputKeepsNativeDimensionsAndHighFrequencyPixels(){
        val w=4032;val h=3024;val out=ByteArrayOutputStream();val row=ByteArray(w*3)
        NativePng(out,w,h).use{png->for(y in 0 until h){for(x in 0 until w){val at=x*3;row[at]=((x+y) and 255).toByte();row[at+1]=(if((x+y)%2==0)255 else 0).toByte();row[at+2]=(y and 255).toByte()};png.row(row)}}
        val input=DataInputStream(out.toByteArray().inputStream());val signature=ByteArray(8);input.readFully(signature);assertArrayEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10),signature)
        val compressed=ByteArrayOutputStream();var ended=false
        while(input.available()>0){val n=input.readInt();val tag=ByteArray(4);input.readFully(tag);val bytes=ByteArray(n);input.readFully(bytes);val crc=input.readInt();val check=CRC32();check.update(tag);check.update(bytes);assertEquals(check.value.toInt(),crc)
            when(String(tag,Charsets.US_ASCII)){"IHDR"->{val header=DataInputStream(bytes.inputStream());assertEquals(w,header.readInt());assertEquals(h,header.readInt());assertEquals(8,header.readUnsignedByte());assertEquals(2,header.readUnsignedByte())};"IDAT"->compressed.write(bytes);"IEND"->ended=true}
        };assertTrue(ended)
        InflaterInputStream(compressed.toByteArray().inputStream()).use{z->val data=DataInputStream(z);val decoded=ByteArray(w*3);for(y in 0 until h){assertEquals(1,data.readUnsignedByte());data.readFully(decoded);for(i in decoded.indices)decoded[i]=((decoded[i].toInt() and 255)+(if(i>=3)decoded[i-3].toInt() and 255 else 0)).toByte()
            for(x in 0 until w){assertEquals((x+y) and 255,decoded[x*3].toInt() and 255);assertEquals(if((x+y)%2==0)255 else 0,decoded[x*3+1].toInt() and 255);assertEquals(y and 255,decoded[x*3+2].toInt() and 255)}
        };assertEquals(-1,data.read())}
    }
    @Test fun incompleteNativeOutputCannotBeReportedAsFinished(){val out=ByteArrayOutputStream();val png=NativePng(out,10,3);png.row(ByteArray(30));try{png.close();fail("incomplete output accepted")}catch(expected:IllegalStateException){assertTrue(expected.message!!.contains("未完成"))}}
    @Test fun overlappedLabFusionHasNoTileSeams(){
        val w=223;val h=211
        val source=Planes.fromArgb(IntArray(w*h){val x=it%w;val y=it/w;0xff000000.toInt() or (((x*13+y*7) and 255) shl 16) or (((x+y) and 255) shl 8) or ((x*3+y*5) and 255)},w,h)
        val gan=Planes.fromArgb(IntArray(w*h){0xff6b9473.toInt()},w,h)
        fun copy(p:Planes)=Planes(w,h,p.p0.copyOf(),p.p1.copyOf(),p.p2.copyOf())
        val whole=Fusion.fuse(copy(source),copy(gan))
        for(y in 0 until h step 128)for(x in 0 until w step 128){
            val b=NativeImageMath.Box(maxOf(0,x-100),maxOf(0,y-100),minOf(w,x+128+100),minOf(h,y+128+100))
            fun crop(p:Planes)=Planes(b.w,b.h).also{out->for(c in 0..2)for(yy in 0 until b.h)System.arraycopy(p.plane(c),(b.top+yy)*w+b.left,out.plane(c),yy*b.w,b.w)}
            val tile=Fusion.fuse(crop(source),crop(gan))
            for(c in 0..2)for(yy in y until minOf(h,y+128))for(xx in x until minOf(w,x+128))assertTrue(abs((whole.plane(c)[yy*w+xx].toInt() and 255)-(tile.plane(c)[(yy-b.top)*b.w+xx-b.left].toInt() and 255))<=1)
        }
    }
}
