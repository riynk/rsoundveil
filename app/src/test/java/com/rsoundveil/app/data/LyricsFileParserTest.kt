package com.rsoundveil.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsFileParserTest {
    @Test
    fun parsesLineAndWordTimingFromLyricsfile() {
        val source = """
            version: '1.0'
            metadata:
              title: Song
              artist: Artist
              duration_ms: 4000
            lines:
              - text: 'Hello world'
                start_ms: 1000
                end_ms: 2000
                words:
                  - text: 'Hello '
                    start_ms: 1000
                    end_ms: 1500
                  - text: 'world'
                    start_ms: 1500
                    end_ms: 2000
            plain: |
              Hello world
        """.trimIndent()

        val lines = LyricsFileParser.parse(source)
        assertEquals(1, lines.size)
        assertEquals(1_000L, lines[0].startMs)
        assertEquals(2_000L, lines[0].endMs)
        assertEquals("Hello world", lines[0].words.joinToString("") { it.text })
        assertEquals(1_500L, lines[0].words[1].startMs)
    }

    @Test
    fun ignoresInvalidTimingAndNonMappingLines() {
        val source = """
            version: '1.0'
            lines:
              - text: invalid
                start_ms: -1
              - just text
              - text: valid
                start_ms: 25
                words:
                  - text: ok
                    start_ms: -5
        """.trimIndent()

        val lines = LyricsFileParser.parse(source)
        assertEquals(1, lines.size)
        assertEquals("valid", lines.single().text)
        assertTrue(lines.single().words.isEmpty())
    }

    @Test
    fun malformedYamlDoesNotCrashLyricsRendering() {
        assertTrue(LyricsFileParser.parse("lines: [unclosed").isEmpty())
    }
}
