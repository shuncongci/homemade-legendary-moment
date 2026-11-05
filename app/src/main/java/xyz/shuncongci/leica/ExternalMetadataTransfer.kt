package xyz.shuncongci.leica

import java.io.File
import java.io.RandomAccessFile

/** Shared, JVM-testable external metadata path. Completeness is earned by archive readback,
 * never inferred from EXIF alone. Explicit deletion consent is a separate policy. */
internal object ExternalMetadataTransfer {
    class Prepared(val exif:ByteArray,val complete:Boolean,internal val bundle:PreservedMetadata.Bundle?,internal val badge:Boolean) {
        internal var compatibility:ExportMetadataCompatibility.Prepared?=null
        var verified:Boolean=false
            internal set
        val deletionReady:Boolean get()=complete && verified
    }
    fun prepare(source:File,width:Int,height:Int,badge:Boolean,cancel:Cancel):Prepared {
        cancel.check()
        var bundle=PreservedMetadata.read(source,cancel)
        val packet=bundle.exif?.let(::CaptureExif)?:CaptureExif.empty()
        val normalized=packet.normalized(width,height,XiaomiLeicaBadge.patches(packet,badge))
        val end=JpegCompletion.primaryEnd(source,cancel)
        val trailing=end?.let{it<source.length()}?:false
        val retained=if(trailing)SourceGainmapArchive.include(source,bundle,end!!,cancel)else null
        if(retained!=null)bundle=retained
        val animated=RandomAccessFile(source,"r").use{f->when(bundle.format){
            "PNG"->CaptureMetadataContainer.chunks(f,true).any{it.kind in setOf("acTL","fcTL","fdAT")}
            "WEBP"->CaptureMetadataContainer.chunks(f,false).any{it.kind in setOf("ANIM","ANMF")}
            else->false
        }}
        // Additional containers can contain RAW, auxiliary images or animation that are not
        // transferable as active output structures. Never infer deletion eligibility from EXIF.
        val completeContainer=bundle.format in setOf("JPEG","PNG","WEBP")
        return Prepared(normalized,completeContainer && (!trailing || retained!=null) && !animated,bundle,badge)
    }
    /** Production export tolerates source descriptions, while strict prepare remains available to guards/tests. */
    fun prepareForExport(source:File,width:Int,height:Int,badge:Boolean,cancel:Cancel):Prepared {
        cancel.check()
        val strict=ExportMetadataCompatibility.optional{prepare(source,width,height,badge,cancel)}
        val compatible=if(strict?.bundle!=null)ExportMetadataCompatibility.prepare(strict.bundle,width,height,badge,cancel)
            else ExportMetadataCompatibility.prepare(source,width,height,badge,cancel).let{it.copy(degraded=true)}
        return Prepared(compatible.exif,strict?.complete==true && !compatible.degraded,compatible.original,badge).also{it.compatibility=compatible}
    }
    fun fallback(exif:ByteArray,badge:Boolean)=Prepared(exif,false,null,badge)
    fun write(encoded:File,target:File,prepared:Prepared,width:Int,height:Int,cancel:Cancel) {
        prepared.verified=false
        // Never clean up a target we did not create.
        check(!target.exists()){"元数据目标已存在，未覆盖"}
        var complete=false
        try {
            prepared.compatibility?.let{plan->
                val written=ExportMetadataCompatibility.write(encoded,target,plan,width,height,prepared.badge,cancel)
                cancel.check();prepared.verified=!written.degraded;complete=true
                return
            }
            val bundle=prepared.bundle
            if(bundle==null)CaptureMetadataContainer.write(encoded,target,prepared.exif,width,height,cancel)
            else CaptureMetadataContainer.writePreserved(encoded,target,bundle,width,height,prepared.badge,cancel)
            cancel.check()
            val actual=checkNotNull(CaptureMetadataContainer.readExif(target).exif)
            check(actual.contentEquals(prepared.exif)){"外接成片EXIF回读不一致，未发布"}
            val flag=CaptureExif(actual).exif().single{it.tag==XiaomiLeicaBadge.TAG}
            check(flag.type==1 && flag.count==1L && (flag.value.single().toInt() and 1)==if(prepared.badge)1 else 0){"外接相册标识回读失败，未发布"}
            if(bundle!=null)check(PreservedMetadata.readArchive(target,cancel).contentEquals(PreservedMetadata.archive(bundle))){"外接原始元数据归档回读失败，原片保留"}
            cancel.check();prepared.verified=true;complete=true
        }finally{if(!complete)target.delete()}
    }
}
