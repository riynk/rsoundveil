package com.rsoundveil.app.data

data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val artworkUrl: String? = null,
    val durationMs: Long = 0L,
    val provider: String,
    val streamUrl: String? = null,
    val downloadUrl: String? = null,
    val downloadAllowed: Boolean = false,
    val licenseLabel: String? = null,
    val localUri: String? = null,
    val isVideo: Boolean = false
)

data class LyricWord(
    val startMs: Long,
    val text: String,
    val endMs: Long? = null
)

data class LyricLine(
    val startMs: Long,
    val text: String,
    val endMs: Long? = null,
    val words: List<LyricWord> = emptyList()
)

data class LyricsDocument(
    val trackId: String,
    val plainLyrics: String?,
    val syncedLyrics: String?,
    val source: String,
    val wordSynced: Boolean = false,
    val lyricsFile: String? = null
) {
    val lines: List<LyricLine>
        get() {
            val wordLines = lyricsFile?.let(LyricsFileParser::parse).orEmpty()
            return wordLines.ifEmpty { LrcParser.parse(syncedLyrics.orEmpty()) }
        }
}

object LrcParser {
    private val stamp = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parse(raw: String): List<LyricLine> = raw.lineSequence().flatMap { line ->
        val matches = stamp.findAll(line).toList()
        val text = stamp.replace(line, "").trim()
        matches.map { match ->
            val minutes = match.groupValues[1].toLongOrNull() ?: 0L
            val seconds = match.groupValues[2].toLongOrNull() ?: 0L
            val fraction = match.groupValues[3]
            val millis = when (fraction.length) {
                1 -> (fraction.toLongOrNull() ?: 0L) * 100L
                2 -> (fraction.toLongOrNull() ?: 0L) * 10L
                3 -> fraction.toLongOrNull() ?: 0L
                else -> 0L
            }
            LyricLine(minutes * 60_000L + seconds * 1_000L + millis, text)
        }
    }.sortedBy { it.startMs }.toList()
}
