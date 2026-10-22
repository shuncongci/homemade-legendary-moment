package xyz.shuncongci.leica

/** Stable IDs, never ordinal persistence. Both models use G_AB, internal FP16 and FP32 I/O. */
enum class LeicaStyle(val id:String,val label:String,val short:String,val asset:String,val sha256:String) {
    COOL("cool","徕卡冷调","冷调","leica/leica-cool-epoch6.fp16.onnx","f85e59126d86b5184c053b51048e36048ced56580b2c1a2c695f742cfa450d29"),
    WARM("warm","徕卡暖调","暖调","leica/leica-warm-epoch6.fp16.onnx","b371371aa34c6b9af8ca0aa90cfa8159a04f4997a170792fab63eeb9523dbcab"),
    ;
    companion object {
        val DEFAULT = COOL
        fun fromId(id:String?):LeicaStyle = entries.firstOrNull{it.id==id} ?: DEFAULT
    }
}
