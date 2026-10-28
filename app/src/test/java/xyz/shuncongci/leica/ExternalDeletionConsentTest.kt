package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ExternalDeletionConsentTest {
    private val delete=ExternalOriginalPolicy.DELETE_AFTER_SAVE
    @Test fun oldChoiceNeedsNewConsent(){assertEquals(ExternalOriginalPolicy.KEEP,ExternalDeletionConsent.restored(delete,0))}
    @Test fun unknownConsentIsNotAccepted(){assertEquals(ExternalOriginalPolicy.KEEP,ExternalDeletionConsent.restored(delete,999))}
    @Test fun currentChoiceRestores(){assertEquals(delete,ExternalDeletionConsent.restored(delete,ExternalDeletionConsent.VERSION))}
    @Test fun keepRemainsKeep(){assertEquals(ExternalOriginalPolicy.KEEP,ExternalDeletionConsent.restored(ExternalOriginalPolicy.KEEP,1))}
    @Test fun defaultCannotRemove(){assertFalse(ExternalDeletionConsent.canRemove(ExternalOriginalPolicy.KEEP,1,true))}
    @Test fun unconfirmedCannotRemove(){assertFalse(ExternalDeletionConsent.canRemove(delete,0,true))}
    @Test fun unverifiedCannotRemove(){assertFalse(ExternalDeletionConsent.canRemove(delete,1,false))}
    @Test fun verifiedAndConfirmedCanRemove(){assertTrue(ExternalDeletionConsent.canRemove(delete,1,true))}
    private fun incompleteExport(encoded:ByteArray){
        val d=Files.createTempDirectory("consented-delete-").toFile()
        try{
            val source=File(d,"source").apply{writeBytes(CaptureMetadataFixtures.jpeg+"motion-video".toByteArray())}
            val before=source.readBytes();val p=ExternalMetadataTransfer.prepare(source,16,12,true,Cancel())
            assertFalse(p.complete);assertFalse(ExternalDeletionConsent.canRemove(delete,1,p.verified))
            val plain=File(d,"plain").apply{writeBytes(encoded)}
            ExternalMetadataTransfer.write(plain,File(d,"out"),p,16,12,Cancel())
            assertFalse(p.complete);assertFalse(p.deletionReady);assertTrue(p.verified)
            assertTrue(ExternalDeletionConsent.canRemove(delete,1,p.verified))
            var calls=0
            ExternalOriginalGuard.afterPublished(delete,true,Cancel(),{true},{true},{true},{calls++;OriginalOutcome(OriginalAction.DELETED,"synthetic only")})
            assertEquals(1,calls);assertArrayEquals(before,source.readBytes())
        }finally{d.deleteRecursively()}
    }
    @Test fun incompleteJpegCanUseExplicitConsent(){incompleteExport(CaptureMetadataFixtures.jpeg)}
    @Test fun incompletePngCanUseExplicitConsent(){incompleteExport(CaptureMetadataFixtures.png)}
    @Test fun incompleteWebpCanUseExplicitConsent(){incompleteExport(CaptureMetadataFixtures.webp)}
    @Test fun incompleteLosslessCanUseExplicitConsent(){incompleteExport(CaptureMetadataFixtures.losslessWebp)}
    private fun guard(published:Boolean=true,active:Boolean=true,source:Boolean=true,output:Boolean=true,cancel:Cancel=Cancel()):Int{
        var calls=0
        ExternalOriginalGuard.afterPublished(delete,published,cancel,{active},{source},{output},{calls++;OriginalOutcome(OriginalAction.DELETED,"synthetic only")})
        return calls
    }
    @Test fun consentNeverSkipsPublication(){assertEquals(0,guard(published=false))}
    @Test fun consentNeverSkipsSourceFingerprint(){assertEquals(0,guard(source=false))}
    @Test fun consentNeverSkipsOutputFingerprint(){assertEquals(0,guard(output=false))}
    @Test fun stoppedRunCannotRemove(){assertEquals(0,guard(active=false))}
    @Test fun cancelledRunCannotRemove(){assertThrows(java.util.concurrent.CancellationException::class.java){guard(cancel=Cancel().also{it.cancelled=true})}}
    @Test fun noticeNamesLossAndPermanentDeletion(){
        val t=ExternalDeletionConsent.MESSAGE
        for(s in listOf("我建议","动态照片","HDR","人像","拍摄信息","永久删除","系统","不补删"))assertTrue(s,t.contains(s))
        assertTrue(ExternalDeletionConsent.ACCEPT.contains("我已了解"))
    }
}
