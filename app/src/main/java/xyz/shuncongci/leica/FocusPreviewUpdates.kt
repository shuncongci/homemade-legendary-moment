package xyz.shuncongci.leica

/** Main-thread latest-only gate. Never cancel/restart expensive depth just because a slider moved. */
class FocusPreviewUpdates {
    private var requested=0L
    private var published=0L
    val ticket get()=requested
    val pending get()=requested!=published
    fun request():Long {requested++;return requested}
    fun publish(ticket:Long):Boolean {
        if(ticket!=requested)return false
        published=ticket;return true
    }
    fun discard(){requested++;published=requested}
}
