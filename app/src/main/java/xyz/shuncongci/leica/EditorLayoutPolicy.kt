package xyz.shuncongci.leica

/** Pure geometry shared with the native ViewGroup; panels scroll instead of stealing all preview space. */
object EditorLayoutPolicy {
    data class Rect(val x:Int,val y:Int,val w:Int,val h:Int)
    data class Layout(val preview:Rect,val controls:Rect,val sideBySide:Boolean)
    /** Expanded parameters reserve preview height: remove the entire space toolbar, including its gap. */
    fun showSpaceTools(spaceMode:Boolean,depthReady:Boolean,expanded:Boolean,composing:Boolean,busy:Boolean,loading:Boolean)=
        spaceMode && depthReady && !expanded && !composing && !busy && !loading
    fun compactChrome(heightDp:Int,fontScale:Float)=heightDp<500 || (fontScale>=1.5f && heightDp<650)
    fun arrange(w:Int,h:Int,density:Float,fontScale:Float,expanded:Boolean):Layout {
        require(w>=0 && h>=0 && density>0 && density.isFinite() && fontScale>0 && fontScale.isFinite())
        if(!expanded)return Layout(Rect(0,0,w,h),Rect(w,h,0,0),false)
        val gap=(10*density).toInt().coerceAtMost(minOf(w,h)/12)
        val side=w/density>=600 && w>=h*1.2
        return if(side){
            val panel=((300+40*(fontScale-1).coerceIn(0f,1f))*density).toInt().coerceIn((w*.32f).toInt(),(w*.48f).toInt())
            Layout(Rect(0,0,w-panel-gap,h),Rect(w-panel,0,panel,h),true)
        }else{
            val preview=((h-gap)*(if(fontScale>=1.5f).50f else .56f)).toInt()
            Layout(Rect(0,0,w,preview),Rect(0,preview+gap,w,(h-preview-gap).coerceAtLeast(0)),false)
        }
    }
}
