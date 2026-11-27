package com.rsoundveil.app.data

import android.content.Context
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URLEncoder

class LyricsRepository(context: Context) {
    private val cache = context.getSharedPreferences("rsoundveil.lyrics.cache", Context.MODE_PRIVATE)

    fun cached(track: MusicTrack): LyricsDocument? {
        val raw = cache.getString(cacheKey(track), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            LyricsDocument(
                trackId = track.id,
                plainLyrics = json.optString("plain").takeIf { it.isNotBlank() && it != "null" },
                syncedLyrics = json.optString("synced").takeIf { it.isNotBlank() && it != "null" },
                source = json.optString("source", "Offline cache"),
                wordSynced = json.optBoolean("wordSynced", false),
                lyricsFile = json.optString("lyricsFile").takeIf { it.isNotBlank() && it != "null" }
            )
        }.getOrNull()
    }

    fun cachedTranslation(track: MusicTrack, sourceLanguage: String): String? =
        cache.getString(translationKey(track, sourceLanguage), null)

    fun saveTranslation(track: MusicTrack, sourceLanguage: String, translation: String) {
        cache.edit().putString(translationKey(track, sourceLanguage), translation).apply()
    }

    private fun cacheDocument(track: MusicTrack, document: LyricsDocument) {
        val json = JSONObject()
            .put("plain", document.plainLyrics)
            .put("synced", document.syncedLyrics)
            .put("source", document.source)
            .put("wordSynced", document.wordSynced)
            .put("lyricsFile", document.lyricsFile)
        cache.edit().putString(cacheKey(track), json.toString()).apply()
    }

    private fun cacheKey(track: MusicTrack) = "lyrics:" + track.provider + ":" + track.id
    private fun translationKey(track: MusicTrack, sourceLanguage: String) =
        "translation:" + track.provider + ":" + track.id + ":" + sourceLanguage.trim().lowercase()

    suspend fun find(track: MusicTrack): LyricsDocument {
        val fromLrclib = runCatching { findLrclib(track) }.getOrNull()
        val document = if (fromLrclib != null &&
            (!fromLrclib.plainLyrics.isNullOrBlank() || !fromLrclib.syncedLyrics.isNullOrBlank())
        ) {
            fromLrclib
        } else {
            val fromLyricsOvh = runCatching { findLyricsOvh(track) }.getOrNull()
            fromLyricsOvh?.takeIf { !it.plainLyrics.isNullOrBlank() }
                ?: throw IllegalStateException("No lyrics found from LRCLIB or lyrics.ovh")
        }
        cacheDocument(track, document)
        return document
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
            wordSynced = result.optBoolean("hasWordSync", false),
            lyricsFile = result.optString("lyricsfile").takeIf { it.isNotBlank() && it != "null" }
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

    private fun pathSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}

/** Splits arbitrary Unicode text without exceeding the provider byte limit or breaking surrogate pairs. */
internal fun splitUtf8Segments(value: String, maxBytes: Int): List<String> {
    require(maxBytes > 0)
    if (value.isEmpty()) return emptyList()
    val output = mutableListOf<String>()
    var chunk = StringBuilder()
    var bytes = 0
    for (word in value.split(Regex("\\s+"))) {
        if (word.isEmpty()) continue
        val wordBytes = word.toByteArray(Charsets.UTF_8).size
        if (wordBytes <= maxBytes && bytes + (if (chunk.isEmpty()) 0 else 1) + wordBytes <= maxBytes) {
            if (chunk.isNotEmpty()) { chunk.append(' '); bytes++ }
            chunk.append(word); bytes += wordBytes
            continue
        }
        if (chunk.isNotEmpty()) { output += chunk.toString(); chunk = StringBuilder(); bytes = 0 }
        var piece = StringBuilder()
        var pieceBytes = 0
        var index = 0
        while (index < word.length) {
            val cpLength = if (Character.isHighSurrogate(word[index]) && index + 1 < word.length && Character.isLowSurrogate(word[index + 1])) 2 else 1
            val cp = word.substring(index, index + cpLength)
            val cpBytes = cp.toByteArray(Charsets.UTF_8).size
            require(cpBytes <= maxBytes) { "maxBytes too small for Unicode code point" }
            if (pieceBytes + cpBytes > maxBytes) { output += piece.toString(); piece = StringBuilder(); pieceBytes = 0 }
            piece.append(cp); pieceBytes += cpBytes; index += cpLength
        }
        if (piece.isNotEmpty()) { chunk.append(piece); bytes = pieceBytes }
    }
    if (chunk.isNotEmpty()) output += chunk.toString()
    return output
}
