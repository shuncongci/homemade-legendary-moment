package xyz.shuncongci.leica

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Gainmap
import android.os.Build
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** TEST APK ONLY: codecs/metadata and production geometry probe; never writes MediaStore.
 * Synthetic fixture by default. User sample must explicitly be placed in filesDir and selected. */
object HdrDiagnostic {
    fun run(context:Context,userSample:Boolean):File {
        check(Build.VERSION.SDK_INT>=34){"Gainmap codec experiment requires Android 14+; no SDR substitute"}
        val dir=File(context.filesDir,"hdr-experiment-${UUID.randomUUID()}");check(dir.mkdirs())
        val w=256;val h=192
        val synthetic=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val pixels=IntArray(w*h){i->val v=40+(i%w)*180/(w-1);(255 shl 24) or (v shl 16) or (v shl 8) or v}
        synthetic.setPixels(pixels,0,w,0,0,w,h);synthetic.setHasAlpha(false)
        val map=Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888)
        val gainPixels=IntArray(64*48){i->val v=(i%64)*255/63;(255 shl 24) or (v shl 16) or (v shl 8) or v}
        map.setPixels(gainPixels,0,64,0,0,64,48)
        synthetic.gainmap=Gainmap(map).apply{setRatioMin(1f,1f,1f);setRatioMax(8f,8f,8f);setGamma(1f,1f,1f);setEpsilonSdr(0f,0f,0f);setEpsilonHdr(0f,0f,0f);setDisplayRatioForFullHdr(8f);setMinDisplayRatioForHdrTransition(1f)}
        val base=if(userSample){val source=File(context.filesDir,"hdr-diagnostic-source.jpg");check(source.isFile){"Explicit HDR sample missing"};checkNotNull(BitmapFactory.decodeFile(source.path))}else synthetic
        val report=JSONObject().put("api",Build.VERSION.SDK_INT).put("synthetic",!userSample).put("productionFiltersExecuted",false).put("displayAcceptance",false)
        fun describe(b:Bitmap):JSONObject {
            val o=JSONObject().put("width",b.width).put("height",b.height).put("config",b.config.toString()).put("colorSpace",b.colorSpace?.name).put("hasGainmap",b.hasGainmap())
            b.gainmap?.let{g->o.put("mapWidth",g.gainmapContents.width).put("mapHeight",g.gainmapContents.height).put("ratioMin",JSONArray(g.ratioMin.toList())).put("ratioMax",JSONArray(g.ratioMax.toList())).put("gamma",JSONArray(g.gamma.toList())).put("epsilonSdr",JSONArray(g.epsilonSdr.toList())).put("epsilonHdr",JSONArray(g.epsilonHdr.toList())).put("displayFull",g.displayRatioForFullHdr).put("displayMin",g.minDisplayRatioForHdrTransition)}
            return o
        }
        try {
            report.put("input",describe(base));check(base.hasGainmap()){"Input is not Ultra HDR; no fake gain map assigned to user photo"}
            val raw=IntArray(base.width*base.height);base.getPixels(raw,0,base.width,0,0,base.width,base.height)
            val flat=Bitmap.createBitmap(raw,base.width,base.height,Bitmap.Config.ARGB_8888)
            report.put("afterGetPixelsOriginal",describe(base)).put("newBitmapFrom8BitPixels",describe(flat));check(!flat.hasGainmap());flat.recycle()
            val x=base.width/8;val y=base.height/8;val crop=Bitmap.createBitmap(base,x,y,base.width*3/4,base.height*3/4)
            try{report.put("bitmapCrop",describe(crop));check(crop.hasGainmap()){"Platform crop did not retain gainmap"}}finally{if(crop!==base)crop.recycle()}
            fun roundTrip(label:String,format:Bitmap.CompressFormat,quality:Int):Boolean {
                val f=File(dir,label);f.outputStream().use{check(base.compress(format,quality,it))}
                val decoded=checkNotNull(BitmapFactory.decodeFile(f.path))
                try{val has=decoded.hasGainmap();report.put(label,describe(decoded).put("bytes",f.length()));return has}finally{decoded.recycle()}
            }
            check(roundTrip("roundtrip.jpg",Bitmap.CompressFormat.JPEG,95)){"JPEG encoder dropped gainmap"}
            roundTrip("roundtrip.png",Bitmap.CompressFormat.PNG,100)
            roundTrip("roundtrip.webp",Bitmap.CompressFormat.WEBP_LOSSLESS,100)
            val geometryCases=JSONArray()
            for(exif in 1..8){
                val (uw,uh)=NativeImageMath.size(base.width,base.height,exif)
                val region=CompositionGeometry.Crop(.125,.25,.875,.875)
                val box=CompositionGeometry.pixels(region,uw,uh)
                val size=maxOf(1,box.w/2) to maxOf(1,box.h/2)
                val transformed=HdrPhotoExport34.geometry(base,exif,region,size,Cancel())
                try {
                    val expected=HdrPhotoExport34.metadata(transformed)
                    check(transformed.width==size.first && transformed.height==size.second)
                    val file=File(dir,"production-geometry-exif-$exif.jpg")
                    file.outputStream().use{check(transformed.compress(Bitmap.CompressFormat.JPEG,95,it))}
                    HdrPhotoExport34.verify(context,android.net.Uri.fromFile(file),expected,Cancel())
                    geometryCases.put(describe(transformed).put("exif",exif).put("encodedReadback",true))
                }finally{if(transformed!==base)transformed.recycle()}
            }
            val pairCases=JSONArray()
            // Production native decode/EXIF, linear look mapping, paired crop and custom container/Android readback.
            // Does not claim ARNet/model execution or MediaStore/display acceptance.
            val decoded=checkNotNull(BitmapFactory.decodeFile(File(dir,"roundtrip.jpg").path))
            try{for(exif in 1..8){
                val (uw,uh)=NativeImageMath.size(decoded.width,decoded.height,exif)
                val pw=minOf(128,uw);val ph=maxOf(1,kotlin.math.round(uh*pw.toDouble()/uw).toInt())
                val source=HdrSource.bitmap(decoded,exif,pw,ph,Cancel(),context.cacheDir)
                val encoded=LegendaryMath.Frame(pw,ph,3,FloatArray(pw*ph*3){i->HdrMath.srgb(source.sdr.data[i]).coerceIn(0f,1f)})
                val sdr=LegendaryMath.planes(encoded)
                for(i in 0 until sdr.size){sdr.p0[i]=((sdr.p0[i].toInt()and 255)*.9f).toInt().toByte()}
                val hdr=HdrMath.color(source.sdr,source.hdr,sdr,Cancel())
                val fused=HdrSource.bitmapForColor(decoded,exif,pw,ph,sdr,Cancel(),context.cacheDir)
                for(i in hdr.data.indices)check(hdr.data[i].toRawBits()==fused.data[i].toRawBits()){"Fused HDR decode/transfer differs at EXIF=$exif pixel=$i"}
                val region=CompositionGeometry.Crop(.125,.125,.875,.875)
                val box=CompositionGeometry.pixels(region,pw,ph)
                val pair=HdrPairExport.crop(sdr,hdr,region,maxOf(1,box.w/2) to maxOf(1,box.h/2),Cancel())
                val file=File(dir,"production-pair-exif-$exif.jpg")
                val expected=HdrPairExport.encode(file,pair.first,pair.second,Cancel())
                val error=HdrPairExport.verify(context,android.net.Uri.fromFile(file),expected,pair.second,Cancel())
                pairCases.put(JSONObject().put("exif",exif).put("fusedDecodeBitExact",true).put("maximumRelativeToPeak",error.maxRelativeToPeak).put("rmseRelativeToPeak",error.rmseRelativeToPeak).put("accepted",true))
            }}finally{decoded.recycle()}
            report.put("productionGeometry",geometryCases).put("productionGeometryExecuted",true).put("productionPairCodec",pairCases).put("productionPairMathExecuted",true).put("mediaStoreExecuted",false)
            report.put("status","PASS_CODEC_AND_PAIR_NUMERIC_ONLY")
        }catch(t:Throwable){report.put("status","FAIL").put("error",t.toString());File(dir,"report.json").writeText(report.toString(2));throw t}
        finally{if(base!==synthetic)base.recycle();synthetic.recycle();map.recycle()}
        File(dir,"report.json").writeText(report.toString(2));return dir
    }
}
