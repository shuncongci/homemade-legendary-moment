package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test

class HdrExportPolicyTest {
    private fun rejects(block:()->Unit){try{block();fail("Expected rejection")}catch(_:IllegalArgumentException){}}
    private fun meta(w:Int=400,h:Int=300,mw:Int=200,mh:Int=150,values:List<Float> = listOf(1f,1f,1f,8f,8f,8f,1f,1f,1f,0f,0f,0f,0f,0f,0f,1f,8f))=HdrExportPolicy.Metadata(w,h,mw,mh,values)
    @Test fun menuDefaultsOff(){assertFalse(HdrExportChoice(null).enabled)}
    @Test fun reopeningDoesNotRememberOptIn(){val old=HdrExportChoice(null);old.setEnabled(true);assertTrue(old.enabled);assertFalse(HdrExportChoice(null).enabled)}
    @Test fun toggleCanReturnToSdr(){val choice=HdrExportChoice(null);choice.setEnabled(true);choice.setEnabled(false);assertFalse(choice.enabled)}
    @Test fun unavailableCannotOptIn(){val choice=HdrExportChoice("unsupported");rejects{choice.setEnabled(true)};assertFalse(choice.enabled)}
    @Test fun unavailableMayRemainOff(){HdrExportChoice("unsupported").setEnabled(false)}
    @Test fun preAndroid14AlwaysExplains(){for(sdk in 26..33){assertNotNull(HdrExportPolicy.unavailable(sdk,false));assertNotNull(HdrExportPolicy.unavailable(sdk,true))}}
    @Test fun sourceGeometryPathRejectsContentEdits(){for(sdk in 34..36)assertEquals(HdrExportPolicy.EDITED,HdrExportPolicy.unavailable(sdk,true))}
    @Test fun geometrySupportedOnAndroid14AndUp(){for(sdk in 34..36)assertNull(HdrExportPolicy.unavailable(sdk,false))}
    @Test fun defaultSdrFormatNotChanged(){assertSame(ExportFormat.PNG,ExportFormat.DEFAULT);val choice=HdrExportChoice(null);choice.setEnabled(true);assertSame(ExportFormat.PNG,ExportFormat.DEFAULT)}
    @Test fun budgetUsesLongAndBothImages(){assertEquals(100_000_000L*16+32L*1024*1024,HdrExportPolicy.workingBytes(10000,5000,10000,5000))}
    @Test fun badDimensionsRejected(){rejects{HdrExportPolicy.workingBytes(0,1,1,1)};rejects{HdrExportPolicy.workingBytes(1,1,-1,1)};rejects{HdrExportPolicy.workingBytes(Int.MAX_VALUE,2,1,1)}}
    @Test fun noUpscale(){rejects{HdrExportPolicy.checkSize(400,300,401,300)};rejects{HdrExportPolicy.checkSize(400,300,400,301)}}
    @Test fun sameSizeAndSameAspectAccepted(){HdrExportPolicy.checkSize(400,300,400,300);HdrExportPolicy.checkSize(400,300,200,150);HdrExportPolicy.checkSize(403,301,201,150)}
    @Test fun aspectChangesAndZeroRejected(){rejects{HdrExportPolicy.checkSize(400,300,200,200)};rejects{HdrExportPolicy.checkSize(400,300,0,1)}}
    @Test fun validMetadataRoundTrips(){val m=meta();HdrExportPolicy.checkRoundTrip(m,m.copy(values=m.values.toList()))}
    @Test fun dimensionsMustRoundTrip(){rejects{HdrExportPolicy.checkRoundTrip(meta(),meta(w=399))};rejects{HdrExportPolicy.checkRoundTrip(meta(),meta(h=299))}}
    @Test fun mapCoordinatesMustRoundTrip(){rejects{HdrExportPolicy.checkRoundTrip(meta(),meta(mw=199))};rejects{HdrExportPolicy.checkRoundTrip(meta(),meta(mh=149))}}
    @Test fun differentGainRejected(){val values=meta().values.toMutableList();values[3]=4f;rejects{HdrExportPolicy.checkRoundTrip(meta(),meta(values=values))}}
    @Test fun tinyCodecParameterRoundingAllowed(){val values=meta().values.toMutableList();values[3]+=0.0001f;HdrExportPolicy.checkRoundTrip(meta(),meta(values=values))}
    @Test fun nonFiniteAndIncompleteMetadataRejected(){val values=meta().values.toMutableList();values[0]=Float.NaN;rejects{meta(values=values)};rejects{meta(values=listOf(1f))}}
    @Test fun invalidGammaAndRatioRejected(){for(index in listOf(0,6,7,8)){val values=meta().values.toMutableList();values[index]=0f;rejects{meta(values=values)}}}
    @Test fun invalidHdrDisplayRangeRejected(){val values=meta().values.toMutableList();values[16]=1f;rejects{meta(values=values)}}
    @Test fun noDisplayPublishWhenReadbackFails(){var rolled=false;var published=false
        try {ExportCommit.run(Cancel(),create={7},write={HdrExportPolicy.checkRoundTrip(meta(),meta(w=399))},publish={published=true},rollback={rolled=true});fail()}catch(_:IllegalArgumentException){}
        assertTrue(rolled);assertFalse(published)
    }
    @Test fun cancelledVerificationNeverPublishes(){val cancel=Cancel();var rolled=false;var published=false
        try{ExportCommit.run(cancel,create={7},write={HdrExportPolicy.checkRoundTrip(meta(),meta());cancel.cancelled=true},publish={published=true},rollback={rolled=true});fail()}catch(_:java.util.concurrent.CancellationException){}
        assertTrue(rolled);assertFalse(published)
    }
}
