package xyz.shuncongci.leica

import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlin.math.roundToInt

/** The same stage in the same Activity decor. No second window or system-bar geometry change. */
class SpaceFullscreen(private val host:ComponentActivity,private val stage:DepthStageView,private val anchor:StudioButton,
                      private val closed:(SpaceFullscreen)->Unit) {
    private var closing=false
    private var help:ExportUi.Handle?=null
    private var previousViewportListener:((Float)->Unit)?=null
    private var previousResetListener:(()->Unit)?=null
    private val gyroToggle=button("陀螺仪 · 关"){toggleGyro()}
    private val calibrate=button("校准"){gyro.recenter()}
    private val hint=TextView(host).apply{textSize=11f;setTextColor(0xffb7b2a9.toInt());gravity=Gravity.CENTER;setPadding(0,dp(6),0,0)}
    @Suppress("DEPRECATION") private fun screenRotation()=host.windowManager.defaultDisplay.rotation
    // Stop/unregister still runs immediately. Its final zero/status callbacks cannot move a closing scene.
    private val gyro=SpaceGyro(host,::screenRotation,{yaw,pitch->if(!closing)stage.setParallax(yaw,pitch)},::renderGyro)
    private var cameraGyro=false
    private lateinit var overlay:FullscreenOverlay
    val isShowing get()=::overlay.isInitialized && overlay.isShowing
    private fun toggleGyro(){if(closing)return;gyro.setForeground(isShowing);gyro.setEnabled(!gyro.enabled)}
    private fun renderGyro(state:SpaceGyro.State){
        if(closing)return
        if(cameraGyro!=gyro.enabled){cameraGyro=gyro.enabled;stage.setGyroMode(cameraGyro)}
        gyroToggle.isEnabled=gyro.available;gyroToggle.isSelected=gyro.enabled
        gyroToggle.text=if(!gyro.available)"陀螺仪 · 不支持"else if(gyro.enabled)"陀螺仪 · 开"else "陀螺仪 · 关"
        gyroToggle.contentDescription=if(gyro.enabled)"关闭陀螺仪视差"else "开启陀螺仪视差，仅全屏可用"
        calibrate.isEnabled=gyro.enabled
        val message=when(state){
            SpaceGyro.State.OFF->"陀螺仪已关闭 · 可手动旋转"
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
    fun show(){
        check(!::overlay.isInitialized && !stage.isLoading)
        val lower=LinearLayout(host).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(6),dp(12),dp(8))}
        val gyroRow=WorkspaceActionRow(host)
        gyroRow.addView(gyroToggle);gyroRow.addView(calibrate);lower.addView(gyroRow,LinearLayout.LayoutParams(-1,-2))
        val tools=WorkspaceActionRow(host)
        listOf(button("缩小"){stage.zoomBy(.8f)},button("放大"){stage.zoomBy(1.25f)},button("复位"){stage.resetView()}).forEach{tools.addView(it)}
        lower.addView(tools,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(MaintenanceUiPolicy.spaceToolGap(host.resources.configuration.fontScale))})
        lower.addView(hint,LinearLayout.LayoutParams(-1,-2))
        val scroll=ScrollView(host).apply{isFillViewport=false;addView(lower)}
        overlay=FullscreenOverlay(host,stage,"空间",scroll){restoreStage();closed(this)}
        val info=button("说明"){}
        info.setOnClickListener{if(help?.isShowing!=true)help=WorkspacePanels.help(info,"space")}
        overlay.toolbar.addView(info,LinearLayout.LayoutParams(-2,-2))
        overlay.requestClose={if(help?.isShowing==true)help?.close()else close()}
        overlay.onClosing={freeze()}
        overlay.onFocus={focus->if(!closing)gyro.setForeground(focus)}
        previousViewportListener=stage.onViewportChanged;previousResetListener=stage.onViewReset
        stage.onViewportChanged={zoom->previousViewportListener?.invoke(zoom);updateTitle(zoom)}
        stage.onViewReset={previousResetListener?.invoke();gyro.recenter()}
        renderGyro(if(gyro.available)SpaceGyro.State.OFF else SpaceGyro.State.UNAVAILABLE)
        updateTitle(stage.zoom);overlay.show()
    }
    private fun updateTitle(zoom:Float){if(::overlay.isInitialized)overlay.title.text="空间 · ${(zoom*100).roundToInt()}%"}
    private fun freeze(){if(closing)return;closing=true;stage.cancelGestures();gyro.setForeground(false)}
    private fun restoreStage(){
        freeze();gyro.close();stage.releaseGyro();cameraGyro=false
        stage.onViewportChanged=previousViewportListener?:{};previousViewportListener=null
        stage.onViewReset=previousResetListener?:{};previousResetListener=null
    }
    fun resize(){if(isShowing)overlay.resize()}
    fun close(){if(isShowing){if(help?.isShowing==true)help?.close()else overlay.close()}}
    fun dismiss(){help?.dismiss();help=null;freeze();if(::overlay.isInitialized)overlay.dismiss()else gyro.close()}
}
