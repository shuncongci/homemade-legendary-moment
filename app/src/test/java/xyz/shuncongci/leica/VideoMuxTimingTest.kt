package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test

class VideoMuxTimingTest {
    private fun reject(f:()->Unit){try{f();fail("must reject")}catch(_:IllegalArgumentException){}}
    private val source=longArrayOf(0,33_367,66_733,100_100,153_439,170_006)
    private fun ticks(p:LongArray,scale:Long)=p.map{(it*scale+500_000)/1_000_000}
    private fun us(p:List<Long>,scale:Long)=p.map{it*1_000_000/scale}
    private fun pass(p:LongArray=source,scale:Long=90_000,end:Long=200_039){
        val t=ticks(p,scale);val endTicks=ticks(longArrayOf(end),scale).single()
        VideoMuxTiming.verify(p,us(t,scale),t,scale,end,endTicks*1_000_000/scale)
    }
    @Test fun defaultIsNInstant(){assertEquals(VideoColor.N_INSTANT,VideoColor.DEFAULT)}
    @Test fun newPageUsesNInstant(){assertEquals(VideoColor.N_INSTANT,VideoColor.restore(null))}
    @Test fun unknownSavedLookUsesNInstant(){for(n in listOf("","missing","99"))assertEquals(VideoColor.N_INSTANT,VideoColor.restore(n))}
    @Test fun explicitSavedSelectionIsNotOverwritten(){for(look in VideoColor.entries)assertEquals(look,VideoColor.restore(look.name))}
    @Test fun reproducesOldMicrosecondFalseRejection(){val t=ticks(source,90_000);val a=us(t,90_000);assertNotEquals(source.toList(),a);reject{VideoContract.verifyFrames(source,a)};pass()}
    @Test fun millisecondToleranceIsNotUsed(){reject{VideoMuxTiming.verify(longArrayOf(0,40_000),listOf(0,40_001),listOf(0,40_001),1_000_000,80_000,80_000)}}
    @Test fun timebasesAndVfr(){for(s in listOf(10_000L,44_100,90_000,100_000,1_000_000,10_000_000))pass(scale=s)}
    @Test fun nonZeroStartIsNotNormalized(){pass(source.map{it+12_345}.toLongArray(),end=220_039)}
    @Test fun longTimelineDoesNotUseFloat(){pass(source.map{it+6_000_000_000}.toLongArray(),end=6_000_200_039)}
    @Test fun floorAndCeilAreBothLegalIntegralTickConversions(){for(ceil in listOf(false,true)){val s=90_000L;val t=source.map{(it*s+if(ceil)999_999 else 0)/1_000_000};VideoMuxTiming.verify(source,us(t,s),t,s,200_000,200_000)}}
    @Test fun exactIntegralTicksDoNotGetOneExtraTick(){val p=longArrayOf(0,40_000);reject{VideoMuxTiming.verify(p,listOf(0,40_011),listOf(0,3601),90_000,80_000,80_000)}}
    @Test fun changedFrameCannotHideBehindContainerScale(){val t=ticks(source,90_000).toMutableList();t[2]+=2;reject{VideoMuxTiming.verify(source,us(t,90_000),t,90_000,200_000,200_000)}}
    @Test fun extractorEditShiftIsNotTolerated(){val t=ticks(source,90_000);reject{VideoMuxTiming.verify(source,us(t,90_000).map{it+100},t,90_000,200_000,200_000)}}
    @Test fun noFrameDrop(){val t=ticks(source,90_000).dropLast(1);reject{VideoMuxTiming.verify(source,us(t,90_000),t,90_000,200_000,200_000)}}
    @Test fun noFrameDuplicate(){val t=ticks(source,90_000)+ticks(source,90_000).last();reject{VideoMuxTiming.verify(source,us(t,90_000),t,90_000,200_000,200_000)}}
    @Test fun noReorderedOutput(){val t=ticks(source,90_000).toMutableList();val a=t[1];t[1]=t[2];t[2]=a;reject{VideoMuxTiming.verify(source,us(t,90_000),t,90_000,200_000,200_000)}}
    @Test fun noRepeatedTimestamp(){val t=ticks(source,90_000).toMutableList();t[1]=t[0];reject{VideoMuxTiming.verify(source,us(t,90_000),t,90_000,200_000,200_000)}}
    @Test fun noEmpty(){reject{VideoMuxTiming.verify(longArrayOf(),emptyList(),emptyList(),90_000,1,1)}}
    @Test fun noNegative(){reject{VideoMuxTiming.verify(longArrayOf(-1,100),listOf(-1,100),listOf(-1,100),1_000_000,200,200)}}
    @Test fun noCoarseOrUnknownScale(){for(s in listOf(-1L,0,1000,9999,0x100000000L))reject{VideoMuxTiming.verify(longArrayOf(0,40_000),listOf(0,40_000),listOf(0,3600),s,80_000,80_000)}}
    @Test fun lastFrameEndStillChecked(){val t=ticks(source,90_000);reject{VideoMuxTiming.verify(source,us(t,90_000),t,90_000,200_000,201_000)}}
    @Test fun noTruncatedEnd(){val t=ticks(source,90_000);reject{VideoMuxTiming.verify(source,us(t,90_000),t,90_000,200_000,170_006)}}
    @Test fun fineScalePreservesExactEnd(){reject{VideoMuxTiming.verify(longArrayOf(0,40_000),listOf(0,40_000),listOf(0,40_000),1_000_000,80_000,80_001)}}
    @Test fun doesNotModifyInputs(){val p=source.copyOf();val t=ticks(p,90_000);val a=us(t,90_000);val before=a.toList();VideoMuxTiming.verify(p,a,t,90_000,200_000,200_000);assertArrayEquals(source,p);assertEquals(before,a)}
    @Test fun codecGateRemainsExact(){reject{VideoContract.verifyFrames(longArrayOf(0,33_367),listOf(0,33_366))}}
    @Test fun diagnosticsContainFirstMismatchAndTimebase(){val t=ticks(source,90_000).toMutableList();t[2]+=2;try{VideoMuxTiming.verify(source,us(t,90_000),t,90_000,200_000,200_000);fail()}catch(e:IllegalArgumentException){assertTrue(e.message!!.contains("第3帧"));assertTrue(e.message!!.contains("90000"));assertTrue(e.message!!.contains("66733"))}}
    private fun editBox(version:Int=0,rows:List<Triple<Long,Long,Int>>):VideoIso.Atom=VideoIso.atom("edts",VideoIso.atom("elst",VideoIso.bytes{writeInt(version shl 24);writeInt(rows.size);for((duration,time,rate) in rows){if(version==1){writeLong(duration);writeLong(time)}else{writeInt(duration.toInt());writeInt(time.toInt())};writeInt(rate)}}).raw)
    private fun mvhd(scale:Long=1000)=VideoIso.bytes{writeInt(0);writeInt(0);writeInt(0);writeInt(scale.toInt())}
    @Test fun noEditMeansNoFittedOffset(){assertEquals(VideoMuxTiming.Edit(),VideoMuxTiming.edit(byteArrayOf(),null))}
    @Test fun parseBothEditVersions(){for(v in 0..1){val e=VideoMuxTiming.edit(mvhd(),editBox(v,listOf(Triple(200,3000,65536))));assertEquals(VideoMuxTiming.Edit(3000,0,1000,200),e)}}
    @Test fun parseExplicitEmptyDelay(){val e=VideoMuxTiming.edit(mvhd(),editBox(rows=listOf(Triple(12,-1,65536),Triple(200,3000,65536))));assertEquals(VideoMuxTiming.Edit(3000,12,1000,200),e)}
    @Test fun explicitEditSubtractsMediaTimeNotAnInferredOffset(){val e=VideoMuxTiming.Edit(3000,0,1000,200);val t=listOf(3000L,6600,10200);VideoMuxTiming.verify(longArrayOf(0,40_000,80_000),listOf(0,40_000,80_000),t,90_000,200_000,200_000,e,21000)}
    @Test fun emptyEditKeepsAbsoluteNonZeroStart(){val e=VideoMuxTiming.Edit(3000,12,1000,200);val t=listOf(3000L,6600,10200);VideoMuxTiming.verify(longArrayOf(12_000,52_000,92_000),listOf(12_000,52_000,92_000),t,90_000,212_000,212_000,e,21000)}
    @Test fun rawOffsetCannotPassWithoutEditWitness(){reject{VideoMuxTiming.verify(longArrayOf(0,40_000),listOf(0,40_000),listOf(3000,6600),90_000,80_000,80_000)}}
    @Test fun editsCannotDiscardAnyOutputSample(){reject{VideoMuxTiming.verify(longArrayOf(0,40_000),listOf(0,40_000),listOf(3000,6600),90_000,80_000,80_000,VideoMuxTiming.Edit(4000))}}
    @Test fun editEndCannotTruncateFinalFrame(){reject{VideoMuxTiming.verify(longArrayOf(0,40_000),listOf(0,40_000),listOf(0,3600),90_000,80_000,80_000,VideoMuxTiming.Edit(segment=40,movieScale=1000),7200)}}
    @Test fun sampleEndIsVerifiedIndependentlyOfFormatDuration(){reject{VideoMuxTiming.verify(longArrayOf(0,40_000),listOf(0,40_000),listOf(0,3600),90_000,80_000,80_000,endTicks=7198)}}
    @Test fun noSpeedOrDwellEdit(){for(rate in listOf(0,32768,131072))reject{VideoMuxTiming.edit(mvhd(),editBox(rows=listOf(Triple(200,0,rate))))}}
    @Test fun noMultipleActiveEdits(){reject{VideoMuxTiming.edit(mvhd(),editBox(rows=listOf(Triple(100,0,65536),Triple(100,9000,65536))))}}
    @Test fun noUnsupportedEmptyOnlyEdit(){reject{VideoMuxTiming.edit(mvhd(),editBox(rows=listOf(Triple(100,-1,65536))))}}
    @Test fun noZeroMovieClock(){reject{VideoMuxTiming.edit(mvhd(0),editBox(rows=listOf(Triple(200,0,65536))))}}
    @Test fun noTruncatedEdit(){val e=editBox(rows=listOf(Triple(200,0,65536)));reject{VideoMuxTiming.edit(mvhd(),VideoIso.Atom(e.type,e.raw.copyOf(e.raw.size-1)))}}
    @Test fun reproducesRealRemuxEndpointClockConversion(){
        val p=longArrayOf(0,33367,66733,100100,153439,170006,206777,240657)
        val t=listOf(0L,3003,6006,9009,13810,15301,18610,21659)
        val a=listOf(0L,33367,66733,100100,153444,170011,206778,240656)
        for(segment in listOf(274022L,274023))VideoMuxTiming.verify(p,a,t,90000,274024,274022,VideoMuxTiming.Edit(movieScale=1000000,segment=segment),24662)
    }
    @Test fun editEndpointCannotAddASecondMediaTick(){reject{
        VideoMuxTiming.verify(longArrayOf(0,33367),listOf(0,33367),listOf(0,3003),90000,66734,66733,VideoMuxTiming.Edit(movieScale=1000000,segment=66735),6006)
    }}
    @Test fun formatDurationMustMatchActualEndpointNotAnotherCandidate(){reject{
        VideoMuxTiming.verify(longArrayOf(0,33367),listOf(0,33367),listOf(0,3003),90000,66734,66744,endTicks=6006)
    }}
    @Test fun movieClockRoundingIsLimitedToOneMovieTick(){
        for(segment in listOf(66L,67))VideoMuxTiming.verify(longArrayOf(0,33367),listOf(0,33367),listOf(0,3003),90000,66734,66733,VideoMuxTiming.Edit(movieScale=1000,segment=segment),6006)
        for(segment in listOf(65L,68))reject{VideoMuxTiming.verify(longArrayOf(0,33367),listOf(0,33367),listOf(0,3003),90000,66734,66733,VideoMuxTiming.Edit(movieScale=1000,segment=segment),6006)}
    }
}
