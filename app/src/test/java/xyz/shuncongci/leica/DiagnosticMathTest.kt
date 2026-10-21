package xyz.shuncongci.leica
import org.junit.Assert.*
import org.junit.Test

class DiagnosticMathTest {
    @Test fun oneCoreAndDeviceCapacityAreDifferent(){val c=DiagnosticMath.cpu(100,850,1000,2500,8)!!;assertEquals(50.0,c.oneCorePercent,0.0);assertEquals(6.25,c.capacityPercent,0.0)}
    @Test fun multiCoreCanExceedOneHundredWithoutClamping(){val c=DiagnosticMath.cpu(0,6000,0,1500,8)!!;assertEquals(400.0,c.oneCorePercent,0.0);assertEquals(50.0,c.capacityPercent,0.0)}
    @Test fun invalidTimingDoesNotInventUsage(){assertNull(DiagnosticMath.cpu(0,1,5,5,8));assertNull(DiagnosticMath.cpu(5,1,0,10,8));assertNull(DiagnosticMath.cpu(0,1,0,10,0))}
    @Test fun noFrameMeansNoPerformanceClaim(){assertNull(DiagnosticMath.percentile(emptyList(),.95))}
    @Test fun p95AndMedianUseActualSamples(){val values=(1..100).map{it.toDouble()};assertEquals(96.0,DiagnosticMath.percentile(values,.95)!!,0.0);assertEquals(51.0,DiagnosticMath.percentile(values,.5)!!,0.0)}
}
