package app.cloudipix.gallery.core.media

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * HEIF·AVIF 사진에서 썸네일 항목만 떼어 작은 HEIF 파일로 다시 묶은 뒤 디코딩한다.
 * 썸네일은 원본과 같은 코덱으로 압축되어 있어 기기의 HEIF 디코더가 그대로 푼다.
 * 메타데이터가 있는 앞부분과 썸네일 데이터만 읽고, 썸네일이 없거나 구조를 알 수 없으면 null을 돌려준다.
 */
internal fun heifThumbnail(reader: RangeReader, target: ThumbnailTarget): Bitmap? = try {
    extractHeifThumbnail(reader, target)
} catch (_: IOException) {
    null
} catch (_: RuntimeException) {
    // 손상된 파일의 잘못된 위치나 크기는 썸네일이 없는 것으로 본다.
    null
}

private fun extractHeifThumbnail(reader: RangeReader, target: ThumbnailTarget): Bitmap? {
    val head = reader.read(0, HEIF_HEAD_BYTES)
    val top = HeifTop.scan(head) ?: return null
    val metaEnd = top.metaStart + top.metaSize
    val meta = when {
        metaEnd <= head.size -> head.copyOfRange(top.metaStart.toInt(), metaEnd.toInt())
        top.metaSize <= MAX_META_BYTES -> reader.read(top.metaStart, top.metaSize.toInt())
            .takeIf { it.size.toLong() == top.metaSize } ?: return null
        else -> return null
    }
    val parsed = HeifMeta.parse(meta)
    val thumbnail = parsed.thumbnail() ?: return null
    val data = parsed.read(thumbnail, head, reader) ?: return null
    val file = repackage(top.ftyp, parsed, thumbnail, data)
    val bitmap = decodeThumbnail(ImageDecoder.createSource(ByteBuffer.wrap(file)), target.size, target.fill)
    if (maxOf(bitmap.width, bitmap.height) >= MIN_EMBEDDED_EDGE) return bitmap
    bitmap.recycle()
    return null
}

/** 파일 맨 앞의 `ftyp`와 `meta` 위치. `meta`는 앞부분보다 길 수 있어 위치와 크기만 둔다. */
private class HeifTop(val ftyp: ByteArray, val metaStart: Long, val metaSize: Long) {
    companion object {
        fun scan(head: ByteArray): HeifTop? {
            var ftyp: ByteArray? = null
            var offset = 0L
            while (offset + 8 <= head.size) {
                val at = offset.toInt()
                var size = head.u32(at)
                val type = head.fourcc(at + 4)
                val header = if (size == 1L) {
                    if (at + 16 > head.size) return null
                    size = head.u64(at + 8)
                    16
                } else 8
                if (size < header) return null
                when (type) {
                    "ftyp" -> {
                        if (offset + size > head.size) return null
                        ftyp = head.copyOfRange(at, (offset + size).toInt())
                    }
                    "meta" -> return ftyp?.let { HeifTop(it, offset, size) }
                }
                offset += size
            }
            return null
        }
    }
}

/** 항목 데이터가 있는 곳. [method] 0은 파일 위치, 1은 `idat` 안의 위치다. */
private class ItemLocation(val method: Int, val extents: List<Pair<Long, Long>>)

private class Association(val essential: Boolean, val index: Int)

/** `meta` 상자에서 썸네일을 꺼내는 데 필요한 내용만 읽은 결과. */
private class HeifMeta(
    val handler: ByteArray,
    val primary: Long,
    val types: Map<Long, String>,
    val locations: Map<Long, ItemLocation>,
    val thumbnailsOf: Map<Long, List<Long>>,
    val properties: List<ByteArray>,
    val associations: Map<Long, List<Association>>,
    val idat: ByteArray?,
) {
    /** 대표 이미지의 썸네일 중 한 장짜리로 압축된 항목을 고른다. */
    fun thumbnail(): Long? = thumbnailsOf.entries
        .filter { (item, targets) -> primary in targets && types[item] in CODED_TYPES && item in locations }
        .map { it.key }
        .firstOrNull()

    /**
     * 썸네일 데이터를 읽는다. 앞부분에 이미 있으면 다시 받지 않는다.
     * 너무 크거나 일부만 읽히면 null을 돌려준다.
     */
    fun read(item: Long, head: ByteArray, reader: RangeReader): ByteArray? {
        val location = locations[item] ?: return null
        val total = location.extents.sumOf { it.second }
        if (total <= 0 || total > MAX_THUMBNAIL_BYTES) return null
        val output = ByteArrayOutputStream(total.toInt())
        for ((offset, length) in location.extents) {
            val bytes = when (location.method) {
                0 -> if (offset + length <= head.size) {
                    head.copyOfRange(offset.toInt(), (offset + length).toInt())
                } else {
                    reader.read(offset, length.toInt())
                }
                1 -> {
                    val idat = idat ?: return null
                    if (offset + length > idat.size) return null
                    idat.copyOfRange(offset.toInt(), (offset + length).toInt())
                }
                else -> return null
            }
            if (bytes.size.toLong() != length) return null
            output.write(bytes)
        }
        return output.toByteArray()
    }

    /**
     * 썸네일에 붙은 속성 상자와 필수 여부. 썸네일에 회전·반전 속성이 없으면 대표 이미지의 것을 붙여
     * 원본과 같은 방향으로 보이게 한다.
     */
    fun propertiesOf(item: Long): List<Pair<Boolean, ByteArray>> {
        fun resolve(id: Long) = associations[id].orEmpty()
            .mapNotNull { association -> properties.getOrNull(association.index - 1)?.let { association.essential to it } }
        val own = resolve(item)
        if (own.any { it.second.fourcc(4) in ORIENTATION_TYPES }) return own
        return own + resolve(primary).filter { it.second.fourcc(4) in ORIENTATION_TYPES }
    }

    companion object {
        @Throws(IOException::class)
        fun parse(meta: ByteArray): HeifMeta {
            val root = boxes(meta, 0, meta.size).firstOrNull { it.type == "meta" } ?: throw IOException()
            var handler: ByteArray? = null
            var primary: Long? = null
            val types = HashMap<Long, String>()
            val locations = HashMap<Long, ItemLocation>()
            val thumbnailsOf = HashMap<Long, List<Long>>()
            val properties = ArrayList<ByteArray>()
            val associations = HashMap<Long, MutableList<Association>>()
            var idat: ByteArray? = null
            // meta는 버전과 플래그 4바이트 뒤에 하위 상자가 온다.
            for (box in boxes(meta, root.body + 4, root.end)) {
                when (box.type) {
                    "hdlr" -> handler = meta.copyOfRange(box.start, box.end)
                    "pitm" -> primary = Cursor(meta, box.body, box.end).run {
                        val version = u8(); skip(3)
                        if (version == 0) u16().toLong() else u32()
                    }
                    "iloc" -> parseLocations(meta, box, locations)
                    "iinf" -> parseItemTypes(meta, box, types)
                    "iref" -> parseThumbnailReferences(meta, box, thumbnailsOf)
                    "iprp" -> for (child in boxes(meta, box.body, box.end)) {
                        when (child.type) {
                            "ipco" -> boxes(meta, child.body, child.end).forEach { properties += meta.copyOfRange(it.start, it.end) }
                            "ipma" -> parseAssociations(meta, child, associations)
                        }
                    }
                    "idat" -> idat = meta.copyOfRange(box.body, box.end)
                }
            }
            return HeifMeta(
                handler = handler ?: throw IOException(),
                primary = primary ?: throw IOException(),
                types = types,
                locations = locations,
                thumbnailsOf = thumbnailsOf,
                properties = properties,
                associations = associations,
                idat = idat,
            )
        }

        private fun parseLocations(meta: ByteArray, box: Box, into: MutableMap<Long, ItemLocation>) = Cursor(meta, box.body, box.end).run {
            val version = u8(); skip(3)
            val sizes = u8()
            val offsetSize = sizes shr 4
            val lengthSize = sizes and 0xF
            val moreSizes = u8()
            val baseOffsetSize = moreSizes shr 4
            val indexSize = if (version == 1 || version == 2) moreSizes and 0xF else 0
            val count = if (version < 2) u16().toLong() else u32()
            repeat(count.toInt()) {
                val item = if (version < 2) u16().toLong() else u32()
                val method = if (version == 1 || version == 2) u16() and 0xF else 0
                skip(2) // data_reference_index
                val base = sized(baseOffsetSize)
                val extents = List(u16()) {
                    if (indexSize > 0) sized(indexSize)
                    val offset = sized(offsetSize)
                    val length = sized(lengthSize)
                    // 길이 0은 파일 끝까지라는 뜻이라 썸네일 크기를 알 수 없다.
                    if (length <= 0) throw IOException()
                    base + offset to length
                }
                into[item] = ItemLocation(method, extents)
            }
        }

        private fun parseItemTypes(meta: ByteArray, box: Box, into: MutableMap<Long, String>) {
            val version = meta.u8(box.body)
            val entries = box.body + 4 + if (version == 0) 2 else 4
            for (entry in boxes(meta, entries, box.end)) {
                if (entry.type != "infe") continue
                Cursor(meta, entry.body, entry.end).run {
                    val entryVersion = u8(); skip(3)
                    if (entryVersion < 2) return@run
                    val item = if (entryVersion == 2) u16().toLong() else u32()
                    skip(2) // item_protection_index
                    into[item] = fourcc()
                }
            }
        }

        private fun parseThumbnailReferences(meta: ByteArray, box: Box, into: MutableMap<Long, List<Long>>) {
            val version = meta.u8(box.body)
            for (reference in boxes(meta, box.body + 4, box.end)) {
                if (reference.type != "thmb") continue
                Cursor(meta, reference.body, reference.end).run {
                    val from = if (version == 0) u16().toLong() else u32()
                    into[from] = List(u16()) { if (version == 0) u16().toLong() else u32() }
                }
            }
        }

        private fun parseAssociations(meta: ByteArray, box: Box, into: MutableMap<Long, MutableList<Association>>) =
            Cursor(meta, box.body, box.end).run {
                val version = u8()
                val flags = (u8() shl 16) or (u8() shl 8) or u8()
                repeat(u32().toInt()) {
                    val item = if (version < 1) u16().toLong() else u32()
                    val list = into.getOrPut(item) { ArrayList() }
                    repeat(u8()) {
                        list += if (flags and 1 != 0) {
                            val value = u16()
                            Association(value and 0x8000 != 0, value and 0x7FFF)
                        } else {
                            val value = u8()
                            Association(value and 0x80 != 0, value and 0x7F)
                        }
                    }
                }
            }
    }
}

/**
 * 썸네일 항목 하나만 대표 이미지로 둔 HEIF 파일을 만든다. 원래 파일의 `ftyp`와 `hdlr`,
 * 썸네일의 속성 상자는 그대로 옮기고, 항목 번호는 1, 데이터는 바로 뒤의 `mdat`에 둔다.
 */
private fun repackage(ftyp: ByteArray, meta: HeifMeta, item: Long, data: ByteArray): ByteArray {
    val properties = meta.propertiesOf(item).take(MAX_PROPERTY_INDEX)
    val type = meta.types.getValue(item)
    fun metaBox(dataOffset: Long): ByteArray {
        val pitm = fullBox("pitm", u16(1))
        // offset_size 4, length_size 4, base_offset_size 0
        val iloc = fullBox("iloc", byteArrayOf(0x44, 0x00) + u16(1) + u16(1) + u16(0) + u16(1) + u32(dataOffset) + u32(data.size.toLong()))
        val infe = fullBox("infe", u16(1) + u16(0) + type.toByteArray(Charsets.US_ASCII) + byteArrayOf(0), version = 2)
        val iinf = fullBox("iinf", u16(1) + infe)
        val ipco = box("ipco", properties.fold(ByteArray(0)) { all, (_, property) -> all + property })
        val links = ByteArray(properties.size) { index ->
            ((if (properties[index].first) 0x80 else 0) or (index + 1)).toByte()
        }
        val ipma = fullBox("ipma", u32(1) + u16(1) + byteArrayOf(properties.size.toByte()) + links)
        val iprp = box("iprp", ipco + ipma)
        return fullBox("meta", meta.handler + pitm + iloc + iinf + iprp)
    }
    // meta 크기는 데이터 위치 값과 관계없이 같으므로 한 번 재어 위치를 정한다.
    val dataOffset = ftyp.size + metaBox(0).size + 8L
    return ftyp + metaBox(dataOffset) + box("mdat", data)
}

private class Box(val type: String, val start: Int, val body: Int, val end: Int)

/** [start]부터 [end]까지의 상자를 나열한다. 범위를 벗어나는 상자에서 멈춘다. */
private fun boxes(bytes: ByteArray, start: Int, end: Int): List<Box> {
    val result = ArrayList<Box>()
    var offset = start
    while (offset + 8 <= end) {
        var size = bytes.u32(offset)
        val type = bytes.fourcc(offset + 4)
        var header = 8
        if (size == 1L) {
            if (offset + 16 > end) break
            size = bytes.u64(offset + 8)
            header = 16
        } else if (size == 0L) {
            size = (end - offset).toLong()
        }
        if (size < header || offset + size > end) break
        result += Box(type, offset, offset + header, (offset + size).toInt())
        offset += size.toInt()
    }
    return result
}

/** 범위를 벗어나면 [IOException]을 던지는 읽기 위치. */
private class Cursor(private val bytes: ByteArray, private var position: Int, private val end: Int) {
    private fun take(count: Int): Int {
        if (count < 0 || position + count > end) throw IOException("HEIF 상자가 잘렸습니다.")
        return position.also { position += count }
    }

    fun skip(count: Int) { take(count) }
    fun u8(): Int = bytes.u8(take(1))
    fun u16(): Int = bytes.u16(take(2))
    fun u32(): Long = bytes.u32(take(4))
    fun fourcc(): String = bytes.fourcc(take(4))

    /** 0·4·8바이트 크기의 정수. */
    fun sized(size: Int): Long = when (size) {
        0 -> 0L
        4 -> u32()
        8 -> bytes.u64(take(8))
        else -> throw IOException("지원하지 않는 HEIF 정수 크기입니다.")
    }
}

private fun box(type: String, body: ByteArray): ByteArray = u32(8L + body.size) + type.toByteArray(Charsets.US_ASCII) + body

private fun fullBox(type: String, body: ByteArray, version: Int = 0): ByteArray =
    box(type, byteArrayOf(version.toByte(), 0, 0, 0) + body)

private fun u16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

private fun u32(value: Long) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

private fun ByteArray.u8(at: Int) = this[at].toInt() and 0xFF

private fun ByteArray.u16(at: Int) = (u8(at) shl 8) or u8(at + 1)

private fun ByteArray.u32(at: Int): Long = (u16(at).toLong() shl 16) or u16(at + 2).toLong()

private fun ByteArray.u64(at: Int): Long = (u32(at) shl 32) or u32(at + 4)

private fun ByteArray.fourcc(at: Int): String = String(this, at, 4, Charsets.US_ASCII)

// 한 장짜리로 압축된 썸네일만 다시 묶는다. 타일로 나뉜 grid 썸네일은 쓰지 않는다.
private val CODED_TYPES = setOf("hvc1", "av01")
private val ORIENTATION_TYPES = setOf("irot", "imir")
// meta는 보통 수십 KB라 앞부분에 들어온다.
private const val HEIF_HEAD_BYTES = 128 * 1024
private const val MAX_META_BYTES = 1024L * 1024
private const val MAX_THUMBNAIL_BYTES = 2L * 1024 * 1024
// ipma의 1바이트 연결은 번호를 7비트로 적는다.
private const val MAX_PROPERTY_INDEX = 127
