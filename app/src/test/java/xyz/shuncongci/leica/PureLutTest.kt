package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Synthetic, independently authored fixtures only; no private look nodes. */
class PureLutTest {
    private fun bytes(n:Int=2,node:(Int,Int,Int,Int)->Double={r,g,b,c->listOf(r,g,b)[c].toDouble()/(n-1)}):ByteArray {
        val out=ByteArrayOutputStream();DataOutputStream(out).use{d->
            d.writeBytes("PLUT0001");d.writeInt(n)
            if(n in 2..65)for(b in 0 until n)for(g in 0 until n)for(r in 0 until n)for(c in 0..2)d.writeDouble(node(r,g,b,c))
        };return out.toByteArray()
    }
    private fun hash(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun lut(b:ByteArray)=PureLut.read(ByteArrayInputStream(b),hash(b),Cancel())
    private fun rejected(f:()->Unit){try{f();fail("invalid input accepted")}catch(_:IllegalArgumentException){}catch(_:java.io.EOFException){}}
    private fun rgb(r:Int,g:Int,b:Int)=Planes(1,1,byteArrayOf(r.toByte()),byteArrayOf(g.toByte()),byteArrayOf(b.toByte()))
    @Test fun identityPreservesAllByteLevels(){val p=Planes(256,1);for(i in 0..255){p.p0[i]=i.toByte();p.p1[i]=(255-i).toByte();p.p2[i]=(i xor 91).toByte()};val q=lut(bytes()).render(p,Cancel());for(c in 0..2)assertArrayEquals(p.plane(c),q.plane(c))}
    @Test fun fullGridIdentityKeepsEndpoints(){val q=lut(bytes(65)).render(rgb(255,128,0),Cancel());assertEquals(255,q.p0[0].toInt() and 255);assertEquals(128,q.p1[0].toInt() and 255);assertEquals(0,q.p2[0].toInt() and 255)}
    @Test fun redFastestAndRgbChannelOrder(){val q=lut(bytes{r,g,b,c->listOf(b,r,g)[c].toDouble()}).render(rgb(12,91,203),Cancel());assertEquals(203,q.p0[0].toInt() and 255);assertEquals(12,q.p1[0].toInt() and 255);assertEquals(91,q.p2[0].toInt() and 255)}
    @Test fun independentlyComputedTrilinearPolynomial(){val q=lut(bytes{r,g,b,c->when(c){0->r*g*b.toDouble();1->(r+2*g+3*b)/6.0;else->1-r*g.toDouble()}}).render(rgb(73,144,215),Cancel());val r=73/255.0;val g=144/255.0;val b=215/255.0;val expected=doubleArrayOf(r*g*b,(r+2*g+3*b)/6,1-r*g);for(c in 0..2)assertEquals((expected[c]*255+.5).toInt(),q.plane(c)[0].toInt() and 255)}
    @Test fun halfIsRoundedUp(){val q=lut(bytes{_,_,_,_->.5}).render(rgb(0,0,0),Cancel());for(c in 0..2)assertEquals(128,q.plane(c)[0].toInt() and 255)}
    @Test fun sourceNeverChanges(){val p=rgb(37,91,213);val before=(0..2).map{p.plane(it).copyOf()};val q=lut(bytes{_,_,_,_->.0}).render(p,Cancel());assertNotSame(p,q);for(c in 0..2)assertArrayEquals(before[c],p.plane(c))}
    @Test fun dimensionsAndSpatialInvariance(){val p=Planes(2051,7);for(c in 0..2)p.plane(c).fill((17+71*c).toByte());val q=lut(bytes()).render(p,Cancel());assertEquals(2051,q.width);assertEquals(7,q.height);for(c in 0..2)assertArrayEquals(p.plane(c),q.plane(c))}
    @Test fun zeroAndOneNodesAreValid(){val q=lut(bytes{_,_,_,c->if(c==1)1.0 else 0.0}).render(rgb(34,77,199),Cancel());assertEquals(0,q.p0[0].toInt());assertEquals(255,q.p1[0].toInt() and 255)}
    @Test fun badMagicRejected(){val b=bytes();b[0]=0;rejected{lut(b)}}
    @Test fun gridsOutsideBoundRejected(){for(n in listOf(-1,0,1,66,Int.MAX_VALUE))rejected{lut(bytes(n))}}
    @Test fun invalidNodesRejected(){for(v in listOf(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-.001,1.001))rejected{lut(bytes{_,_,_,_->v})}}
    @Test fun truncatedDataRejected(){val b=bytes();for(n in listOf(0,7,11,12,b.size-1))rejected{lut(b.copyOf(n))}}
    @Test fun suffixRejected(){rejected{lut(bytes()+byteArrayOf(0))}}
    @Test fun wrongDigestRejected(){rejected{PureLut.read(ByteArrayInputStream(bytes()),"0".repeat(64),Cancel())}}
    @Test fun malformedDigestRejected(){rejected{PureLut.read(ByteArrayInputStream(bytes()),"invalid",Cancel())}}
    @Test fun readClosesStreamOnFailure(){var closed=false;val input=object:ByteArrayInputStream(bytes()){override fun close(){closed=true;super.close()}};rejected{PureLut.read(input,"0".repeat(64),Cancel())};assertTrue(closed)}
    @Test fun readClosesStreamOnSuccess(){val b=bytes();var closed=false;val input=object:ByteArrayInputStream(b){override fun close(){closed=true;super.close()}};PureLut.read(input,hash(b),Cancel());assertTrue(closed)}
    @Test fun cancelledReadDoesNotReturnPartialTable(){val b=bytes();try{PureLut.read(ByteArrayInputStream(b),hash(b),Cancel().apply{cancelled=true});fail("cancelled read completed")}catch(_:CancellationException){}}
    @Test fun cancelledRenderDoesNotPublish(){val l=lut(bytes());try{l.render(rgb(2,5,7),Cancel().apply{cancelled=true});fail("cancelled render completed")}catch(_:CancellationException){}}
}
