package xyz.shuncongci.leica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import kotlin.math.abs

/**
 * Compares the Kotlin image math with references produced on the PC by Pillow / OpenCV
 * (tools/generate_test_fixtures.py; synthetic inputs, no photographs).
 * References are raw RGB (.rgb: BE int width, BE int height, interleaved RGB) because the
 * Android unit-test classpath has no java.awt / ImageIO.
 */
class LeicaMathTest {
    private fun load(name: String): Planes {
        val file = name.removeSuffix(".png") + ".rgb"
        val stream = javaClass.classLoader!!.getResourceAsStream("leica/$file") ?: error("missing $file")
        return DataInputStream(stream.buffered()).use { s ->
            val w = s.readInt()
            val h = s.readInt()
            val rgb = ByteArray(w * h * 3)
            s.readFully(rgb)
            val p = Planes(w, h)
            for (i in 0 until w * h) {
                p.p0[i] = rgb[i * 3]; p.p1[i] = rgb[i * 3 + 1]; p.p2[i] = rgb[i * 3 + 2]
            }
            p
        }
    }

    private data class Diff(val max: Int, val mean: Double, val over2: Double)

    private fun diff(a: Planes, b: Planes): Diff {
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        var max = 0; var sum = 0L; var over = 0L
        for (p in 0 until 3) {
            val x = a.plane(p); val y = b.plane(p)
            for (i in x.indices) {
                val d = abs((x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF))
                if (d > max) max = d
                sum += d
                if (d > 2) over++
            }
        }
        val n = a.size * 3.0
        return Diff(max, sum / n, over / n)
    }

    @Test fun bilinearDownscaleMatchesPillow() {
        val d = diff(Resample.resize(load("input_1024.png"), 500, 666, Resample.Filter.BILINEAR), load("bilinear_500x666.png"))
        println("bilinear $d")
        assertTrue("$d", d.max <= 1)
    }

    @Test fun bicubicDownscaleMatchesPillow() {
        val d = diff(Resample.resize(load("input_1024.png"), 777, 1033, Resample.Filter.BICUBIC), load("bicubic_777x1033.png"))
        println("bicubic-down $d")
        assertTrue("$d", d.max <= 1)
    }

    @Test fun bicubicUpscaleMatchesPillow() {
        val d = diff(Resample.resize(load("leica-modern-1.0.expected_1024.png"), 1500, 1998, Resample.Filter.BICUBIC), load("bicubic_1500x1998.png"))
        println("bicubic-up $d")
        assertTrue("$d", d.max <= 1)
    }

    @Test fun labMatchesOpenCv() {
        val p = load("input_1024.png")
        Lab.rgbToLabInPlace(p)
        val d = diff(p, load("lab_input_1024.png"))
        println("lab $d")
        assertTrue("$d", d.max <= 1 && d.mean < 0.001)
    }

    @Test fun fusionMatchesPipeline() {
        val out = Fusion.fuse(load("input_1024.png"), load("leica-modern-1.0.expected_1024.png"))
        val d = diff(out, load("fusion_modern_1024.png"))
        println("fusion $d")
        // PC checks: integer RGB2Lab + cv2 LAB2RGB reaches max 11 on a few isolated pixels (diag_lab_int.py);
        // float LAB2RGB vs cv2's table-based version differs by max 2, mean 0.074 (diag_fusion.py).
        assertTrue("$d", d.max <= 12 && d.mean < 0.1 && d.over2 < 0.0005)
    }

    @Test fun gaussianKernelMatchesOpenCvSize() {
        assertEquals(201, Gaussian.kernel(25.0).size)
        assertEquals(161, Gaussian.kernel(20.0).size)
        assertEquals(1.0, Gaussian.kernel(25.0).sum().toDouble(), 1e-5)
        assertEquals(3, Gaussian.reflect101(-3, 10)); assertEquals(8, Gaussian.reflect101(10, 10))
    }

    @Test fun exifOrientationMatchesPilTranspose() {
        // 3x2 image, pixel value = index; checks where the top-left / top-right source pixels land.
        val p = Planes(3, 2)
        for (i in 0 until 6) { p.p0[i] = i.toByte(); p.p1[i] = i.toByte(); p.p2[i] = i.toByte() }
        val cw = p.exifTransposed(6)   // rotate 90° clockwise -> 2x3, top row = source column 0 bottom-up
        assertEquals(2, cw.width); assertEquals(3, cw.height)
        assertEquals(listOf<Byte>(3, 0, 4, 1, 5, 2), cw.p0.toList())
        val ccw = p.exifTransposed(8)  // rotate 90° counter-clockwise -> top row = source column 2 top-down
        assertEquals(listOf<Byte>(2, 5, 1, 4, 0, 3), ccw.p0.toList())
        assertEquals(listOf<Byte>(5, 4, 3, 2, 1, 0), p.exifTransposed(3).p0.toList())
        assertEquals(listOf<Byte>(2, 1, 0, 5, 4, 3), p.exifTransposed(2).p0.toList())
        assertTrue(p.exifTransposed(1) === p)
    }

    @Test fun modelSizeFitsLong1024() {
        assertEquals(768 to 1024, LeicaSizes.modelSize(3072, 4094))
        assertEquals(1024 to 768, LeicaSizes.modelSize(4000, 3000))
        assertEquals(1024 to 1024, LeicaSizes.modelSize(360, 360))
        assertEquals(768 to 1024, LeicaSizes.modelSize(360, 480))
        assertEquals(1024 to 512, LeicaSizes.modelSize(800, 400))
        assertEquals(1024 to 768, LeicaSizes.modelSize(640, 480))
        assertTrue(LeicaSizes.usesFusion(3072, 4094))
        assertTrue(LeicaSizes.usesFusion(360, 480))
        assertTrue(LeicaSizes.usesFusion(1024, 4000))
        assertEquals(10 to 1024, LeicaSizes.modelSize(100, 10000))
        assertEquals(1024 to 10, LeicaSizes.modelSize(10000, 100))
        assertEquals(1024 to 1024, LeicaSizes.modelSize(1, 1))
    }
    @Test fun bokehGanModelSizeAlwaysHasLongEdge1024() {
        assertEquals(768 to 1024,LeicaSizes.modelSize(3072,4094,true))
        assertEquals(1024 to 768,LeicaSizes.modelSize(4000,3000,true))
        assertEquals(1024 to 1024,LeicaSizes.modelSize(360,360,true))
        assertEquals(768 to 1024,LeicaSizes.modelSize(360,480,true))
        assertEquals(1024 to 512,LeicaSizes.modelSize(800,400,true))
        assertEquals(10 to 1024,LeicaSizes.modelSize(100,10000,true))
        assertEquals(1024 to 10,LeicaSizes.modelSize(10000,100,true))
        assertTrue(LeicaSizes.usesFusion(360,480))
    }
    @Test fun fatalErrorsEscapeEvenWhenWrapped() {
        val fatal=OutOfMemoryError("test marker only; no large allocation")
        try { propagateFatalCause(java.util.concurrent.ExecutionException(fatal)); org.junit.Assert.fail("fatal error swallowed") }
        catch(e:OutOfMemoryError) { org.junit.Assert.assertSame(fatal,e) }
        propagateFatalCause(java.io.IOException("normal IO failure"))
    }
    @Test fun pngWidthHasNoApplicationCap() {
        val bytes=java.io.ByteArrayOutputStream()
        val w=100001
        NativePng(bytes,w,1).use { it.row(ByteArray(w*3)) }
        val header=java.io.DataInputStream(java.io.ByteArrayInputStream(bytes.toByteArray()))
        header.skipBytes(16);assertEquals(w,header.readInt());assertEquals(1,header.readInt())
    }
    @Test fun memoryRssParserAndMiBUnits() {
        assertEquals(123456L*1024,MemoryMetrics.rssBytes("VmRSS:\t  123456 kB"))
        assertEquals(-1L,MemoryMetrics.rssBytes(null))
        assertEquals(-1L,MemoryMetrics.rssBytes("VmRSS: unknown"))
        assertEquals("1024",MemoryMetrics.mib(1024L*1024*1024))
    }
    @Test fun workingSizeFits2048AndPreservesAspect() {
        assertEquals(1536 to 2048,LeicaSizes.workingSize(3000,4000))
        assertEquals(2048 to 1536,LeicaSizes.workingSize(4000,3000))
        assertEquals(2048 to 2048,LeicaSizes.workingSize(4096,4096))
        assertEquals(2048 to 1152,LeicaSizes.workingSize(3840,2160))
        assertEquals(2048 to 1229,LeicaSizes.workingSize(5000,3000))
    }
    @Test fun workingSizeKeepsSmallPhotosWithoutUpscaling() {
        assertEquals(360 to 480,LeicaSizes.workingSize(360,480))
        assertEquals(640 to 480,LeicaSizes.workingSize(640,480))
        assertEquals(1 to 1,LeicaSizes.workingSize(1,1))
        assertEquals(1 to 2048,LeicaSizes.workingSize(1,100000))
        assertEquals(2048 to 1,LeicaSizes.workingSize(Int.MAX_VALUE,1))
    }
    @Test fun workingSizeRespectsExifAxisSwap() {
        val rotated=Planes(4,3).exifTransposed(6)
        assertEquals(3 to 4,LeicaSizes.workingSize(rotated.width,rotated.height))
        for(w in listOf(1,3,360,2048,4001,12000))for(h in listOf(1,9,480,2048,6001)){
            val (x,y)=LeicaSizes.workingSize(w,h)
            assertEquals(minOf(2048,maxOf(w,h)),kotlin.math.max(x,y));assertTrue(x>0 && y>0)
            val gan=LeicaSizes.modelSize(x,y,true)
            assertEquals(1024,kotlin.math.max(gan.first,gan.second))
            val ratio=w.toDouble()/h
            if(x>1 && y>1)assertTrue(abs(x-ratio*y)<=maxOf(1.0,ratio))
        }
    }
    @Test fun workingDecodeSampleIsFixedNotMemoryDependent() {
        assertEquals(1,LeicaSizes.workingSample(1500,2048))
        assertEquals(1,LeicaSizes.workingSample(4000,3000))
        assertEquals(2,LeicaSizes.workingSample(4096,3072))
        assertEquals(4,LeicaSizes.workingSample(8192,6144))
        assertEquals(1,LeicaSizes.workingSample(360,480))
        assertEquals(4,LeicaSizes.workingSample(12000,9000))
    }
    @Test fun depthInputPatchSizeAlsoFits2048() {
        assertEquals(686 to 518,LeicaSizes.depthSize(768,576))
        for(w in listOf(1,20,360,768,2048))for(h in listOf(1,20,480,768,2048)){
            val (x,y)=LeicaSizes.depthSize(w,h)
            assertTrue(x in 14..2048 && y in 14..2048)
            assertEquals(0,x%14);assertEquals(0,y%14)
        }
    }
    @Test fun bokehRenders2048PhotoAsOneWholeFrame() {
        val photo=Planes(1536,2048)
        val depth=LegendaryMath.Frame(3,4,1,FloatArray(12){.2f});var calls=0
        val result=LegendaryMath.render(photo,depth,.1f,2.8f,Cancel()){rgb,coc ->
            calls++;assertEquals(1536,rgb.w);assertEquals(2048,rgb.h)
            assertEquals(rgb.w,coc.w);assertEquals(rgb.h,coc.h);rgb
        }
        assertEquals(1,calls);assertEquals(1536,result.width);assertEquals(2048,result.height)
    }

    @Test fun onlyBokehUsesSelectedOutputDimensions() {
        for((w,h) in listOf(4000 to 3000,3000 to 4000,5000 to 3000,360 to 480,1 to 100000)) {
            assertEquals(LeicaSizes.colorSize(w,h),LeicaSizes.processingSize(w,h,false))
            assertEquals(LeicaSizes.workingSize(w,h),LeicaSizes.processingSize(w,h,true))
        }
        assertEquals(1024 to 768,LeicaSizes.modelSize(4000,3000,false))
        assertEquals(1024 to 768,LeicaSizes.modelSize(4000,3000,true))
    }

}
