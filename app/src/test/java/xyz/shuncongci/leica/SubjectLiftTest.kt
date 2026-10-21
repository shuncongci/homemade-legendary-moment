package xyz.shuncongci.leica
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import xyz.shuncongci.leica.LegendaryMath.Frame
import xyz.shuncongci.leica.LegendaryMath.Point

class SubjectLiftTest {
    private fun depth()=Frame(121,81,1,FloatArray(121*81){.15f+.7f*(it%121)/120})
    private fun mask(value:(Int)->Float)=Frame(121,81,1,FloatArray(121*81,value))
    private fun tiles(alpha:Frame?=null)=DevelopMath.focusTiles(depth(),Point(.2f,.5f),.95f,2048,1365,Cancel(),alpha)!!
    @Test fun nullProtectionMatchesOriginalOpticalOnly(){val d=depth();val a=DevelopMath.focusTiles(d,Point(.2f,.5f),.95f,2048,1365,Cancel())!!;assertArrayEquals(a.values,tiles().values,0f)}
    @Test fun zeroMaskDoesNotRaiseBackground(){assertArrayEquals(tiles().values,tiles(mask{0f}).values,0f)}
    @Test fun fullProtectionRaisesAllTiles(){assertTrue(tiles(mask{1f}).values.all{it==1f})}
    @Test fun outOfFocusSubjectIsIncludedInRaisedSurface(){val a=tiles();val b=tiles(mask{if(it%121>70)1f else 0f});var raised=0;for(i in a.values.indices){assertTrue(b.values[i]>=a.values[i]);if(i%b.cols>b.cols*3/4){assertEquals(1f,b.values[i],0f);if(a.values[i]<.01f)raised++}};assertTrue(raised>10)}
    @Test fun fractionalProtectionRetainsSmoothAlphaLift(){val b=tiles(mask{.5f});assertTrue(b.values.all{it>=.5f});assertTrue(b.values.any{it==.5f})}
    @Test fun multipleDisjointSubjectsBothLift(){val b=tiles(mask{if(it%121 in 53..68 || it%121>95)1f else 0f});val row=b.rows/2;assertTrue(b.values[row*b.cols+b.cols/2]>.9f);assertTrue(b.values[row*b.cols+b.cols-2]>.9f)}
    @Test fun neitherAlphaNorDepthIsMutated(){val d=depth();val a=mask{.7f};val da=d.data.copyOf();val aa=a.data.copyOf();DevelopMath.focusTiles(d,Point(.2f,.5f),.95f,1024,683,Cancel(),a);assertArrayEquals(da,d.data,0f);assertArrayEquals(aa,a.data,0f)}
    @Test fun protectionDoesNotInventReliableFocus(){assertNull(DevelopMath.focusTiles(mask{.5f},Point(.2f,.5f),.95f,1024,683,Cancel(),mask{1f}))}
    @Test(expected=CancellationException::class) fun cancelledSamplingStops(){DevelopMath.focusTiles(depth(),Point(.2f,.5f),.95f,2048,1365,Cancel().apply{cancelled=true},mask{1f})}
    @Test(expected=IllegalArgumentException::class) fun invalidAlphaRejected(){tiles(mask{Float.NaN})}
}
