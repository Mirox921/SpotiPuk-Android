package com.project.puk.api

import java.io.ByteArrayOutputStream

/** The length-delimited fields used by Spotify's entity Canvas lookup. */
internal object CanvasProtocol {
    data class Entry(val uri: String, val url: String, val fileId: String, val type: Long)

    fun request(uri: String): ByteArray {
        fun field(value: ByteArray): ByteArray = ByteArrayOutputStream().apply {
            write(10)
            var length = value.size
            while (length >= 128) {
                write((length and 127) or 128)
                length = length ushr 7
            }
            write(length)
            write(value)
        }.toByteArray()
        return field(field(uri.toByteArray(Charsets.UTF_8)))
    }

    private class Reader(val bytes: ByteArray) {
        var position = 0
        fun number(): Long {
            var result = 0L
            for (shift in 0..63 step 7) {
                require(position < bytes.size)
                val value = bytes[position++].toInt() and 255
                if (shift == 63) require(value <= 1)
                result = result or ((value and 127).toLong() shl shift)
                if (value < 128) return result
            }
            error("Invalid varint")
        }

        fun fields(consume: (Int, ByteArray?, Long?) -> Unit) {
            while (position < bytes.size) {
                val tag = number()
                val field = (tag ushr 3).toInt()
                require(field > 0)
                when ((tag and 7).toInt()) {
                    0 -> consume(field, null, number())
                    2 -> {
                        val length = number()
                        require(length in 0..(bytes.size - position).toLong())
                        val end = position + length.toInt()
                        consume(field, bytes.copyOfRange(position, end), null)
                        position = end
                    }
                    1 -> { require(bytes.size - position >= 8); position += 8 }
                    5 -> { require(bytes.size - position >= 4); position += 4 }
                    else -> error("Unsupported wire type")
                }
            }
        }
    }

    fun entries(bytes: ByteArray): List<Entry>? = runCatching {
        buildList {
            Reader(bytes).fields { field, value, _ ->
                if (field == 1 && value != null) {
                    var uri = ""
                    var url = ""
                    var fileId = ""
                    var type = 0L
                    Reader(value).fields { key, text, number ->
                        when (key) {
                            2 -> url = text?.toString(Charsets.UTF_8).orEmpty()
                            3 -> fileId = text?.toString(Charsets.UTF_8).orEmpty()
                            4 -> type = number ?: 0
                            5 -> uri = text?.toString(Charsets.UTF_8).orEmpty()
                        }
                    }
                    add(Entry(uri, url, fileId, type))
                }
            }
        }
    }.getOrNull()
}
