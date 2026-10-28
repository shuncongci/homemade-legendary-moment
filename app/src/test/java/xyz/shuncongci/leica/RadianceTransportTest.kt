package xyz.shuncongci.leica

import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import xyz.shuncongci.leica.LegendaryMath.Frame
import kotlin.math.*

class RadianceTransportTest {
    private fun <T> cache(block:(File)->T):T {val d=Files.createTempDirectory("radiance-test-").toFile();return try{block(d)}finally{d.walkBottomUp().forEach{it.delete()}}}
    private fun co(w:Int=47,h:Int=31,constant:Float?=null)=Frame(w,h,1,FloatArray(w*h){i->constant?:((i%w)*6.6f/max(1,w-1)-3.2f)})
    private fun light(w:Int=47,h:Int=31,signed:Boolean=false)=Frame(w,h,3,FloatArray(w*h*3){i->((i*37%151)/31f-(if(signed)2.1f else 0f))})
    private fun transport(d:File,src:List<Frame>,coc:Frame,signed:BooleanArray,tag:String="r",band:Int=17,fg:Boolean=true,cancel:Cancel=Cancel(),progress:(Int,Int)->Unit={_,_->}):Pair<List<Frame>,List<Frame>?> {
        val ins=src.mapIndexed{i,f->BokehFloatFile.create(File(d,"$tag-in$i"),f.w,f.h,3).also{it.put(f,it.whole,Cancel());it.sync()}}
        val outs=src.mapIndexed{i,f->BokehFloatFile.create(File(d,"$tag-out$i"),f.w,f.h,3)}
        val fronts=if(fg)src.mapIndexed{i,f->BokehFloatFile.create(File(d,"$tag-fg$i"),f.w,f.h,3)}else null
        try{StreamingLightScatter.Pupils(coc.w,coc.h,2.8f,d).use{pupils->
            RadianceTransport.run(ins,coc,2.8f,cancel,outs,fronts,signed,pupils,band,progress)
            return outs.map{it.read(it.whole,Cancel())} to fronts?.map{it.read(it.whole,Cancel())}
        }}finally{(ins+outs+(fronts?:emptyList())).forEach{it.close()}}
    }
    private fun reference(src:Frame,coc:Frame):HighlightScatter.LightLayers {
        fun run(input:Frame):HighlightScatter.LightLayers {val fg=Frame(src.w,src.h,3,FloatArray(src.data.size));val out=HighlightScatter.opticalAtScale(input,coc,2.8f,Cancel(),1f,fg,pruneDark=false);return HighlightScatter.LightLayers(out,fg)}
        val pos=run(Frame(src.w,src.h,3,FloatArray(src.data.size){max(0f,src.data[it])}))
        val neg=run(Frame(src.w,src.h,3,FloatArray(src.data.size){max(0f,-src.data[it])}))
        for(i in src.data.indices){pos.color.data[i]-=neg.color.data[i];pos.foreground.data[i]-=neg.foreground.data[i]};return pos
    }
    private fun compare(w:Int,h:Int,value:Float?=null){cache{d->val c=co(w,h,value);val a=light(w,h);val b=light(w,h,true);val got=transport(d,listOf(a,b),c,booleanArrayOf(false,true))
        for((i,src) in listOf(a,b).withIndex()){val ref=reference(src,c);assertArrayEquals(ref.color.data,got.first[i].data,3e-5f);assertArrayEquals(ref.foreground.data,got.second!![i].data,3e-5f)}
    }}
    @Test fun variableDepthMatchesIndependentSignedPasses()=compare(47,31)
    @Test fun fractionalNearPlaneMatches()=compare(43,29,3.27f)
    @Test fun fractionalFarPlaneMatches()=compare(43,29,-3.27f)
    @Test fun integerNearPlaneMatches()=compare(41,27,4f)
    @Test fun integerFarPlaneMatches()=compare(41,27,-4f)
    @Test fun oneColumnMatches()=compare(1,47)
    @Test fun oneRowMatches()=compare(53,1)
    @Test fun onePixelMatches()=compare(1,1,1.2f)
    @Test fun subUlpCoCMatches()=compare(31,23,1e-7f)
    @Test fun focusPreservesNegativeResidualBits(){cache{d->val a=light(signed=true);val got=transport(d,listOf(a),co(constant=0f),booleanArrayOf(true));assertArrayEquals(a.data,got.first[0].data,0f)}}
    @Test fun hdrValuesAboveOneRemain(){cache{d->val a=light();val got=transport(d,listOf(a),co(constant=2f),booleanArrayOf(false));assertTrue(got.first[0].data.maxOrNull()!!>1f)}}
    @Test fun negativeResidualNotTurnedIntoLight(){cache{d->val a=light();a.data.fill(-2.75f);val got=transport(d,listOf(a),co(),booleanArrayOf(true));assertTrue(got.first[0].data.all{it<0f});assertArrayEquals(a.data,got.first[0].data,3e-5f)}}
    @Test fun constantDarkDoesNotBecomeFogAtDepthEdge(){cache{d->val a=light();a.data.fill(.018f);val c=co();for(i in c.data.indices)c.data[i]=if(i%c.w<c.w/2)-3.3f else 2.7f;val got=transport(d,listOf(a),c,booleanArrayOf(false));assertArrayEquals(a.data,got.first[0].data,1e-6f)}}
    @Test fun signedNearAndFarCancellationRemainsFinite(){cache{d->val a=light(signed=true);val got=transport(d,listOf(a),co(),booleanArrayOf(true));assertTrue(got.first[0].data.all{it.isFinite()});assertTrue(got.first[0].data.any{it<0f})}}
    @Test fun sdrIndependentOfHdr(){cache{d->val a=light();val c=co();val x=transport(d,listOf(a),c,booleanArrayOf(false),"solo");val y=transport(d,listOf(a,light(signed=true)),c,booleanArrayOf(false,true),"dual");assertArrayEquals(x.first[0].data,y.first[0].data,0f)}}
    @Test fun receiverBandsAreStorageOnly(){cache{d->val a=light(signed=true);val c=co();val x=transport(d,listOf(a),c,booleanArrayOf(true),"b1",1);val y=transport(d,listOf(a),c,booleanArrayOf(true),"b31",31);assertArrayEquals(x.first[0].data,y.first[0].data,0f)}}
    @Test fun sourceAndCoCAreNotMutated(){cache{d->val a=light(signed=true);val c=co();val ab=a.data.copyOf();val cb=c.data.copyOf();transport(d,listOf(a),c,booleanArrayOf(true));assertArrayEquals(ab,a.data,0f);assertArrayEquals(cb,c.data,0f)}}
    @Test fun zeroLightStaysZero(){cache{d->val a=light();a.data.fill(0f);val got=transport(d,listOf(a),co(),booleanArrayOf(false));assertTrue(got.first[0].data.all{it==0f})}}
    @Test fun noThreeTimesRadiusHalo(){cache{d->val a=light(61,61);a.data.fill(0f);for(c in 0..2)a.data[c*a.size+30*61+30]=3f;val got=transport(d,listOf(a),co(61,61,3f),booleanArrayOf(false)).first[0];for(y in 0..60)for(x in 0..60)if(abs(x-30)>4 || abs(y-30)>4)for(c in 0..2)assertEquals(0f,got.data[c*a.size+y*61+x],0f)}}
    @Test fun isolatedCenteredLightEnergyConserved(){cache{d->val a=light(65,65);a.data.fill(0f);for(c in 0..2)a.data[c*a.size+32*65+32]=2f;val got=transport(d,listOf(a),co(65,65,3f),booleanArrayOf(false)).first[0];assertEquals(6.0,got.data.sumOf{it.toDouble()},.06)}}
    @Test fun foregroundEqualsZeroForFarOnlyPlane(){cache{d->val got=transport(d,listOf(light()),co(constant=-3f),booleanArrayOf(false));assertTrue(got.second!![0].data.all{it==0f})}}
    @Test fun progressCountsRealReceiverRows(){cache{d->val seen=arrayListOf<Int>();transport(d,listOf(light()),co(),booleanArrayOf(false),progress={n,total->assertEquals(31,total);seen.add(n)});assertEquals(listOf(17,31),seen)}}
    @Test(expected=CancellationException::class) fun cancelledBeforeRunStops(){cache{d->transport(d,listOf(light()),co(),booleanArrayOf(false),cancel=Cancel().apply{cancelled=true})}}
    @Test(expected=CancellationException::class) fun cancelAfterCompletedBandStops(){cache{d->val c=Cancel();transport(d,listOf(light()),co(),booleanArrayOf(false),cancel=c,progress={_,_->c.cancelled=true})}}
    @Test(expected=IllegalArgumentException::class) fun unsignedNegativeInputRejected(){cache{d->transport(d,listOf(light(signed=true)),co(),booleanArrayOf(false))}}
    @Test(expected=IllegalArgumentException::class) fun excessiveRadiusRejected(){cache{d->transport(d,listOf(light()),co(constant=1000f),booleanArrayOf(false))}}
    @Test(expected=IllegalArgumentException::class) fun nanCoCRejected(){cache{d->transport(d,listOf(light()),co(constant=Float.NaN),booleanArrayOf(false))}}
    @Test(expected=IllegalArgumentException::class) fun zeroBandRejected(){cache{d->transport(d,listOf(light()),co(),booleanArrayOf(false),band=0)}}
    @Test(expected=IllegalArgumentException::class) fun wrongSignCountRejected(){cache{d->transport(d,listOf(light()),co(),booleanArrayOf())}}
    @Test(expected=IllegalArgumentException::class) fun mismatchedDimensionsRejected(){cache{d->transport(d,listOf(light(23,17)),co(),booleanArrayOf(false))}}
}
