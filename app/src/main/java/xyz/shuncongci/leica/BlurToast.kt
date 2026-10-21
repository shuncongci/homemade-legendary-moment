package xyz.shuncongci.leica

import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

object BlurToast {
    
    fun show(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        val inflater = LayoutInflater.from(context)
        val layout = inflater.inflate(R.layout.custom_toast, null)
        
        val textView = layout.findViewById<TextView>(R.id.toast_text)
        textView.text = message
        
        val toast = Toast(context)
        toast.duration = duration
        toast.view = layout
        toast.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 150)
        applyBlurBehindIfPossible(toast)
        toast.show()
    }

    private fun applyBlurBehindIfPossible(toast: Toast) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        try {
            val tnField = Toast::class.java.getDeclaredField("mTN")
            tnField.isAccessible = true
            val tn = tnField.get(toast) ?: return

            val paramsField = tn.javaClass.getDeclaredField("mParams")
            paramsField.isAccessible = true
            val params = paramsField.get(tn) as? WindowManager.LayoutParams ?: return

            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            params.blurBehindRadius = 60

            paramsField.set(tn, params)
        } catch (_: Exception) {
            // ignore
        }
    }
    
    fun showShort(context: Context, message: String) {
        show(context, message, Toast.LENGTH_SHORT)
    }
    
    fun showLong(context: Context, message: String) {
        show(context, message, Toast.LENGTH_LONG)
    }
}
