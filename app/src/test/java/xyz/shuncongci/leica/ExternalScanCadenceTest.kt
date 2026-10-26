package xyz.shuncongci.leica

import org.junit.Assert.*
import org.junit.Test

/** Incremental scanning must reduce storage reads without changing which photos become work. */
class ExternalScanCadenceTest {
    private fun photo(id:String,size:Long=100,modified:Long=10,pending:Boolean=false)=ExternalPhoto("/DCIM/Camera/$id","file:///DCIM/Camera/$id","$id.jpg",size,modified,pending)
    private fun media(row:Long)=ExternalPhoto("content://media/external_primary/images/media/$row","content://media/external_primary/images/media/$row","IMG_$row.jpg",100,10)

    @Test fun settlingUsesShortCadence(){
        for(kind in ExternalFolderKind.entries){
            assertEquals(ExternalScanCadence.SETTLING_MS,ExternalScanCadence.next(true,kind,true))
            assertEquals(ExternalScanCadence.SETTLING_MS,ExternalScanCadence.next(true,kind,false))
        }
    }
    @Test fun idleWithoutNotificationsStaysResponsive()=assertEquals(ExternalScanCadence.UNOBSERVED_IDLE_MS,ExternalScanCadence.next(false,ExternalFolderKind.MEDIA,false))
    @Test fun idleMediaWithNotificationsBacksOff()=assertEquals(ExternalScanCadence.MEDIA_IDLE_MS,ExternalScanCadence.next(false,ExternalFolderKind.MEDIA,true))
    @Test fun idleDirectoryWithNotificationsBacksOff(){
        assertEquals(ExternalScanCadence.DIRECTORY_IDLE_MS,ExternalScanCadence.next(false,ExternalFolderKind.DIRECTORY,true))
        assertEquals(ExternalScanCadence.DIRECTORY_IDLE_MS,ExternalScanCadence.next(false,ExternalFolderKind.TREE,true))
    }
    @Test fun idleIsSlowerThanOldFixedTwoSeconds(){
        for(kind in ExternalFolderKind.entries)for(n in listOf(true,false))assertTrue(ExternalScanCadence.next(false,kind,n)>2000)
    }
    @Test fun settlingStillReachesStableWindowWithinFourScans(){
        // 3 observations spanning STABLE_MS must fit the settling cadence without extra delay.
        assertTrue(ExternalScanCadence.SETTLING_MS*3>=ExternalModePolicy.STABLE_MS)
        val g=ExternalArrivalGate(emptyList());val p=photo("new")
        var now=0L;var ready=emptyList<ExternalPhoto>();var scans=0
        while(ready.isEmpty() && scans<8){ready=g.observe(listOf(p),now);now+=ExternalScanCadence.SETTLING_MS;scans++}
        assertEquals(listOf(p),ready);assertTrue(scans<=4)
    }

    @Test fun mediaCursorIsBaselineMaximumRow()=assertEquals(42L,ExternalModePolicy.mediaCursor(listOf(media(7),media(42),media(13))))
    @Test fun mediaCursorEmptyBaselineReadsEverything()=assertEquals(0L,ExternalModePolicy.mediaCursor(emptyList()))
    @Test fun mediaCursorIgnoresNonNumericKeys()=assertEquals(5L,ExternalModePolicy.mediaCursor(listOf(photo("a"),media(5))))
    @Test fun mediaCursorDirectoryBaselineIsZero()=assertEquals(0L,ExternalModePolicy.mediaCursor(listOf(photo("IMG_1"),photo("IMG_2"))))
    @Test fun cursorAdvancesPastDecidedRows()=assertEquals(45L,ExternalModePolicy.advanceMediaCursor(42,listOf(media(43),media(45))){true})
    @Test fun cursorStopsBeforeSettlingRow()=assertEquals(43L,ExternalModePolicy.advanceMediaCursor(42,listOf(media(43),media(44),media(45))){it!=media(44)})
    @Test fun cursorNeverMovesBack(){
        assertEquals(42L,ExternalModePolicy.advanceMediaCursor(42,listOf(media(43))){false})
        assertEquals(42L,ExternalModePolicy.advanceMediaCursor(42,listOf(media(7),photo("a"))){true})
        assertEquals(42L,ExternalModePolicy.advanceMediaCursor(42,emptyList()){true})
    }
    @Test fun cursorWithGateQueriesEachNewRowOnlyUntilAdmitted(){
        // Simulates the service loop: query `_ID > cursor`, skip known, observe, advance.
        val rows=listOf(media(43),media(44));val g=ExternalArrivalGate(listOf(media(42)))
        var cursor=ExternalModePolicy.mediaCursor(listOf(media(42)));var now=0L;val admitted=ArrayList<ExternalPhoto>()
        repeat(6){
            val scanned=rows.filter{(it.uri.substringAfterLast('/').toLong())>cursor && !g.known(it.key)}
            admitted+=g.observe(scanned,now)
            cursor=ExternalModePolicy.advanceMediaCursor(cursor,scanned){g.known(it.key) || !ExternalModePolicy.image(it.name)}
            now+=ExternalScanCadence.SETTLING_MS
        }
        assertEquals(rows,admitted);assertEquals(44L,cursor)
    }

    @Test fun knownReportsBaselineAndEmittedOnly(){
        val old=photo("old");val p=photo("new");val g=ExternalArrivalGate(listOf(old))
        assertTrue(g.known(old.key));assertFalse(g.known(p.key))
        g.observe(listOf(old,p),0);g.observe(listOf(old,p),1500);assertEquals(listOf(p),g.observe(listOf(old,p),3000))
        assertTrue(g.known(p.key))
    }
    @Test fun skippingKnownKeysYieldsSameArrivals(){
        // Full listing vs incremental listing (known keys removed before observe) admit identical work.
        val old=(1..50).map{photo("old$it")};val news=listOf(photo("n1"),photo("n2"))
        val full=ExternalArrivalGate(old);val inc=ExternalArrivalGate(old)
        val all=old+news
        for(t in listOf(0L,1500L,3000L)){
            val a=full.observe(all,t);val b=inc.observe(all.filterNot{inc.known(it.key)},t)
            assertEquals(a,b)
        }
        assertTrue(all.all{inc.known(it.key)})
        assertTrue(inc.observe(all.filterNot{inc.known(it.key)},6000).isEmpty())
    }
    @Test fun settlingFlagTracksCandidates(){
        val g=ExternalArrivalGate(emptyList());val p=photo("new")
        assertFalse(g.isSettling)
        g.observe(listOf(p),0);assertTrue(g.isSettling)
        g.observe(listOf(p),1500);g.observe(listOf(p),3000);assertFalse(g.isSettling)
    }
    @Test fun pendingWriteKeepsShortCadence(){
        val g=ExternalArrivalGate(emptyList())
        g.observe(listOf(photo("new",pending=true)),0);assertTrue(g.isSettling)
        g.observe(listOf(photo("empty",size=0)),1000);assertTrue(g.isSettling)
        g.observe(emptyList(),2000);assertFalse(g.isSettling)
    }
    @Test fun vanishedCandidateStopsSettling(){
        val g=ExternalArrivalGate(emptyList());g.observe(listOf(photo("new")),0);g.observe(emptyList(),1000);assertFalse(g.isSettling)
    }
}
