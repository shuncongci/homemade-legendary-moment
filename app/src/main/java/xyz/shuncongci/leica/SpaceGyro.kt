package xyz.shuncongci.leica

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Owned only by SpaceFullscreen. All callbacks and registrations run on the main looper.
 * Game rotation vector uses gyro fusion without compass; ordinary fused RV is fallback only
 * on a device with a physical gyro. No accelerometer-only or fabricated-motion substitute. */
class SpaceGyro(context:Context,private val rotation:()->Int,
                private val output:(Float,Float)->Unit,private val status:(State)->Unit):SensorEventListener {
    enum class State { OFF, WAITING, ACTIVE, PAUSED, UNAVAILABLE, FAILED }
    private val manager=context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor:Sensor?=try {
        if(manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)==null)null
        else manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?:manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }catch(_:RuntimeException){null}
    val available get()=sensor!=null
    var enabled=false;private set
    private var foreground=false
    private var listening=false
    private var disposed=false
    private var state=if(available)State.OFF else State.UNAVAILABLE
    private val filter=SpaceParallax()
    private val handler=Handler(Looper.getMainLooper())
    private var lastValidAt=0L
    private val watchdog=object:Runnable {
        override fun run(){
            if(!listening || disposed)return
            if(SystemClock.elapsedRealtime()-lastValidAt>=2000L)fail()
            else handler.postDelayed(this,1000L)
        }
    }
    private fun publish(next:State){if(state!=next){state=next;status(next)}}
    fun setEnabled(value:Boolean){
        if(disposed)return
        enabled=value && available
        if(!value || !available){stop();publish(if(available)State.OFF else State.UNAVAILABLE)}else sync()
    }
    fun setForeground(value:Boolean){if(disposed)return;foreground=value;sync()}
    private fun sync(){
        if(!enabled){stop();return}
        if(!foreground){stop();publish(State.PAUSED);return}
        if(listening)return
        filter.start();lastValidAt=SystemClock.elapsedRealtime();listening=true
        val registered=try{manager?.registerListener(this,sensor,33_333,handler)==true}catch(_:RuntimeException){false}
        if(!registered){fail();return}
        publish(State.WAITING);handler.postDelayed(watchdog,2000L)
    }
    private fun stop(){
        listening=false;handler.removeCallbacks(watchdog)
        try{manager?.unregisterListener(this)}catch(_:RuntimeException){}
        filter.stop();output(0f,0f)
    }
    private fun fail(){enabled=false;stop();publish(State.FAILED)}
    fun recenter(){if(disposed)return;filter.recenter();output(0f,0f)}
    fun close(){if(disposed)return;enabled=false;foreground=false;stop();disposed=true}
    override fun onSensorChanged(event:SensorEvent){
        if(disposed || !enabled || !foreground || !listening || event.sensor!==sensor)return
        if(filter.sample(event.values,event.timestamp,rotation())){
            lastValidAt=SystemClock.elapsedRealtime();output(filter.yaw,filter.pitch);publish(State.ACTIVE)
        }
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int){}
}
