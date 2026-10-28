package xyz.shuncongci.leica

import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*

class FourTileBokehTest {
    private fun <T> cache(block:(File)->T):T{val f=Files.createTempDirectory("bokeh4-test-").toFile();return try{block(f)}finally{f.walkBottomUp().forEach{it.delete()}}}
    private fun photo(w:Int=157,h:Int=113):Planes=Planes(w,h).also{p->
        for(y in 0 until h)for(x in 0 until w){val i=y*w+x;for(c in 0..2)p.plane(c)[i]=(25+(x*13+y*7+c*33)%130).toByte()}
        for((x,y) in listOf(w/2 to h/2,w/2-2 to h/3,w/3 to h/2-2,w-2 to h-2))for(yy in max(0,y-1)..min(h-1,y+1))for(xx in max(0,x-1)..min(w-1,x+1))for(c in 0..2)p.plane(c)[yy*w+xx]=(if(c==2)244 else 255).toByte()
    }
    private fun depth(w:Int=23,h:Int=17)=Frame(w,h,1,FloatArray(w*h){i->(.5+.44*sin(i%w*.23+i/w*.31)).toFloat()})
    private fun alpha(w:Int,h:Int)=Frame(w,h,1,FloatArray(w*h){i->((w*.58-i%w)/max(1.0,w*.12)).toFloat().coerceIn(0f,1f)})
    private val full=object:FullCocRenderer{override fun invoke(a:Frame,d:Frame)=LayeredBokeh.render(a,d,Cancel())}
    private fun equal(a:Planes,b:Planes,maxError:Int=1){assertEquals(a.width,b.width);assertEquals(a.height,b.height)
        for(c in 0..2)for(i in 0 until a.size)assertTrue("channel=$c pixel=$i expected=${a.plane(c)[i].toInt()and 255} actual=${b.plane(c)[i].toInt()and 255}",abs((a.plane(c)[i].toInt()and 255)-(b.plane(c)[i].toInt()and 255))<=maxError)
    }
    private fun sameCaps(p:Planes){cache{d->val a=HighlightExtraction.caps(p,Cancel());val b=BoundedHighlightExtraction.caps(p,Cancel(),File(d,"queue"));assertArrayEquals(a,b,0f)}}
    private fun sdr(w:Int,h:Int,a:Frame?=null,ap:Float=.95f){cache{d->val p=photo(w,h);val dep=depth();val expected=LegendaryMath.render(p,dep,.38f,ap,Cancel(),subjectMask=a,infer=full)
        val seen=arrayListOf<Int>();val actual=FourTileBokeh.render(p,dep,.38f,ap,Cancel(),d,subjectMask=a,onPart={n,total->assertEquals(BokehBox.four(w,h).size,total);seen.add(n)})
        equal(expected,actual);assertEquals((1..BokehBox.four(w,h).size).toList(),seen);assertTrue(d.listFiles()!!.isEmpty())
    }}
    @Test fun fourCoresCoverOddPhotoExactly(){for((w,h) in listOf(157 to 113,10 to 11,1 to 19,17 to 1,1 to 1)){
        val seen=IntArray(w*h);val parts=BokehBox.four(w,h);assertEquals(if(w==1 && h==1)1 else if(w==1 || h==1)2 else 4,parts.size)
        for(b in parts)for(y in b.top until b.bottom)for(x in b.left until b.right)seen[y*w+x]++;assertTrue(seen.all{it==1})
    }}
    @Test fun alignedHaloNeverCutsCore(){for(b in BokehBox.four(157,113))for(r in 0..37){val x=b.expand(r,157,113,4);assertTrue(x.left<=b.left && x.top<=b.top && x.right>=b.right && x.bottom>=b.bottom);assertEquals(0,x.left%4);assertEquals(0,x.top%4)}}
    @Test fun scalarAndRgbRegionBitsRoundTrip(){cache{d->for(c in listOf(1,3)){
        val a=Frame(29,21,c,FloatArray(29*21*c){i->if(i%11==0)-0f else (i-300)*.000731f});val f=File(d,"f$c")
        BokehFloatFile.create(f,a.w,a.h,c).use{it.put(a,it.whole,Cancel());it.sync()}
        BokehFloatFile.open(f).use{r->val b=r.read(r.whole,Cancel());assertArrayEquals(a.data.map{it.toRawBits()}.toIntArray(),b.data.map{it.toRawBits()}.toIntArray());val box=BokehBox(7,5,13,11);val v=r.read(box,Cancel());for(ch in 0 until c)for(y in 0 until box.h)for(x in 0 until box.w)assertEquals(a.data[ch*a.size+(y+5)*29+x+7].toRawBits(),v.data[ch*v.size+y*13+x].toRawBits())}
    }}}
    @Test fun hdrHeaderCompatibleWithoutWholeFrameRead(){cache{d->val f=File(d,"hdr");BokehFloatFile.create(f,31,23,3).use{r->for(b in BokehBox.four(31,23))r.put(Frame(b.w,b.h,3,FloatArray(b.w*b.h*3){2.25f}),b,Cancel());r.sync()};HdrFloatFile.open(f).use{val row=FloatArray(31);it.read(2,22,1,row,Cancel());assertTrue(row.all{v->v==2.25f})}}}
    @Test(expected=IllegalArgumentException::class) fun existingFloatFileCannotBeOverwritten(){cache{d->val f=File(d,"old");f.writeText("keep");try{BokehFloatFile.create(f,2,2,1)}finally{assertEquals("keep",f.readText())}}}
    @Test(expected=IllegalArgumentException::class) fun truncatedFloatFileRejected(){cache{d->val f=File(d,"bad");BokehFloatFile.create(f,5,7,1).close();java.io.RandomAccessFile(f,"rw").use{it.setLength(f.length()-4)};BokehFloatFile.open(f)}}
    @Test fun capRowRingMatchesGlobalReference(){sameCaps(photo(193,137))}
    @Test fun capThinRowsMatchGlobalReference(){sameCaps(photo(191,1));sameCaps(photo(1,193))}
    @Test fun capPagedQueueAndLargeComponentsMatch(){val p=Planes(257,193);for(c in 0..2)p.plane(c).fill(190.toByte());for(c in 0..2)p.plane(c)[51*257+81]=255.toByte();sameCaps(p)}
    @Test fun capSeparatedComponentsReuseFifo(){val p=photo(301,153);for(y in 0 until p.height)for(x in 0 until p.width)for(c in 0..2)p.plane(c)[y*p.width+x]=(if(x in 145..155)0 else 190).toByte();for(c in 0..2){p.plane(c)[27*301+54]=255.toByte();p.plane(c)[81*301+254]=255.toByte()};sameCaps(p)}
    @Test fun basePixelMatchesExistingSplitBits(){val p=photo();val caps=HighlightExtraction.caps(p,Cancel());val a=LegendaryMath.split(p,Cancel()).first;val pix=FloatArray(3);for(i in 0 until p.size){LegendaryMath.basePixel(p,i,caps[i],pix);for(c in 0..2)assertEquals(a.data[c*p.size+i].toRawBits(),pix[c].toRawBits())}}
    @Test fun globalPlateMatchesWholePhotoPyramid(){cache{d->val p=photo(139,101);val dep=depth();val mask=alpha(p.width,p.height);val prep=BokehPreparation.prepare(p,dep,.38f,.95f,mask,d,Cancel())
        val (low,_)=LegendaryMath.split(p,Cancel());val coc=SubjectProtectionMath.protectedCoc(FullFrameOptics.coc(dep,.38f,.95f,p.width,p.height,Cancel()),mask,Cancel());BackgroundPlate.fillInPlace(mask,Cancel(),low,coc)
        assertNotNull(prep.plate);BokehFloatFile.open(prep.plate!!).use{assertArrayEquals(low.data,it.read(it.whole,Cancel()).data,0f)};BokehFloatFile.open(prep.plateCoc).use{assertArrayEquals(coc.data,it.read(it.whole,Cancel()).data,0f)}
    }}
    @Test fun oddDimensionsAndCrossSeamLightsMatch(){sdr(157,113)}
    @Test fun nonSquareAndCornersMatch(){sdr(197,89,ap=2.8f)}
    @Test fun thinRowMatches(){sdr(101,1)}
    @Test fun thinColumnMatches(){sdr(1,101)}
    @Test fun protectedSubjectAcrossFourCoresMatches(){sdr(149,107,alpha(149,107))}
    @Test fun upsampledSubjectUsesGlobalEdgeScale(){sdr(161,117,alpha(67,49))}
    @Test fun uniformColorPreserved(){cache{d->val p=Planes(127,93);p.p0.fill(92);p.p1.fill(78);p.p2.fill(51);equal(p,FourTileBokeh.render(p,depth(),.38f,2.8f,Cancel(),d),0)}}
    @Test fun zeroDefocusKeepsSourceBits(){cache{d->val p=photo();val z=Frame(7,5,1,FloatArray(35){.38f});equal(p,FourTileBokeh.render(p,z,.38f,.95f,Cancel(),d),0)}}
    @Test fun independentHdrExcessAndDeficitMatchFullFlow(){cache{d->val p=photo(143,105);val dep=depth();val mask=alpha(p.width,p.height);val sharp=Frame(p.width,p.height,3,FloatArray(p.size*3){i->HdrMath.linearByte(p.plane(i/p.size)[i%p.size].toInt()and 255)*(if(i%p.size%p.width<p.width/2)3.3f else .22f)})
        var expectedSdr:Planes?=null;val expected=LegendaryMath.renderHdrCombined(p,sharp,dep,.38f,.95f,Cancel(),subjectMask=mask,onSdr={expectedSdr=it},infer=full)
        val target=File(d,"result.f32");val actualSdr=FourTileBokeh.render(p,dep,.38f,.95f,Cancel(),d,subjectMask=mask,sharp=HdrPixels.of(sharp),hdrTarget=target)
        equal(expectedSdr!!,actualSdr);BokehFloatFile.open(target).use{assertArrayEquals(expected.data,it.read(it.whole,Cancel()).data,3e-5f)};assertEquals(listOf("result.f32"),d.list()!!.toList())
    }}
    @Test fun zeroDefocusHdrRemainsTrueHdr(){cache{d->val p=photo(51,37);val s=Frame(51,37,3,FloatArray(51*37*3){3.75f});val z=Frame(3,3,1,FloatArray(9){.38f});val f=File(d,"hdr");equal(p,FourTileBokeh.render(p,z,.38f,.95f,Cancel(),d,sharp=HdrPixels.of(s),hdrTarget=f),0);BokehFloatFile.open(f).use{assertArrayEquals(s.data,it.read(it.whole,Cancel()).data,0f)}}}
    @Test fun cancellationAfterFirstPartDoesNotPublishHdr(){cache{d->val p=photo(89,67);val sharp=HdrPixels.of(Frame(89,67,3,FloatArray(89*67*3){2f}));val f=File(d,"hdr");val c=Cancel();val seen=arrayListOf<Int>()
        try{FourTileBokeh.render(p,depth(),.38f,.95f,c,d,sharp=sharp,hdrTarget=f,onPart={done,_->seen.add(done);c.cancelled=true});fail()}catch(_:CancellationException){}
        assertEquals(listOf(1),seen);assertFalse(f.exists());assertTrue(d.listFiles()!!.isEmpty())
    }}
    @Test fun preparationCancellationCleansScratch(){cache{d->val c=Cancel();try{FourTileBokeh.render(photo(),depth(),.38f,.95f,c,d,progress={c.cancelled=true});fail()}catch(_:CancellationException){};assertTrue(d.listFiles()!!.isEmpty())}}
    @Test(expected=IllegalArgumentException::class) fun hdrDimensionMismatchNotDowngraded(){cache{d->FourTileBokeh.render(photo(),depth(),.38f,.95f,Cancel(),d,sharp=HdrPixels.of(Frame(2,2,3,FloatArray(12))),hdrTarget=File(d,"hdr"))}}
    @Test fun inputsNotModified(){cache{d->val p=photo();val dep=depth();val m=alpha(31,23);val before=listOf(p.p0.copyOf(),p.p1.copyOf(),p.p2.copyOf());val db=dep.data.copyOf();val mb=m.data.copyOf();FourTileBokeh.render(p,dep,.38f,.95f,Cancel(),d,subjectMask=m);for(c in 0..2)assertArrayEquals(before[c],p.plane(c));assertArrayEquals(db,dep.data,0f);assertArrayEquals(mb,m.data,0f)}}
    @Test fun rendererCacheIdentityIsDifferent(){assertNotEquals(LayeredBokeh.ID,FourTileBokeh.ID);assertTrue(FourTileBokeh.ID.contains("four-quadrants"))}
}
