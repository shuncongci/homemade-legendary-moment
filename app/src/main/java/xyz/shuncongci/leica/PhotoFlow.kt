package xyz.shuncongci.leica

/** Three valid workflows; no accidental no-op mode. Color branch is lazy so blur-only never loads GAN. */
enum class ProcessingMode(val label:String,val colorEnabled:Boolean,val blurEnabled:Boolean) {
    COLOR_ONLY("仅调色",true,false), BLUR_ONLY("仅虚化",false,true), COLOR_AND_BLUR("调色＋虚化",true,true);
    fun <T> selectColorPath(color:()->T,original:()->T):T = if(colorEnabled)color() else original()
    companion object {
        fun from(color:Boolean,blur:Boolean):ProcessingMode = entries.firstOrNull{it.colorEnabled==color && it.blurEnabled==blur}
            ?: throw IllegalArgumentException("请至少选择调色或虚化")
    }
}

/** One source of truth for controls. Completion never depends on Job.isActive. */
class PhotoFlow {
    enum class Operation { IDLE, PREVIEW, FOCUS_PREVIEW, DEPTH, GENERATE, COMPOSITION, SAVE }
    var operation = Operation.IDLE; private set
    var hasPhoto = false; private set
    var hasResult = false; private set
    var saved = false; private set
    var blur = false; private set
    var colorEnabled = true; private set
    val processingMode get() = ProcessingMode.from(colorEnabled,blur)
    fun selectProcessingMode(mode:ProcessingMode) {
        check(!busy)
        if(mode==processingMode){picking=false;subjectPicking=false;return}
        colorEnabled=mode.colorEnabled;blur=mode.blurEnabled;picking=false;subjectPicking=false;parametersChanged()
    }
    var subjectEnabled=false;private set
    private val subjectDone=linkedSetOf<LegendaryMath.Point>()
    var subjectRevision=0L;private set
    data class SubjectTicket(val revision:Long,val points:List<LegendaryMath.Point>)
    fun subjectTicket()=SubjectTicket(subjectRevision,subjectPoints.toList())
    val subjectCompletedCount get()=subjectPoints.count{it in subjectDone}
    val subjectPendingCount get()=subjectPoints.size-subjectCompletedCount
    val subjectReady get()=subjectEnabled && subjectPoints.isNotEmpty() && subjectPendingCount==0
    /** Inference completion is independent of aperture-preview tickets and animation timing. */
    fun acceptSubjectCompletion(ticket:SubjectTicket,completed:List<LegendaryMath.Point>):Boolean {
        if(ticket!=subjectTicket())return false
        require(completed.all{it in ticket.points})
        subjectDone.addAll(completed);return true
    }
    var subjectPoints:List<LegendaryMath.Point> = emptyList();private set
    var subjectPicking=false;private set
    val canComputeSubjects get()=canAdjustOptics && hasPhoto && blur && subjectEnabled && focus!=null && subjectPoints.isNotEmpty()
    fun chooseSubjects(){check(!busy && hasPhoto && blur && subjectEnabled);picking=false;subjectPicking=!subjectPicking}
    fun endSubjectPicking(){subjectPicking=false}
    fun addSubjectPoint(point:LegendaryMath.Point):Boolean {
        check(!busy && hasPhoto && blur && subjectEnabled && subjectPicking)
        if(point in subjectPoints)return false
        subjectPoints=subjectPoints+point;subjectRevision++;parametersChanged();return true
    }
    fun removeSubjectPoint(point:LegendaryMath.Point):Boolean {
        check(!busy && subjectEnabled);if(point !in subjectPoints)return false
        subjectPoints=subjectPoints.filter{it!=point};subjectRevision++;parametersChanged();return true
    }
    fun moveSubjectPoint(old:LegendaryMath.Point,point:LegendaryMath.Point):Boolean {
        check(!busy && subjectEnabled);if(old !in subjectPoints || point==old)return false
        subjectPoints=subjectPoints.map{if(it==old)point else it}.distinct();subjectRevision++;parametersChanged();return true
    }
    fun undoSubjectPoint(){check(!busy && subjectEnabled);subjectPoints.lastOrNull()?.let{removeSubjectPoint(it)}}
    fun clearSubjectPoints(){check(!busy && subjectEnabled);subjectPoints=emptyList();subjectInvalidated();parametersChanged()}
    fun toggleSubject(){check(!busy);subjectEnabled=!subjectEnabled;subjectPicking=false;parametersChanged()}
    fun subjectCompleted(){check(subjectEnabled && focus!=null && subjectPoints.isNotEmpty() && !cancelling);subjectDone.addAll(subjectPoints)}
    fun subjectInvalidated(){subjectDone.clear();subjectRevision++}
    var focus: LegendaryMath.Point? = null; private set
    var picking = false; private set
    var aperture = 2.8f; private set
    var resultStale = false; private set
    val canAdjustOptics get() = !busy || (operation==Operation.FOCUS_PREVIEW && !cancelling)
    fun outputChanged(){parametersChanged();if(hasResult)saved=false}
    fun parametersChanged(){if(hasResult)resultStale=true}
    var cancelling = false; private set
    val busy get() = operation != Operation.IDLE
    val canGenerate get() = !busy && hasPhoto && (!blur || focus != null) && !picking && !subjectPicking && (!blur || !subjectEnabled || subjectReady)
    val canSave get() = !busy && hasResult && !saved
    fun begin(op: Operation) { check(!busy); operation=op; picking=false;subjectPicking=false; cancelling=false }
    fun finish() { operation=Operation.IDLE; cancelling=false }
    fun photoLoaded() { hasPhoto=true;hasResult=false;saved=false;resultStale=false;focus=null;picking=false;subjectInvalidated();subjectPoints=emptyList();subjectPicking=false }
    fun photoFailed() { hasPhoto=false;hasResult=false;saved=false;resultStale=false;focus=null;picking=false;subjectInvalidated();subjectPoints=emptyList();subjectPicking=false }
    fun toggleBlur() { check(!busy);blur=!blur;if(!blur)colorEnabled=true;picking=false;subjectPicking=false;parametersChanged() }
    fun chooseFocus() { check(!busy && hasPhoto && blur);subjectPicking=false;picking=!picking }
    fun picked(point: LegendaryMath.Point) { check(!busy && blur && hasPhoto);focus=point;picking=false;subjectPicking=false;subjectInvalidated();parametersChanged() }
    fun aperture(value: Float) { check(canAdjustOptics);FullFrameOptics.checkAperture(value);if(aperture!=value){aperture=value;parametersChanged()} }
    fun generated() { hasResult=true;saved=false;resultStale=false }
    fun compositionApplied() { check(!busy && hasPhoto);hasResult=true;saved=false;picking=false;subjectPicking=false }
    fun saved() { saved=true }
    fun focusUnreliable() { check(operation==Operation.FOCUS_PREVIEW);focus=null;picking=false;subjectInvalidated() }
    fun cancel() { check(operation==Operation.GENERATE || operation==Operation.FOCUS_PREVIEW || operation==Operation.DEPTH || operation==Operation.COMPOSITION);cancelling=true }
}
