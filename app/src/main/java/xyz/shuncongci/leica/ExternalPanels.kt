package xyz.shuncongci.leica

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle

/** App-owned panels only. Android permission/SAF/IntentSender UI is never replaced or bypassed. */
class ExternalPanels(private val host:ComponentActivity) {
    data class Action(val label:String,val perform:()->Unit)
    private var active:ExportUi.Handle?=null
    private fun dp(n:Int)=(n*host.resources.displayMetrics.density).toInt()
    fun dismiss(){active?.dismiss();active=null}
    fun options(anchor:View,title:String,choices:List<String>,selected:Int=-1,chosen:(Int)->Unit){
        present(anchor,title,"",choices,selected,emptyList(),"取消",{},chosen)
    }
    fun message(anchor:View,title:String,body:String,confirm:(()->Unit)?=null){
        actions(anchor,title,body,listOf(Action(if(confirm==null)"知道了"else "继续"){confirm?.invoke()}),if(confirm==null)null else "取消")
    }
    fun actions(anchor:View,title:String,body:String,actions:List<Action>,cancelLabel:String?="取消",onCancel:()->Unit={}){
        present(anchor,title,body,emptyList(),-1,actions,cancelLabel,onCancel,{})
    }
    fun directory(anchor:View,title:String,children:List<String>,choose:(Int)->Unit,use:()->Unit,up:()->Unit){
        present(anchor,title,"选择目录不会开始监听。",children,-1,listOf(Action("选择此文件夹",use),Action("上一级",up)),"取消",{},choose)
    }
    private fun present(anchor:View,title:String,body:String,choices:List<String>,selected:Int,actions:List<Action>,cancelLabel:String?,onCancel:()->Unit,choose:(Int)->Unit){
        if(host.isFinishing || host.isDestroyed || !host.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || !anchor.isAttachedToWindow || !anchor.isShown){onCancel();return}
        active?.takeIf{it.isShowing}?.let{old->old.close{present(anchor,title,body,choices,selected,actions,cancelLabel,onCancel,choose)};return}
        val column=LinearLayout(host).apply{orientation=LinearLayout.VERTICAL}
        if(body.isNotBlank())column.addView(TextView(host).apply{
            text=body;textSize=14f;setTextColor(0xffddd9d0.toInt());setLineSpacing(dp(4).toFloat(),1f);setTextIsSelectable(true);setPadding(dp(4),dp(8),dp(4),dp(14))
        },LinearLayout.LayoutParams(-1,-2))
        val token=FullscreenMotion.ChoiceCommit()
        var handle:ExportUi.Handle?=null
        fun chooseAction(action:()->Unit){
            val h=handle?:return
            if(token.begin())h.close{if(token.commit(h.closeCompleted))action()}
        }
        choices.forEachIndexed{i,label->
            column.addView(StudioButton(host).apply{
                text=label;gravity=Gravity.START or Gravity.CENTER_VERTICAL;quietSelection=true;isSelected=i==selected
                contentDescription=label+if(i==selected)"，已选择"else ""
                ViewCompat.setStateDescription(this,if(i==selected)"已选择"else "未选择")
                setOnClickListener{chooseAction{choose(i)}}
            },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})
        }
        val footer=WorkspaceActionRow(host).apply{setPadding(0,dp(10),0,0)}
        cancelLabel?.let{label->footer.addView(StudioButton(host).apply{text=label;setOnClickListener{handle?.close()}})}
        actions.forEach{action->footer.addView(StudioButton(host).apply{text=action.label;setOnClickListener{chooseAction(action.perform)}})}
        handle=ExportUi.panel(anchor,title,column,if(choices.size>4 || body.length>220)590 else 400,460,footer=footer)
        val h=handle
        if(h==null){onCancel();return}
        active=h
        h.onDismiss={
            if(active===h)active=null
            // Close/back/outside/pause/rotation are declines, never acceptance. A lifecycle-aborted
            // affirmative animation also declines; callbacks run only after a completed explicit action.
            if(token.cancel(h.closeCompleted))onCancel()
        }
    }
}
