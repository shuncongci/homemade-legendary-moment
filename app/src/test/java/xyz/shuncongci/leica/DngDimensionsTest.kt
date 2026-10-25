package xyz.shuncongci.leica

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

/** Synthetic metadata only: these fixtures are not demosaiced camera files. */
class DngDimensionsTest {
    private data class Tag(val id:Int,val type:Int,val values:List<Long>)
    private fun tag(id:Int,type:Int=4,vararg values:Long)=Tag(id,type,values.toList())
    private class Tiff(val little:Boolean=true) {
        val buffer=ByteBuffer.allocate(65536).order(if(little)ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        var payload=16384;var end=8
        init{buffer.put(0,if(little)73 else 77);buffer.put(1,if(little)73 else 77);buffer.putShort(2,42);buffer.putInt(4,8)}
        fun ifd(offset:Int,tags:List<Tag>,next:Int=0):Tiff {
            buffer.putShort(offset,tags.size.toShort());var p=offset+2
            for(t in tags){
                val unit=when(t.type){1->1;3->2;5->8;else->4};val count=if(t.type==5)t.values.size/2 else t.values.size
                buffer.putShort(p,t.id.toShort());buffer.putShort(p+2,t.type.toShort());buffer.putInt(p+4,count)
                val size=unit*count;var at=if(size<=4)p+8 else payload.also{buffer.putInt(p+8,it);payload+=size}
                for(v in t.values){when(t.type){1->{buffer.put(at,v.toByte());at++};3->{buffer.putShort(at,v.toShort());at+=2};else->{buffer.putInt(at,v.toInt());at+=4}}}
                end=maxOf(end,at);p+=12
            }
            buffer.putInt(p,next);end=maxOf(end,p+4);return this
        }
        fun bytes()=buffer.array().copyOf(end)
    }
    private fun raw(w:Long=4000,h:Long=3000,photo:Long=32803,subfile:Long=0)=listOf(tag(256,4,w),tag(257,4,h),tag(262,3,photo),tag(254,4,subfile))
    private fun root(child:Long=256)=listOf(tag(50706,1,1,4,0,0),tag(256,4,1600),tag(257,4,1200),tag(262,3,2),tag(254,4,1),tag(330,4,child))
    private fun read(bytes:ByteArray)=DngDimensions.read{ByteArrayInputStream(bytes)}
    private fun primary(little:Boolean=true,extra:List<Tag> = emptyList())=Tiff(little).ifd(8,root()).ifd(256,raw()+extra)
    private fun reject(block:()->Unit){assertThrows(Exception::class.java){block()}}
    @Test fun nonTiffFormatsDoNotTriggerRawRules(){for(bytes in listOf(byteArrayOf(-1,-40,-1,-31),"GIF89a".toByteArray(),byteArrayOf(-119,80,78,71),byteArrayOf()))assertNull(read(bytes))}
    @Test fun littleEndianSubIfdFindsSensorNotPreview(){val p=read(primary().bytes())!!;assertEquals(4000,p.width);assertEquals(3000,p.height);assertEquals(setOf(4000 to 3000),p.fullSizes)}
    @Test fun bigEndianSubIfdFindsSensorNotPreview(){val p=read(primary(false).bytes())!!;p.requireFull(4000,3000);reject{p.requireFull(1600,1200)}}
    @Test fun cfaInRootIsSupported(){val p=read(Tiff().ifd(8,raw()+tag(50706,1,1,6,0,0)).bytes())!!;p.requireFull(4000,3000)}
    @Test fun linearDngIsRecognized(){val p=read(Tiff().ifd(8,root()).ifd(256,raw(photo=34892)).bytes())!!;p.requireFull(4000,3000)}
    @Test fun nextIfdIsTraversed(){val p=read(Tiff().ifd(8,root().filterNot{it.id==330},256).ifd(256,raw()).bytes())!!;p.requireFull(4000,3000)}
    @Test fun ifdTypedSubIfdOffsetIsSupported(){val p=read(Tiff().ifd(8,root().filterNot{it.id==330}+tag(330,13,256)).ifd(256,raw()).bytes())!!;p.requireFull(4000,3000)}
    @Test fun ordinaryTiffIsNotMislabeledDng(){assertNull(read(Tiff().ifd(8,raw()).bytes()))}
    @Test fun activeAreaIsAcceptedWithoutInventingRotation(){val p=read(primary(extra=listOf(tag(50829,4,10,20,2990,3980))).bytes())!!;p.requireFull(3960,2980);reject{p.requireFull(2980,3960)}}
    @Test fun integerDefaultCropIsAccepted(){val p=read(primary(extra=listOf(tag(50720,4,3900,2900))).bytes())!!;p.requireFull(3900,2900);reject{p.requireFull(1600,1200)}}
    @Test fun rationalDefaultCropIsAccepted(){val p=read(primary(extra=listOf(tag(50720,5,7800,2,5800,2))).bytes())!!;p.requireFull(3900,2900)}
    @Test fun fractionalCropFailsClosed(){reject{read(primary(extra=listOf(tag(50720,5,7801,2,5800,2))).bytes())}}
    @Test fun zeroDenominatorFailsClosed(){reject{read(primary(extra=listOf(tag(50720,5,3900,0,2900,1))).bytes())}}
    @Test fun shortDimensionsWorkInBothByteOrders(){for(little in listOf(true,false)){val r=raw().filterNot{it.id in listOf(256,257)}+listOf(tag(256,3,4000),tag(257,3,3000));read(Tiff(little).ifd(8,root()).ifd(256,r).bytes())!!.requireFull(4000,3000)}}
    @Test fun largerPreviewIsNeverThePrimary(){val root=root().filterNot{it.id in listOf(256,257)}+listOf(tag(256,4,8000),tag(257,4,6000));val p=read(Tiff().ifd(8,root).ifd(256,raw()).bytes())!!;reject{p.requireFull(8000,6000)}}
    @Test fun largestNonReducedRawIsSelected(){val root=root().filterNot{it.id==330}+tag(330,4,256,512,768);val p=read(Tiff().ifd(8,root).ifd(256,raw(1600,1200)).ifd(512,raw()).ifd(768,raw(8000,6000,subfile=1)).bytes())!!;p.requireFull(4000,3000);reject{p.requireFull(1600,1200)}}
    @Test fun transparencyMaskIsNotPrimary(){reject{read(Tiff().ifd(8,root()).ifd(256,raw(subfile=4)).bytes())}}
    @Test fun dngWithOnlyPreviewIsRejected(){reject{read(Tiff().ifd(8,root().filterNot{it.id==330}).bytes())}}
    @Test fun reducedCfaCannotMasqueradeAsMain(){reject{read(Tiff().ifd(8,root()).ifd(256,raw(subfile=1)).bytes())}}
    @Test fun truncatedDirectoryIsRejected(){val b=primary().bytes();reject{read(b.copyOf(b.size-3))}}
    @Test fun cycleIsRejected(){reject{read(Tiff().ifd(8,root(),8).ifd(256,raw()).bytes())}}
    @Test fun hugeDirectoryCountIsRejected(){val t=primary();t.buffer.putShort(256,65535.toShort());reject{read(t.bytes())}}
    @Test fun offsetBeyondEofIsRejected(){reject{read(Tiff().ifd(8,root(0xfffffff0L)).bytes())}}
    @Test fun hugeValueCountIsRejectedBeforeAllocation(){val t=primary();t.buffer.putInt(8+2+5*12+4,-1);reject{read(t.bytes())}}
    @Test fun duplicateCriticalTagIsRejected(){reject{read(primary(extra=listOf(tag(256,4,1600))).bytes())}}
    @Test fun zeroAndUnsignedOverflowDimensionsAreRejected(){for(w in listOf(0L,0xffffffffL))reject{read(Tiff().ifd(8,root()).ifd(256,raw(w=w)).bytes())}}
    @Test fun activeAreaOutsideStoredPixelsIsRejected(){reject{read(primary(extra=listOf(tag(50829,4,10,20,3001,4000))).bytes())}}
    @Test fun defaultCropOutsideStoredPixelsIsRejected(){reject{read(primary(extra=listOf(tag(50720,4,4001,3000))).bytes())}}
    @Test fun bigTiffFailsExplicitlyRatherThanBypassingGuard(){val t=primary();t.buffer.putShort(2,43);reject{read(t.bytes())}}
    @Test fun zeroLengthReadsAndNonSkippingStreamsDoNotHang(){val b=primary().bytes();val p=DngDimensions.read{object:ByteArrayInputStream(b){var once=true;override fun skip(n:Long)=0L;override fun read(b:ByteArray,o:Int,n:Int):Int{if(once){once=false;return 0};return super.read(b,o,n)}}}!!;p.requireFull(4000,3000)}
    @Test fun excessiveIfdChainIsBounded(){val t=Tiff();for(i in 0..64)t.ifd(8+i*200,if(i==0)listOf(tag(50706,1,1,4,0,0))else emptyList(),if(i==64)0 else 8+(i+1)*200);reject{read(t.bytes())}}
    @Test fun fullDecodeMustMatchBothDimensions(){PhotoPixelContract.decoded(4000,3000,1,4000,3000);for(s in listOf(1600 to 1200,4000 to 2999,3000 to 4000,4001 to 3000))reject{PhotoPixelContract.decoded(4000,3000,1,s.first,s.second)}}
    @Test fun sampledOddDimensionsAllowFloorAndCeiling(){PhotoPixelContract.decoded(4033,3025,2,2016,1512);PhotoPixelContract.decoded(4033,3025,2,2017,1513)}
    @Test fun ignoredSamplingIsNotAnUpscale(){PhotoPixelContract.decoded(4000,3000,4,4000,3000)}
    @Test fun unexpectedSmallDecodeCannotBeResizedToOutput(){reject{PhotoPixelContract.decoded(4000,3000,2,640,480)}}
    @Test fun invalidSampleAndDimensionsAreRejected(){for(sample in listOf(0,-1,3))reject{PhotoPixelContract.decoded(4000,3000,sample,4000,3000)};reject{PhotoPixelContract.decoded(0,1,1,1,1)}}
    @Test fun onePixelAxesStayPositiveWhenSampled(){PhotoPixelContract.decoded(1,17,8,1,2);reject{PhotoPixelContract.decoded(1,17,8,0,2)}}
}
