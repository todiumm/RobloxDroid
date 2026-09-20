package com.robloxdroid.studio

/** Rewrite only complete NUL-padded string slots, preserving ELF offsets. */
internal object WineSocketPatch {
    fun apply(data: ByteArray, original: String): ByteArray? {
        val originalBytes = (original + '\u0000').toByteArray(Charsets.ISO_8859_1)
        val expected = original.removePrefix("/tmp/").toByteArray(Charsets.ISO_8859_1)
        val previous = original.removePrefix("/tmp/.wine-")
            .toByteArray(Charsets.ISO_8859_1).copyOf(originalBytes.size)
        val replacement = expected.copyOf(originalBytes.size)
        val result = data.copyOf()
        var matched = false
        var i = 0
        while (i <= data.size - originalBytes.size) {
            fun matches(pattern: ByteArray) = pattern.indices.all { data[i + it] == pattern[it] }
            if (matches(originalBytes) || matches(previous) || matches(replacement)) {
                replacement.copyInto(result, i)
                matched = true
                i += originalBytes.size
            } else i++
        }
        return if (matched) result else null
    }
}
