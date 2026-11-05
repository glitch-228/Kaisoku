package org.koitharu.kotatsu.core.image

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvifSniffTest {

    private fun ftyp(major: String, vararg compatible: String): ByteArray {
        val size = 16 + compatible.size * 4
        val buffer = Buffer().writeInt(size).writeUtf8("ftyp").writeUtf8(major).writeInt(0)
        compatible.forEach { buffer.writeUtf8(it) }
        return buffer.readByteArray() + ByteArray(32)
    }

    @Test
    fun `AVIF is recognised by its brands whatever the file is called`() {
        assertTrue(BitmapDecoderCompat.isAvif(Buffer().write(ftyp("avif", "mif1"))))
        assertTrue(BitmapDecoderCompat.isAvif(Buffer().write(ftyp("mif1", "avif"))))
        assertTrue(BitmapDecoderCompat.isAvif(Buffer().write(ftyp("avis"))))
    }

    @Test
    fun `HEIC shares the container but is not AVIF`() {
        assertFalse(BitmapDecoderCompat.isAvif(Buffer().write(ftyp("heic", "mif1"))))
    }

    @Test
    fun `other formats and short input are not AVIF`() {
        assertFalse(BitmapDecoderCompat.isAvif(Buffer().write(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte()))))
        assertFalse(BitmapDecoderCompat.isAvif(Buffer()))
    }

    @Test
    fun `the check does not consume the source`() {
        val source = Buffer().write(ftyp("avif"))
        val size = source.size
        BitmapDecoderCompat.isAvif(source)
        assertEquals(size, source.size)
    }

    @Test
    fun `byte probing reports AVIF before the platform guess`() {
        assertEquals("image/avif", BitmapDecoderCompat.probeMimeType(ftyp("avif", "mif1"))?.toString())
    }
}
