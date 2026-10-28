package xyz.shuncongci.leica

import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*

/** These exercise the NEW production entry, not the retained 66 reference entry. */
class NaturalBokehIntegrationTest {
    private fun <T> cache(block:(File)->T):T{val d=Files.createTempDirectory("natural-bokeh-test-").toFile();return try{block(d)}finally{d.walkBottomUp().forEach{it.delete()}}}
    private fun photo(w:Int=89,h:Int=67,bright:Boolean=true):Planes=Planes(w,h).also{p->
        for(i in 0 until p.size)for(c in 0..2)p.plane(c)[i]=(20+(i*13+c*17)%90).toByte()
        if(bright)for(y in max(0,h/2-1)..min(h-1,h/2+1))for(x in max(0,w/2-1)..min(w-1,w/2+1)){p.p0[y*w+x]=255.toByte();p.p1[y*w+x]=244.toByte();p.p2[y*w+x]=236.toByte()}
    }
    private fun depth()=Frame(23,17,1,FloatArray(391){i->(.5+.44*sin(i%23*.23+i/23*.31)).toFloat()})
    private fun sharp(p:Planes)=Frame(p.width,p.height,3,FloatArray(p.size*3){i->HdrMath.linearByte(p.plane(i/p.size)[i%p.size].toInt()and 255)*(if(i%p.size%p.width<p.width/2)4f else .15f)})
    private fun natural(p:Planes,d:File,dep:Frame=depth(),mask:Frame?=null,split:Boolean=true,progress:(String)->Unit={}):Planes=
        FourTileBokeh.renderNatural(p,dep,.38f,2.8f,Cancel(),d,subjectMask=mask,split=split,progress=progress)
    private fun equal(a:Planes,b:Planes,tolerance:Int=0){assertEquals(a.width,b.width);assertEquals(a.height,b.height);for(c in 0..2)for(i in 0 until a.size)assertTrue(abs((a.plane(c)[i].toInt()and 255)-(b.plane(c)[i].toInt()and 255))<=tolerance)}
    private fun raw(p:Planes,caps:FloatArray):Pair<Frame,Frame>{val a=Frame(p.width,p.height,3,FloatArray(p.size*3));val b=Frame(p.width,p.height,3,FloatArray(p.size*3));val pixel=FloatArray(3)
        for(i in 0 until p.size){LegendaryMath.basePixel(p,i,caps[i],pixel);for(c in 0..2){a.data[c*p.size+i]=pixel[c];b.data[c*p.size+i]=max(0f,HdrMath.linearByte(p.plane(c)[i].toInt()and 255)-LegendaryMath.linearQuantized(pixel[c]))}};return a to b
    }
    @Test fun darkChromaCannotQualifyAsLight(){cache{d->val p=Planes(181,137);for(c in 0..2)p.plane(c).fill(25)
        for(y in 62..66)for(x in 88..92){p.p0[y*181+x]=55;p.p1[y*181+x]=85;p.p2[y*181+x]=145.toByte()}
        val old=raw(p,BoundedHighlightExtraction.caps(p,Cancel(),File(d,"old"))).second;assertTrue(old.data.any{it>0f})
        val now=raw(p,NaturalBokehLights.caps(p,Cancel(),File(d,"new"))).second;assertTrue(now.data.all{it==0f})
    }}
    @Test fun isolatedWhiteLightStillSelected(){cache{d->val p=photo();val hi=raw(p,NaturalBokehLights.caps(p,Cancel(),File(d,"q"))).second;assertTrue(hi.data.any{it>.2f})}}
    @Test fun saturatedBrightRedStillSelected(){cache{d->val p=Planes(181,137);p.p0.fill(15);p.p1.fill(15);p.p2.fill(15);for(y in 62..66)for(x in 88..92)p.p0[y*181+x]=255.toByte();val high=raw(p,NaturalBokehLights.caps(p,Cancel(),File(d,"q"))).second;assertTrue(high.data.any{it>.2f})}}
    @Test fun brightBroadSurfaceStaysBase(){cache{d->val p=Planes(103,79);for(c in 0..2)p.plane(c).fill(240.toByte());val high=raw(p,NaturalBokehLights.caps(p,Cancel(),File(d,"q"))).second;assertTrue(high.data.all{it==0f})}}
    @Test fun capThresholdIsContinuous(){val a=NaturalBokehLights.cap(.60f,.06f);val b=NaturalBokehLights.cap(.600001f,.06f);assertEquals(.60f,a,0f);assertTrue(abs(a-b)<.00001f)}
    @Test fun fullBrightGateKeepsOriginalCap(){assertEquals(.12f,NaturalBokehLights.cap(.95f,.12f),1e-7f)}
    @Test fun brightnessGateNeverExtractsMoreThanOld(){for(i in 0..1000){val p=i/1000f;for(old in listOf(.06f,.25f,max(.25f,p)))assertTrue(NaturalBokehLights.cap(p,old)>=old)}}
    @Test fun darkZeroHasNoInventedEnergy(){assertEquals(.25f,NaturalBokehLights.cap(0f,.06f),0f)}
    @Test fun noHdrFallbackAndDistinctCacheIdentity(){assertNotEquals(FourTileBokeh.ID,FourTileBokeh.NATURAL_ID);assertTrue(FourTileBokeh.NATURAL_ID.contains("signed"))}
    @Test fun darkPhotoDoesNotEnterSeparateLightStage(){cache{d->val stages=arrayListOf<String>();natural(photo(bright=false),d,progress={stages.add(it)});assertFalse(stages.any{it.contains("光能") || it.startsWith("SDR高光")})}}
    @Test fun fullSdrMatchesIndependentPhysicalReference(){cache{d->val p=photo(97,73);val dep=depth();val caps=NaturalBokehLights.caps(p,Cancel(),File(d,"reference-q"));val (base,high)=raw(p,caps);val coc=FullFrameOptics.coc(dep,.38f,2.8f,p.width,p.height,Cancel())
        val blurred=LayeredBokeh.render(base,coc,Cancel());val light=HighlightScatter.opticalAtScale(high,coc,2.8f,Cancel(),1f);val expected=Planes(p.width,p.height)
        for(c in 0..2)for(i in 0 until p.size){val v=LegendaryMath.srgb(SoftBokehEdges.shoulder(LegendaryMath.linearQuantized(blurred.data[c*p.size+i]),light.data[c*p.size+i]));expected.plane(c)[i]=Math.rint((v.coerceIn(0f,1f)*255).toDouble()).toInt().toByte()}
        equal(expected,natural(p,d,dep),1)
    }}
    @Test fun fourRegionsMatchSingleGlobalRegion(){cache{d->val p=photo(101,77);val a=natural(p,d,split=true);val b=natural(p,d,split=false);equal(a,b,1)}}
    @Test fun oddWidthAndHeightPreserved(){cache{d->val out=natural(photo(97,71),d);assertEquals(97,out.width);assertEquals(71,out.height)}}
    @Test fun thinRowFinite(){cache{d->val p=photo(97,1);val out=natural(p,d);assertEquals(p.size,out.size)}}
    @Test fun thinColumnFinite(){cache{d->val p=photo(1,97);val out=natural(p,d);assertEquals(p.size,out.size)}}
    @Test fun uniformDarkIsExact(){cache{d->val p=Planes(97,71);p.p0.fill(51);p.p1.fill(78);p.p2.fill(92);equal(p,natural(p,d))}}
    @Test fun focusedSdrIsBitExact(){cache{d->val p=photo();equal(p,natural(p,d,Frame(3,3,1,FloatArray(9){.38f})))}}
    @Test fun focusedHdrIsBitExactAndGreaterThanOne(){cache{d->val p=photo();val s=sharp(p);val target=File(d,"result");FourTileBokeh.renderNatural(p,Frame(3,3,1,FloatArray(9){.38f}),.38f,2.8f,Cancel(),d,sharp=HdrPixels.of(s),hdrTarget=target);BokehFloatFile.open(target).use{assertArrayEquals(s.data,it.read(it.whole,Cancel()).data,0f)};assertTrue(s.data.any{it>1f})}}
    @Test fun fullHdrSignedReferencePreservesDeficit(){cache{d->val p=photo(97,73);val dep=depth();val s=sharp(p);val caps=NaturalBokehLights.caps(p,Cancel(),File(d,"reference-q"));val base=raw(p,caps).first;val coc=FullFrameOptics.coc(dep,.38f,2.8f,p.width,p.height,Cancel());val blurred=LayeredBokeh.render(base,coc,Cancel())
        val pos=Frame(p.width,p.height,3,FloatArray(s.data.size){max(0f,s.data[it]-LegendaryMath.linearQuantized(base.data[it]))})
        val neg=Frame(p.width,p.height,3,FloatArray(s.data.size){max(0f,LegendaryMath.linearQuantized(base.data[it])-s.data[it])});assertTrue(neg.data.any{it>0f})
        val a=HighlightScatter.opticalAtScale(pos,coc,2.8f,Cancel(),1f);val b=HighlightScatter.opticalAtScale(neg,coc,2.8f,Cancel(),1f)
        val expected=FloatArray(s.data.size){max(0f,LegendaryMath.linearQuantized(blurred.data[it])+a.data[it]-b.data[it])}
        val target=File(d,"result");FourTileBokeh.renderNatural(p,dep,.38f,2.8f,Cancel(),d,sharp=HdrPixels.of(s),hdrTarget=target)
        BokehFloatFile.open(target).use{assertArrayEquals(expected,it.read(it.whole,Cancel()).data,3e-5f)}
    }}
    @Test fun sdrPixelsDoNotDependOnHdr(){cache{d->val p=photo();val a=natural(p,d);val b=FourTileBokeh.renderNatural(p,depth(),.38f,2.8f,Cancel(),d,sharp=HdrPixels.of(sharp(p)),hdrTarget=File(d,"result"));equal(a,b)}}
    @Test fun opaqueSubjectRestoresSdrExactly(){cache{d->val p=photo();val m=Frame(p.width,p.height,1,FloatArray(p.size){1f});equal(p,natural(p,d,mask=m))}}
    @Test fun opaqueSubjectRestoresHdrExactly(){cache{d->val p=photo();val s=sharp(p);val m=Frame(p.width,p.height,1,FloatArray(p.size){1f});val target=File(d,"result");FourTileBokeh.renderNatural(p,depth(),.38f,2.8f,Cancel(),d,subjectMask=m,sharp=HdrPixels.of(s),hdrTarget=target);BokehFloatFile.open(target).use{assertArrayEquals(s.data,it.read(it.whole,Cancel()).data,0f)}}}
    @Test fun crossRegionSubjectHdrIsFinite(){cache{d->val p=photo(101,77);val m=Frame(29,23,1,FloatArray(29*23){i->if(i%29 in 10..18)1f else 0f});val target=File(d,"result");FourTileBokeh.renderNatural(p,depth(),.38f,2.8f,Cancel(),d,subjectMask=m,sharp=HdrPixels.of(sharp(p)),hdrTarget=target);BokehFloatFile.open(target).use{r->val f=r.read(r.whole,Cancel());assertTrue(f.data.all{it.isFinite() && it>=0f});assertTrue(f.data.any{it>1f})}}}
    @Test fun allBasePartsFinishBeforeSharedTransport(){cache{d->val p=photo();var parts=0;val stages=arrayListOf<String>();FourTileBokeh.renderNatural(p,depth(),.38f,2.8f,Cancel(),d,sharp=HdrPixels.of(sharp(p)),hdrTarget=File(d,"result"),onPart={n,_->parts=n},progress={s->if(s.contains("光能") || s.contains("散射")){assertEquals(4,parts);stages.add(s)}})
        assertEquals(1,stages.count{it.endsWith("提取光能")});assertEquals(1,stages.count{it.endsWith("共用光学散射")});assertFalse(stages.any{it.contains("HDR增光") || it.contains("HDR减光")});assertTrue(stages.any{it.contains("物理半径档位")})
    }}
    @Test fun realLayerAndPreviewCountsRemain(){cache{d->val p=photo();val layers=arrayListOf<BokehLayerProgress>();var parts=0;var bytes=0L;FourTileBokeh.renderNatural(p,depth(),.38f,2.8f,Cancel(),d,onPart={n,total->parts=n;assertEquals(4,total)},onLayer={layers.add(it)},onPreview={bytes+=4L*it.pixels.size});assertEquals(4,parts);assertEquals(64,layers.size);assertTrue(layers.all{it.done%4==0 && it.layers==64});assertTrue(bytes<=640L*640*4)}}
    @Test fun successfulFlowLeavesNoScratch(){cache{d->natural(photo(),d);assertTrue(d.listFiles()!!.isEmpty())}}
    @Test fun cancellationInSharedExtractionLeavesNoResult(){cache{d->val p=photo();val c=Cancel();val f=File(d,"result");try{FourTileBokeh.renderNatural(p,depth(),.38f,2.8f,c,d,sharp=HdrPixels.of(sharp(p)),hdrTarget=f,progress={if(it.endsWith("提取光能"))c.cancelled=true});fail()}catch(_:CancellationException){};assertFalse(f.exists());assertTrue(d.listFiles()!!.isEmpty())}}
    @Test fun cancellationInSharedTransportLeavesNoResult(){cache{d->val p=photo();val c=Cancel();val f=File(d,"result");try{FourTileBokeh.renderNatural(p,depth(),.38f,2.8f,c,d,sharp=HdrPixels.of(sharp(p)),hdrTarget=f,progress={if(it.contains("全局散射"))c.cancelled=true});fail()}catch(_:CancellationException){};assertFalse(f.exists());assertTrue(d.listFiles()!!.isEmpty())}}
    @Test fun existingHdrTargetRemainsUnchanged(){cache{d->val p=photo();val f=File(d,"result");f.writeText("KEEP");try{FourTileBokeh.renderNatural(p,depth(),.38f,2.8f,Cancel(),d,sharp=HdrPixels.of(sharp(p)),hdrTarget=f);fail()}catch(_:IllegalArgumentException){};assertEquals("KEEP",f.readText());assertEquals(listOf("result"),d.list()!!.toList())}}
    @Test(expected=IllegalArgumentException::class) fun mismatchedHdrNotDowngraded(){cache{d->FourTileBokeh.renderNatural(photo(),depth(),.38f,2.8f,Cancel(),d,sharp=HdrPixels.of(Frame(2,2,3,FloatArray(12))),hdrTarget=File(d,"result"))}}
    @Test fun invalidHdrDoesNotPublish(){cache{d->val p=photo();val s=object:HdrPixels{override val w=p.width;override val h=p.height;override fun read(channel:Int,y:Int,rows:Int,dst:FloatArray,cancel:Cancel){dst.fill(Float.NaN)}};val f=File(d,"result");try{FourTileBokeh.renderNatural(p,depth(),.38f,2.8f,Cancel(),d,sharp=s,hdrTarget=f);fail()}catch(_:IllegalArgumentException){};assertFalse(f.exists());assertTrue(d.listFiles()!!.isEmpty())}}
    @Test fun callerPhotoDepthAndMaskNotMutated(){cache{d->val p=photo();val dep=depth();val mask=Frame(31,23,1,FloatArray(713){if(it%31<15).8f else 0f});val planes=(0..2).map{p.plane(it).copyOf()};val db=dep.data.copyOf();val mb=mask.data.copyOf();natural(p,d,dep,mask);for(c in 0..2)assertArrayEquals(planes[c],p.plane(c));assertArrayEquals(db,dep.data,0f);assertArrayEquals(mb,mask.data,0f)}}
}
