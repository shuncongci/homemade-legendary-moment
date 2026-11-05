package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test

class VideoIconGeometryTest {
    // Actual path bounds (round stroke expansion is symmetric); guarded against catalog by local verifier.
    private fun ink(key:String)=when(key){
        "PLAY"->VideoIconGeometry.Ink(7.15f,3.15f,21.85f,20.85f)
        "PAUSE"->VideoIconGeometry.Ink(6.15f,3.15f,18.85f,20.85f)
        "FILE"->VideoIconGeometry.Ink(4.15f,2.15f,20.85f,21.85f)
        else->VideoIconGeometry.Ink(3.15f,2.15f,20.85f,21.85f)
    }
    private fun centered(key:String) {
        val b=ink(key)
        for(w in listOf(48,54,64,107,108,161,192))for(h in listOf(48,54,95,144))for(side in listOf(22,33,44,66)){
            val o=VideoIconGeometry.origin(w,h,side,b);val s=side/24f
            assertEquals(w/2f,o.x+(b.left+b.right)/2*s,0.00003f)
            assertEquals(h/2f,o.y+(b.top+b.bottom)/2*s,0.00003f)
        }
    }
    @Test fun playCentered()=centered("PLAY")
    @Test fun pauseCentered()=centered("PAUSE")
    @Test fun importCentered()=centered("FILE")
    @Test fun exportCentered()=centered("EXPORT")
    @Test fun playViewportOffsetCorrected(){assertTrue(VideoIconGeometry.origin(54,54,22,ink("PLAY")).x<(54-22)/2f)}
    @Test fun pauseViewportOffsetCorrected(){assertTrue(VideoIconGeometry.origin(54,54,22,ink("PAUSE")).x<(54-22)/2f)}
    @Test fun oddWidthPreservesHalfPixel(){val a=VideoIconGeometry.origin(54,54,22,ink("FILE"));val b=VideoIconGeometry.origin(55,54,22,ink("FILE"));assertEquals(.5f,b.x-a.x,0.00001f)}
    @Test fun oddHeightPreservesHalfPixel(){val a=VideoIconGeometry.origin(54,54,22,ink("FILE"));val b=VideoIconGeometry.origin(54,55,22,ink("FILE"));assertEquals(.5f,b.y-a.y,0.00001f)}
    @Test fun zeroSizeLayoutFinite(){val o=VideoIconGeometry.origin(0,0,22,ink("PLAY"));assertTrue(o.x.isFinite()&&o.y.isFinite())}
    private fun rejects(block:()->Unit){try{block();fail()}catch(e:IllegalArgumentException){}}
    @Test fun invalidSide()=rejects{VideoIconGeometry.origin(54,54,0,ink("PLAY"))}
    @Test fun invalidWidth()=rejects{VideoIconGeometry.origin(-1,54,22,ink("PLAY"))}
    @Test fun nonfiniteInk()=rejects{VideoIconGeometry.origin(54,54,22,VideoIconGeometry.Ink(Float.NaN,0f,22f,22f))}
    @Test fun emptyInk()=rejects{VideoIconGeometry.origin(54,54,22,VideoIconGeometry.Ink(2f,2f,2f,2f))}
    @Test fun reversedInk()=rejects{VideoIconGeometry.origin(54,54,22,VideoIconGeometry.Ink(20f,20f,2f,2f))}
}
