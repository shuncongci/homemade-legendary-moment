package xyz.shuncongci.leica

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** The photo and a scrollable settings panel are independent measured regions. */
class EditorLayout(context:Context,private val preview:View,private val controls:View):ViewGroup(context) {
    var expanded=true
        set(value){field=value;controls.visibility=if(value)VISIBLE else GONE;requestLayout()}
    init{addView(preview);addView(controls)}
    private fun plan(w:Int,h:Int)=EditorLayoutPolicy.arrange(w,h,resources.displayMetrics.density,resources.configuration.fontScale,expanded)
    override fun onMeasure(widthSpec:Int,heightSpec:Int){
        val w=MeasureSpec.getSize(widthSpec);val h=MeasureSpec.getSize(heightSpec);val p=plan(w,h)
        preview.measure(MeasureSpec.makeMeasureSpec(p.preview.w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(p.preview.h,MeasureSpec.EXACTLY))
        if(expanded)controls.measure(MeasureSpec.makeMeasureSpec(p.controls.w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(p.controls.h,MeasureSpec.EXACTLY))
        setMeasuredDimension(w,h)
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){
        val p=plan(r-l,b-t)
        preview.layout(p.preview.x,p.preview.y,p.preview.x+p.preview.w,p.preview.y+p.preview.h)
        if(expanded)controls.layout(p.controls.x,p.controls.y,p.controls.x+p.controls.w,p.controls.y+p.controls.h)
    }
}
