package xyz.shuncongci.leica

import java.io.DataInputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/** Public, unencrypted PLUT0001 resources. Bounded exact-length read with pinned content hash.
 * The photo/native consumers additionally validate all finite, in-gamut Float64 nodes.
 */
internal object ColorLutFiles {
    const val SIZE=12+65*65*65*3*8
    fun read(input:InputStream,expectedSha256:String,cancel:Cancel):ByteArray = input.use { raw->
        require(expectedSha256.matches(Regex("[a-f0-9]{64}"))){"无效的色彩查表校验值"}
        cancel.check()
        val hash=MessageDigest.getInstance("SHA-256")
        val data=DataInputStream(DigestInputStream(raw.buffered(),hash))
        val bytes=ByteArray(SIZE)
        var at=0
        while(at<bytes.size){cancel.check();val n=minOf(32768,bytes.size-at);data.readFully(bytes,at,n);at+=n}
        require(data.read()==-1){"色彩查表长度异常"}
        require(bytes.copyOfRange(0,12).contentEquals("PLUT0001".toByteArray(Charsets.US_ASCII)+byteArrayOf(0,0,0,65))){"色彩查表格式不支持"}
        val actual=hash.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
        require(actual==expectedSha256){"内置色彩查表校验失败，请重新安装应用"}
        cancel.check();bytes
    }
}
