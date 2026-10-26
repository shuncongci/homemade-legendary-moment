package xyz.shuncongci.leica

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.animation.ValueAnimator
import androidx.core.view.ViewCompat

/** Stable, scrollable readout: everyday numbers first, selectable diagnostics on demand. */
class DiagnosticsPanel(context:Context,private val toggleNotification:()->Unit,private val notificationOn:()->Boolean):LinearLayout(context) {
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun text(value:String,size:Float=13f)=TextView(context).apply{text=value;textSize=size;setTextColor(0xffddd5c8.toInt());setLineSpacing(dp(3).toFloat(),1f)}
    private fun add(v:View,gap:Int=10){addView(v,LayoutParams(-1,-2).apply{topMargin=dp(gap)})}
    private val phase=text("正在读取运行参数…",15f).apply{minLines=2}
    private val values=mutableListOf<TextView>()
    private val notify=StudioButton(context).apply{text="通知栏显示运行参数";setOnClickListener{toggleNotification();syncNotification()}}
    private val detail=text("展开后读取设备、帧耗时、线程和错误信息",12f).apply{setTextIsSelectable(true);visibility=GONE}
    var expanded=false;private set
    init {
        orientation=VERTICAL;setPadding(dp(18),0,dp(18),dp(12))
        add(phase,0)
        listOf("处理器占用" to "应用内存","温度状态" to "手机可用内存").forEach{titles->
            val row=LinearLayout(context)
            listOf(titles.first,titles.second).forEachIndexed{i,title->
                val card=LinearLayout(context).apply{orientation=VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));background=GradientDrawable().apply{setColor(0xff242526.toInt());cornerRadius=dp(8).toFloat()}}
                card.addView(text(title,11f));val v=text("—",18f).apply{typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);minLines=2};values+=v;card.addView(v)
                row.addView(card,LayoutParams(0,-2,1f).apply{if(i>0)marginStart=dp(8)})
            };add(row,8)
        }
        add(notify)
        add(text("约 1.5 秒刷新。应用占用不等于整机；温度为系统状态，不是摄氏度。通知约 2 秒刷新。",11f))
        add(StudioButton(context).apply{text="展开详情";contentDescription="展开设备、帧耗时、工作线程和最近错误";setOnClickListener{
            if(ValueAnimator.areAnimatorsEnabled())TransitionManager.beginDelayedTransition(this@DiagnosticsPanel,AutoTransition().apply{duration=220})
            expanded=!expanded;detail.visibility=if(expanded)VISIBLE else GONE;text=if(expanded)"收起详情" else "展开详情";isSelected=expanded
            ViewCompat.setStateDescription(this,if(expanded)"已展开" else "已收起")
        }})
        add(detail);syncNotification()
    }
    fun syncNotification(){notify.isSelected=notificationOn();ViewCompat.setStateDescription(notify,if(notify.isSelected)"已开启" else "已关闭")}
    fun publish(s:LiveDiagnostics.Snapshot){phase.text=s.phase;values.forEachIndexed{i,v->v.text=s.metrics.getOrElse(i){"—"}};if(expanded)detail.text=s.summary+"\n\n"+s.details}
}
