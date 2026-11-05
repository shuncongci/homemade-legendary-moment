package xyz.shuncongci.leica

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import androidx.core.graphics.PathParser

/** Video-only opt-in. Keeps StudioButton's ripple, morph capture, tint and accessibility;
 * never installs a left/top compound drawable or changes photo button drawing. */
internal class VideoIconButton(context:Context):StudioButton(context) {
    private var glyph:WorkspaceGlyph?=null
    private var ink=VideoIconGeometry.Ink(0f,0f,24f,24f)
    private val side=(22*resources.displayMetrics.density+.5f).toInt().coerceAtLeast(1)
    fun setGlyph(kind:WorkspaceGlyph.Kind) {
        val spec=WorkspaceIcons.spec(kind.name)
        val bounds=RectF()
        var first=true
        for((data,stroke) in listOf(spec.outline to .85f,spec.solid to 0f)) {
            if(data.isEmpty())continue
            val path=requireNotNull(PathParser.createPathFromPathData(data))
            val b=RectF()
            @Suppress("DEPRECATION")
            path.computeBounds(b,true)
            b.inset(-stroke,-stroke)
            if(first){bounds.set(b);first=false}else bounds.union(b)
        }
        check(!first)
        ink=VideoIconGeometry.Ink(bounds.left,bounds.top,bounds.right,bounds.bottom)
        glyph=WorkspaceGlyph(kind){currentTextColor}.apply { setBounds(0,0,side,side) }
        setCompoundDrawables(null,null,null,null)
        compoundDrawablePadding=0
        invalidate()
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val g=glyph ?: return
        val origin=VideoIconGeometry.origin(width,height,side,ink)
        val save=canvas.save()
        canvas.translate(origin.x,origin.y)
        g.draw(canvas)
        canvas.restoreToCount(save)
    }
}
