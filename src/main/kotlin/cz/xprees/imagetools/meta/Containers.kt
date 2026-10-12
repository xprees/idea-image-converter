package cz.xprees.imagetools.meta

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** EXIF and ICC blobs lifted out of (or about to be put into) an encoded file. */
class RawMetadata(val exif: ByteArray?, val icc: ByteArray?) {
    val isEmpty get() = exif == null && icc == null
}

private val EXIF_HEADER = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
private val ICC_ID = "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1)

private fun ByteArray.startsWith(prefix: ByteArray, offset: Int = 0): Boolean =
    size >= offset + prefix.size && prefix.indices.all { this[offset + it] == prefix[it] }

private fun ByteArray.ascii(offset: Int, length: Int) = String(this, offset, length, Charsets.ISO_8859_1)

object JpegMetadata {
    private const val MAX_SEGMENT_PAYLOAD = 65533 // 65535 minus the 2 length bytes

    fun read(jpeg: ByteArray): RawMetadata {
        var exif: ByteArray? = null
        val iccParts = sortedMapOf<Int, ByteArray>()
        forEachSegment(jpeg) { marker, start, length ->
            if (marker == 0xE1 && exif == null && jpeg.startsWith(EXIF_HEADER, start)) {
                exif = jpeg.copyOfRange(start + EXIF_HEADER.size, start + length)
            } else if (marker == 0xE2 && jpeg.startsWith(ICC_ID, start) && length > ICC_ID.size + 2) {
                val seq = jpeg[start + ICC_ID.size].toInt() and 0xFF
                iccParts[seq] = jpeg.copyOfRange(start + ICC_ID.size + 2, start + length)
            }
        }
        val icc = iccParts.values.takeIf { it.isNotEmpty() }?.fold(ByteArrayOutputStream()) { out, part -> out.apply { write(part) } }
        return RawMetadata(exif, icc?.toByteArray())
    }

    /** Inserts APP1(Exif)/APP2(ICC) right after SOI and an optional JFIF APP0. Existing Exif/ICC segments are dropped. */
    fun write(jpeg: ByteArray, metadata: RawMetadata): ByteArray {
        require(jpeg.size > 3 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "Not a JPEG stream" }
        if (metadata.isEmpty) return jpeg
        val out = ByteArrayOutputStream(jpeg.size + 4096)
        out.write(jpeg, 0, 2)
        var pos = 2
        // keep JFIF APP0 first, as the spec requires
        if ((jpeg[2].toInt() and 0xFF) == 0xFF && (jpeg[3].toInt() and 0xFF) == 0xE0) {
            val len = u16(jpeg, 4) + 2
            out.write(jpeg, 2, len)
            pos = 2 + len
        }
        metadata.exif?.let { segment(out, 0xE1, EXIF_HEADER + it) }
        metadata.icc?.let { icc ->
            val chunk = MAX_SEGMENT_PAYLOAD - ICC_ID.size - 2
            val parts = (icc.size + chunk - 1) / chunk
            for (i in 0 until parts) {
                val slice = icc.copyOfRange(i * chunk, minOf(icc.size, (i + 1) * chunk))
                segment(out, 0xE2, ICC_ID + byteArrayOf((i + 1).toByte(), parts.toByte()) + slice)
            }
        }
        // copy the rest, skipping any pre-existing Exif / ICC segments
        while (pos + 4 <= jpeg.size) {
            if ((jpeg[pos].toInt() and 0xFF) != 0xFF) break
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker == 0xDA) break // start of scan: the remainder is entropy-coded data
            val len = u16(jpeg, pos + 2) + 2
            val payload = pos + 4
            val drop = (marker == 0xE1 && jpeg.startsWith(EXIF_HEADER, payload)) ||
                (marker == 0xE2 && jpeg.startsWith(ICC_ID, payload))
            if (!drop) out.write(jpeg, pos, len)
            pos += len
        }
        out.write(jpeg, pos, jpeg.size - pos)
        return out.toByteArray()
    }

    private fun segment(out: ByteArrayOutputStream, marker: Int, payload: ByteArray) {
        require(payload.size <= MAX_SEGMENT_PAYLOAD) { "JPEG segment payload too large: ${payload.size}" }
        out.write(0xFF)
        out.write(marker)
        out.write((payload.size + 2) ushr 8)
        out.write((payload.size + 2) and 0xFF)
        out.write(payload)
    }

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    /** Visits segments before SOS: marker, payload start, payload length. */
    private inline fun forEachSegment(jpeg: ByteArray, visit: (marker: Int, start: Int, length: Int) -> Unit) {
        var pos = 2
        while (pos + 4 <= jpeg.size && (jpeg[pos].toInt() and 0xFF) == 0xFF) {
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) return
            val len = u16(jpeg, pos + 2)
            if (len < 2 || pos + 2 + len > jpeg.size) return
            visit(marker, pos + 4, len - 2)
            pos += 2 + len
        }
    }
}

object PngMetadata {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

    fun read(png: ByteArray): RawMetadata {
        var exif: ByteArray? = null
        var icc: ByteArray? = null
        forEachChunk(png) { type, start, length ->
            when (type) {
                "eXIf" -> if (exif == null) exif = png.copyOfRange(start, start + length).stripExifHeader()
                "iCCP" -> if (icc == null) {
                    // name\0 + compression method + zlib stream
                    var nameEnd = start
                    while (nameEnd < start + length && png[nameEnd] != 0.toByte()) nameEnd++
                    val dataStart = nameEnd + 2
                    if (dataStart <= start + length) {
                        icc = runCatching {
                            InflaterInputStream(png.inputStream(dataStart, start + length - dataStart)).readBytes()
                        }.getOrNull()
                    }
                }
            }
        }
        return RawMetadata(exif, icc)
    }

    /** Inserts iCCP and eXIf directly after IHDR. */
    fun write(png: ByteArray, metadata: RawMetadata): ByteArray {
        require(png.startsWith(SIGNATURE)) { "Not a PNG stream" }
        if (metadata.isEmpty) return png
        val ihdrEnd = 8 + 12 + u32(png, 8) // signature + length/type/crc + IHDR data
        val out = ByteArrayOutputStream(png.size + 4096)
        out.write(png, 0, ihdrEnd)
        metadata.icc?.let { icc ->
            val deflated = ByteArrayOutputStream().also { bos ->
                DeflaterOutputStream(bos, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(icc) }
            }.toByteArray()
            chunk(out, "iCCP", "icc".toByteArray() + byteArrayOf(0, 0) + deflated)
        }
        metadata.exif?.let { chunk(out, "eXIf", it) }
        // drop original copies so there are never duplicates
        var pos = ihdrEnd
        while (pos + 12 <= png.size) {
            val len = u32(png, pos)
            val type = png.ascii(pos + 4, 4)
            if (type != "eXIf" && type != "iCCP") out.write(png, pos, len + 12)
            pos += len + 12
        }
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }
        out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
    }

    private fun u32(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 4).order(ByteOrder.BIG_ENDIAN).int

    private inline fun forEachChunk(png: ByteArray, visit: (type: String, start: Int, length: Int) -> Unit) {
        if (!png.startsWith(SIGNATURE)) return
        var pos = 8
        while (pos + 12 <= png.size) {
            val len = u32(png, pos)
            if (len < 0 || pos + 12 + len > png.size) return
            visit(png.ascii(pos + 4, 4), pos + 8, len)
            pos += 12 + len
        }
    }
}

object WebpMetadata {
    private const val FLAG_ICC = 0x20
    private const val FLAG_ALPHA = 0x10
    private const val FLAG_EXIF = 0x08

    fun read(webp: ByteArray): RawMetadata {
        var exif: ByteArray? = null
        var icc: ByteArray? = null
        forEachChunk(webp) { type, start, length ->
            when (type) {
                "EXIF" -> exif = webp.copyOfRange(start, start + length).stripExifHeader()
                "ICCP" -> icc = webp.copyOfRange(start, start + length)
            }
        }
        return RawMetadata(exif, icc)
    }

    /** Rewrites the container as VP8X (extended) carrying ICCP before and EXIF after the image data. */
    fun write(webp: ByteArray, metadata: RawMetadata): ByteArray {
        require(webp.size >= 20 && webp.ascii(0, 4) == "RIFF" && webp.ascii(8, 4) == "WEBP") { "Not a WebP stream" }
        if (metadata.isEmpty) return webp

        var vp8x: ByteArray? = null
        val body = ByteArrayOutputStream()
        var firstImageChunk: Triple<String, Int, Int>? = null
        forEachChunk(webp) { type, start, length ->
            when (type) {
                "VP8X" -> vp8x = webp.copyOfRange(start, start + length)
                "ICCP", "EXIF" -> Unit
                else -> {
                    if (firstImageChunk == null && (type == "VP8 " || type == "VP8L")) firstImageChunk = Triple(type, start, length)
                    chunk(body, type, webp.copyOfRange(start, start + length))
                }
            }
        }
        val header = vp8x ?: run {
            val (type, start, _) = firstImageChunk ?: error("WebP stream has no image data")
            val (w, h, alpha) = canvas(webp, type, start)
            ByteArray(10).also {
                it[0] = if (alpha) FLAG_ALPHA.toByte() else 0
                put24(it, 4, w - 1)
                put24(it, 7, h - 1)
            }
        }.copyOf()
        var flags = header[0].toInt() and 0xFF
        if (metadata.icc != null) flags = flags or FLAG_ICC
        if (metadata.exif != null) flags = flags or FLAG_EXIF
        header[0] = flags.toByte()

        val out = ByteArrayOutputStream(webp.size + 4096)
        chunk(out, "VP8X", header)
        metadata.icc?.let { chunk(out, "ICCP", it) }
        out.write(body.toByteArray())
        metadata.exif?.let { chunk(out, "EXIF", it) }

        val payload = out.toByteArray()
        return ByteBuffer.allocate(12 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(4 + payload.size).put("WEBP".toByteArray()).put(payload).array()
    }

    private fun put24(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = (v ushr 16).toByte()
    }

    private fun le24(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16)

    /** width, height, hasAlpha from a bare VP8 / VP8L chunk. */
    private fun canvas(webp: ByteArray, type: String, start: Int): Triple<Int, Int, Boolean> =
        if (type == "VP8L") {
            val bits = ByteBuffer.wrap(webp, start + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
            Triple((bits and 0x3FFF) + 1, ((bits ushr 14) and 0x3FFF) + 1, ((bits ushr 28) and 1) == 1)
        } else {
            // VP8 key frame: 3-byte frame tag, 3-byte start code, then 14-bit width / height
            val w = (webp[start + 6].toInt() and 0xFF) or ((webp[start + 7].toInt() and 0x3F) shl 8)
            val h = (webp[start + 8].toInt() and 0xFF) or ((webp[start + 9].toInt() and 0x3F) shl 8)
            Triple(w, h, false)
        }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array())
        out.write(data)
        if (data.size % 2 == 1) out.write(0)
    }

    private inline fun forEachChunk(webp: ByteArray, visit: (type: String, start: Int, length: Int) -> Unit) {
        if (webp.size < 12 || webp.ascii(0, 4) != "RIFF" || webp.ascii(8, 4) != "WEBP") return
        var pos = 12
        while (pos + 8 <= webp.size) {
            val len = ByteBuffer.wrap(webp, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (len < 0 || pos + 8 + len > webp.size) return
            visit(webp.ascii(pos, 4), pos + 8, len)
            pos += 8 + len + (len and 1)
        }
    }

    /** Frame count from the ANIM / ANMF chunks; 1 for stills. */
    fun frameCount(webp: ByteArray): Int {
        var frames = 0
        forEachChunk(webp) { type, _, _ -> if (type == "ANMF") frames++ }
        return maxOf(frames, 1)
    }
}

/** Some writers prefix EXIF with `Exif\0\0`; the canonical in-memory form is the bare TIFF block. */
private fun ByteArray.stripExifHeader(): ByteArray = if (startsWith(EXIF_HEADER)) copyOfRange(EXIF_HEADER.size, size) else this
