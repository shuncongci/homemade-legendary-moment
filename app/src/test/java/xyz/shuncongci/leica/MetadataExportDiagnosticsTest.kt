package xyz.shuncongci.leica

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic carriers prove that a visible error and a successful photo export can coexist. */
class MetadataExportDiagnosticsTest {
    @get:Rule val temp=TemporaryFolder()
    private fun file(b:ByteArray)=temp.newFile().apply{writeBytes(b)}
    private fun target()=File(temp.root,"out-${java.util.UUID.randomUUID()}")
    private fun bad()=CaptureMetadataFixtures.tiff().also{ByteBuffer.wrap(it).putShort(156,0)}
    private fun jpeg(e:ByteArray):ByteArray {val p="Exif\u0000\u0000".toByteArray()+e;val j=CaptureMetadataFixtures.jpeg;return j.copyOfRange(0,2)+byteArrayOf(-1,-31,((p.size+2)/256).toByte(),((p.size+2)%256).toByte())+p+j.copyOfRange(2,j.size)}
    private fun bundle(e:ByteArray?=bad(),x:List<ByteArray> = emptyList())=PreservedMetadata.Bundle(emptyList(),e,x,"JPEG")
    private fun report(block:()->Unit)=MetadataExportDiagnostics.Report().also{MetadataExportDiagnostics.collect(it,block)}
    @Test fun healthyReportHasNoError(){assertNull(MetadataExportDiagnostics.Report().description(true))}
    @Test fun savedErrorExplicitlySaysPhotoSaved(){val d=report{MetadataExportDiagnostics.record("invalid EXIF")}.description(true)!!;assertTrue(d.contains("元数据保留出错"));assertTrue(d.contains("照片已保存"));assertTrue(d.contains("invalid EXIF"))}
    @Test fun failedPublicationCannotClaimSaved(){val d=report{MetadataExportDiagnostics.record("invalid EXIF")}.description(false)!!;assertTrue(d.contains("导出未完成"));assertFalse(d.contains("照片已保存"))}
    @Test fun exceptionClassMessageAndStackAreKept(){val d=report{MetadataExportDiagnostics.record(IllegalStateException("字段类型0"))}.description(true)!!;assertTrue(d.contains("IllegalStateException"));assertTrue(d.contains("字段类型0"));assertTrue(d.contains("MetadataExportDiagnosticsTest"))}
    @Test fun duplicateReasonsDoNotFloodMonitor(){val d=report{repeat(100){MetadataExportDiagnostics.record("unique-error")}}.description(true)!!;assertEquals(1,Regex("unique-error").findAll(d).count())}
    @Test fun tooManyReasonsAreBoundedAndDisclosed(){val d=report{repeat(40){MetadataExportDiagnostics.record("error-$it-"+"x".repeat(6000))}}.description(true)!!;assertTrue(d.length<34000);assertTrue(d.contains("记录上限"));assertFalse(d.contains("error-8-"))}
    @Test fun blankReasonDoesNotInventFailure(){val d=report{MetadataExportDiagnostics.record("  ")};assertNull(d.description(true))}
    @Test fun successDoesNotEraseEarlierMetadataError(){val first=report{MetadataExportDiagnostics.record("first")}.description(true);assertEquals(first,MetadataExportDiagnostics.retain(first,MetadataExportDiagnostics.Report(),true))}
    @Test fun latestMetadataErrorReplacesPreviousReport(){val p=report{MetadataExportDiagnostics.record("latest")};assertTrue(MetadataExportDiagnostics.retain("old",p,true)!!.contains("latest"))}
    @Test fun ordinaryAndMetadataFailuresCoexist(){val s=MetadataExportDiagnostics.errors("write failed","metadata failed")!!;assertTrue(s.contains("write failed"));assertTrue(s.contains("metadata failed"))}
    @Test fun emptyCombinedErrorsStayEmpty(){assertNull(MetadataExportDiagnostics.errors(null," ",""))}
    @Test fun combinedErrorDeduplicates(){assertEquals("same",MetadataExportDiagnostics.errors("same","same"))}
    @Test fun monitorSummaryIsShortButDetailedRecordIsRetained(){val p=report{MetadataExportDiagnostics.record("x".repeat(1000))};val d=p.description(true)!!;assertTrue(MetadataExportDiagnostics.summary(d)!!.length<=180);assertTrue(d.length>1000)}
    @Test fun scopeReturnsPhotoResult(){val r=MetadataExportDiagnostics.Report();assertEquals("saved-location",MetadataExportDiagnostics.collect(r){MetadataExportDiagnostics.record("bad EXIF");"saved-location"})}
    @Test fun scopePropagatesRealIo(){val r=MetadataExportDiagnostics.Report();val e=IOException("disk");assertSame(e,assertThrows(IOException::class.java){MetadataExportDiagnostics.collect(r){throw e}})}
    @Test fun scopeIsRemovedAfterSuccess(){val r=report{MetadataExportDiagnostics.record("inside")};MetadataExportDiagnostics.record("outside");assertFalse(r.description(true)!!.contains("outside"))}
    @Test fun scopeIsRemovedAfterFailure(){val r=MetadataExportDiagnostics.Report();assertThrows(IOException::class.java){MetadataExportDiagnostics.collect(r){MetadataExportDiagnostics.record("inside");throw IOException()}};MetadataExportDiagnostics.record("outside");assertFalse(r.description(false)!!.contains("outside"))}
    @Test fun nestedScopeRestoresOuterCollector(){val outer=MetadataExportDiagnostics.Report();val inner=MetadataExportDiagnostics.Report();MetadataExportDiagnostics.collect(outer){MetadataExportDiagnostics.record("outer-before");MetadataExportDiagnostics.collect(inner){MetadataExportDiagnostics.record("inner-only")};MetadataExportDiagnostics.record("outer-after")};assertTrue(outer.description(true)!!.contains("outer-after"));assertFalse(outer.description(true)!!.contains("inner-only"));assertTrue(inner.description(true)!!.contains("inner-only"))}
    @Test fun unrelatedWorkerDoesNotContaminateExport(){val r=report{val t=Thread{MetadataExportDiagnostics.record("foreign")};t.start();t.join();MetadataExportDiagnostics.record("own")};assertFalse(r.description(true)!!.contains("foreign"))}
    @Test fun normalMetadataProducesNoFalseError(){val r=report{ExportMetadataCompatibility.prepare(bundle(CaptureMetadataFixtures.tiff()),16,12,false,Cancel())};assertNull(r.description(true))}
    @Test fun noSourceExifIsNotAFabricatedFailure(){val r=report{ExportMetadataCompatibility.prepare(bundle(null),16,12,true,Cancel())};assertNull(r.description(true))}
    @Test fun malformedExifExportsWithOriginalCause(){val target=target();val r=report{val p=ExportMetadataCompatibility.prepare(bundle(),16,12,true,Cancel());assertTrue(p.degraded);ExportMetadataCompatibility.write(file(CaptureMetadataFixtures.jpeg),target,p,16,12,true,Cancel())};assertTrue(target.isFile);val d=r.description(true)!!;assertTrue(d.contains("EXIF"));assertTrue(d.contains("类型0"));assertTrue(d.contains("照片已保存"))}
    @Test fun malformedXmlExportsAndRetainsXmlFailure(){val r=report{val p=ExportMetadataCompatibility.prepare(bundle(CaptureMetadataFixtures.tiff(),listOf("<broken".toByteArray())),16,12,true,Cancel());assertTrue(p.degraded);ExportMetadataCompatibility.write(file(CaptureMetadataFixtures.jpeg),target(),p,16,12,true,Cancel())};assertTrue(r.description(true)!!.contains("SAXParseException"))}
    @Test fun capacityFallbackReportsActualCapacityReason(){val r=report{ExportMetadataCompatibility.prepare(bundle(bad().copyOf(66000)),16,12,true,Cancel())};assertTrue(r.description(true)!!.contains("APP1容量"))}
    @Test fun interopSkipAlsoReportsBoundsError(){val e=CaptureMetadataFixtures.tiff();val b=ByteBuffer.wrap(e);b.putShort(80,8);b.putShort(166,0xa005.toShort());b.putShort(168,4);b.putInt(170,1);b.putInt(174,99999);val r=report{val p=ExportMetadataCompatibility.prepare(bundle(e),16,12,true,Cancel());assertTrue(p.degraded)};assertTrue(r.description(true)!!.contains("ReferenceBoundsException"))}
    @Test fun externalErrorDoesNotRelabelPublishedPhotoAsFailed(){val target=target();val r=report{val p=ExternalMetadataTransfer.prepareForExport(file(jpeg(bad())),16,12,true,Cancel());ExternalMetadataTransfer.write(file(CaptureMetadataFixtures.jpeg),target,p,16,12,Cancel());assertFalse(p.verified);assertFalse(p.deletionReady)};assertTrue(target.isFile);assertTrue(r.description(true)!!.contains("照片已保存"))}
    @Test fun cancellationIsNotRecoverableMetadataError(){val r=MetadataExportDiagnostics.Report();assertThrows(CancellationException::class.java){MetadataExportDiagnostics.collect(r){ExportMetadataCompatibility.optional<Unit>{throw CancellationException("stop")}}};assertNull(r.description(false))}
    @Test fun wrappedIoIsNotRecoverableMetadataError(){val r=MetadataExportDiagnostics.Report();assertThrows(IOException::class.java){MetadataExportDiagnostics.collect(r){ExportMetadataCompatibility.optional<Unit>{throw IllegalStateException(IOException("disk"))}}};assertNull(r.description(false))}
    @Test fun securityIsNotRecoverableMetadataError(){val r=MetadataExportDiagnostics.Report();assertThrows(SecurityException::class.java){MetadataExportDiagnostics.collect(r){ExportMetadataCompatibility.optional<Unit>{throw SecurityException("permission")}}};assertNull(r.description(false))}
    @Test fun vmErrorIsNotRecoverableMetadataError(){val r=MetadataExportDiagnostics.Report();assertThrows(OutOfMemoryError::class.java){MetadataExportDiagnostics.collect(r){ExportMetadataCompatibility.optional<Unit>{throw OutOfMemoryError("synthetic")}}};assertNull(r.description(false))}
    @Test fun invalidDestinationStillFailsWithoutPublishing(){val target=target();val r=MetadataExportDiagnostics.Report();assertThrows(Exception::class.java){MetadataExportDiagnostics.collect(r){val p=ExportMetadataCompatibility.prepare(bundle(),16,12,true,Cancel());ExportMetadataCompatibility.write(file("invalid destination".toByteArray()),target,p,16,12,true,Cancel())}};assertFalse(target.exists());assertFalse(r.description(false)!!.contains("照片已保存"))}
    private fun captureFieldsEqual(expected:CaptureExif,actual:CaptureExif){
        assertArrayEquals(expected.value(false,0x10f),actual.value(false,0x10f))
        for(tag in listOf(0x829a,0x920a,0xa434,0x927c))assertArrayEquals(expected.value(true,tag),actual.value(true,tag))
        for(e in expected.gps()){val a=actual.gps().single{it.tag==e.tag};assertEquals(e.type,a.type);assertEquals(e.count,a.count);assertArrayEquals(e.value,a.value)}
    }
    @Test fun unrelatedUnknownFieldDoesNotBlockReadableCaptureFields(){
        val original=CaptureExif(CaptureMetadataFixtures.tiff());lateinit var prepared:ExportMetadataCompatibility.Prepared
        val r=report{prepared=ExportMetadataCompatibility.prepare(bundle(),16,12,true,Cancel())}
        captureFieldsEqual(original,CaptureExif(prepared.exif));assertNotNull(r.description(true))
    }
    @Test fun writeReadbackPreservesFieldsAfterUnknownType(){
        val output=target();val r=report{val p=ExportMetadataCompatibility.prepare(bundle(),16,12,false,Cancel());ExportMetadataCompatibility.write(file(CaptureMetadataFixtures.jpeg),output,p,16,12,false,Cancel())}
        captureFieldsEqual(CaptureExif(CaptureMetadataFixtures.tiff()),CaptureExif(CaptureMetadataContainer.readExif(output).exif!!));assertNotNull(r.description(true))
    }
    @Test fun invalidXmpDoesNotDiscardReadableExif(){
        lateinit var p:ExportMetadataCompatibility.Prepared
        val r=report{p=ExportMetadataCompatibility.prepare(bundle(CaptureMetadataFixtures.tiff(),listOf("<broken".toByteArray())),16,12,true,Cancel())}
        captureFieldsEqual(CaptureExif(CaptureMetadataFixtures.tiff()),CaptureExif(p.exif));assertNotNull(r.description(true))
    }
    @Test fun oversizedArchiveDoesNotDiscardActiveCaptureFields(){
        val b=bundle(CaptureMetadataFixtures.tiff()).copy(packets=List(80){PreservedMetadata.Packet("J:e7",ByteArray(60000))})
        assertThrows(IllegalArgumentException::class.java){PreservedMetadata.archive(b)}
        lateinit var p:ExportMetadataCompatibility.Prepared
        val r=report{p=ExportMetadataCompatibility.prepare(b,16,12,true,Cancel())}
        captureFieldsEqual(CaptureExif(CaptureMetadataFixtures.tiff()),CaptureExif(p.exif));assertNotNull(r.description(true));assertTrue(p.degraded)
    }
    @Test fun compatibilityReportDoesNotInventCaptureFieldLoss(){
        val d=report{MetadataExportDiagnostics.record("归档容量超限")}.description(true)!!
        assertFalse(d.contains("部分拍摄信息未保留"));assertFalse(d.contains("拍摄信息无法保留"));assertTrue(d.contains("兼容异常不代表拍摄字段全部丢失"));assertTrue(d.contains("归档容量超限"))
    }
    @Test fun compactRecoveryStillKeepsStandardCaptureFields(){
        val source=bundle(bad().copyOf(66000));val p=ExportMetadataCompatibility.prepare(source,16,12,true,Cancel());val a=CaptureExif(p.exif);val original=CaptureExif(CaptureMetadataFixtures.tiff())
        for(tag in listOf(0x829a,0x920a,0xa434))assertArrayEquals(original.value(true,tag),a.value(true,tag))
        assertArrayEquals(original.value(false,0x10f),a.value(false,0x10f));assertEquals(3,a.gps().size)
    }
    @Test fun oneBadGpsFieldDoesNotDiscardReadableGpsSiblings(){
        val source=CaptureMetadataFixtures.tiff().also{ByteBuffer.wrap(it).putShort(348,0)}
        val actual=CaptureExif(ExportMetadataCompatibility.prepare(bundle(source),16,12,true,Cancel()).exif)
        val original=CaptureExif(CaptureMetadataFixtures.tiff());assertEquals(2,actual.gps().size)
        for(tag in listOf(0,1))assertArrayEquals(original.gps().single{it.tag==tag}.value,actual.gps().single{it.tag==tag}.value)
        assertArrayEquals(original.value(true,0x829a),actual.value(true,0x829a))
    }
    @Test fun absentCaptureFieldIsNotFabricatedByRecovery(){
        val actual=CaptureExif(ExportMetadataCompatibility.prepare(bundle(),16,12,true,Cancel()).exif)
        assertNull(actual.value(true,0x8827));assertNull(actual.value(true,0x9003));assertNull(actual.value(true,0x829d))
    }
}
