package xyz.shuncongci.leica

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** App overlay, not a background-launched Activity. Cannot grant or intercept system permissions. */
class ExternalProgressOverlay(private val context:Context) {
    private val wm=context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var panel:LinearLayout?=null
    private var label:TextView?=null
    private var dismissed=""
    private var currentId=""
    private fun dp(n:Int)=(n*context.resources.displayMetrics.density).toInt()
    fun render(s:ExternalModeStatus,enabled:Boolean){
        if(!enabled || !ExternalPermissions.overlay(context) || !s.running || !s.working){hide();return}
        currentId=s.currentId
        if(dismissed==s.currentId)return
        if(panel==null){
            val root=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(10),dp(14),dp(12));elevation=dp(8).toFloat();background=GradientDrawable().apply{setColor(0xee202224.toInt());cornerRadius=dp(12).toFloat()}}
            val header=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL}
            header.addView(TextView(context).apply{text="外接模式 · 处理中";setTextColor(0xffeee8dd.toInt());textSize=14f},LinearLayout.LayoutParams(0,-2,1f))
            header.addView(TextView(context).apply{text="×";textSize=22f;setTextColor(-1);gravity=Gravity.CENTER;contentDescription="收起本张照片的悬浮进度，不停止处理";setOnClickListener{dismissed=currentId;hide()}},LinearLayout.LayoutParams(dp(44),dp(44)))
            root.addView(header)
            label=TextView(context).apply{setTextColor(0xffdfd9ce.toInt());textSize=12f;maxLines=7;setLineSpacing(dp(2).toFloat(),1f)};root.addView(label)
            root.addView(ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply{isIndeterminate=true;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(-1,dp(8)).apply{topMargin=dp(8)})
            val width=minOf(dp(300),(context.resources.displayMetrics.widthPixels*.9).toInt())
            val lp=WindowManager.LayoutParams(width,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.END;x=dp(12);y=dp(64)}
            try{wm.addView(root,lp);panel=root}catch(_:WindowManager.BadTokenException){label=null;return}catch(_:SecurityException){label=null;return}
        }
        label?.text=s.description()
    }
    fun hide(){panel?.let{view->try{wm.removeView(view)}catch(_:IllegalArgumentException){}};panel=null;label=null}
}
