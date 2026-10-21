package xyz.shuncongci.leica
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import xyz.shuncongci.leica.LegendaryMath.Frame

class BokehRadianceTest {
    private fun frame(w:Int=71,h:Int=53,ch:Int=3,v:Float=0f)=Frame(w,h,ch,FloatArray(w*h*ch){v})
    private fun planes(v:Int)=Planes(71,53,ByteArray(71*53){v.toByte()},ByteArray(71*53){v.toByte()},ByteArray(71*53){v.toByte()})
    private fun same(a:Planes,b:Planes){for(ch in 0..2)assertArrayEquals(a.plane(ch),b.plane(ch))}
    @Test fun gainIsExactlyOneInFocus(){for(c in listOf(0f,.3f,-.3f,.75f,-.75f))assertEquals(1f,BokehRadiance.gain(c),0f)}
    @Test fun explicitCreativeFullyDefocusedGainIsFour(){for(c in listOf(2f,-2f,30f,-30f))assertEquals(4f,BokehRadiance.gain(c,4f),0f)}
    @Test fun transitionIsSmoothSymmetricAndMonotonic(){var last=1f;for(i in 0..250){val c=i/100f;val g=BokehRadiance.gain(c,4f);assertTrue(g>=last);assertEquals(g,BokehRadiance.gain(-c,4f),0f);last=g};assertTrue(BokehRadiance.gain(.751f,4f)-1f<.0001f);assertTrue(4f-BokehRadiance.gain(1.999f,4f)<.0001f)}
    @Test fun sourceBoostDoesNotMutateInputOrCoc(){val h=frame(v=.2f);val c=frame(ch=1,v=3f);val out=BokehRadiance.boost(h,c,Cancel(),4f);assertTrue(h.data.all{it==.2f});assertTrue(c.data.all{it==3f});assertTrue(out.data.all{it==.8f})}
    @Test fun sourceBoostPreservesDarkAndFocusPixels(){val h=frame(v=.2f);h.data[0]=0f;val out=BokehRadiance.boost(h,frame(ch=1,v=0f),Cancel());assertArrayEquals(h.data,out.data,0f)}
    @Test fun historicalGainOneIsIdentity(){val h=frame(v=.2f);assertArrayEquals(h.data,BokehRadiance.boost(h,frame(ch=1,v=10f),Cancel(),1f).data,0f)}
    @Test(expected=IllegalArgumentException::class) fun invalidGainRejected(){BokehRadiance.gain(2f,Float.NaN)}
    @Test(expected=IllegalArgumentException::class) fun invalidRadianceRejected(){BokehRadiance.boost(frame(v=-1f),frame(ch=1,v=2f),Cancel())}
    @Test(expected=CancellationException::class) fun cancelledBoostStops(){BokehRadiance.boost(frame(),frame(ch=1),Cancel().apply{cancelled=true})}
    @Test fun zeroForegroundKeepsOldCompositeByteExactly(){val s=planes(120);val r=planes(30);for(a in listOf(0f,.2f,.7f,1f)){val mask=frame(ch=1,v=a);same(SubjectProtectionMath.composite(s,r,mask,Cancel()),BokehRadiance.protect(s,r,mask,frame(),Cancel()))}}
    @Test fun unprotectedPixelsDoNotReceiveLightTwice(){same(planes(140),BokehRadiance.protect(planes(80),planes(140),frame(ch=1),frame(v=.1f),Cancel()))}
    @Test fun foregroundCanVeilOpaqueSubject(){val out=BokehRadiance.protect(planes(80),planes(30),frame(ch=1,v=1f),frame(v=.03f),Cancel());assertTrue(out.p0.all{(it.toInt() and 255)>80 && (it.toInt() and 255)<255})}
    @Test fun subjectTextureIsNotReplacedByFlatSticker(){val s=planes(70);for(i in s.p0.indices)if(i%2==0)s.p0[i]=140.toByte();val o=BokehRadiance.protect(s,planes(20),frame(ch=1,v=1f),frame(v=.03f),Cancel());assertTrue((o.p0[0].toInt() and 255)>(o.p0[1].toInt() and 255)+40)}
    @Test fun alphaEdgeRestoresOnlyItsMissingShare(){val s=planes(80);val r=planes(80);val light=frame(v=.03f);val values=listOf(0f,.25f,.5f,.75f,1f).map{BokehRadiance.protect(s,r,frame(ch=1,v=it),light,Cancel()).p0[0].toInt() and 255};assertEquals(80,values.first());assertTrue(values.zipWithNext().all{it.first<it.second})}
    @Test fun saturationHeadroomIsSharedAcrossChannels(){val light=frame(v=.5f);for(i in 0 until light.size)light.data[i]=5f;val o=BokehRadiance.protect(planes(80),planes(30),frame(ch=1,v=1f),light,Cancel());assertEquals(255,o.p0[0].toInt() and 255);assertTrue((o.p1[0].toInt() and 255)<180);assertEquals(o.p1[0],o.p2[0])}
    @Test fun protectionDoesNotMutateItsInputs(){val s=planes(80);val r=planes(30);val a=frame(ch=1,v=.7f);val light=frame(v=.03f);BokehRadiance.protect(s,r,a,light,Cancel());same(planes(80),s);same(planes(30),r);assertTrue(a.data.all{it==.7f});assertTrue(light.data.all{it==.03f})}
    @Test fun backgroundLightIsNeverCapturedAsForeground(){val h=frame();for(ch in 0..2)h.data[ch*h.size+26*71+31]=1f;val out=HighlightScatter.opticalLayers(h,frame(ch=1,v=-3f),.95f,Cancel());assertTrue(out.color.data.any{it>0f});assertTrue(out.foreground.data.all{it==0f})}
    @Test fun foregroundCaptureUsesSameVisibleColor(){val h=frame();for(ch in 0..2)h.data[ch*h.size+26*71+31]=1f;val c=frame(ch=1,v=3f);val out=HighlightScatter.opticalLayers(h,c,.95f,Cancel());assertArrayEquals(HighlightScatter.optical(h,c,.95f,Cancel()).data,out.color.data,0f);assertArrayEquals(out.color.data,out.foreground.data,2e-7f)}
    @Test fun nearLightCrossesSharpMaskButFarLightDoesNot(){val h=frame();for(ch in 0..2)h.data[ch*h.size+26*71+31]=1f;fun run(sign:Float):HighlightScatter.LightLayers{val c=frame(ch=1,v=3f*sign);for(y in 0 until c.h)for(x in 36 until c.w)c.data[y*c.w+x]=0f;return HighlightScatter.opticalLayers(h,c,.95f,Cancel())};val near=run(1f);val far=run(-1f);assertTrue(near.foreground.data[26*71+37]>0f);assertEquals(0f,far.foreground.data[26*71+37],0f);assertEquals(0f,far.color.data[26*71+37],0f)}
    @Test fun boostChangesOnlyHighLayerNotSplitterBase(){val p=planes(25);for(y in 24..28)for(x in 28..32){val i=y*71+x;p.p0[i]=230.toByte();p.p1[i]=180.toByte();p.p2[i]=100};val split=LegendaryMath.split(p,Cancel());val base=split.first.data.copyOf();val high=BokehRadiance.boost(split.second,frame(ch=1,v=3f),Cancel(),4f);assertArrayEquals(base,split.first.data,0f);assertTrue(high.data.sum()>split.second.data.sum()*3.99f)}
}
