package xyz.shuncongci.leica

import java.security.MessageDigest

/** External mode is color-only. These IDs are persisted independently of the editor. */
enum class ExternalExportPolicy(val id:String,val label:String) {
    SDR("sdr","全部导出 SDR"), AUTO_HDR("auto-hdr","优先 HDR，不支持时导出 SDR");
    fun mode()=if(this==SDR)HdrDevelopMode.SDR else HdrDevelopMode.AUTO
    companion object { fun fromId(id:String?)=entries.firstOrNull{it.id==id}?:SDR }
}

data class ExternalPhoto(val key:String,val uri:String,val name:String,val size:Long,val modified:Long,val pending:Boolean=false,val created:Long=0) {
    val id:String get()=ExternalModePolicy.hash("$key|$size|$modified")
    fun sameBytes(other:ExternalPhoto)=key==other.key && size==other.size && modified==other.modified && !other.pending
}

object ExternalModePolicy {
    const val OUTPUT_PREFIX="传奇一瞬-外接-"
    const val OUTPUT_FOLDER="Pictures/传奇一瞬/外接/"
    const val STABLE_MS=3000L
    const val MAX_ENTRIES=100000
    fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    fun image(name:String):Boolean {
        if(name.startsWith('.') || name.startsWith(OUTPUT_PREFIX) || name.endsWith(".part",true))return false
        return name.substringAfterLast('.',"").lowercase(java.util.Locale.ROOT) in setOf("jpg","jpeg","png","webp","heic","heif","dng")
    }
    fun cameraScore(path:String):Int {
        val p=path.replace('\\','/').trim('/').lowercase(java.util.Locale.ROOT)
        return when {
            p=="dcim/camera" || p.endsWith("/dcim/camera")->100
            p.endsWith("/camera") || p=="camera"->80
            p.contains("dcim/")->60
            else->0
        }
    }
    fun outputPath(path:String)=path.replace('\\','/').trim('/').lowercase(java.util.Locale.ROOT).let {
        it=="pictures/传奇一瞬" || it.startsWith("pictures/传奇一瞬/") || it.contains("/pictures/传奇一瞬/") || it.endsWith("/pictures/传奇一瞬")
    }
    fun outputName(id:String,hdr:Boolean,format:ExportFormat):String {
        require(id.matches(Regex("[a-f0-9]{64}")))
        return "$OUTPUT_PREFIX$id.${if(hdr)"jpg" else format.extension}"
    }
    fun shouldRecoverAsDone(published:Boolean,exists:Boolean)=exists && published
    /** MediaStore row ids only grow. Rows at or below the baseline maximum existed before listening
     *  (or are old rows edited later), so incremental scans query `_ID > cursor` only. */
    fun mediaCursor(baseline:List<ExternalPhoto>):Long=baseline.maxOfOrNull{it.uri.substringAfterLast('/').toLongOrNull()?:0L}?:0L
    /** Moves the row cursor past rows that are finished ([done]: admitted/known or never an image), but never past
     *  a row still settling or pending, so each new row is queried only until it is decided. Never moves back. */
    fun advanceMediaCursor(cursor:Long,scanned:List<ExternalPhoto>,done:(ExternalPhoto)->Boolean):Long {
        var top=cursor;var blocked=Long.MAX_VALUE
        for(p in scanned){
            val id=p.uri.substringAfterLast('/').toLongOrNull()?:continue
            if(id<=cursor)continue
            if(done(p))top=maxOf(top,id) else blocked=minOf(blocked,id)
        }
        return if(blocked==Long.MAX_VALUE)top else maxOf(cursor,minOf(top,blocked-1))
    }
}

/** Scan cadence. Short only while a new photo is settling; otherwise change notifications drive scans and
 *  the timer is just a safety net, so an idle session stops re-listing the camera folder every 2 s. */
object ExternalScanCadence {
    const val SETTLING_MS=1000L
    const val DIRECTORY_IDLE_MS=5000L
    const val MEDIA_IDLE_MS=10000L
    const val UNOBSERVED_IDLE_MS=3000L
    fun next(settling:Boolean,kind:ExternalFolderKind,notified:Boolean):Long=when{
        settling->SETTLING_MS
        !notified->UNOBSERVED_IDLE_MS
        kind==ExternalFolderKind.MEDIA->MEDIA_IDLE_MS
        else->DIRECTORY_IDLE_MS
    }
}

/** Metadata only. Baseline must finish BEFORE the UI says listening; edits to old files are not arrivals.
 * No sampled/decode-preview pixels are ever used as a rendered output. */
class ExternalArrivalGate(baseline:List<ExternalPhoto>,private val notBeforeWall:Long=0) {
    private data class Observation(val photo:ExternalPhoto,val since:Long,val count:Int)
    private val known=baseline.mapTo(HashSet()){it.key}
    private val settling=HashMap<String,Observation>()
    init { require(known.size<=ExternalModePolicy.MAX_ENTRIES){"目录照片过多，请选择更具体的相机目录"} }
    /** Incremental scans may omit known keys entirely: they can never become work. */
    fun known(key:String)=key in known
    /** While a candidate settles the caller keeps the short cadence; otherwise it may wait for change notifications. */
    val isSettling:Boolean get()=settling.isNotEmpty() || pendingSeen
    private var pendingSeen=false
    fun observe(photos:List<ExternalPhoto>,now:Long):List<ExternalPhoto> {
        require(photos.size<=ExternalModePolicy.MAX_ENTRIES){"目录照片过多，请缩小监听范围"}
        pendingSeen=false
        val present=photos.mapTo(HashSet()){it.key}
        settling.keys.retainAll(present)
        val ready=ArrayList<ExternalPhoto>()
        for(p in photos){
            if(p.key in known || !ExternalModePolicy.image(p.name))continue
            // Delayed MediaStore indexing of a pre-start photo must not turn it into a new capture.
            if(p.created>0 && p.created<notBeforeWall){known+=p.key;continue}
            check(known.size+settling.size<ExternalModePolicy.MAX_ENTRIES){"本次监听条目已达上限，请停止后重新建立基线"}
            if(p.pending || p.size<=0){settling.remove(p.key);pendingSeen=true;continue}
            val before=settling[p.key]
            val o=if(before==null || !p.sameBytes(before.photo))Observation(p,now,1) else Observation(p,before.since,before.count+1)
            settling[p.key]=o
            if(o.count>=3 && now-o.since>=ExternalModePolicy.STABLE_MS){
                ready+=p;known+=p.key;settling.remove(p.key)
            }
        }
        return ready.sortedWith(compareBy<ExternalPhoto>{it.modified}.thenBy{it.key})
    }
}
