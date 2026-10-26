package xyz.shuncongci.leica

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** Wholly synthetic metadata and 16x12 solid-color codec fixtures; no user's image/GPS is packaged. */
object CaptureMetadataFixtures {
    val jpeg:ByteArray get()=Base64.getDecoder().decode("/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAIBAQEBAQIBAQECAgICAgQDAgICAgUEBAMEBgUGBgYFBgYGBwkIBgcJBwYGCAsICQoKCgoKBggLDAsKDAkKCgr/2wBDAQICAgICAgUDAwUKBwYHCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgr/wAARCAAMABADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDLooor+oD8PP/Z")
    val png:ByteArray get()=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAABAAAAAMCAIAAADkharWAAAAGklEQVR4nGMMaTzIQApgIkk1w6gG4sBwCFYAvvgBrqfPnlYAAAAASUVORK5CYII=")
    val webp:ByteArray get()=Base64.getDecoder().decode("UklGRj4AAABXRUJQVlA4IDIAAADQAQCdASoQAAwAAIAIJaACdLoB+AADsAD+54v//S4P0cb9HG/9Lg/++BPvAn3gT/bYAA==")
    val losslessWebp:ByteArray get()=Base64.getDecoder().decode("UklGRh4AAABXRUJQVlA4TBEAAAAvD8ACAAfQwFI1uP+BiOh/AAA=")
    fun tiff(order:ByteOrder=ByteOrder.BIG_ENDIAN):ByteArray {
        val bytes=ByteArray(512);val b=ByteBuffer.wrap(bytes).order(order)
        b.put(if(order==ByteOrder.BIG_ENDIAN)byteArrayOf(77,77)else byteArrayOf(73,73));b.putShort(42);b.putInt(8)
        fun inlineShort(n:Int)=ByteBuffer.allocate(2).order(order).putShort(n.toShort()).array()
        fun e(at:Int,tag:Int,type:Int,count:Int,offset:Int=0,inline:ByteArray?=null){b.position(at);b.putShort(tag.toShort());b.putShort(type.toShort());b.putInt(count);if(inline==null)b.putInt(offset)else{b.put(inline);b.put(ByteArray(4-inline.size))}}
        b.putShort(8,5)
        e(10,0x10f,2,8,200);e(22,0x112,3,1,inline=inlineShort(6));e(34,0x8769,4,1,80);e(46,0x8825,4,1,320);e(58,0x111,4,1,123456)
        b.putShort(80,7)
        e(82,0x829a,5,1,180);e(94,0x920a,5,1,188);e(106,0xa434,2,12,220);e(118,0x927c,7,16,240)
        e(130,0xa002,4,1,640);e(142,0xa001,3,1,inline=inlineShort(65535));e(154,0xc123,7,6,280)
        b.putInt(180,1);b.putInt(184,125);b.putInt(188,50);b.putInt(192,1)
        "Fixture\u0000".toByteArray().copyInto(bytes,200);"FixtureLens\u0000".toByteArray().copyInto(bytes,220)
        ByteArray(16){(it+16).toByte()}.copyInto(bytes,240);byteArrayOf(9,8,7,6,5,4).copyInto(bytes,280)
        b.putShort(320,3);e(322,0,1,4,inline=byteArrayOf(2,3,0,0));e(334,1,2,2,inline=byteArrayOf(78,0));e(346,2,5,3,380)
        for(i in 0..2){b.putInt(380+i*8,i+1);b.putInt(384+i*8,1)}
        return bytes
    }
}
