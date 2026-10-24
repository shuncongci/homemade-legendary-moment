package xyz.shuncongci.leica

/** Menu options only; codecs and the user's remembered SDR format stay unchanged.
 * [hdrReady] describes the actual export target, not merely the originally imported photo. */
object ExportMenuChoices {
    const val ULTRA_HDR_LABEL="Ultra HDR JPG"
    data class Option(val format:ExportFormat?) {
        val hdr get()=format==null
        val label get()=format?.label?:ULTRA_HDR_LABEL
        fun selected(current:ExportFormat)=hdr || format==current
    }
    fun available(hdrReady:Boolean,sdk:Int):List<Option> =
        if(hdrReady)listOf(Option(null)) else ExportFormat.available(sdk).map{Option(it)}
}
