package xyz.shuncongci.leica

import kotlin.math.roundToInt

/** Display-only geometry. A collapsed overlay has no layout reservation and never resizes the live image. */
object WorkspaceMotion {
    fun panel(w:Int,h:Int,density:Float,font:Float):EditorLayoutPolicy.Layout {
        require(w>=0 && h>=0 && density>0 && density.isFinite() && font>0 && font.isFinite())
        val side=w/density>=600 && w>h*1.15f
        val controls=if(side){
            val cw=(w*(.50f+.04f*(font-1).coerceIn(0f,1f))).roundToInt()
            EditorLayoutPolicy.Rect(w-cw,0,cw,h)
        }else{
            val ch=FullscreenMotion.panelHeight(h,font)
            EditorLayoutPolicy.Rect(0,h-ch,w,ch)
        }
        return EditorLayoutPolicy.Layout(EditorLayoutPolicy.Rect(0,0,w,h),controls,side)
    }
    /** Keep top/bottom-origin surfaces inside the CURRENT window, not display metrics. */
    fun place(safe:ExportMotion.Rect,anchor:ExportMotion.Rect,width:Float,height:Float,below:Boolean):ExportMotion.Rect {
        require(width>0 && height>0 && width.isFinite() && height.isFinite())
        val w=width.coerceAtMost(safe.width);val h=height.coerceAtMost(safe.height)
        val x=(if(anchor.left+ w<=safe.right)anchor.left else anchor.right-w).coerceIn(safe.left,safe.right-w)
        val y=(if(below)anchor.bottom+8 else anchor.top-h-8).coerceIn(safe.top,safe.bottom-h)
        return ExportMotion.Rect(x,y,x+w,y+h)
    }
    fun content(p:Float)=ExportMotion.smooth((p-.12f)/.88f)
    fun duration(from:Float,to:Float):Long {
        require(from.isFinite() && to.isFinite() && from in 0f..1f && to in 0f..1f)
        return ((if(to>from)340 else 280)*kotlin.math.abs(to-from)).toLong().coerceAtLeast(1)
    }
    fun modeForGesture(start:Float,end:Float,width:Float,swiping:Boolean):HdrDevelopMode {
        require(start.isFinite() && end.isFinite() && width.isFinite() && width>0)
        val hdr=if(swiping)end>start else end>=width/2
        return if(hdr)HdrDevelopMode.HDR else HdrDevelopMode.SDR
    }
    /** Desired segment width is always bounded by this row's actual inset-adjusted width. */
    fun modeWidth(available:Int,density:Float,font:Float):Int {
        require(available>=0 && density>0 && density.isFinite() && font>0 && font.isFinite())
        return (132*density*font.coerceIn(1f,2.5f)).roundToInt().coerceIn(0,available)
    }
    fun irisRadius(width:Float,height:Float,density:Float):Float {
        require(width>=0 && height>=0 && density>0 && width.isFinite() && height.isFinite() && density.isFinite())
        return minOf(height*.34f,width*.19f,38*density).coerceAtLeast(0f)
    }
    val viewers=listOf("ORIGINAL","THERMAL","RELIEF","RESULT","COMPARE")
}
