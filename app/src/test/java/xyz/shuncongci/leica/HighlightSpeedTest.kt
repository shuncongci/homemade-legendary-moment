package xyz.shuncongci.leica
import org.junit.Assert.*
import org.junit.Test
import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.random.Random

/** Compare the optimized support with the same full-frame PSF/depth algorithm, no changed tolerances. */
class HighlightSpeedTest {
    private fun compare(w:Int,h:Int,spots:List<Pair<Int,Int>>,depth:(Int)->Float={2f},energy:Float=1f,ap:Float=.95f){
        val n=w*h;val high=Frame(w,h,3,FloatArray(n*3));for((x,y) in spots)for(ch in 0..2)high.data[ch*n+y*w+x]=energy/(ch+1)
        val c=Frame(w,h,1,FloatArray(n,depth));val saved=c.data.copyOf();val before=high.data.copyOf()
        val nearA=Frame(w,h,3,FloatArray(n*3));val nearB=Frame(w,h,3,FloatArray(n*3))
        val a=HighlightScatter.opticalAtScale(high,c,ap,Cancel(),3f,nearA,false)
        val b=HighlightScatter.opticalAtScale(high,c,ap,Cancel(),3f,nearB,true)
        assertArrayEquals("color must be bit-identical",a.data,b.data,0f);assertArrayEquals("foreground must be bit-identical",nearA.data,nearB.data,0f)
        assertArrayEquals(saved,c.data,0f);assertArrayEquals(before,high.data,0f)
    }
    @Test fun centeredSparseLight(){compare(121,83,listOf(60 to 41))}
    @Test fun allFourCornersAndReflectedEdges(){for(p in listOf(0 to 0,0 to 82,120 to 0,120 to 82,60 to 0,0 to 41))compare(121,83,listOf(p))}
    @Test fun disconnectedLightSupports(){compare(141,91,listOf(20 to 20,120 to 70))}
    @Test fun fractionalPositiveAndNegativeLayers(){compare(91,67,listOf(14 to 11,60 to 50),{i->if(i%91<40)2.35f else -1.65f})}
    @Test fun nearDarkOccluderIsPreserved(){compare(91,67,listOf(31 to 33),{i->if(i%91>35).25f else -3.5f})}
    @Test fun farDarkLayersKeepReceiverCompletion(){compare(91,67,listOf(31 to 33),{i->if(i%91>35)-4.4f else 2.25f})}
    @Test fun noDarkThresholdForVeryDimLight(){compare(91,67,listOf(20 to 15),energy=1e-20f)}
    @Test fun allDarkRemainsExact(){compare(91,67,emptyList())}
    @Test fun denseLightsDoNotChange(){compare(71,51,(0 until 71*51).map{it%71 to it/71})}
    @Test fun zeroRadiusRemainsExact(){compare(121,83,listOf(60 to 41),{0f})}
    @Test fun onePixelAxesAndRepeatedReflection(){compare(1,43,listOf(0 to 0),{3.6f});compare(45,1,listOf(44 to 0),{-3.6f});compare(1,1,listOf(0 to 0),{3.6f})}
    @Test fun wideRadiusLargerThanCanvas(){compare(17,13,listOf(2 to 3),{9.6f})}
    @Test fun stoppedDownPupilRemainsExact(){compare(101,75,listOf(12 to 18),{-2.3f},ap=5.6f)}
    @Test fun randomizedLayerMaps(){repeat(4){seed->val r=Random(seed);val c=FloatArray(81*55){r.nextInt(-2,3)+.3f};compare(81,55,List(4){r.nextInt(81) to r.nextInt(55)},{c[it]})}}
}
