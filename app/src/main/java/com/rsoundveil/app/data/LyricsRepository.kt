package com.rsoundveil.app.data

import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URLEncoder

class LyricsRepository {
    suspend fun find(track: MusicTrack): LyricsDocument {
        val params = buildList {
            add("track_name=" + enc(track.title))
            add("artist_name=" + enc(track.artist))
            if (track.album.isNotBlank()) add("album_name=" + enc(track.album))
            if (track.durationMs > 0) add("duration=" + track.durationMs / 1_000L)
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

    suspend fun translateToIndonesian(text: String): String {
        if (text.isBlank()) return ""
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        text.lines().forEach { line ->
            val append = if (current.isEmpty()) line else "\n" + line
            if (current.length + append.length > 430 && current.isNotEmpty()) {
                chunks.add(current.toString())
                current.setLength(0)
                current.append(line)
            } else current.append(append)
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        val translated = mutableListOf<String>()
        chunks.forEachIndexed { index, chunk ->
            if (index > 0) delay(450)
            val response = NetworkJson.get(
                "https://api.mymemory.translated.net/get?q=" + enc(chunk) + "&langpair=auto%7Cid"
            )
            val data = response.optJSONObject("responseData") ?: JSONObject()
            val value = data.optString("translatedText")
            if (value.isBlank()) error("Translation provider returned no text")
            translated.add(value)
        }
        return translated.joinToString("\n")
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
