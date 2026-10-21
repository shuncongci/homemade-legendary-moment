package xyz.shuncongci.leica

class LegacyRangeFlow {
    enum class Operation { IDLE, PREVIEW, FOCUS_PREVIEW, GENERATE, SAVE }
    var operation = Operation.IDLE; private set
    var hasPhoto = false; private set
    var hasResult = false; private set
    var saved = false; private set
    var blur = false; private set
    var colorEnabled = true; private set
    val processingMode get() = ProcessingMode.from(colorEnabled,blur)
    fun selectProcessingMode(mode:ProcessingMode) {
        check(!busy)
        colorEnabled=mode.colorEnabled;blur=mode.blurEnabled;picking=false;parametersChanged()
    }
    var focus: LegendaryMath.Point? = null; private set
    var picking = false; private set
    var aperture = 2.8f; private set
    var focusRangeSteps = 0; private set
    var resultStale = false; private set
    val canAdjustOptics get() = !busy || (operation==Operation.FOCUS_PREVIEW && !cancelling)
    fun parametersChanged(){if(hasResult)resultStale=true}
    fun focusRange(steps:Int){check(canAdjustOptics);FocusRangeMath.checkSteps(steps);if(steps!=focusRangeSteps){focusRangeSteps=steps;parametersChanged()}}
    var cancelling = false; private set
    val busy get() = operation != Operation.IDLE
    val canGenerate get() = !busy && hasPhoto && (!blur || focus != null) && !picking
    val canSave get() = !busy && hasResult && !saved
    fun begin(op: Operation) { check(!busy); operation=op; picking=false; cancelling=false }
    fun finish() { operation=Operation.IDLE; cancelling=false }
    fun photoLoaded() { hasPhoto=true;hasResult=false;saved=false;resultStale=false;focus=null;picking=false;focusRangeSteps=0 }
    fun photoFailed() { hasPhoto=false;hasResult=false;saved=false;resultStale=false;focus=null;picking=false;focusRangeSteps=0 }
    fun toggleBlur() { check(!busy);blur=!blur;if(!blur)colorEnabled=true;picking=false;parametersChanged() }
    fun chooseFocus() { check(!busy && hasPhoto && blur);picking=!picking }
    fun picked(point: LegendaryMath.Point) { check(!busy && blur && hasPhoto);focus=point;picking=false;parametersChanged() }
    fun aperture(value: Float) { check(canAdjustOptics);LegendaryMath.gain(value);if(aperture!=value){aperture=value;parametersChanged()} }
    fun generated() { hasResult=true;saved=false;resultStale=false }
    fun saved() { saved=true }
    fun focusUnreliable() { check(operation==Operation.FOCUS_PREVIEW);focus=null;picking=false }
    fun cancel() { check(operation==Operation.GENERATE || operation==Operation.FOCUS_PREVIEW);cancelling=true }
}
