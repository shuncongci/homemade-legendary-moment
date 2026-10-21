package xyz.shuncongci.leica

/** FP32 depth capped at long edge 1220; replicate padding to patch multiples. */
object NativeDepthMath {
    const val PRECISION = "FP32"
    const val QUERY_CHUNK = 64
    const val MAX_EDGE = 1220
    fun analysisSize(width:Int,height:Int):Pair<Int,Int> {
        require(width>0 && height>0)
        val edge=maxOf(width,height)
        if(edge<=MAX_EDGE)return width to height
        fun fit(n:Int)=((n.toLong()*MAX_EDGE+edge/2)/edge).toInt().coerceAtLeast(1)
        return fit(width) to fit(height)
    }
    fun paddedSize(width:Int,height:Int):Pair<Int,Int> {
        require(width>0 && height>0)
        fun pad(n:Int):Int {val v=((n.toLong()+13)/14)*14;require(v<=Int.MAX_VALUE);return v.toInt()}
        val w=pad(width);val h=pad(height)
        require(w.toLong()*h*3<=Int.MAX_VALUE) { "原图超出单张量索引范围；不自动降低深度分辨率" }
        return w to h
    }
    fun input(source:Planes,cancel:Cancel):LegendaryMath.Frame {
        val (aw,ah)=analysisSize(source.width,source.height)
        val orig=if(aw==source.width && ah==source.height)source else Resample.resize(source,aw,ah,Resample.Filter.BICUBIC,cancel)
        val (w,h)=paddedSize(orig.width,orig.height)
        val dst=LegendaryMath.Frame(w,h,3,FloatArray(w*h*3))
        val mean=floatArrayOf(.485f,.456f,.406f);val std=floatArrayOf(.229f,.224f,.225f)
        for(c in 0..2){val src=orig.plane(c)
            for(y in 0 until h){cancel.check();val row=y.coerceAtMost(orig.height-1)*orig.width
                for(x in 0 until w){val p=src[row+x.coerceAtMost(orig.width-1)].toInt() and 255
                    dst.data[c*dst.size+y*w+x]=(p/255f-mean[c])/std[c]
                }
            }
        }
        return dst
    }
    fun crop(raw:LegendaryMath.Frame,width:Int,height:Int,cancel:Cancel):LegendaryMath.Frame {
        val (w,h)=paddedSize(width,height)
        require(raw.channels==1 && raw.w==w && raw.h==h) { "原尺寸深度模型输出尺寸异常" }
        if(w==width && h==height)return raw
        val result=FloatArray(width*height)
        for(y in 0 until height){cancel.check();raw.data.copyInto(result,y*width,y*w,y*w+width)}
        return LegendaryMath.Frame(width,height,1,result)
    }
}
