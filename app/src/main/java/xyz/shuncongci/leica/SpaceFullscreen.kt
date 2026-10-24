package xyz.shuncongci.leica

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt

/** One camera and one existing stage, temporarily hosted by a modal full-screen window.
 * No photo copy, bitmap snapshot, inference, activity launch or changes to the editor's bars. */
class SpaceFullscreen(private val host:Activity,private val stage:DepthStageView,
                      private val closed:(SpaceFullscreen)->Unit):Dialog(host,android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen) {
    private val body=LinearLayout(host).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xff101112.toInt())}
    private val viewport=FrameLayout(host)
    private val title=TextView(host).apply{textSize=14f;setTextColor(0xffeee8dd.toInt());gravity=Gravity.CENTER;maxLines=1}
    private var source:ViewGroup?=null
    private var sourceIndex=0
    private var sourceParams:ViewGroup.LayoutParams?=null
    private var previousViewportListener:((Float)->Unit)?=null
    private var previousResetListener:(()->Unit)?=null
    private val gyroToggle=button("陀螺仪 · 关"){toggleGyro()}
    private val calibrate=button("校准"){gyro.recenter()}
    private val hint=TextView(host).apply{textSize=11f;setTextColor(0xffb7b2a9.toInt());gravity=Gravity.CENTER;setPadding(0,dp(6),0,0)}
    @Suppress("DEPRECATION")
    private fun screenRotation()=host.windowManager.defaultDisplay.rotation
    private val gyro=SpaceGyro(host,::screenRotation,stage::setParallax,::renderGyro)
    private fun toggleGyro(){
        gyro.setForeground(isShowing && window?.decorView?.hasWindowFocus()==true)
        gyro.setEnabled(!gyro.enabled)
    }
    private var cameraGyro=false
    private fun renderGyro(state:SpaceGyro.State){
        if(cameraGyro!=gyro.enabled){cameraGyro=gyro.enabled;stage.setGyroMode(cameraGyro)}
        gyroToggle.isEnabled=gyro.available
        gyroToggle.isSelected=gyro.enabled
        gyroToggle.text=if(!gyro.available)"陀螺仪 · 不支持" else if(gyro.enabled)"陀螺仪 · 开" else "陀螺仪 · 关"
        gyroToggle.contentDescription=if(gyro.enabled)"关闭陀螺仪视差" else "开启陀螺仪视差，仅全屏可用"
        calibrate.isEnabled=gyro.enabled
        val message=when(state){
            SpaceGyro.State.OFF->"陀螺仪已关闭 · 默认微侧视角"
            SpaceGyro.State.WAITING->"保持当前姿态 · 等待传感器定中心"
            SpaceGyro.State.ACTIVE->"正面居中起步 · 可手动旋转 · 校准重设握持中心"
            SpaceGyro.State.PAUSED->"陀螺仪已暂停 · 返回此窗口后重新定中心"
            SpaceGyro.State.UNAVAILABLE->"无可用陀螺仪姿态传感器 · 仍可手势操作"
            SpaceGyro.State.FAILED->"传感器暂不可用，已关闭 · 可点开关重试"
        }
        hint.text="$message\n单指旋转 · 双指缩放/平移 · 双击复位并校准"
    }
    private fun dp(n:Int)=(n*host.resources.displayMetrics.density).roundToInt()
    private fun button(text:String,action:()->Unit)=StudioButton(host).apply{this.text=text;setOnClickListener{action()}}
    init {
        setCanceledOnTouchOutside(false)
        val top=LinearLayout(host).apply{gravity=Gravity.CENTER_VERTICAL}
        top.addView(button("退出全屏"){dismiss()},LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT))
        top.addView(title,LinearLayout.LayoutParams(0,WRAP_CONTENT,1f).apply{marginStart=dp(8)})
        body.addView(top,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT).apply{bottomMargin=dp(6)})
        val gyroRow=LinearLayout(host).apply{gravity=Gravity.CENTER_VERTICAL}
        gyroRow.addView(gyroToggle,LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
        gyroRow.addView(calibrate,LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT).apply{marginStart=dp(8)})
        body.addView(gyroRow,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT).apply{bottomMargin=dp(6)})
        body.addView(viewport,LinearLayout.LayoutParams(MATCH_PARENT,0,1f))
        val tools=LinearLayout(host).apply{gravity=Gravity.CENTER_VERTICAL}
        listOf(button("缩小"){stage.zoomBy(.8f)},button("放大"){stage.zoomBy(1.25f)},button("复位"){stage.resetView()}).forEachIndexed{i,b->
            tools.addView(b,LinearLayout.LayoutParams(0,WRAP_CONTENT,1f).apply{if(i>0)marginStart=dp(8)})
        }
        body.addView(tools,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT).apply{topMargin=dp(6)})
        body.addView(hint,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT))
        renderGyro(if(gyro.available)SpaceGyro.State.OFF else SpaceGyro.State.UNAVAILABLE)
        setContentView(body)
        ViewCompat.setOnApplyWindowInsetsListener(body){v,insets->
            // Stable safe area even while bars are temporarily revealed by swiping.
            val safe=insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(dp(12)+safe.left,dp(6)+safe.top,dp(12)+safe.right,dp(8)+safe.bottom);insets
        }
        setOnDismissListener{restoreStage();closed(this)}
    }
    override fun show(){
        check(!isShowing && source==null)
        check(!stage.isLoading && stage.parent is ViewGroup)
        super.show()
        try {
            source=stage.parent as ViewGroup;sourceIndex=source!!.indexOfChild(stage);sourceParams=stage.layoutParams
            previousViewportListener=stage.onViewportChanged
            previousResetListener=stage.onViewReset
            stage.onViewReset={previousResetListener?.invoke();gyro.recenter()}
            stage.rehost(viewport,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
            stage.onViewportChanged={zoom->previousViewportListener?.invoke(zoom);updateTitle(zoom)}
            updateTitle(stage.zoom);resize();stage.requestFocus()
        }catch(t:Throwable){dismiss();throw t}
    }
    private fun updateTitle(zoom:Float){title.text="空间 · ${(zoom*100).roundToInt()}%"}
    /** MATCH_PARENT relayouts on rotation; camera state stays normalized to the viewport. */
    fun resize(){
        stage.cancelGestures();gyro.recenter()
        window?.let{w->
            w.setLayout(MATCH_PARENT,MATCH_PARENT);w.setBackgroundDrawable(ColorDrawable(0xff101112.toInt()))
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            if(Build.VERSION.SDK_INT>=28)w.attributes=w.attributes.apply{layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES}
            WindowCompat.setDecorFitsSystemWindows(w,false)
            WindowInsetsControllerCompat(w,w.decorView).apply{
                systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            ViewCompat.requestApplyInsets(body)
        }
    }
    private fun restoreStage(){
        gyro.close();stage.setGyroMode(false);cameraGyro=false
        val parent=source?:return
        stage.onViewReset=previousResetListener?:{};previousResetListener=null
        stage.onViewportChanged=previousViewportListener?:{};previousViewportListener=null
        val params=checkNotNull(sourceParams)
        stage.rehost(parent,params,sourceIndex.coerceIn(0,parent.childCount))
        source=null;sourceParams=null;stage.requestFocus()
    }
    override fun onWindowFocusChanged(hasFocus:Boolean){super.onWindowFocusChanged(hasFocus);gyro.setForeground(hasFocus && isShowing)}
    override fun onStop(){gyro.close();stage.setGyroMode(false);cameraGyro=false;super.onStop()}
    override fun dismiss(){restoreStage();super.dismiss()}
}
