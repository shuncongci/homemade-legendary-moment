package xyz.shuncongci.leica

import org.junit.Test
import org.junit.Assert.*

class PhotoImportPolicyTest {
    @Test fun pickerDoesNotRequestMediaLocation(){assertFalse(PhotoImportPolicy.original(35,"content","media","picker"))}
    @Test fun pickerDetected(){assertTrue(PhotoImportPolicy.picker("content","media","picker"))}
    @Test fun getContentPickerDetected(){assertTrue(PhotoImportPolicy.picker("content","media","picker_get_content"))}
    @Test fun transcodedPickerDetected(){assertTrue(PhotoImportPolicy.picker("content","media","picker_transcoded"))}
    @Test fun userScopedPickerDetected(){assertTrue(PhotoImportPolicy.picker("content","10@media","picker"))}
    @Test fun regularMediaStillNeedsOriginal(){assertTrue(PhotoImportPolicy.original(35,"content","media","external"))}
    @Test fun oldSdkUnchanged(){assertFalse(PhotoImportPolicy.original(28,"content","media","external"))}
    @Test fun documentsStayOrdinary(){assertFalse(PhotoImportPolicy.original(35,"content","com.android.providers.media.documents","document"))}
    @Test fun localFileUnchanged(){assertFalse(PhotoImportPolicy.original(35,"file",null,"storage"))}
    @Test fun similarlyNamedProviderNotPicker(){assertFalse(PhotoImportPolicy.picker("content","other.media","picker"))}
    @Test fun busySelectionRetained(){val q=PendingPhotoImport();q.offer("content://media/picker/0/p/media/1");assertNull(q.ready(true,true));assertNotNull(q.pending);assertEquals(q.pending,q.ready(false,true))}
    @Test fun pausedSelectionRetained(){val q=PendingPhotoImport();q.offer("a");assertNull(q.ready(false,false));assertEquals("a",q.ready(false,true)!!.uri)}
    @Test fun consentNotInvented(){val q=PendingPhotoImport();q.offer("a");assertFalse(q.pending!!.renditionAccepted);q.offer("a",true);assertTrue(q.pending!!.renditionAccepted)}
    @Test fun cancelClearsOnlyPending(){val q=PendingPhotoImport();q.offer("a");q.clear();assertNull(q.ready(false,true))}
    @Test fun stateRoundTrip(){val q=PendingPhotoImport();q.offer("a",true);val s=q.pending!!;val restored=PendingPhotoImport();restored.offer(s.uri,s.renditionAccepted);assertEquals(s,restored.ready(false,true))}
    @Test fun newSelectionWinsExplicitly(){val q=PendingPhotoImport();q.offer("a",true);q.offer("b");assertEquals("b",q.pending!!.uri);assertFalse(q.pending!!.renditionAccepted)}
    @Test fun missingSourceErrorVisible(){assertTrue(UiText.failure(IllegalArgumentException(PhotoImportPolicy.MISSING)).contains(PhotoImportPolicy.MISSING))}
    @Test fun revokedGrantErrorVisible(){assertTrue(UiText.failure(IllegalArgumentException(PhotoImportPolicy.PERMISSION)).contains(PhotoImportPolicy.PERMISSION))}
    @Test fun emptySourceErrorVisible(){assertTrue(UiText.failure(IllegalArgumentException(PhotoImportPolicy.EMPTY)).contains(PhotoImportPolicy.EMPTY))}
    @Test fun unknownExceptionStillPrivate(){assertFalse(UiText.failure(IllegalStateException("secret-path")).contains("secret-path"))}
}
