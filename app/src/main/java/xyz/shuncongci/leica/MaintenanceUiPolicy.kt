package xyz.shuncongci.leica

import kotlin.math.roundToInt

/** Small display-only rules shared by Android layout and local geometry/state regressions. */
internal object MaintenanceUiPolicy {
    fun spaceToolGap(fontScale:Float)=(12*fontScale.coerceIn(1f,2f)).roundToInt()
    fun hideParameterSource(expanded:Boolean,animating:Boolean)=expanded || animating
}
