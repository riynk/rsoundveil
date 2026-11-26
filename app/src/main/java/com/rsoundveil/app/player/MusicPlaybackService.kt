package com.rsoundveil.app.player

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListenableFutureTask
import com.rsoundveil.app.MainActivity
import com.rsoundveil.app.data.MusicTrack
import com.rsoundveil.app.data.OnlineMusicRepository
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * One player/session for the phone UI, system controls, Bluetooth and Android Auto.
 * The car library deliberately exposes audio only.
 */
@UnstableApi
class MusicPlaybackService : MediaLibraryService() {
    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var libraryCallback: CarLibraryCallback

    override fun onCreate() {
        super.onCreate()

        // Catalogue credentials are never attached to media requests or redirects.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("RSoundveil/0.1.0")
        val upstreamFactory = DefaultDataSource.Factory(this, httpFactory)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(upstreamFactory))
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true
                )
                setHandleAudioBecomingNoisy(true)
            }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        libraryCallback = CarLibraryCallback()
        mediaLibrarySession = MediaLibrarySession.Builder(this, player, libraryCallback)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaLibrarySession

    override fun onDestroy() {
        if (::libraryCallback.isInitialized) libraryCallback.shutdown()
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        super.onDestroy()
    }

    private inner class CarLibraryCallback : MediaLibrarySession.Callback {
        private val executor: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "rsoundveil-media-library").apply { isDaemon = true }
        }
        private val mainHandler = Handler(Looper.getMainLooper())
        private val onlineRepository = OnlineMusicRepository(this@MusicPlaybackService)
        private val onlineTracks = ConcurrentHashMap<String, MusicTrack>()
        private val searchResults = ConcurrentHashMap<String, List<MediaItem>>()

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val extras = Bundle().apply {
                putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
            }
            val rootParams = LibraryParams.Builder().setExtras(extras).build()
            return Futures.immediateFuture(
                LibraryResult.ofItem(folder(ROOT_ID, "rsoundveil"), rootParams)
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = submit {
            val items = when (parentId) {
                ROOT_ID -> listOf(
                    folder(ALL_TRACKS_ID, "All songs"),
                    folder(RECENT_TRACKS_ID, "Recently added"),
                    folder(ALBUMS_ID, "Albums"),
                    folder(ARTISTS_ID, "Artists")
                )
                ALL_TRACKS_ID -> queryLocalTracks(recentFirst = false)
                RECENT_TRACKS_ID -> queryLocalTracks(recentFirst = true)
                ALBUMS_ID -> queryAlbums()
                ARTISTS_ID -> queryArtists()
                else -> when {
                    parentId.startsWith(ALBUM_PREFIX) ->
                        parentId.removePrefix(ALBUM_PREFIX).toLongOrNull()
                            ?.let { queryLocalTracks(albumId = it) }.orEmpty()
                    parentId.startsWith(ARTIST_PREFIX) ->
                        parentId.removePrefix(ARTIST_PREFIX).toLongOrNull()
                            ?.let { queryLocalTracks(artistId = it) }.orEmpty()
                    else -> emptyList()
                }
            }
            val size = pageSize.coerceIn(1, MAX_PAGE_SIZE)
            val start = (page.toLong().coerceAtLeast(0L) * size)
                .coerceAtMost(items.size.toLong()).toInt()
            LibraryResult.ofItemList(items.drop(start).take(size), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> = submit {
            val item = when (mediaId) {
                ROOT_ID -> folder(ROOT_ID, "rsoundveil")
                ALL_TRACKS_ID -> folder(ALL_TRACKS_ID, "All songs")
                RECENT_TRACKS_ID -> folder(RECENT_TRACKS_ID, "Recently added")
                ALBUMS_ID -> folder(ALBUMS_ID, "Albums")
                ARTISTS_ID -> folder(ARTISTS_ID, "Artists")
                else -> when {
                    mediaId.startsWith(ALBUM_PREFIX) ->
                        mediaId.removePrefix(ALBUM_PREFIX).toLongOrNull()?.let(::findAlbum)
                    mediaId.startsWith(ARTIST_PREFIX) ->
                        mediaId.removePrefix(ARTIST_PREFIX).toLongOrNull()?.let(::findArtist)
                    mediaId.startsWith(LOCAL_PREFIX) ->
                        mediaId.removePrefix(LOCAL_PREFIX).toLongOrNull()
                            ?.let { queryLocalTracks(id = it).firstOrNull() }
                    else -> null
                }
            }
            if (item != null) LibraryResult.ofItem(item, null)
            else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> = submit {
            val items = loadSearchResults(query)
            val cacheKey = query.trim().lowercase()
            searchResults[cacheKey] = items
            if (searchResults.size > MAX_CACHED_SEARCHES) {
                searchResults.keys.firstOrNull { it != cacheKey }?.let(searchResults::remove)
            }
            mainHandler.post {
                session.notifySearchResultChanged(browser, query, items.size, params)
            }
            LibraryResult.ofVoid(params)
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = submit {
            val normalizedQuery = query.trim()
            val items = searchResults[normalizedQuery.lowercase()]
                ?: loadSearchResults(normalizedQuery).also {
                    searchResults[normalizedQuery.lowercase()] = it
                }
            val size = pageSize.coerceIn(1, MAX_PAGE_SIZE)
            val start = (page.toLong().coerceAtLeast(0L) * size)
                .coerceAtMost(items.size.toLong()).toInt()
            LibraryResult.ofItemList(items.drop(start).take(size), params)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> = submit {
            mediaItems.flatMap { requested ->
                when {
                    requested.mediaId.startsWith(ALBUM_PREFIX) -> {
                        val albumId = requested.mediaId.removePrefix(ALBUM_PREFIX).toLongOrNull()
                            ?: return@flatMap emptyList()
                        queryLocalTracks(albumId = albumId)
                    }
                    requested.mediaId.startsWith(ARTIST_PREFIX) -> {
                        val artistId = requested.mediaId.removePrefix(ARTIST_PREFIX).toLongOrNull()
                            ?: return@flatMap emptyList()
                        queryLocalTracks(artistId = artistId)
                    }
                    else -> {
                        val track = onlineTracks[requested.mediaId]
                            ?: return@flatMap listOf(requested)
                        val resolved = runBlocking { onlineRepository.resolvePlayback(track) }
                        val stream = resolved.streamUrl ?: resolved.localUri
                            ?: error("The selected provider did not return a playable audio stream.")
                        listOf(
                            MediaItem.Builder()
                                .setMediaId(requested.mediaId)
                                .setUri(Uri.parse(stream))
                                .setMediaMetadata(requested.mediaMetadata)
                                .build()
                        )
                    }
                }
            }
        }

        fun shutdown() {
            executor.shutdownNow()
            onlineTracks.clear()
            searchResults.clear()
        }

        private fun loadSearchResults(query: String): List<MediaItem> {
            val normalized = query.trim()
            if (normalized.isBlank()) return emptyList()

            val local = queryLocalTracks(search = normalized)
            val preferences = getSharedPreferences("rsoundveil.settings", MODE_PRIVATE)
            val audiusKey = preferences.getString("audius_api_key", "").orEmpty()
            val remote = runCatching {
                runBlocking { onlineRepository.search(normalized, audiusKey) }
            }.getOrDefault(emptyList())
                .filterNot { it.isVideo }
                .take(MAX_REMOTE_RESULTS)

            if (onlineTracks.size > MAX_CACHED_REMOTE_TRACKS) onlineTracks.clear()
            remote.forEach { onlineTracks[onlineMediaId(it)] = it }
            return (local + remote.map(::onlineMediaItem))
                .distinctBy { it.mediaId }
        }

        private fun <T> submit(task: () -> T): ListenableFuture<T> {
            val future = ListenableFutureTask.create(Callable<T> { task() })
            try {
                executor.execute(future)
            } catch (_: RejectedExecutionException) {
                future.cancel(false)
            }
            return future
        }

        private fun queryLocalTracks(
            search: String? = null,
            id: Long? = null,
            recentFirst: Boolean = false,
            albumId: Long? = null,
            artistId: Long? = null
        ): List<MediaItem> {
            val hasAudioPermission =
                hasAudioPermission()
            if (!hasAudioPermission) return emptyList()

            val columns = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID
            )
            val clauses = mutableListOf(MediaStore.Audio.Media.IS_MUSIC + " != 0")
            val selectionArgs = mutableListOf<String>()

            if (id != null) {
                clauses += MediaStore.Audio.Media._ID + " = ?"
                selectionArgs += id.toString()
            }
            if (albumId != null) {
                clauses += MediaStore.Audio.Media.ALBUM_ID + " = ?"
                selectionArgs += albumId.toString()
            }
            if (artistId != null) {
                clauses += MediaStore.Audio.Media.ARTIST_ID + " = ?"
                selectionArgs += artistId.toString()
            }
            if (!search.isNullOrBlank()) {
                clauses += "(" +
                    MediaStore.Audio.Media.TITLE + " LIKE ? OR " +
                    MediaStore.Audio.Media.ARTIST + " LIKE ? OR " +
                    MediaStore.Audio.Media.ALBUM + " LIKE ?)"
                val pattern = "%" + search.trim() + "%"
                selectionArgs += pattern
                selectionArgs += pattern
                selectionArgs += pattern
            }

            val order = when {
                recentFirst -> MediaStore.Audio.Media.DATE_ADDED + " DESC"
                albumId != null -> MediaStore.Audio.Media.TRACK + " ASC, " +
                    MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"
                artistId != null -> MediaStore.Audio.Media.ALBUM + " COLLATE NOCASE ASC, " +
                    MediaStore.Audio.Media.TRACK + " ASC"
                else -> MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"
            }
            val result = ArrayList<MediaItem>()

            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                columns,
                clauses.joinToString(" AND "),
                selectionArgs.toTypedArray(),
                order
            )?.use { cursor ->
                while (cursor.moveToNext() && result.size < MAX_LOCAL_RESULTS) {
                    val songId = cursor.longValue(MediaStore.Audio.Media._ID)
                    val title = cursor.stringValue(MediaStore.Audio.Media.TITLE)
                    val artist = cursor.stringValue(MediaStore.Audio.Media.ARTIST)
                    val album = cursor.stringValue(MediaStore.Audio.Media.ALBUM)
                    val albumId = cursor.longValue(MediaStore.Audio.Media.ALBUM_ID)
                    val songUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        songId
                    )
                    val artworkUri = if (albumId > 0L) {
                        Uri.parse("content://media/external/audio/albumart/" + albumId)
                    } else null
                    val metadata = MediaMetadata.Builder()
                        .setTitle(title.ifBlank { "Unknown title" })
                        .setArtist(artist.ifBlank { "Unknown artist" })
                        .setAlbumTitle(album)
                        .setArtworkUri(artworkUri)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build()
                    result += MediaItem.Builder()
                        .setMediaId(LOCAL_PREFIX + songId)
                        .setUri(songUri)
                        .setMediaMetadata(metadata)
                        .build()
                }
            }
            return result
        }

        private fun queryAlbums(): List<MediaItem> {
            if (!hasAudioPermission()) return emptyList()
            val columns = arrayOf(
                MediaStore.Audio.Albums._ID,
                MediaStore.Audio.Albums.ALBUM,
                MediaStore.Audio.Albums.ARTIST,
                MediaStore.Audio.Albums.NUMBER_OF_SONGS,
                MediaStore.Audio.Albums.FIRST_YEAR
            )
            val items = ArrayList<MediaItem>()
            contentResolver.query(
                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                columns,
                MediaStore.Audio.Albums.NUMBER_OF_SONGS + " > 0",
                null,
                MediaStore.Audio.Albums.ALBUM + " COLLATE NOCASE ASC"
            )?.use { cursor ->
                while (cursor.moveToNext() && items.size < MAX_LOCAL_RESULTS) {
                    val id = cursor.longValue(MediaStore.Audio.Albums._ID)
                    val title = cursor.stringValue(MediaStore.Audio.Albums.ALBUM)
                    val artist = cursor.stringValue(MediaStore.Audio.Albums.ARTIST)
                    val count = cursor.longValue(MediaStore.Audio.Albums.NUMBER_OF_SONGS)
                    val year = cursor.longValue(MediaStore.Audio.Albums.FIRST_YEAR)
                    val artwork = Uri.parse("content://media/external/audio/albumart/" + id)
                    val metadata = MediaMetadata.Builder()
                        .setTitle(title.ifBlank { "Unknown album" })
                        .setArtist(artist.ifBlank { "Unknown artist" })
                        .setSubtitle(count.toString() + " tracks" + if (year > 0) " · " + year else "")
                        .setArtworkUri(artwork)
                        .setIsBrowsable(true)
                        .setIsPlayable(true)
                        .build()
                    items += MediaItem.Builder()
                        .setMediaId(ALBUM_PREFIX + id)
                        .setMediaMetadata(metadata)
                        .build()
                }
            }
            return items
        }

        private fun queryArtists(): List<MediaItem> {
            if (!hasAudioPermission()) return emptyList()
            val columns = arrayOf(
                MediaStore.Audio.Artists._ID,
                MediaStore.Audio.Artists.ARTIST,
                MediaStore.Audio.Artists.NUMBER_OF_TRACKS,
                MediaStore.Audio.Artists.NUMBER_OF_ALBUMS
            )
            val items = ArrayList<MediaItem>()
            contentResolver.query(
                MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI,
                columns,
                MediaStore.Audio.Artists.NUMBER_OF_TRACKS + " > 0",
                null,
                MediaStore.Audio.Artists.ARTIST + " COLLATE NOCASE ASC"
            )?.use { cursor ->
                while (cursor.moveToNext() && items.size < MAX_LOCAL_RESULTS) {
                    val id = cursor.longValue(MediaStore.Audio.Artists._ID)
                    val artist = cursor.stringValue(MediaStore.Audio.Artists.ARTIST)
                    val tracks = cursor.longValue(MediaStore.Audio.Artists.NUMBER_OF_TRACKS)
                    val albums = cursor.longValue(MediaStore.Audio.Artists.NUMBER_OF_ALBUMS)
                    val metadata = MediaMetadata.Builder()
                        .setTitle(artist.ifBlank { "Unknown artist" })
                        .setSubtitle(albums.toString() + " albums · " + tracks + " tracks")
                        .setIsBrowsable(true)
                        .setIsPlayable(true)
                        .build()
                    items += MediaItem.Builder()
                        .setMediaId(ARTIST_PREFIX + id)
                        .setMediaMetadata(metadata)
                        .build()
                }
            }
            return items
        }

        private fun findAlbum(id: Long): MediaItem? =
            queryAlbums().firstOrNull { it.mediaId == ALBUM_PREFIX + id }

        private fun findArtist(id: Long): MediaItem? =
            queryArtists().firstOrNull { it.mediaId == ARTIST_PREFIX + id }

        private fun hasAudioPermission(): Boolean =
            ContextCompat.checkSelfPermission(
                this@MusicPlaybackService,
                android.Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this@MusicPlaybackService,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

        private fun onlineMediaId(track: MusicTrack): String =
            ONLINE_PREFIX + track.provider + ":" + track.id

        private fun onlineMediaItem(track: MusicTrack): MediaItem {
            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                .setArtworkUri(track.artworkUrl?.let(Uri::parse))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
            val builder = MediaItem.Builder()
                .setMediaId(onlineMediaId(track))
                .setMediaMetadata(metadata)
            track.streamUrl?.takeIf { it.isNotBlank() }?.let { builder.setUri(Uri.parse(it)) }
            return builder.build()
        }
    }

    private fun folder(id: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()

    private fun Cursor.stringValue(column: String): String {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index).orEmpty() else ""
    }

    private fun Cursor.longValue(column: String): Long {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else 0L
    }

    companion object {
        private const val ROOT_ID = "rsoundveil_root"
        private const val ALL_TRACKS_ID = "local_all_tracks"
        private const val RECENT_TRACKS_ID = "local_recent_tracks"
        private const val ALBUMS_ID = "local_albums"
        private const val ARTISTS_ID = "local_artists"
        private const val ALBUM_PREFIX = "album:"
        private const val ARTIST_PREFIX = "artist:"
        private const val LOCAL_PREFIX = "local:"
        private const val ONLINE_PREFIX = "online:"
        private const val MAX_PAGE_SIZE = 100
        private const val MAX_LOCAL_RESULTS = 2_000
        private const val MAX_REMOTE_RESULTS = 60
        private const val MAX_CACHED_SEARCHES = 20
        private const val MAX_CACHED_REMOTE_TRACKS = 500
    }
}
