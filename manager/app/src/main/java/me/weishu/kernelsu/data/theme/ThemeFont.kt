package me.weishu.kernelsu.data.theme

import android.graphics.Typeface
import java.io.File
import java.io.InputStream

object ThemeFont {
    const val ASSET = "global-font.ttf"
    const val MAX_BYTES = ThemeCodec.MAX_ASSET_BYTES

    fun read(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "Font is too large (maximum 12 MB)" }
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        require(bytes.size <= MAX_BYTES) { "Font is too large (maximum 12 MB)" }
        validateHeader(bytes)
        return bytes
    }

    fun validateHeader(bytes: ByteArray) {
        require(bytes.size in 12..MAX_BYTES) { "Invalid or empty font" }
        val tag = bytes.take(4).toByteArray()
        require(tag.contentEquals(byteArrayOf(0, 1, 0, 0)) || tag.toString(Charsets.US_ASCII) == "OTTO") {
            "Choose a TTF or OTF font"
        }
        val tables = ((bytes[4].toInt() and 255) shl 8) or (bytes[5].toInt() and 255)
        require(tables in 1..256 && 12 + tables * 16 <= bytes.size) { "Incomplete font table" }
        fun uint(at: Int): Long = (0..3).fold(0L) { result, offset ->
            (result shl 8) or (bytes[at + offset].toLong() and 255)
        }
        repeat(tables) { i ->
            val start = uint(12 + i * 16 + 8)
            val length = uint(12 + i * 16 + 12)
            require(start + length <= bytes.size) { "Font table is out of bounds" }
        }
    }

    fun validateFile(file: File): Typeface {
        file.inputStream().use { read(it) }
        return Typeface.createFromFile(file)
    }
}
