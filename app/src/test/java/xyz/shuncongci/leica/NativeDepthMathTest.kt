package xyz.shuncongci.leica
import org.junit.Assert.*
import org.junit.Test
class NativeDepthMathTest {
 @Test fun fullPhotoPadsWithoutResizing(){assertEquals(3080 to 4088,NativeDepthMath.paddedSize(3072,4080))}
 @Test fun wideAndTallSupported(){assertEquals(4088 to 3080,NativeDepthMath.paddedSize(4080,3072));assertEquals(1008 to 756,NativeDepthMath.paddedSize(1000,750))}
 @Test fun exactMultiplesUnchanged(){assertEquals(518 to 686,NativeDepthMath.paddedSize(518,686))}
 @Test(expected=IllegalArgumentException::class) fun rejectsZero(){NativeDepthMath.paddedSize(0,100)}
 @Test(expected=IllegalArgumentException::class) fun rejectsOverflowWithoutFallback(){NativeDepthMath.paddedSize(Int.MAX_VALUE,Int.MAX_VALUE)}
 @Test fun paddingReplicatesLastPixelAndNormalizesFp32(){
  val p=Planes(2,2);p.p0[0]=0;p.p0[1]=64;p.p0[2]=128.toByte();p.p0[3]=255.toByte()
  val f=NativeDepthMath.input(p,Cancel());assertEquals(14,f.w);assertEquals(14,f.h)
  assertEquals((0f-.485f)/.229f,f.data[0],0f)
  assertEquals((64/255f-.485f)/.229f,f.data[1],0f)
  assertEquals(f.data[1],f.data[13],0f)
  assertEquals((1f-.485f)/.229f,f.data[195],0f)
 }
 @Test fun cropRemovesPaddingNotResizes(){val raw=LegendaryMath.Frame(14,14,1,FloatArray(196){it.toFloat()});val c=NativeDepthMath.crop(raw,2,2,Cancel());assertArrayEquals(floatArrayOf(0f,1f,14f,15f),c.data,0f)}
 @Test fun exifBeforePadding(){val p=Planes(2,3).exifTransposed(6);assertEquals(3,p.width);assertEquals(2,p.height);assertEquals(14 to 14,NativeDepthMath.paddedSize(p.width,p.height))}
 @Test fun outputChoiceCannotChangeDepthLimit(){for(o in BokehOutput.entries){LeicaSizes.workingSize(3072,4080,o);assertEquals(919 to 1220,NativeDepthMath.analysisSize(3072,4080))}}
 @Test fun cancelledInputAborts(){val c=Cancel();c.cancelled=true;try{NativeDepthMath.input(Planes(2,2),c);fail("must cancel")}catch(expected:java.util.concurrent.CancellationException){}}
 @Test fun declaredPrecisionAndGlobalQueryChunk(){assertEquals("FP32",NativeDepthMath.PRECISION);assertEquals(64,NativeDepthMath.QUERY_CHUNK)}
 @Test fun analysisCapsLongEdgeAndKeepsAspect(){assertEquals(919 to 1220,NativeDepthMath.analysisSize(3072,4080));assertEquals(1220 to 919,NativeDepthMath.analysisSize(4080,3072));assertEquals(1220 to 915,NativeDepthMath.analysisSize(4000,3000))}
 @Test fun smallImagesNotUpscaled(){assertEquals(1000 to 750,NativeDepthMath.analysisSize(1000,750));assertEquals(1220 to 1220,NativeDepthMath.analysisSize(1220,1220))}
 @Test fun limitedInputReallyResizesBeforePadding(){val f=NativeDepthMath.input(Planes(2050,1),Cancel());assertEquals(1232,f.w);assertEquals(14,f.h);val crop=NativeDepthMath.crop(LegendaryMath.Frame(f.w,f.h,1,FloatArray(f.w*f.h)),1220,1,Cancel());assertEquals(1220,crop.w);assertEquals(1,crop.h)}
 @Test fun extremeAspectHasAtLeastOnePixel(){assertEquals(1220 to 1,NativeDepthMath.analysisSize(Int.MAX_VALUE,1))}
 @Test fun newLimitBoundaryNeverUpscales(){
  assertEquals(1220,NativeDepthMath.MAX_EDGE)
  assertEquals(1219 to 1219,NativeDepthMath.analysisSize(1219,1219))
  assertEquals(1220 to 1220,NativeDepthMath.analysisSize(1220,1220))
  assertEquals(1220 to 1220,NativeDepthMath.analysisSize(1221,1221))
  assertEquals(1220 to 1220,NativeDepthMath.analysisSize(2048,2048))
 }
 @Test fun portraitInputPadsThenCropsToRequestedAnalysis(){
  val (w,h)=NativeDepthMath.analysisSize(3072,4080)
  assertEquals(919 to 1220,w to h)
  assertEquals(924 to 1232,NativeDepthMath.paddedSize(w,h))
  val raw=LegendaryMath.Frame(924,1232,1,FloatArray(924*1232){(it%924).toFloat()})
  val cropped=NativeDepthMath.crop(raw,w,h,Cancel())
  assertEquals(w*h,cropped.size);assertEquals(918f,cropped.data.last(),0f)
  assertEquals(0f,cropped.data[w],0f)
 }
 @Test fun actualTwoDimensionalInputUses1220Budget(){
  val frame=NativeDepthMath.input(Planes(1300,1000),Cancel())
  assertEquals(1232,frame.w);assertEquals(938,frame.h)
  assertEquals(3,frame.channels);assertTrue(frame.data.all{it.isFinite()})
  assertEquals(frame.data[1219],frame.data[1231],0f)
 }
 @Test fun renderOutputAndCoarsePreviewStayIndependent(){
  assertEquals(1542 to 2048,LeicaSizes.workingSize(3072,4080,BokehOutput.SIZE_2048))
  assertEquals(517 to 686,ProgressiveDepth.size(3072,4080))
  assertEquals(919 to 1220,NativeDepthMath.analysisSize(3072,4080))
 }
}
