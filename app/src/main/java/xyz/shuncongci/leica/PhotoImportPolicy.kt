package xyz.shuncongci.leica

/** Photo Picker grants are NOT ordinary MediaStore entries. Never attach requireOriginal
 * or request ACCESS_MEDIA_LOCATION for the picker provider's rendition. The UI asks consent
 * before importing a potentially metadata-redacted rendition; file/ordinary-media rules stay. */
internal object PhotoImportPolicy {
    fun picker(scheme:String?,authority:String?,firstSegment:String?):Boolean =
        scheme=="content" && authority?.substringAfterLast('@')=="media" &&
            (firstSegment=="picker" || firstSegment?.startsWith("picker_")==true)
    fun original(sdk:Int,scheme:String?,authority:String?,firstSegment:String?):Boolean =
        sdk>=29 && scheme=="content" && authority=="media" && !picker(scheme,authority,firstSegment)
    const val MISSING="照片来源已失效或文件尚未下载。请用“系统文件”重新选择本机照片；不会改动原图。"
    const val PERMISSION="照片读取授权已失效，请用“系统文件”重新选择照片。"
    const val EMPTY="所选照片为空，请选择已下载到本机的照片"
    val publicMessages=setOf(MISSING,PERMISSION,EMPTY)
}

/** Main-thread, single-selection mailbox: a busy editor never silently drops a callback. */
internal class PendingPhotoImport {
    data class Selection(val uri:String,val renditionAccepted:Boolean)
    var pending:Selection?=null;private set
    fun offer(uri:String,renditionAccepted:Boolean=false){require(uri.isNotEmpty());pending=Selection(uri,renditionAccepted)}
    fun ready(busy:Boolean,resumed:Boolean):Selection?=if(busy || !resumed)null else pending
    fun clear(){pending=null}
}
