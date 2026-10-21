package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.math.*

class SubjectMatteTilesTest {
    private val U=SubjectMatteTiles.UNKNOWN
    /** Padded 4-channel matte input; channel 3 is the trimap from [tri]. */
    private fun input(w:Int,h:Int,tri:(Int,Int)->Float):LegendaryMath.Frame {
        val n=w*h;val d=FloatArray(4*n)
        for(y in 0 until h)for(x in 0 until w){val i=y*w+x
            d[i]=((x*37+y*91)%23)/11f-1f;d[n+i]=((x*53+y*29)%13)/6f-1f;d[2*n+i]=((x+2*y)%17)/8f-1f;d[3*n+i]=tri(x,y)}
        return LegendaryMath.Frame(w,h,4,d)
    }
    private fun ring(w:Int,h:Int)={x:Int,y:Int->val r=hypot(x-w/2.0,y-h/2.0);if(r<300)1f else if(r<330)U else 0f}
    /** Position-independent stand-in model: alpha depends only on that pixel's 4 input values. */
    private fun pointwise(f:LegendaryMath.Frame):LegendaryMath.Frame {
        val n=f.size;return LegendaryMath.Frame(f.w,f.h,1,FloatArray(n){i->(.5f+.1f*f.data[i]-.07f*f.data[n+i]+.05f*f.data[2*n+i]+.3f*(f.data[3*n+i]-.5f)).coerceIn(0f,1f)})
    }

    @Test fun oldBudgetFrameIsOneWholePassUnchanged(){
        val f=input(928,1248,ring(928,1248));var calls=0
        val out=SubjectMatteTiles.matte(f,Cancel()){calls++;assertSame(f,it);pointwise(it)}
        assertEquals(1,calls);assertArrayEquals(pointwise(f).data,out.data,0f)
    }
    @Test fun largeFrameUsesBoundedAlignedTilesCoveringEveryUnknown(){
        val w=1536;val h=2048;val f=input(w,h,ring(w,h));val tiles=SubjectMatteTiles.plan(f,Cancel())
        assertTrue(tiles.size>1)
        for(t in tiles){assertEquals(1024,t.w);assertEquals(1024,t.h);assertEquals(0,t.x%32);assertEquals(0,t.y%32);assertTrue(t.x+t.w<=w && t.y+t.h<=h)}
        val off=3*f.size
        for(y in 0 until h)for(x in 0 until w)if(f.data[off+y*w+x]==U){
            // Every grid tile touching an unknown pixel runs, not merely one of them.
            for(ty in SubjectMatteTiles.starts(h,1024))for(tx in SubjectMatteTiles.starts(w,1024))
                if(x in tx until tx+1024 && y in ty until ty+1024)assertTrue(Pair(tx,ty).toString(),tiles.any{it.x==tx && it.y==ty})
        }
    }
    @Test fun tiledPointwiseModelEqualsWholeFrame(){
        for((w,h) in listOf(1536 to 2048,2048 to 1536,2048 to 640)){
            val f=input(w,h,ring(w,h));val whole=pointwise(f);var maxTile=0
            val tiled=SubjectMatteTiles.matte(f,Cancel()){maxTile=max(maxTile,it.w*it.h);pointwise(it)}
            assertTrue(maxTile<=1024*1024)
            val off=3*f.size;var unknown=0
            for(i in 0 until f.size){
                if(f.data[off+i]==U){unknown++;assertEquals("$w×$h @$i",whole.data[i],tiled.data[i],1e-5f)}
                else assertTrue(tiled.data[i]==f.data[off+i] || abs(tiled.data[i]-whole.data[i])<=1e-5f)
            }
            assertTrue(unknown>0)
        }
    }
    @Test fun knownOnlyRegionsSkipModel(){
        val w=2048;val h=1536
        val f=input(w,h){x,y->if(x in 100..140 && y in 100..140)U else if(x<120)1f else 0f}
        val tiles=SubjectMatteTiles.plan(f,Cancel());assertEquals(1,tiles.size);assertEquals(0,tiles[0].x);assertEquals(0,tiles[0].y)
        val none=input(w,h){x,_->if(x<1000)1f else 0f};var calls=0
        val out=SubjectMatteTiles.matte(none,Cancel()){calls++;pointwise(it)}
        assertEquals(0,calls);for(i in 0 until none.size)assertEquals(none.data[3*none.size+i],out.data[i],0f)
    }
    @Test fun startsAlignedSpanFullLengthWithFeatherOverlap(){
        for(len in (1024..8192 step 32)){
            val s=SubjectMatteTiles.starts(len,1024)
            assertEquals(0,s.first());assertEquals(len-1024,s.last())
            for(i in s.indices){assertEquals(0,s[i]%32);if(i>0){val gap=s[i]-s[i-1];assertTrue("len=$len gap=$gap",gap>0 && gap<=SubjectMatteTiles.MAX_STEP);assertTrue(1024-gap>=2*SubjectMatteTiles.FEATHER)}}
        }
        assertArrayEquals(intArrayOf(0),SubjectMatteTiles.starts(640,640))
    }
    @Test fun featherWeightPositiveInsideTile(){
        for(u in 0 until 1024){assertTrue(SubjectMatteTiles.weight(u,0,1024,2048)>0f);assertTrue(SubjectMatteTiles.weight(u,512,1024,2048)>0f);assertEquals(1f,SubjectMatteTiles.weight(u,0,1024,1024),0f)}
    }
    @Test fun cancelBetweenTilesPropagates(){
        val f=input(1536,2048,ring(1536,2048));val c=Cancel();var calls=0
        try{SubjectMatteTiles.matte(f,c){calls++;c.cancelled=true;pointwise(it)};fail("must cancel")}catch(_:CancellationException){}
        assertEquals(1,calls)
    }
    @Test fun wrongModelOutputSizeRejected(){
        for((w,h) in listOf(928 to 1248,1536 to 2048)){val f=input(w,h,ring(w,h))
            try{SubjectMatteTiles.matte(f,Cancel()){LegendaryMath.Frame(it.w-32,it.h,1,FloatArray((it.w-32)*it.h))};fail()}catch(_:IllegalArgumentException){}}
    }
    @Test(expected=IllegalArgumentException::class) fun unpaddedInputRejected(){SubjectMatteTiles.plan(LegendaryMath.Frame(1537,2048,4,FloatArray(4*1537*2048)),Cancel())}

    @Test fun trimapRadiusFollowsAnalysisScale(){
        assertEquals(5,SubjectProtectionMath.trimapRadius(23,19));assertEquals(5,SubjectProtectionMath.trimapRadius(914,1220))
        assertEquals(8,SubjectProtectionMath.trimapRadius(1536,2048));assertEquals(8,SubjectProtectionMath.trimapRadius(2048,1536))
        assertEquals(7,SubjectProtectionMath.trimapRadius(1280,1708))
    }
    /** Literal 2.1.20 per-pixel scan, generalised to radius r; r=5 is byte-for-byte the old code. */
    private fun naive(mask:LegendaryMath.Frame,r:Int):FloatArray {
        val out=FloatArray(mask.size);val rr=r.toDouble()
        val radii=IntArray(2*r+1){dy->round(rr*sqrt(max(0.0,1-(dy-rr).pow(2)/(rr*rr)))).toInt()}
        for(y in 0 until mask.h)for(x in 0 until mask.w){var all=true;var any=false
            for(dy in -r..r){val yy=y+dy;if(yy !in 0 until mask.h)continue;val q=radii[dy+r]
                for(dx in -q..q){val xx=x+dx;if(xx !in 0 until mask.w)continue;val inside=mask.data[yy*mask.w+xx]>.5f;all=all && inside;any=any || inside}}
            out[y*mask.w+x]=if(all)1f else if(!any)0f else 128f/255f}
        return out
    }
    @Test fun fastTrimapMatchesPerPixelEllipse(){
        val w=97;val h=73
        val m=LegendaryMath.Frame(w,h,1,FloatArray(w*h){i->val x=i%w;val y=i/w
            if(hypot(x-40.0,y-30.0)<18 || (x>70 && (x*7+y*3)%11<5) || (y==60 && x%3==0))1f else if((x+y)%29==0).5f else 0f})
        for(r in listOf(1,5,8,11))assertArrayEquals("r=$r",naive(m,r),SubjectProtectionMath.trimap(m,r,Cancel()),0f)
        assertArrayEquals(naive(m,5),SubjectProtectionMath.trimap(m,Cancel()),0f)
    }
}
