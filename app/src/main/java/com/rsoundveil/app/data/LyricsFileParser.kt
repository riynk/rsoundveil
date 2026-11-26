package com.rsoundveil.app.data

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Safe reader for the Lyricsfile 1.0 draft format.
 * YAML custom tags are not enabled; malformed or unsupported documents return no parsed lines.
 */
object LyricsFileParser {
    private val yaml = Yaml(SafeConstructor(LoaderOptions()))

    fun parse(source: String): List<LyricLine> = runCatching {
        val root = yaml.load<Any?>(source) as? Map<*, *> ?: return emptyList()
        val rawLines = root["lines"] as? List<*> ?: return emptyList()
        rawLines.mapNotNull { rawLine ->
            val line = rawLine as? Map<*, *> ?: return@mapNotNull null
            val start = (line["start_ms"] as? Number)?.toLong() ?: return@mapNotNull null
            if (start < 0L) return@mapNotNull null
            val text = line["text"] as? String ?: return@mapNotNull null
            val end = (line["end_ms"] as? Number)?.toLong()?.takeIf { it >= start }
            val words = (line["words"] as? List<*>).orEmpty().mapNotNull { rawWord ->
                val word = rawWord as? Map<*, *> ?: return@mapNotNull null
                val wordStart = (word["start_ms"] as? Number)?.toLong() ?: return@mapNotNull null
                val wordText = word["text"] as? String ?: return@mapNotNull null
                if (wordStart < 0L) return@mapNotNull null
                val wordEnd = (word["end_ms"] as? Number)?.toLong()?.takeIf { it >= wordStart }
                LyricWord(wordStart, wordText, wordEnd)
            }.sortedBy { it.startMs }
            LyricLine(start, text, end, words)
        }.sortedBy { it.startMs }
    }.getOrDefault(emptyList())
}
