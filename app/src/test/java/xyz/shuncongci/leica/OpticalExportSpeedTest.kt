package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import xyz.shuncongci.leica.LegendaryMath.Frame

class OpticalExportSpeedTest {
    private fun <T> work(run:(File)->T):T {val d=Files.createTempDirectory("optical-export-test-").toFile();return try{run(d)}finally{d.walkBottomUp().forEach{it.delete()}}}
    private fun check(w:Int,h:Int,ap:Float=.95f,uniform:Float?=null,sparse:Boolean=false){work{d->
        val n=w*h;val co=Frame(w,h,1,FloatArray(n){uniform?:((it*17%67)-31)*.103f})
        val hi=Frame(w,h,3,FloatArray(n*3){i->if(sparse && i%n!=0)0f else ((i*13%29)+1)*.013f})
        val before=hi.data.copyOf();val cb=co.data.copyOf();val expected=HighlightScatter.opticalLayers(hi,co,ap,Cancel())
        val colors=File(d,"color");val fronts=File(d,"foreground");val progress=arrayListOf<Int>();val stats=StreamingLightScatter.Stats()
        StreamingLightScatter.Pupils(w,h,ap,d).use{p->BokehFloatFile.create(colors,w,h,3).use{c->BokehFloatFile.create(fronts,w,h,3).use{f->
            StreamingLightScatter.run(hi,Frame(w,h,1,FloatArray(n){co.data[it]*3f}),ap,Cancel(),c,f,p,{done,total->assertEquals(h,total);progress.add(done)},stats)
        }}}
        BokehFloatFile.open(colors).use{assertArrayEquals(expected.color.data,it.read(it.whole,Cancel()).data,0f)}
        BokehFloatFile.open(fronts).use{assertArrayEquals(expected.foreground.data,it.read(it.whole,Cancel()).data,0f)}
        assertEquals(h,stats.receiverWrites);assertEquals(h,progress.last().toInt());assertTrue(progress.zipWithNext().all{it.first<it.second})
        assertEquals(setOf("color","foreground"),d.list()!!.toSet());assertArrayEquals(before,hi.data,0f);assertArrayEquals(cb,co.data,0f)
    }}
    @Test fun mixedSignedDepthBitExact(){check(61,47)}
    @Test fun threeReceiverStripsBitExact(){check(39,519)}
    @Test fun lastShortStripBitExact(){check(43,263,2.8f)}
    @Test fun finiteApertureBitExact(){check(51,39,5.6f)}
    @Test fun constantFractionalDepthBitExact(){check(37,43,2.8f,1.13f)}
    @Test fun negativeFractionalDepthBitExact(){check(37,43,.95f,-1.13f)}
    @Test fun integerDepthBitExact(){check(41,29,2f,2f)}
    @Test fun zeroDepthBitExact(){check(29,31,2.8f,0f)}
    @Test fun thinPortraitBitExact(){check(1,271,2.8f)}
    @Test fun thinLandscapeBitExact(){check(271,1,2.8f)}
    @Test fun singlePixelBitExact(){check(1,1,2.8f)}
    @Test fun darkSupportStillKeepsOccluders(){check(53,277,.95f,sparse=true)}
    @Test fun blackProducesNoScratchOrNonzeroOutput(){work{d->val c=File(d,"color");StreamingLightScatter.Pupils(7,5,2.8f,d).use{p->BokehFloatFile.create(c,7,5,3).use{f->StreamingLightScatter.run(Frame(7,5,3,FloatArray(105)),Frame(7,5,1,FloatArray(35)),2.8f,Cancel(),f,null,p)};assertEquals(0,p.generatedRadii)};BokehFloatFile.open(c).use{assertArrayEquals(FloatArray(105),it.read(it.whole,Cancel()).data,0f)};assertEquals(setOf("color"),d.list()!!.toSet())}}
    @Test fun pupilsAreSharedAcrossPassesAndSignedDepth(){work{d->StreamingLightScatter.Pupils(17,13,2.8f,d).use{p->val first=p.rows(5,Cancel());p.rows(1,Cancel());val reread=p.rows(5,Cancel());assertEquals(2,p.generatedRadii);for(k in first.indices)for(i in first[k].indices){assertArrayEquals(first[k][i].tw,reread[k][i].tw,0f);assertArrayEquals(first[k][i].tx,reread[k][i].tx)}};assertTrue(d.list()!!.isEmpty())}}
    @Test fun cancelAfterRealStripCleansPupils(){work{d->val cancel=Cancel();try{StreamingLightScatter.Pupils(9,271,.95f,d).use{p->BokehFloatFile.create(File(d,"color"),9,271,3).use{f->StreamingLightScatter.run(Frame(9,271,3,FloatArray(9*271*3){.1f}),Frame(9,271,1,FloatArray(9*271){1.3f}),.95f,cancel,f,null,p,{_,_->cancel.cancelled=true})}};fail()}catch(_:CancellationException){};assertEquals(setOf("color"),d.list()!!.toSet())}}
    @Test fun cancelledBeforeAllocatingPupils(){work{d->val c=Cancel().also{it.cancelled=true};StreamingLightScatter.Pupils(7,5,2.8f,d).use{p->try{p.rows(3,c);fail()}catch(_:CancellationException){}};assertTrue(d.list()!!.isEmpty())}}
    @Test(expected=IllegalArgumentException::class) fun geometryCannotReuseDifferentPupils(){work{d->StreamingLightScatter.Pupils(7,5,2.8f,d).use{p->BokehFloatFile.create(File(d,"c"),7,5,3).use{f->StreamingLightScatter.run(Frame(7,5,3,FloatArray(105)),Frame(7,5,1,FloatArray(35)),.95f,Cancel(),f,null,p)}}}}
    @Test fun wideDepthRangeSkipsEmptySlotsButKeepsExactMath(){work{d->val w=13;val h=9;val n=w*h;val co=Frame(w,h,1,FloatArray(n){if(it%2==0)-33f else 33f});val hi=Frame(w,h,3,FloatArray(n*3){.25f});val expected=HighlightScatter.optical(hi,co,.95f,Cancel());val stats=StreamingLightScatter.Stats();val out=File(d,"c");StreamingLightScatter.Pupils(w,h,.95f,d).use{p->BokehFloatFile.create(out,w,h,3).use{f->StreamingLightScatter.run(hi,Frame(w,h,1,FloatArray(n){co.data[it]*3f}),.95f,Cancel(),f,null,p,stats=stats)};assertEquals(1,p.generatedRadii)};assertEquals(197,stats.emptyDepthStrips);assertEquals(2,stats.depthStripVisits);BokehFloatFile.open(out).use{assertArrayEquals(expected.data,it.read(it.whole,Cancel()).data,0f)}}}
}
