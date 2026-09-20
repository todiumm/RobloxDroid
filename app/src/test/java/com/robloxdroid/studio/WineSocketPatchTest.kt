package com.robloxdroid.studio

import org.junit.Assert.*
import org.junit.Test

class WineSocketPatchTest {
    @Test fun rewritesOriginalWithoutChangingBinarySize() {
        val original = "/tmp/.wine-%u/server-%s"
        val bytes = ("prefix:" + original + '\u0000' + ":suffix").toByteArray()
        val patched = WineSocketPatch.apply(bytes, original)!!
        assertEquals(bytes.size, patched.size)
        assertTrue(String(patched).startsWith("prefix:.wine-%u/server-%s\u0000"))
        assertTrue(String(patched).endsWith(":suffix"))
        assertArrayEquals(patched, WineSocketPatch.apply(patched, original))
    }

    @Test fun repairsPreviouslyTruncatedSocketPrefix() {
        val original = "/tmp/.wine-%u"
        val old = "%u".toByteArray().copyOf(original.length + 1)
        val patched = WineSocketPatch.apply(old, original)!!
        assertTrue(String(patched).startsWith(".wine-%u\u0000"))
        assertEquals(old.size, patched.size)
    }

    @Test fun doesNotRewriteOtherFormatStringsOrShortBuffers() {
        assertNull(WineSocketPatch.apply("%u\u0000other text".toByteArray(), "/tmp/.wine-%u"))
        assertNull(WineSocketPatch.apply(byteArrayOf(), "/tmp/.wine-%u"))
    }
}
