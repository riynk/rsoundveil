package com.rsoundveil.app.data

import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URLEncoder

class LyricsRepository {
    suspend fun find(track: MusicTrack): LyricsDocument {
        val fromLrclib = runCatching { findLrclib(track) }.getOrNull()
        if (fromLrclib != null && (!fromLrclib.plainLyrics.isNullOrBlank() || !fromLrclib.syncedLyrics.isNullOrBlank())) {
            return fromLrclib
        }

        val fromLyricsOvh = runCatching { findLyricsOvh(track) }.getOrNull()
        if (fromLyricsOvh != null && !fromLyricsOvh.plainLyrics.isNullOrBlank()) return fromLyricsOvh

        throw IllegalStateException("No lyrics found from LRCLIB or lyrics.ovh")
    }

    private suspend fun findLrclib(track: MusicTrack): LyricsDocument {
        val params = buildList {
            add("track_name=" + enc(track.title))
            add("artist_name=" + enc(track.artist))
            if (track.album.isNotBlank()) add("album_name=" + enc(track.album))
            if (track.durationMs in 1_000L..3_600_000L) add("duration=" + track.durationMs / 1_000L)
        }.joinToString("&")
        val result = NetworkJson.get("https://lrclib.net/api/get?" + params)
        return LyricsDocument(
            trackId = track.id,
            plainLyrics = result.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" },
            syncedLyrics = result.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" },
            source = "LRCLIB",
            wordSynced = result.optBoolean("hasWordSync", false)
        )
    }

    private suspend fun findLyricsOvh(track: MusicTrack): LyricsDocument {
        val artist = pathSegment(track.artist)
        val title = pathSegment(track.title)
        val result = NetworkJson.get("https://api.lyrics.ovh/v1/" + artist + "/" + title)
        return LyricsDocument(
            trackId = track.id,
            plainLyrics = result.optString("lyrics").takeIf { it.isNotBlank() && it != "null" },
            syncedLyrics = null,
            source = "lyrics.ovh",
            wordSynced = false
        )
    }

    /**
     * Translates lyric text only, keeping LRC timestamps and metadata tags unchanged.
     * MyMemory requires an explicit ISO language pair and a maximum of 500 UTF-8 bytes per segment.
     */
    suspend fun translateToIndonesian(text: String, sourceLanguage: String = "en"): String {
        if (text.isBlank()) return ""
        val language = sourceLanguage.trim().lowercase()
        require(language.matches(Regex("[a-z]{2,3}([_-][a-z]{2})?"))) {
            "Enter a valid ISO language code such as en, ja, ko, or es."
        }
        if (language == "id") return text

        val timePrefix = Regex("""^((?:\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?])+\s*)(.*)$""")
        val metadataTag = Regex("""^\[[a-z]{2}:[^]]+]$""", RegexOption.IGNORE_CASE)
        val translatedLines = ArrayList<String>(text.lines().size)
        var requestCount = 0

        for (line in text.lines()) {
            if (line.isBlank() || metadataTag.matches(line)) {
                translatedLines += line
                continue
            }
            val match = timePrefix.matchEntire(line)
            val prefix = match?.groupValues?.get(1).orEmpty()
            val lyricText = (match?.groupValues?.get(2) ?: line).trimEnd()
            if (lyricText.isBlank()) {
                translatedLines += prefix
                continue
            }

            val parts = splitUtf8Segments(lyricText, 450)
            val translatedParts = ArrayList<String>(parts.size)
            for (part in parts) {
                if (requestCount > 0) delay(300)
                translatedParts += translateSegment(part, language)
                requestCount++
            }
            translatedLines += prefix + translatedParts.joinToString(" ")
        }
        return translatedLines.joinToString("\n")
    }

    private suspend fun translateSegment(segment: String, sourceLanguage: String): String {
        val url = "https://api.mymemory.translated.net/get?q=" + enc(segment) +
            "&langpair=" + enc(sourceLanguage + "|id")
        val response = NetworkJson.get(url)
        val data = response.optJSONObject("responseData") ?: JSONObject()
        return data.optString("translatedText").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Translation provider returned no text")
    }

    private fun splitUtf8Segments(value: String, maxBytes: Int): List<String> {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return listOf(value)
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (word in value.split(Regex("\\s+"))) {
            val candidate = if (current.isEmpty()) word else current.toString() + " " + word
            if (candidate.toByteArray(Charsets.UTF_8).size > maxBytes && current.isNotEmpty()) {
                result += current.toString()
                current.setLength(0)
                current.append(word)
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(word)
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    private fun pathSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
