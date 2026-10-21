package xyz.shuncongci.leica

/** Main-thread request + publication gate. Optical preview completion is not subject completion.
 * Cache-only reads carry an epoch: an older read cannot overwrite a newly computed overlay. */
class SubjectPreviewWork {
    private var requested:PhotoFlow.SubjectTicket?=null
    private var presented:PhotoFlow.SubjectTicket?=null
    var epoch=0L;private set
    fun needs(ticket:PhotoFlow.SubjectTicket)=requested==ticket
    fun isPresented(ticket:PhotoFlow.SubjectTicket)=presented==ticket
    fun request(ticket:PhotoFlow.SubjectTicket){require(ticket.points.isNotEmpty());requested=ticket;changed()}
    fun changed(){epoch++}
    fun acceptsRead(readEpoch:Long)=readEpoch==epoch
    fun invalidate(){requested=null;presented=null;changed()}
    fun abort(){requested=null;changed()}
    /** Only an actual complete cache snapshot AND its overlay can consume the explicit request. */
    fun publish(ticket:PhotoFlow.SubjectTicket,completed:List<LegendaryMath.Point>,hasOverlay:Boolean){
        check(ticket.points.isNotEmpty() && completed.toSet()==ticket.points.toSet() && hasOverlay){"保护结果尚未完整应用，请重新计算待完成点"}
        presented=ticket;if(requested==ticket)requested=null;changed()
    }
}
