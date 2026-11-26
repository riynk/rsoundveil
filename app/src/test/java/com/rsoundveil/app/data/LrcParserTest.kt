package com.rsoundveil.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {
    @Test
    fun parsesMultipleTimestampsAndCentiseconds() {
        val lines = LrcParser.parse("[00:12.34][00:14.56]Line")
        assertEquals(2, lines.size)
        assertEquals(12_340L, lines[0].startMs)
        assertEquals(14_560L, lines[1].startMs)
        assertEquals("Line", lines[0].text)
    }

    @Test
    fun parsesMillisecondsAndSorts() {
        val lines = LrcParser.parse("[01:02.345]later\n[00:04.5]earlier")
        assertEquals(4_500L, lines[0].startMs)
        assertEquals(62_345L, lines[1].startMs)
    }

    @Test
    fun ignoresLinesWithoutTimestamps() {
        assertEquals(0, LrcParser.parse("title\nartist").size)
    }
}
