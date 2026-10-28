package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import xyz.shuncongci.leica.LegendaryMath.Frame
import java.io.DataInputStream
import java.util.concurrent.CancellationException
import kotlin.math.*

class LayeredBokehTest {
    private fun image(w:Int=31,h:Int=23,v:Float=.4f)=Frame(w,h,3,FloatArray(w*h*3){v})
    private fun map(w:Int,h:Int)=Frame(w,h,1,FloatArray(w*h){i->-7f+16f*i/max(1,w*h-1)})
    private fun callback(c:Cancel=Cancel())=object:FullCocRenderer{override fun invoke(a:Frame,d:Frame)=LayeredBokeh.render(a,d,c)}
    private fun photo(w:Int=31,h:Int=23,v:Int=40)=Planes(w,h).apply{for(c in 0..2)plane(c).fill(v.toByte())}
    private fun checkFixture(name:String){
        val stream=checkNotNull(javaClass.getResourceAsStream("/layered64/$name.bin"))
        DataInputStream(stream).use{f->val w=f.readInt();val h=f.readInt();val n=w*h
            val im=Frame(w,h,3,FloatArray(3*n){f.readFloat()});val df=Frame(w,h,1,FloatArray(n){f.readFloat()});val expected=FloatArray(3*n){f.readFloat()}
            val actual=LayeredBokeh.render(im,df,Cancel());var error=0f
            for(i in expected.indices)error=max(error,abs(expected[i]-actual.data[i]))
            assertTrue("prototype $name max error=$error",error<3e-5f)
        }
    }
    @Test fun prototypeMixedParity()=checkFixture("mixed")
    @Test fun prototypeThinRowParity()=checkFixture("thin-row")
    @Test fun prototypeThinColumnParity()=checkFixture("thin-col")
    @Test fun prototypeBorderParity()=checkFixture("border")
    @Test fun diskUnitMassAndSymmetry(){for(r in floatArrayOf(0f,.34f,.35f,.6f,1f,2.2f,5.7f,20f)){
        val k=LayeredBokeh.disk(r)
        // Actual coefficients are summed in Double row prefixes. A sequential
        // FloatArray.sum() accumulates ~8e-6 error at radius20 even for unit mass.
        assertEquals(1.0,k.data.sumOf{it.toDouble()},2e-7)
        for(y in 0 until k.w)for(x in 0 until k.w){assertEquals(k.data[y*k.w+x],k.data[(k.w-1-y)*k.w+x],0f);assertEquals(k.data[y*k.w+x],k.data[y*k.w+k.w-1-x],0f)}
    }}
    @Test fun zeroAndCoreKeepSourceBits(){val im=image().also{for(i in it.data.indices)it.data[i]=((i*17)%251)/255f}
        for(z in floatArrayOf(0f,.1f,-.25f,.25f))assertArrayEquals(im.data,LayeredBokeh.render(im,Frame(im.w,im.h,1,FloatArray(im.size){z}),Cancel()).data,0f)
    }
    @Test fun variableDepthConservesConstantColor(){for(v in floatArrayOf(0f,.03f,.37f,1f)){val im=image(v=v);assertArrayEquals(im.data,LayeredBokeh.render(im,map(im.w,im.h),Cancel()).data,2e-5f)}}
    @Test fun uniformDepthIsDefinedAndConservesColor(){for(z in floatArrayOf(-9f,2f,6.5f)){val im=image();val result=LayeredBokeh.render(im,Frame(im.w,im.h,1,FloatArray(im.size){z}),Cancel());assertArrayEquals(im.data,result.data,2e-5f)}}
    @Test fun subUlpGridRangeIsDefined(){val im=image();val tiny=java.lang.Math.nextUp(6f);val df=Frame(im.w,im.h,1,FloatArray(im.size){if(it%2==0)6f else tiny});assertArrayEquals(im.data,LayeredBokeh.render(im,df,Cancel()).data,2e-5f)}
    @Test fun uniformRadiusSpreadsPoint(){val im=image(v=0f);im.data[11*im.w+15]=1f;val out=LayeredBokeh.render(im,Frame(im.w,im.h,1,FloatArray(im.size){5f}),Cancel());assertTrue(out.data[11*im.w+15]<1f);assertTrue(out.data[11*im.w+14]>0f);assertEquals(0f,out.data[0],0f)}
    @Test fun singlePixelFinite(){for(z in floatArrayOf(-7f,0f,12f)){val im=image(1,1);assertArrayEquals(im.data,LayeredBokeh.render(im,Frame(1,1,1,floatArrayOf(z)),Cancel()).data,2e-5f)}}
    @Test fun inputsAreNeverMutated(){val im=image();val df=map(im.w,im.h);val a=im.data.copyOf();val b=df.data.copyOf();LayeredBokeh.render(im,df,Cancel());assertArrayEquals(a,im.data,0f);assertArrayEquals(b,df.data,0f)}
    @Test(expected=IllegalArgumentException::class) fun nonfiniteDepthRejected(){LayeredBokeh.render(image(1,1),Frame(1,1,1,floatArrayOf(Float.NaN)),Cancel())}
    @Test(expected=IllegalArgumentException::class) fun mismatchedDimensionsRejected(){LayeredBokeh.render(image(),map(2,2),Cancel())}
    @Test(expected=IllegalArgumentException::class) fun nonSdrBaseRejected(){LayeredBokeh.render(image(1,1,2f),Frame(1,1,1,floatArrayOf(2f)),Cancel())}
    @Test(expected=CancellationException::class) fun earlyCancel(){LayeredBokeh.render(image(),map(31,23),Cancel().apply{cancelled=true})}
    @Test fun cancelsBetweenLayers(){val c=Cancel();var seen=0
        try{LayeredBokeh.render(image(),map(31,23),c){done,_->seen=done;if(done==8)c.cancelled=true};fail()}catch(_:CancellationException){}
        assertEquals(8,seen)
    }
    @Test fun consumesFullCocOnceNotNormalizedModelScales(){val p=photo();val df=map(p.width,p.height);var calls=0;var pass=0
        val full=object:FullCocRenderer{override fun invoke(a:Frame,d:Frame):Frame{calls++;assertEquals(p.width,a.w);assertEquals(p.height,a.h);assertSame(df,d);assertTrue(d.data.maxOrNull()!!>1f);return LayeredBokeh.render(a,d,Cancel())}}
        val out=LegendaryMath.renderCoc(p,df,2.8f,Cancel(),onPass={pass++},infer=full)
        assertEquals(1,calls);assertEquals(1,pass);assertEquals(p.width,out.width);assertEquals(p.height,out.height)
    }
    @Test fun existingHighlightsRunAfterLayeredBase(){val p=photo(v=30);for(c in 0..2)p.plane(c)[p.size/2]=255.toByte();val df=map(p.width,p.height);val events=mutableListOf<String>()
        val full=object:FullCocRenderer{override fun invoke(a:Frame,d:Frame):Frame{events.add("base");return LayeredBokeh.render(a,d,Cancel())}}
        LegendaryMath.renderCoc(p,df,2.8f,Cancel(),highlightRenderer={light,d,ap,_->events.add("highlight");assertSame(df,d);assertEquals(2.8f,ap,0f);Frame(light.w,light.h,3,FloatArray(light.data.size))},onPass={events.add("pass")},infer=full)
        assertEquals(listOf("base","pass","highlight"),events)
    }
    @Test fun completedBaseCheckpointPrecedesPreviewAndResume(){val p=photo();val df=map(p.width,p.height);var saved:MultiscaleCheckpoint.State?=null;var calls=0
        val checkpoint=object:MultiscaleCheckpoint{override fun load()=saved;override fun save(state:MultiscaleCheckpoint.State,cancel:Cancel){saved=state}}
        val full=object:FullCocRenderer{override fun invoke(a:Frame,d:Frame):Frame{calls++;return LayeredBokeh.render(a,d,Cancel())}}
        val a=LegendaryMath.renderCoc(p,df,2.8f,Cancel(),checkpoint=checkpoint,onPass={assertNotNull(saved);assertTrue(saved!!.finished)},infer=full)
        val b=LegendaryMath.renderCoc(p,df,2.8f,Cancel(),checkpoint=checkpoint,infer=full)
        assertEquals(1,calls);for(c in 0..2)assertArrayEquals(a.plane(c),b.plane(c))
    }
    @Test fun opaqueSubjectStillKeepsOriginalPixels(){val p=photo();val df=map(p.width,p.height);val alpha=Frame(p.width,p.height,1,FloatArray(p.size){if(it%p.width>15)1f else 0f});val depth=Frame(p.width,p.height,1,FloatArray(p.size){it.toFloat()/(p.size-1)})
        val out=LegendaryMath.render(p,depth,.4f,2.8f,Cancel(),subjectMask=alpha,infer=callback())
        for(c in 0..2)for(i in 0 until p.size)if(alpha.data[i]==1f)assertEquals(p.plane(c)[i],out.plane(c)[i])
    }
    @Test fun unifiedHdrKeepsIndependentHdrLightAndSdr(){val p=photo(17,13,50);val depth=Frame(17,13,1,FloatArray(17*13){.8f});var sdr:Planes?=null;var calls=0
        val full=object:FullCocRenderer{override fun invoke(a:Frame,d:Frame):Frame{calls++;assertTrue(a.data.all{it<=1f});return LayeredBokeh.render(a,d,Cancel())}}
        val out=LegendaryMath.renderHdrCombined(p,image(17,13,4f),depth,.5f,2.8f,Cancel(),onSdr={sdr=it},infer=full)
        assertEquals(1,calls);assertNotNull(sdr);assertTrue(out.data.all{it.isFinite() && it>=0f});assertTrue(out.data.maxOrNull()!!>1f)
    }
    @Test fun cacheRendererIdentityIsDistinctAndPreservesLastPhoto(){val dir=java.nio.file.Files.createTempDirectory("layered-cache").toFile()
        try{val s=EditSession(dir);s.store.update(blobs=mapOf("source" to {it.writeText("original")},"result" to {it.writeText("last complete")}));s.beginRender("old-arnet");s.store.update(blobs=mapOf("render.output" to {it.writeText("old")},"render.multiscale" to {it.writeText("old scale")}));s.beginRender(LayeredBokeh.ID);assertNull(s.store.file("render.output"));assertNull(s.store.file("render.multiscale"));assertEquals("original",s.source()!!.readText());assertEquals("last complete",s.store.file("result")!!.readText())}finally{dir.deleteRecursively()}
    }
}
