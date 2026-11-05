package xyz.shuncongci.leica

/** Stable IDs, never ordinal persistence. Both models use G_AB, internal FP16 and FP32 I/O. */
enum class LeicaStyle(val id:String,val label:String,val short:String,val asset:String,val sha256:String) {
    COOL("cool","徕卡冷调","冷调","leica/leica-cool-epoch6.fp16.onnx","f85e59126d86b5184c053b51048e36048ced56580b2c1a2c695f742cfa450d29"),
    WARM("warm","徕卡暖调","暖调","leica/leica-warm-epoch6.fp16.onnx","b371371aa34c6b9af8ca0aa90cfa8159a04f4997a170792fab63eeb9523dbcab"),
    LEICA_INSTANT("leica-instant-v2","徕卡一瞬","徕卡一瞬","looks/leica-instant-v2.bin","87d1b46ae221b272227f845f2a7a984357b0d4056a79a2570b84c1271e3f9dcf"),
    ;
    companion object {
        val DEFAULT = COOL
        fun fromId(id:String?):LeicaStyle = entries.firstOrNull{it.id==id} ?: DEFAULT
    }
}
