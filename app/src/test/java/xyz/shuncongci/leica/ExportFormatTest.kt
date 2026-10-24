package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CancellationException
import kotlin.random.Random

class ExportFormatTest {
    private fun rejected(block:()->Unit){try{block();fail("Expected rejection")}catch(_:IllegalArgumentException){}}
    @Test fun firstInstallDefaultsToOriginalPngFirst(){assertSame(ExportFormat.PNG,ExportFormat.DEFAULT);assertSame(ExportFormat.PNG,ExportFormat.entries.first());assertSame(ExportFormat.PNG,ExportFormat.fromId(null))}
    @Test fun invalidStoredIdsFallBackSafely(){for(s in listOf("","0","WEBP_LOSSLESS","image/heif","bad"))assertSame(ExportFormat.PNG,ExportFormat.fromId(s))}
    @Test fun allStableIdsRoundTrip(){for(f in ExportFormat.entries)assertSame(f,ExportFormat.fromId(f.id))}
    @Test fun stableIdsAreUniqueAndNonOrdinal(){val ids=ExportFormat.entries.map{it.id};assertEquals(ids.size,ids.distinct().size);assertTrue(ids.all{it.toIntOrNull()==null})}
    @Test fun oldAndroidNeverOffersFakeLosslessWebp(){for(sdk in 26..29){assertFalse(ExportFormat.WEBP_LOSSLESS in ExportFormat.available(sdk));assertSame(ExportFormat.PNG,ExportFormat.fromId("webp-lossless",sdk))}}
    @Test fun android11OffersExplicitLosslessWebp(){assertTrue(ExportFormat.WEBP_LOSSLESS in ExportFormat.available(30));assertSame(ExportFormat.WEBP_LOSSLESS,ExportFormat.fromId("webp-lossless",30))}
    @Test fun jpegPngLossyWebpSupportedOnMinSdk(){for(f in listOf(ExportFormat.JPEG,ExportFormat.PNG,ExportFormat.WEBP_SMALL,ExportFormat.WEBP_HIGH))assertTrue(f.supports(26))}
    @Test fun extensionsAndMimesMatchActualCodec(){assertEquals("jpg",ExportFormat.JPEG.extension);assertEquals("image/jpeg",ExportFormat.JPEG.mime);assertEquals("png",ExportFormat.PNG.extension);assertEquals("image/png",ExportFormat.PNG.mime);for(f in listOf(ExportFormat.WEBP_SMALL,ExportFormat.WEBP_HIGH,ExportFormat.WEBP_LOSSLESS)){assertEquals("webp",f.extension);assertEquals("image/webp",f.mime)}}
    @Test fun hintsClearlySeparateStorageAndFidelity(){assertTrue(ExportFormat.PNG.storageHint.contains("0%"));for(f in ExportFormat.entries){assertTrue(f.storageHint.contains("%"));assertTrue(f.qualityRating.contains(if(f.lossless)"无损" else "有损"))};assertTrue(ExportFormat.WEBP_SMALL.qualityRating.contains("均衡"))}
    @Test fun qualityAndLosslessFlagsExplicit(){assertEquals(95,ExportFormat.JPEG.quality);assertEquals(90,ExportFormat.WEBP_SMALL.quality);assertEquals(95,ExportFormat.WEBP_HIGH.quality);assertTrue(ExportFormat.PNG.lossless);assertTrue(ExportFormat.WEBP_LOSSLESS.lossless);assertFalse(ExportFormat.JPEG.lossless)}
    @Test fun webpDimensionLimitDoesNotSilentlyResize(){for(f in listOf(ExportFormat.WEBP_SMALL,ExportFormat.WEBP_HIGH,ExportFormat.WEBP_LOSSLESS)){assertTrue(f.supportsDimensions(16383,1));assertFalse(f.supportsDimensions(16384,1));assertFalse(f.supportsDimensions(1,16384))}}
    @Test fun pngAndJpegNotSubjectToWebpLimit(){assertTrue(ExportFormat.PNG.supportsDimensions(20000,10));assertTrue(ExportFormat.JPEG.supportsDimensions(20000,10))}
    @Test fun pngMemoryIndependentOfHeight(){assertEquals(ExportFormat.PNG.estimatedWorkingBytes(8000,1),ExportFormat.PNG.estimatedWorkingBytes(8000,6000))}
    @Test fun bitmapCodecBudgetUsesLongMath(){assertEquals(50_000_000L*8+32L*1024*1024,ExportFormat.JPEG.estimatedWorkingBytes(10000,5000));assertTrue(ExportFormat.WEBP_SMALL.estimatedWorkingBytes(4000,3000)>ExportFormat.JPEG.estimatedWorkingBytes(4000,3000))}
    @Test fun invalidSizesRejected(){rejected{ExportFormat.PNG.estimatedWorkingBytes(0,1)};rejected{ExportFormat.JPEG.estimatedWorkingBytes(Int.MAX_VALUE,2)};rejected{ExportFormat.WEBP_SMALL.supportsDimensions(1,0)}}
    @Test fun formatChangeDoesNotInvalidatePixels(){val f=PhotoFlow();f.photoLoaded();f.generated();f.selectExportFormat(ExportFormat.WEBP_SMALL);assertTrue(f.hasResult);assertFalse(f.resultStale);assertTrue(f.canSave)}
    @Test fun formatChangeAfterSaveAllowsReencodingWithoutRegeneration(){val f=PhotoFlow();f.photoLoaded();f.generated();f.saved();f.selectExportFormat(ExportFormat.JPEG);assertFalse(f.saved);assertTrue(f.canSave);assertFalse(f.resultStale)}
    @Test fun choosingSameFormatDoesNotResetSaved(){val f=PhotoFlow();f.photoLoaded();f.generated();f.saved();f.selectExportFormat(f.exportFormat);assertTrue(f.saved);assertFalse(f.canSave)}
    @Test fun formatChangePreservesExistingStaleState(){val f=PhotoFlow();f.photoLoaded();f.generated();f.selectStyle(LeicaStyle.WARM);f.selectExportFormat(ExportFormat.JPEG);assertTrue(f.resultStale);assertSame(LeicaStyle.WARM,f.style)}
    @Test fun formatChangeBeforeImportDoesNotCreateResult(){val f=PhotoFlow();f.selectExportFormat(ExportFormat.JPEG);assertFalse(f.hasResult);assertFalse(f.hasPhoto);assertFalse(f.canSave)}
    @Test fun newPhotoKeepsExportChoice(){val f=PhotoFlow();f.selectExportFormat(ExportFormat.WEBP_HIGH);f.photoLoaded();assertSame(ExportFormat.WEBP_HIGH,f.exportFormat);f.photoFailed();assertSame(ExportFormat.WEBP_HIGH,f.exportFormat)}
    @Test fun everyBusyOperationRejectsFormatEdits(){for(op in PhotoFlow.Operation.entries.filter{it!=PhotoFlow.Operation.IDLE}){val f=PhotoFlow();f.begin(op);try{f.selectExportFormat(ExportFormat.JPEG);fail()}catch(_:IllegalStateException){};assertSame(ExportFormat.DEFAULT,f.exportFormat)}}
    @Test fun formatChangeDoesNotTouchFocusOrSubjectCache(){val f=PhotoFlow();f.photoLoaded();f.selectProcessingMode(ProcessingMode.COLOR_AND_BLUR);val p=LegendaryMath.Point(.4f,.5f);f.picked(p);f.toggleSubject();f.chooseSubjects();f.addSubjectPoint(p);f.endSubjectPicking();f.subjectCompleted();val ticket=f.subjectTicket();f.generated();f.selectExportFormat(ExportFormat.WEBP_LOSSLESS);assertEquals(ticket,f.subjectTicket());assertEquals(p,f.focus);assertTrue(f.subjectReady);assertFalse(f.resultStale)}
    @Test fun saveCanBeCancelledAndRetried(){val f=PhotoFlow();f.photoLoaded();f.generated();f.begin(PhotoFlow.Operation.SAVE);f.cancel();assertTrue(f.cancelling);f.finish();assertTrue(f.canSave);assertFalse(f.saved)}
    private fun commit(c:Cancel=Cancel(),create:()->Int={7},write:(Int)->Unit={},publish:(Int)->Unit={},rollback:(Int)->Unit={})=ExportCommit.run(c,create,write,publish,rollback)
    @Test fun pendingTransactionOrderAndSinglePublish(){val events=mutableListOf<String>();val result=commit(create={events.add("create");7},write={events.add("write")},publish={events.add("publish")},rollback={events.add("rollback")});assertEquals(7,result);assertEquals(listOf("create","write","publish"),events)}
    @Test fun cancelledBeforeCreateTouchesNoDestination(){var touched=false;try{commit(Cancel().apply{cancelled=true},create={touched=true;7});fail()}catch(_:CancellationException){};assertFalse(touched)}
    @Test fun createFailureNeverDeletesExistingItem(){var rolled=false;try{commit(create={throw IOException("create")},rollback={rolled=true});fail()}catch(_:IOException){};assertFalse(rolled)}
    @Test fun writeFailureRollsBackNewPendingItem(){var rolled=0;var published=false;try{commit(write={throw IOException("write")},publish={published=true},rollback={rolled=it});fail()}catch(_:IOException){};assertEquals(7,rolled);assertFalse(published)}
    @Test fun cancellationAfterWriteDeletesPartial(){val c=Cancel();var published=false;var rolled=false;try{commit(c,write={c.cancelled=true},publish={published=true},rollback={rolled=true});fail()}catch(_:CancellationException){};assertFalse(published);assertTrue(rolled)}
    @Test fun publishFailureRollsBack(){var rolled=false;try{commit(publish={throw IOException("publish")},rollback={rolled=true});fail()}catch(_:IOException){};assertTrue(rolled)}
    @Test fun cleanupFailureDoesNotHideOriginal(){val first=IOException("write");try{commit(write={throw first},rollback={throw IOException("cleanup")});fail()}catch(t:IOException){assertSame(first,t);assertEquals("cleanup",t.suppressed.single().message)}}
    @Test fun fatalStillPropagatesAfterCleanup(){val fatal=AssertionError("fatal");var rolled=false;try{commit(write={throw fatal},rollback={rolled=true});fail()}catch(t:AssertionError){assertSame(fatal,t)};assertTrue(rolled)}
    @Test fun cancellationAfterPublishDoesNotDeleteCommittedPhoto(){val c=Cancel();var rolled=false;assertEquals(7,commit(c,publish={c.cancelled=true},rollback={rolled=true}));assertFalse(rolled)}
    private fun encoded(w:Int,h:Int,pixels:IntArray,width:Int=w,height:Int=h):ByteArray {
        val out=ByteArrayOutputStream();CompositionExport.writeSized(out,w,h,CompositionGeometry.FULL,width,height,Cancel()){box->IntArray(box.w*box.h){i->pixels[(box.top+i/box.w)*w+box.left+i%box.w]}};return out.toByteArray()
    }
    private fun decode(bytes:ByteArray):Any=Class.forName("javax.imageio.ImageIO").getMethod("read",java.io.InputStream::class.java).invoke(null,ByteArrayInputStream(bytes))?:error("Invalid PNG")
    private fun rgb(decoded:Any,x:Int,y:Int)=decoded.javaClass.getMethod("getRGB",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).invoke(decoded,x,y) as Int
    @Test fun opaquePngDropsUnusedAlphaButKeepsEveryByte(){val random=Random(985);val w=97;val h=131;val a=IntArray(w*h){random.nextInt() or (255 shl 24)};val b=encoded(w,h,a);assertEquals(2,b[25].toInt());val image=decode(b);for(i in a.indices)assertEquals(a[i],rgb(image,i%w,i/w))}
    @Test fun transparentPngKeepsRgbaAndHiddenRgb(){val w=47;val h=39;val a=IntArray(w*h){i->(i*1337) xor (i shl 24)};val b=encoded(w,h,a);assertEquals(6,b[25].toInt());val image=decode(b);for(i in a.indices)assertEquals(a[i],rgb(image,i%w,i/w))}
    @Test fun transparencyInLastPixelNotMissed(){val a=IntArray(200*150){-1};a[a.lastIndex]=0x01020304;val b=encoded(200,150,a);assertEquals(6,b[25].toInt());assertEquals(0x01020304,rgb(decode(b),199,149))}
    @Test fun onePixelBothAlphaModes(){for(c in listOf(-1,0xff123456.toInt(),0,0x01123456,0x80123456.toInt())){val b=encoded(1,1,intArrayOf(c));assertEquals(c,rgb(decode(b),0,0));assertEquals(if(c ushr 24==255)2 else 6,b[25].toInt())}}
    @Test fun downsampleStillUsesPremultipliedAlpha(){val a=intArrayOf(0xffff0000.toInt(),0x000000ff,0xffff0000.toInt(),0x000000ff);val b=encoded(2,2,a,1,1);val c=rgb(decode(b),0,0);assertEquals(128,c ushr 24);assertEquals(255,(c ushr 16) and 255);assertEquals(0,c and 255)}
    @Test fun sharedRowsExactCropAndOrder(){val w=139;val h=159;val crop=CompositionGeometry.Crop(.11,.13,.91,.87);val box=CompositionGeometry.pixels(crop,w,h);var y=0;var max=0
        CompositionExport.rows(w,h,crop,box.w,box.h,Cancel(),{b->max=maxOf(max,b.w*b.h);IntArray(b.w*b.h){i->(b.top+i/b.w)*w+b.left+i%b.w}}){row,p,offset->assertEquals(y++,row);for(x in 0 until box.w)assertEquals((box.top+row)*w+box.left+x,p[offset+x])};assertEquals(box.h,y);assertTrue(max<=262144)}
    @Test fun sharedRowsAndPngHaveIdenticalDownsamplePixels(){val w=91;val h=137;val ow=49;val oh=73;val a=IntArray(w*h){i->0xff000000.toInt() or ((i*317) and 0xffffff)};val streamed=IntArray(ow*oh)
        CompositionExport.rows(w,h,CompositionGeometry.FULL,ow,oh,Cancel(),{b->IntArray(b.w*b.h){i->a[(b.top+i/b.w)*w+b.left+i%b.w]}}){y,p,off->p.copyInto(streamed,y*ow,off,off+ow)}
        val got=decode(encoded(w,h,a,ow,oh));for(i in streamed.indices)assertEquals(streamed[i],rgb(got,i%ow,i/ow))}
    @Test fun secondPassCancellationNeverFinishesPng(){val c=Cancel();val out=ByteArrayOutputStream();var reads=0
        try{CompositionExport.write(out,20,20,CompositionGeometry.FULL,c){b->reads++;if(reads==2)c.cancelled=true;IntArray(b.w*b.h){-1}};fail()}catch(_:CancellationException){};assertFalse(String(out.toByteArray(),Charsets.ISO_8859_1).endsWith("IEND\u00aeB`\u0082"))}
    @Test fun immutableReaderGuardRejectsNewTransparency(){var reads=0;try{CompositionExport.write(ByteArrayOutputStream(),10,10,CompositionGeometry.FULL,Cancel()){b->reads++;IntArray(b.w*b.h){if(reads==1)-1 else 0x80123456.toInt()}};fail()}catch(_:IllegalStateException){}}
    @Test fun pngWriteFailurePropagates(){val out=object:OutputStream(){override fun write(b:Int){throw IOException("disk full")}};try{CompositionExport.write(out,30,30,CompositionGeometry.FULL,Cancel()){b->IntArray(b.w*b.h){-1}};fail()}catch(_:IOException){}}
    @Test fun upscalingStillRejected(){rejected{encoded(2,2,IntArray(4){-1},3,2)}}
    private val safe=ExportMenuGeometry.Box(10,40,390,820)
    @Test fun menuGrowsUpFromButtonBottom(){val anchor=ExportMenuGeometry.Box(270,750,380,804);val g=ExportMenuGeometry.place(safe,anchor,360,570);assertEquals(anchor.bottom,g.box.bottom);assertEquals(570,g.box.height);assertEquals(anchor.height,g.origin.height);assertEquals(anchor.width,g.origin.width)}
    @Test fun menuFitsSmallLandscapeAndScrollsInternally(){val s=ExportMenuGeometry.Box(20,20,780,350);val a=ExportMenuGeometry.Box(660,280,760,338);val g=ExportMenuGeometry.place(s,a,380,580);assertEquals(318,g.box.height);assertTrue(g.box.top>=s.top);assertTrue(g.box.right<=s.right)}
    @Test fun narrowScreenClampsWidthWithoutHorizontalOverflow(){val s=ExportMenuGeometry.Box(4,20,236,780);val a=ExportMenuGeometry.Box(160,700,230,754);val g=ExportMenuGeometry.place(s,a,380,580);assertEquals(s.width,g.box.width);assertEquals(s.left,g.box.left)}
    @Test fun allAnchorPositionsRemainInsideSafeArea(){for(x in listOf(10,120,260))for(y in listOf(90,200,500,760)){val a=ExportMenuGeometry.Box(x,y,x+80,y+48);val g=ExportMenuGeometry.place(safe,a,350,580);assertTrue(g.box.left>=safe.left);assertTrue(g.box.right<=safe.right);assertTrue(g.box.top>=safe.top);assertTrue(g.box.bottom<=safe.bottom);assertEquals(g.box.height,g.origin.bottom)}}
    @Test fun animationStartsAndEndsExactly(){assertEquals(0f,ExportMenuGeometry.reveal(0f),0f);assertEquals(1f,ExportMenuGeometry.reveal(1f),0f);for(i in 0..12){assertEquals(0f,ExportMenuGeometry.stagger(0f,i),0f);assertEquals(1f,ExportMenuGeometry.stagger(1f,i),0f)}}
    @Test fun staggeredOptionsHaveOrderedEntry(){assertTrue(ExportMenuGeometry.stagger(.45f,0)>ExportMenuGeometry.stagger(.45f,4));for(t in 0..100)for(i in 0..10)assertTrue(ExportMenuGeometry.stagger(t/100f,i) in 0f..1f)}
    @Test fun springNeverMakesInvalidClip(){for(i in 0..1000)assertTrue(ExportMenuGeometry.reveal(i/1000f) in 0f..1.025f)}
}
