package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Real public resource identity and synthetic reader failures; no private look or photo fixtures. */
class PublicColorLutTest {
    private fun asset(name:String):File = listOf(File("src/main/assets",name),File("app/src/main/assets",name)).single{it.isFile}
    private fun hash(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun reject(action:()->Unit){try{action();fail("must reject")}catch(_:IllegalArgumentException){}catch(_:java.io.EOFException){}}
    @Test fun exactlyThreePublicPhotoChoices(){assertEquals(listOf("cool","warm","leica-instant-v2"),LeicaStyle.entries.map{it.id})}
    @Test fun privateSavedChoiceFallsBack(){assertSame(LeicaStyle.DEFAULT,LeicaStyle.fromId("legendary-moment-v1"))}
    @Test fun photoAndVideoShareIdenticalInstant(){assertEquals(LeicaStyle.LEICA_INSTANT.asset,VideoColor.INSTANT.asset);assertEquals(LeicaStyle.LEICA_INSTANT.sha256,VideoColor.INSTANT.sha256)}
    @Test fun bothResourcesArePinnedAndExactLength(){for(v in VideoColor.entries){val b=asset(v.asset).readBytes();assertEquals(ColorLutFiles.SIZE,b.size);assertEquals(v.sha256,hash(b));assertArrayEquals(b,ColorLutFiles.read(b.inputStream(),v.sha256,Cancel()))}}
    @Test fun publicNodesAreFiniteAndInGamut(){for(v in VideoColor.entries){val b=asset(v.asset).readBytes();val n=ByteBuffer.wrap(b,12,b.size-12);while(n.hasRemaining()){val x=n.double;assertTrue(x.isFinite() && x in 0.0..1.0)}}}
    @Test fun twoLooksAreDistinct(){assertNotEquals(VideoColor.INSTANT.sha256,VideoColor.N_INSTANT.sha256);assertFalse(asset(VideoColor.INSTANT.asset).readBytes().contentEquals(asset(VideoColor.N_INSTANT.asset).readBytes()))}
    @Test fun wrongHashRejected(){val b=asset(VideoColor.INSTANT.asset).readBytes();reject{ColorLutFiles.read(b.inputStream(),"0".repeat(64),Cancel())}}
    @Test fun changedNodeRejected(){val b=asset(VideoColor.INSTANT.asset).readBytes();b[b.lastIndex]=(b.last().toInt() xor 1).toByte();reject{ColorLutFiles.read(b.inputStream(),VideoColor.INSTANT.sha256,Cancel())}}
    @Test fun truncatedAndTrailingDataRejected(){val b=asset(VideoColor.INSTANT.asset).readBytes();reject{ColorLutFiles.read(b.copyOf(b.size-1).inputStream(),hash(b),Cancel())};val extra=b+byteArrayOf(0);reject{ColorLutFiles.read(extra.inputStream(),hash(extra),Cancel())}}
    @Test fun wrongHeaderRejectedEvenWithMatchingHash(){val b=asset(VideoColor.INSTANT.asset).readBytes();b[0]=0;reject{ColorLutFiles.read(b.inputStream(),hash(b),Cancel())}}
    @Test fun streamClosedOnFailureAndCancellation(){for(cancel in listOf(false,true)){var closed=false;val stream=object:ByteArrayInputStream(byteArrayOf(1)){override fun close(){closed=true;super.close()}};try{ColorLutFiles.read(stream,"0".repeat(64),Cancel().apply{cancelled=cancel});fail()}catch(_:java.io.EOFException){}catch(_:CancellationException){};assertTrue(closed)}}
    @Test fun photoLutPreservesSourceAndDimensions(){val s=LeicaStyle.LEICA_INSTANT;val lut=PureLut.read(asset(s.asset).inputStream(),s.sha256,Cancel());val p=Planes(7,5).apply{for(i in p0.indices){p0[i]=(i*7).toByte();p1[i]=(255-i*3).toByte();p2[i]=(i*5).toByte()}};val a=p.p0.copyOf();val b=p.p1.copyOf();val c=p.p2.copyOf();val o=lut.render(p,Cancel());assertEquals(p.width,o.width);assertEquals(p.height,o.height);assertArrayEquals(a,p.p0);assertArrayEquals(b,p.p1);assertArrayEquals(c,p.p2);assertFalse(o.p0.contentEquals(p.p0) && o.p1.contentEquals(p.p1) && o.p2.contentEquals(p.p2))}
}
