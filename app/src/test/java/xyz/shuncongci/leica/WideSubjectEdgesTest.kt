package xyz.shuncongci.leica
import org.junit.Assert.*
import org.junit.Test
import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*

class WideSubjectEdgesTest {
    private fun scene(band:Int,fg:Int=200,bg:Int=40,next:Int=100):Triple<Planes,Planes,Frame>{
        val w=161;val h=41;val a=Frame(w,h,1,FloatArray(w*h){((it%w-80f)/band+.5f).coerceIn(0f,1f)})
        val s=ByteArray(w*h){Math.rint((bg+(fg-bg)*a.data[it]).toDouble()).toInt().toByte()};val b=ByteArray(w*h){next.toByte()}
        return Triple(Planes(w,h,s,s.copyOf(),s.copyOf()),Planes(w,h,b,b.copyOf(),b.copyOf()),a)
    }
    @Test fun wideBandsDoNotSquareAlpha(){
        for(band in listOf(8,16,32,64))for(colors in listOf(Triple(200,40,100),Triple(30,220,90))){
            val (s,b,a)=scene(band,colors.first,colors.second,colors.third);val out=SubjectProtectionMath.composite(s,b,a,Cancel())
            for(i in 0 until s.size){val ideal=colors.first*a.data[i]+colors.third*(1-a.data[i]);assertTrue("band=$band index=$i error=${abs((out.p0[i].toInt() and 255)-ideal)}",abs((out.p0[i].toInt() and 255)-ideal)<=1.1)}
        }
    }
    @Test fun endpointsAndInputsStayExact(){
        val (s,b,a)=scene(32);val before=s.p0.copyOf();val mask=a.data.copyOf();val out=SubjectProtectionMath.composite(s,b,a,Cancel())
        assertArrayEquals(before,s.p0);assertArrayEquals(mask,a.data,0f)
        for(i in a.data.indices){if(a.data[i]==0f)assertEquals(b.p0[i],out.p0[i]);if(a.data[i]==1f)assertEquals(s.p0[i],out.p0[i])}
    }
    @Test fun noOpaqueOrNoBackgroundEvidenceDoesNotInventCorrection(){
        for(alpha in listOf(.5f,0f,1f)){val (s,b,_)=scene(32);val out=Planes(s.width,s.height);out.p0.fill(17);out.p1.fill(17);out.p2.fill(17)
            WideSubjectEdges.refine(s,b,Frame(s.width,s.height,1,FloatArray(s.size){alpha}),out,Cancel());assertTrue(out.p0.all{it.toInt()==17})}
    }
    @Test fun mismatchedAlphaDoesNotPaintFlatColour(){
        val (s,b,a)=scene(32);for(i in a.data.indices)if(a.data[i]>0 && a.data[i]<1){s.p0[i]=0;s.p1[i]=0;s.p2[i]=0}
        val out=Planes(s.width,s.height);out.p0.fill(17);out.p1.fill(17);out.p2.fill(17)
        WideSubjectEdges.refine(s,b,a,out,Cancel());assertTrue(out.p0.all{it.toInt()==17})
    }
    @Test(expected=java.util.concurrent.CancellationException::class) fun cancellationIsNotSwallowed(){val (s,b,a)=scene(32);WideSubjectEdges.refine(s,b,a,Planes(s.width,s.height),Cancel().apply{cancelled=true})}
}
