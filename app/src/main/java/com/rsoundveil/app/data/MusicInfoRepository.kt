package com.rsoundveil.app.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class ArtistInfo(
    val id: String,
    val name: String,
    val type: String?,
    val country: String?,
    val disambiguation: String?,
    val beginDate: String?,
    val endDate: String?,
    val ended: Boolean,
    val genres: List<String>
)

data class AlbumInfo(
    val id: String,
    val title: String,
    val date: String?,
    val country: String?,
    val status: String?,
    val artistCredit: String?,
    val trackCount: Int,
    val coverArtUrl: String
)

data class MusicDetails(
    val artist: ArtistInfo?,
    val album: AlbumInfo?
)

class MusicInfoRepository {
    private val requestLock = Mutex()
    private var lastRequestAt = 0L

    suspend fun getDetails(track: MusicTrack): MusicDetails {
        val artist = track.artist.takeIf { it.isNotBlank() }?.let { findArtist(it) }
        val album = track.album.takeIf { it.isNotBlank() }?.let { findAlbum(it, track.artist) }
        return MusicDetails(artist, album)
    }

    private suspend fun findArtist(name: String): ArtistInfo? {
        val query = "artist:\"" + name.replace("\"", "") + "\""
        val url = "https://musicbrainz.org/ws/2/artist/?query=" + enc(query) + "&fmt=json&limit=5"
        val array = request(url).optJSONArray("artists") ?: return null
        val match = bestMatch(array, name, "name") ?: return null
        val life = match.optJSONObject("life-span")
        val tags = match.optJSONArray("tags").toStringList("name").take(8)
        return ArtistInfo(
            id = match.optString("id"),
            name = match.optString("name", name),
            type = match.optString("type").takeIf { it.isNotBlank() && it != "null" },
            country = match.optString("country").takeIf { it.isNotBlank() && it != "null" },
            disambiguation = match.optString("disambiguation").takeIf { it.isNotBlank() && it != "null" },
            beginDate = life?.optString("begin")?.takeIf { it.isNotBlank() && it != "null" },
            endDate = life?.optString("end")?.takeIf { it.isNotBlank() && it != "null" },
            ended = life?.optBoolean("ended", false) ?: false,
            genres = tags
        )
    }

    private suspend fun findAlbum(title: String, artist: String): AlbumInfo? {
        val query = "release:\"" + title.replace("\"", "") + "\"" +
            if (artist.isNotBlank()) " AND artist:\"" + artist.replace("\"", "") + "\"" else ""
        val url = "https://musicbrainz.org/ws/2/release/?query=" + enc(query) + "&fmt=json&limit=5"
        val array = request(url).optJSONArray("releases") ?: return null
        val match = bestMatch(array, title, "title") ?: return null
        val credits = match.optJSONArray("artist-credit")
        val creditText = credits?.let { values ->
            buildList {
                for (index in 0 until values.length()) {
                    when (val value = values.opt(index)) {
                        is JSONObject -> value.optJSONObject("artist")?.optString("name")?.takeIf { it.isNotBlank() }?.let(::add)
                        is String -> add(value)
                    }
                }
            }.joinToString("").takeIf { it.isNotBlank() }
        }
        val media = match.optJSONArray("media")
        val trackCount = media?.let { values ->
            (0 until values.length()).sumOf { values.optJSONObject(it)?.optInt("track-count") ?: 0 }
        } ?: 0
        val id = match.optString("id")
        return AlbumInfo(
            id = id,
            title = match.optString("title", title),
            date = match.optString("date").takeIf { it.isNotBlank() && it != "null" },
            country = match.optString("country").takeIf { it.isNotBlank() && it != "null" },
            status = match.optString("status").takeIf { it.isNotBlank() && it != "null" },
            artistCredit = creditText,
            trackCount = trackCount,
            coverArtUrl = "https://coverartarchive.org/release/" + id + "/front-250"
        )
    }

    private suspend fun request(url: String): JSONObject = requestLock.withLock {
        val waitMs = 1_100L - (System.currentTimeMillis() - lastRequestAt)
        if (lastRequestAt > 0L && waitMs > 0L) delay(waitMs)
        lastRequestAt = System.currentTimeMillis()
        NetworkJson.get(url)
    }

    private fun bestMatch(array: JSONArray, expected: String, field: String): JSONObject? {
        val expectedNormalized = expected.trim().lowercase()
        val candidates = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        return candidates.firstOrNull { it.optString(field).trim().equals(expectedNormalized, true) }
            ?: candidates.firstOrNull()
    }

    private fun JSONArray?.toStringList(field: String): List<String> {
        val values = this ?: return emptyList()
        return (0 until values.length()).mapNotNull { index ->
            values.optJSONObject(index)?.optString(field)?.takeIf { it.isNotBlank() && it != "null" }
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
