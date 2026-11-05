package xyz.shuncongci.leica

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat

/** Every ordinary picker/help surface shares the actual trigger, safe-window placement and lifecycle handle. */
object WorkspacePanels {
    private fun text(anchor:View,value:String,size:Float)=TextView(anchor.context).apply{
        text=value;textSize=size;setTextColor(0xffddd9d0.toInt());setLineSpacing(4*resources.displayMetrics.density,1f)
    }
    fun help(anchor:View,id:String?=null):ExportUi.Handle? {
        val body=LinearLayout(anchor.context).apply{orientation=LinearLayout.VERTICAL}
        val topics=if(id==null)WorkspaceGuide.topics else listOf(WorkspaceGuide.topic(id))
        topics.forEach{topic->
            body.addView(text(anchor,topic.title,16f).apply{setPadding(0,18*resources.displayMetrics.density.toInt(),0,8);ViewCompat.setAccessibilityHeading(this,true)})
            body.addView(text(anchor,topic.overview,14f))
            body.addView(text(anchor,"操作\n${topic.steps}\n\n注意\n${topic.limits}",13f).apply{setTextIsSelectable(true);setPadding(0,12,0,22)})
        }
        return ExportUi.panel(anchor,if(id==null)"操作说明"else WorkspaceGuide.topic(id).title,body,620,460)
    }
    fun options(anchor:View,title:String,choices:List<String>,selected:Int,summary:String="",chosen:(Int)->Unit):ExportUi.Handle? {
        val body=LinearLayout(anchor.context).apply{orientation=LinearLayout.VERTICAL}
        if(summary.isNotBlank())body.addView(text(anchor,summary,12f))
        var handle:ExportUi.Handle?=null
        choices.forEachIndexed{i,label->
            body.addView(StudioButton(anchor.context).apply{
                text=label;gravity=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                quietSelection=true;isSelected=i==selected;contentDescription="$label，${if(i==selected)"已选择"else "未选择"}"
                ViewCompat.setStateDescription(this,if(i==selected)"已选择"else "未选择")
                setOnClickListener{handle?.close{chosen(i)}}
            },LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT).apply{topMargin=(10*anchor.resources.displayMetrics.density).toInt()})
        }
        handle=ExportUi.panel(anchor,title,body,if(choices.size<=4)400 else 590,440)
        return handle
    }
    fun selectionBackground(view:View,selected:Boolean)=GradientDrawable().apply{
        setColor(if(selected)0xff34312b.toInt()else 0xff242628.toInt());cornerRadius=14*view.resources.displayMetrics.density
        setStroke(view.resources.displayMetrics.density.toInt().coerceAtLeast(1),if(selected)0xffa89b83.toInt()else 0xff393c3d.toInt())
    }
}
