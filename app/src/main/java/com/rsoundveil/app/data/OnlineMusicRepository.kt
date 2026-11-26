package com.rsoundveil.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

class OnlineMusicRepository(context: Context) {
    private val prefs = context.getSharedPreferences("rsoundveil.settings", Context.MODE_PRIVATE)

    suspend fun search(query: String, audiusKey: String): List<MusicTrack> = coroutineScope {
        val jamendoId = prefs.getString("jamendo_client_id", "").orEmpty()
        val archive = async { runCatching { searchArchive(query, videos = false) }.getOrDefault(emptyList()) }
        val jamendo = async {
            if (jamendoId.isBlank()) emptyList()
            else runCatching { searchJamendo(query, jamendoId) }.getOrDefault(emptyList())
        }
        val audius = async {
            if (audiusKey.isBlank()) emptyList()
            else runCatching { searchAudius(query, audiusKey) }.getOrDefault(emptyList())
        }
        (jamendo.await() + audius.await() + archive.await()).distinctBy { it.provider + ":" + it.id }.take(60)
    }

    suspend fun searchVideos(query: String): List<MusicTrack> =
        runCatching { searchArchive(query, videos = true) }.getOrDefault(emptyList())

    fun saveJamendoClientId(value: String) {
        prefs.edit().putString("jamendo_client_id", value.trim()).apply()
    }

    suspend fun resolvePlayback(track: MusicTrack): MusicTrack {
        if (track.localUri != null) return track.copy(streamUrl = track.localUri)
        if (!track.streamUrl.isNullOrBlank()) return track
        if (track.provider != "Archive.org") error("This source did not provide a playable stream")
        val metadata = NetworkJson.get("https://archive.org/metadata/" + encode(track.id))
        val files = metadata.optJSONArray("files") ?: error("Archive item has no file list")
        val candidates = (0 until files.length()).mapNotNull { files.optJSONObject(it) }
            .filter { file ->
                val name = file.optString("name").lowercase()
                val supported = if (track.isVideo) {
                    name.endsWith(".mp4") || name.endsWith(".m4v") || name.endsWith(".webm")
                } else {
                    name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".ogg") ||
                        name.endsWith(".flac") || name.endsWith(".opus")
                }
                supported && !name.contains("_thumb")
            }
            .sortedByDescending { file ->
                val name = file.optString("name").lowercase()
                when {
                    name.endsWith(".mp4") -> 5
                    name.endsWith(".m4v") -> 4
                    name.endsWith(".webm") -> 3
                    name.endsWith(".mp3") -> 5
                    name.endsWith(".m4a") -> 4
                    name.endsWith(".ogg") || name.endsWith(".opus") -> 3
                    name.endsWith(".flac") -> 2
                    else -> 0
                }
            }
        val filename = candidates.firstOrNull()?.optString("name") ?: error("No supported audio file found")
        val url = "https://archive.org/download/" + encode(track.id) + "/" + filename.split("/").joinToString("/") { Uri.encode(it) }
        return track.copy(streamUrl = url, downloadUrl = url, downloadAllowed = true)
    }

    private suspend fun searchJamendo(query: String, clientId: String): List<MusicTrack> {
        val url = "https://api.jamendo.com/v3.0/tracks/?client_id=" + encode(clientId) +
            "&format=json&namesearch=" + encode(query) +
            "&type=single%20albumtrack&include=licenses&audioformat=mp32&limit=25"
        val results = NetworkJson.get(url).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val audio = item.optString("audio").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            MusicTrack(
                id = item.optString("id"),
                title = item.optString("name", "Unknown track"),
                artist = item.optString("artist_name", "Unknown artist"),
                album = item.optString("album_name"),
                artworkUrl = item.optString("album_image").ifBlank { item.optString("image") }.takeIf { it.startsWith("https://") },
                durationMs = item.optLong("duration") * 1_000L,
                provider = "Jamendo",
                streamUrl = audio,
                downloadUrl = item.optString("audiodownload").takeIf { it.startsWith("https://") },
                downloadAllowed = item.optBoolean("audiodownload_allowed", false),
                licenseLabel = "Jamendo — lihat lisensi pada sumber"
            )
        }
    }

    private suspend fun searchAudius(query: String, apiKey: String): List<MusicTrack> {
        val root = NetworkJson.get(
            "https://api.audius.co/v1/tracks/search?query=" + encode(query) + "&limit=25",
            mapOf("x-api-key" to apiKey)
        )
        val data = root.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            val user = item.optJSONObject("user")
            val art = item.optJSONObject("artwork")
            MusicTrack(
                id = id,
                title = item.optString("title", "Unknown track"),
                artist = user?.optString("name", "Unknown artist") ?: "Unknown artist",
                durationMs = item.optLong("duration") * 1_000L,
                artworkUrl = art?.optString("150x150")?.takeIf { it.startsWith("https://") },
                provider = "Audius",
                streamUrl = "https://api.audius.co/v1/tracks/" + encode(id) + "/stream",
                licenseLabel = "Audius — periksa ketentuan kreator"
            )
        }
    }

    private suspend fun searchArchive(query: String, videos: Boolean): List<MusicTrack> {
        val mediaFilter = if (videos) "movies" else "audio"
        val url = "https://archive.org/advancedsearch.php?q=" + encode(query.trim() + " AND mediatype:" + mediaFilter) +
            "&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=licenseurl&fl[]=year&rows=30&page=1&output=json"
        val docs = NetworkJson.get(url).optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        return (0 until docs.length()).mapNotNull { index ->
            val item = docs.optJSONObject(index) ?: return@mapNotNull null
            val license = item.optString("licenseurl")
            val recognized = license.contains("creativecommons.org", true) || license.contains("publicdomain", true)
            if (!recognized) return@mapNotNull null
            val creator = item.opt("creator")
            val artist = if (creator is org.json.JSONArray) {
                (0 until creator.length()).joinToString(", ") { creator.optString(it) }
            } else creator?.toString().orEmpty()
            val identifier = item.optString("identifier")
            MusicTrack(
                id = identifier,
                title = item.optString("title", identifier),
                artist = artist.ifBlank { "Unknown creator" },
                album = item.optString("year"),
                artworkUrl = "https://archive.org/services/img/" + Uri.encode(identifier),
                provider = "Archive.org",
                licenseLabel = license,
                downloadAllowed = true,
                isVideo = videos
            )
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
