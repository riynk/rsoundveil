package com.rsoundveil.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRepositoryTest {
    @Test fun splitsLongUnbrokenAsciiTextUnderByteLimit() {
        val parts = splitUtf8Segments("a".repeat(1200), 450)
        assertEquals(3, parts.size)
        assertTrue(parts.all { it.toByteArray(Charsets.UTF_8).size <= 450 })
        assertEquals("a".repeat(1200), parts.joinToString(""))
    }

    @Test fun splitsUtf8AndPreservesSurrogatePairs() {
        val value = "😀".repeat(300)
        val parts = splitUtf8Segments(value, 450)
        assertTrue(parts.all { it.toByteArray(Charsets.UTF_8).size <= 450 })
        assertEquals(value, parts.joinToString(""))
        assertTrue(parts.all { part ->
            var i = 0
            while (i < part.length) {
                val ch = part[i]
                if (Character.isHighSurrogate(ch)) { if (i + 1 >= part.length || !Character.isLowSurrogate(part[i + 1])) return@all false; i += 2 }
                else if (Character.isLowSurrogate(ch)) return@all false
                else i++
            }
            true
        })
    }

    @Test fun retainsWordBoundariesWhenPossible() {
        assertEquals(listOf("one two", "three"), splitUtf8Segments("one two three", 7))
    }
}
