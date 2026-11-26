package com.rsoundveil.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.app.DownloadManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil3.compose.AsyncImage
import com.rsoundveil.app.data.LyricsDocument
import com.rsoundveil.app.data.LyricsRepository
import com.rsoundveil.app.data.MusicTrack
import com.rsoundveil.app.data.OnlineMusicRepository
import com.rsoundveil.app.player.MusicPlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Destination { HOME, EXPLORE, PLAYER, LYRICS, LIBRARY, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RSoundveilApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RSoundveilApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("rsoundveil.settings", Context.MODE_PRIVATE) }
    val music = remember { OnlineMusicRepository(context) }
    val lyricService = remember { LyricsRepository(context) }
    val sessionToken = remember { SessionToken(context, ComponentName(context, MusicPlaybackService::class.java)) }
    val controllerFuture = remember { MediaController.Builder(context, sessionToken).buildAsync() }

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var destination by remember { mutableStateOf(Destination.HOME) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var localTracks by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var currentTrack by remember { mutableStateOf<MusicTrack?>(null) }
    var lyrics by remember { mutableStateOf<LyricsDocument?>(null) }
    var translation by remember { mutableStateOf<String?>(null) }
    var lyricSourceLanguage by remember { mutableStateOf("en") }
    var jamendoId by remember { mutableStateOf(prefs.getString("jamendo_client_id", "").orEmpty()) }
    var audiusKey by remember { mutableStateOf(prefs.getString("audius_api_key", "").orEmpty()) }
    var loading by remember { mutableStateOf(false) }
    var translating by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingDownload by remember { mutableStateOf<MusicTrack?>(null) }
    val scope = rememberCoroutineScope()

    DisposableEffect(controllerFuture) {
        controllerFuture.addListener(
            { runCatching { controller = controllerFuture.get() }.onFailure { message = "Player connection failed: " + it.message } },
            ContextCompat.getMainExecutor(context)
        )
        onDispose { }
    }
    DisposableEffect(controller) {
        val active = controller ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(playbackState: Int) {
                duration = active.duration.takeIf { it > 0L } ?: duration
            }
        }
        active.addListener(listener)
        onDispose { active.removeListener(listener) }
    }
    LaunchedEffect(controller) {
        while (true) {
            controller?.let {
                position = it.currentPosition.coerceAtLeast(0L)
                duration = it.duration.takeIf { value -> value > 0L && value != androidx.media3.common.C.TIME_UNSET } ?: duration
                playing = it.isPlaying
            }
            delay(250)
        }
    }

    val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) localTracks = scanAudio(context) else message = "Permission is needed to read your device music."
    }

    val writePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingDownload
        pendingDownload = null
        if (granted && pending != null) {
            runCatching { enqueueAuthorizedDownload(context, pending) }
                .onSuccess { message = "Download added to Music/rsoundveil." }
                .onFailure { message = "Download could not be started: " + it.message }
        } else if (pending != null) {
            message = "Storage permission was denied; no file was downloaded."
        }
    }

    val onDownloadTrack: (MusicTrack) -> Unit = { track ->
        scope.launch {
            val preparation = runCatching { music.resolvePlayback(track) }
            val prepared = preparation.getOrNull()
            if (prepared == null) {
                message = "Download preparation failed: " + preparation.exceptionOrNull()?.message
            } else {
                val downloadUrl = prepared.downloadUrl
                    ?: if (prepared.provider == "Archive.org") prepared.streamUrl else null
                if (!prepared.downloadAllowed || downloadUrl.isNullOrBlank()) {
                    message = "This provider does not permit downloading this track."
                } else {
                    val downloadable = prepared.copy(downloadUrl = downloadUrl)
                    if (Build.VERSION.SDK_INT < 29 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingDownload = downloadable
                        writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        runCatching { enqueueAuthorizedDownload(context, downloadable) }
                            .onSuccess { message = "Download added to Music/rsoundveil." }
                            .onFailure { message = "Download could not be started: " + it.message }
                    }
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFB9A2FF),
            secondary = Color(0xFF8BE0D1),
            background = Color(0xFF0D0D12),
            surface = Color(0xFF17171F),
            surfaceVariant = Color(0xFF252530)
        )
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("rsoundveil", fontWeight = FontWeight.Bold)
                            Text("Music · Lyrics · Together", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        IconButton(onClick = { destination = Destination.EXPLORE }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            },
            bottomBar = {
                Column {
                    currentTrack?.let { track ->
                        MiniPlayer(
                            track = track,
                            playing = playing,
                            position = position,
                            duration = duration,
                            onPlayPause = { if (playing) controller?.pause() else controller?.play() },
                            onOpen = { destination = Destination.PLAYER },
                            onLyrics = { destination = Destination.LYRICS }
                        )
                    }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        val items = listOf(
                            Triple(Destination.HOME, Icons.Default.Home, "Home"),
                            Triple(Destination.EXPLORE, Icons.Default.Explore, "Explore"),
                            Triple(Destination.LYRICS, Icons.Default.Lyrics, "Lyrics"),
                            Triple(Destination.LIBRARY, Icons.Default.LibraryMusic, "Library"),
                            Triple(Destination.SETTINGS, Icons.Default.Settings, "Settings")
                        )
                        items.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item.first,
                                onClick = {
                                    destination = item.first
                                    if (item.first == Destination.LIBRARY) {
                                        val granted = ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
                                        if (granted) localTracks = scanAudio(context) else permissionLauncher.launch(audioPermission)
                                    }
                                },
                                icon = { Icon(item.second, contentDescription = item.third) },
                                label = { Text(item.third) }
                            )
                        }
                    }
                }
            }
        ) { insets ->
            Column(Modifier.fillMaxSize().padding(insets)) {
                message?.let { text ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = { message = null }) { Text("Close") }
                        }
                    }
                }
                when (destination) {
                    Destination.HOME -> HomeScreen(
                        track = currentTrack,
                        onExplore = { destination = Destination.EXPLORE },
                        onLibrary = {
                            destination = Destination.LIBRARY
                            val granted = ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
                            if (granted) localTracks = scanAudio(context) else permissionLauncher.launch(audioPermission)
                        },
                        onPlayer = { destination = Destination.PLAYER },
                        onLyrics = { destination = Destination.LYRICS }
                    )
                    Destination.PLAYER -> currentTrack?.let { track ->
                        NowPlayingScreen(
                            track = track,
                            playing = playing,
                            position = position,
                            duration = duration,
                            shuffle = controller?.shuffleModeEnabled == true,
                            repeatMode = controller?.repeatMode ?: Player.REPEAT_MODE_OFF,
                            onPlayPause = { if (playing) controller?.pause() else controller?.play() },
                            onPrevious = { controller?.seekToPreviousMediaItem() },
                            onNext = { controller?.seekToNextMediaItem() },
                            onSeek = { controller?.seekTo(it) },
                            onShuffle = { controller?.shuffleModeEnabled = !(controller?.shuffleModeEnabled ?: false) },
                            onRepeat = {
                                val next = when (controller?.repeatMode) {
                                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                                    Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                                    else -> Player.REPEAT_MODE_OFF
                                }
                                controller?.repeatMode = next
                            },
                            onLyrics = { destination = Destination.LYRICS }
                        )
                    } ?: HomeScreen(
                        currentTrack,
                        { destination = Destination.EXPLORE },
                        { destination = Destination.LIBRARY },
                        { destination = Destination.PLAYER },
                        { destination = Destination.LYRICS }
                    )
                    Destination.EXPLORE -> ExploreScreen(
                        query = query,
                        onQuery = { query = it },
                        tracks = results,
                        loading = loading,
                        onDownload = onDownloadTrack,
                        onSearch = {
                            if (query.isNotBlank()) scope.launch {
                                loading = true
                                message = null
                                results = music.search(query.trim(), audiusKey)
                                if (results.isEmpty()) message = "No results. Connect Jamendo or Audius in Settings, or try a different search."
                                loading = false
                            }
                        },
                        onPlay = { startTrack(it, results, music, controller, { currentTrack = it }, { message = it }, scope) }
                    )
                    Destination.LYRICS -> LyricsScreen(
                        track = currentTrack,
                        lyrics = lyrics,
                        translation = translation,
                        sourceLanguage = lyricSourceLanguage,
                        onSourceLanguage = { lyricSourceLanguage = it },
                        position = position,
                        loading = loading,
                        translating = translating,
                        onFind = {
                            val track = currentTrack
                            if (track != null) scope.launch {
                                lyrics = lyricService.cached(track)
                                translation = lyricService.cachedTranslation(track, lyricSourceLanguage)
                                loading = true
                                message = null
                                runCatching { lyricService.find(track) }
                                    .onSuccess {
                                        lyrics = it
                                        translation = lyricService.cachedTranslation(track, lyricSourceLanguage)
                                    }
                                    .onFailure {
                                        if (lyrics == null) message = "Lyrics lookup failed: " + it.message
                                        else message = "Showing saved offline lyrics; online refresh failed."
                                    }
                                loading = false
                            }
                        },
                        onTranslate = {
                            val text = lyrics?.plainLyrics ?: lyrics?.syncedLyrics
                            if (!text.isNullOrBlank()) scope.launch {
                                translating = true
                                runCatching { lyricService.translateToIndonesian(text, lyricSourceLanguage) }
                                    .onSuccess {
                                        translation = it
                                        currentTrack?.let { active -> lyricService.saveTranslation(active, lyricSourceLanguage, it) }
                                    }
                                    .onFailure { message = "Translation failed: " + it.message }
                                translating = false
                            }
                        }
                    )
                    Destination.LIBRARY -> LibraryScreen(
                        tracks = localTracks,
                        onRefresh = {
                            val granted = ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
                            if (granted) localTracks = scanAudio(context) else permissionLauncher.launch(audioPermission)
                        },
                        onPlay = { startTrack(it, localTracks, music, controller, { currentTrack = it }, { message = it }, scope) }
                    )
                    Destination.SETTINGS -> SettingsScreen(
                        jamendoId = jamendoId,
                        onJamendoId = { jamendoId = it },
                        audiusKey = audiusKey,
                        onAudiusKey = { audiusKey = it },
                        onSave = {
                            music.saveJamendoClientId(jamendoId)
                            prefs.edit().putString("audius_api_key", audiusKey.trim()).apply()
                            message = "Provider settings saved on this device."
                        }
                    )
                }
            }
        }
    }
}

private fun startTrack(
    track: MusicTrack,
    candidates: List<MusicTrack>,
    music: OnlineMusicRepository,
    controller: MediaController?,
    onSelected: (MusicTrack) -> Unit,
    onError: (String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val player = controller
    if (player == null) {
        onError("Player is still connecting. Try again.")
        return
    }
    scope.launch {
        runCatching { music.resolvePlayback(track) }
            .onSuccess { resolved ->
                onSelected(resolved)
                val queue = candidates
                    .filter { candidate -> candidate.id == track.id || candidate.provider != "Archive.org" }
                    .map { candidate -> if (candidate.id == track.id) resolved else candidate }
                    .filter { !it.streamUrl.isNullOrBlank() || !it.localUri.isNullOrBlank() }
                val selectedIndex = queue.indexOfFirst { it.id == resolved.id }.coerceAtLeast(0)
                val mediaItems = queue.map { item ->
                    val metadata = MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtist(item.artist)
                        .setAlbumTitle(item.album)
                        .setArtworkUri(item.artworkUrl?.let { Uri.parse(it) })
                        .build()
                    MediaItem.Builder()
                        .setMediaId(item.provider + ":" + item.id)
                        .setUri(item.streamUrl ?: item.localUri!!)
                        .setMediaMetadata(metadata)
                        .build()
                }
                if (mediaItems.isEmpty()) {
                    onError("The provider did not return a playable URL.")
                } else {
                    player.setMediaItems(mediaItems, selectedIndex, 0L)
                    player.prepare()
                    player.play()
                }
            }
            .onFailure { onError("Cannot play " + track.title + ": " + it.message) }
    }
}

@Composable
private fun HomeScreen(
    track: MusicTrack?,
    onExplore: () -> Unit,
    onLibrary: () -> Unit,
    onPlayer: () -> Unit,
    onLyrics: () -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Surface(shape = RoundedCornerShape(26.dp), color = Color(0xFF28223D), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("One song. One experience.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Temukan musik, dengarkan, ikuti lirik, dan pahami maknanya dalam satu alur.", color = Color(0xFFD8D0E9))
                    Button(onClick = onExplore) { Icon(Icons.Default.Explore, null); Spacer(Modifier.width(8.dp)); Text("Explore music") }
                }
            }
        }
        item {
            Text("Your space", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionCard("Local library", "Music on device", Icons.Default.LibraryMusic, Modifier.weight(1f), onLibrary)
                ActionCard("Live lyrics", "Lyrics and meaning", Icons.Default.Lyrics, Modifier.weight(1f), onLyrics)
            }
        }
        item {
            Text("Current track", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (track == null) {
                Text("Play a track from Explore or Library. Its lyrics will be linked to the active player.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                TrackRow(track, onClick = onPlayer, trailing = { Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.secondary) })
            }
        }
    }
}

@Composable
private fun ExploreScreen(
    query: String,
    onQuery: (String) -> Unit,
    tracks: List<MusicTrack>,
    loading: Boolean,
    onDownload: (MusicTrack) -> Unit,
    onSearch: () -> Unit,
    onPlay: (MusicTrack) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search tracks, artists, albums") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { IconButton(onClick = onSearch, enabled = !loading) { Icon(Icons.Default.ArrowForward, "Search") } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() })
        )
        Spacer(Modifier.height(12.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Unified results", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 10.dp))
        if (tracks.isEmpty() && !loading) {
            Text("Internet Archive works without credentials. Connect Jamendo or Audius in Settings to add their catalogs.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(tracks, key = { it.provider + it.id }) { track ->
                TrackRow(track, onClick = { onPlay(track) }, trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onPlay(track) }) { Icon(Icons.Default.PlayArrow, "Play") }
                        if (track.downloadAllowed) {
                            IconButton(onClick = { onDownload(track) }) { Icon(Icons.Default.Download, "Download to Music/rsoundveil") }
                        }
                    }
                })
            }
        }
    }
}

private fun linesHaveWordSync(document: LyricsDocument): Boolean =
    document.lines.any { it.words.isNotEmpty() }

@Composable
private fun LyricsScreen(
    track: MusicTrack?,
    lyrics: LyricsDocument?,
    translation: String?,
    sourceLanguage: String,
    onSourceLanguage: (String) -> Unit,
    position: Long,
    loading: Boolean,
    translating: Boolean,
    onFind: () -> Unit,
    onTranslate: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Lyrics that follow the music", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        if (track == null) {
            Text("Start a track first. Lyrics are matched using title, artist, album, and duration.")
            return@Column
        }
        TrackRow(track, onClick = {}, trailing = { Text(track.provider, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) })
        OutlinedTextField(
            value = sourceLanguage,
            onValueChange = onSourceLanguage,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Lyrics language (ISO code)") },
            supportingText = { Text("Example: en, ja, ko, es. Translation target: Indonesian.") }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onFind, enabled = !loading) {
                if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Sync, null)
                Spacer(Modifier.width(6.dp)); Text("Find lyrics")
            }
            OutlinedButton(onClick = onTranslate, enabled = lyrics != null && !translating) {
                if (translating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Translate, null)
                Spacer(Modifier.width(6.dp)); Text("Translate")
            }
        }
        lyrics?.let { document ->
            Text("Source: " + document.source + if (linesHaveWordSync(document)) " · word-by-word sync" else " · line-sync", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            val lines = document.lines
            if (lines.isNotEmpty()) {
                val active = lines.indexOfLast { it.startMs <= position }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(lines) { line ->
                        val index = lines.indexOf(line)
                        val wordText = remember(line, position) {
                            buildAnnotatedString {
                                line.words.forEachIndexed { wordIndex, word ->
                                    val wordEnd = word.endMs
                                        ?: line.words.getOrNull(wordIndex + 1)?.startMs
                                        ?: line.endMs
                                        ?: lines.getOrNull(index + 1)?.startMs
                                        ?: (word.startMs + 900L)
                                    val activeWord = position >= word.startMs && position < wordEnd
                                    withStyle(
                                        SpanStyle(
                                            color = if (activeWord) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = if (activeWord) FontWeight.Bold else FontWeight.Normal
                                        )
                                    ) {
                                        append(word.text)
                                    }
                                }
                            }
                        }
                        if (line.words.isNotEmpty()) {
                            Text(
                                wordText,
                                style = if (index == active) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge
                            )
                        } else {
                            Text(
                                line.text.ifBlank { "♪" },
                                style = if (index == active) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                                fontWeight = if (index == active) FontWeight.Bold else FontWeight.Normal,
                                color = if (index == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f)) { item { Text(document.plainLyrics ?: "No lyrics available") } }
            }
            translation?.let {
                HorizontalDivider()
                Text("Indonesian translation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                LazyColumn(Modifier.weight(1f)) { item { Text(it) } }
            }
        } ?: Spacer(Modifier.weight(1f))
        Text("Translation is opt-in and sent to MyMemory. Provider availability and limits may apply.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LibraryScreen(tracks: List<MusicTrack>, onRefresh: () -> Unit, onPlay: (MusicTrack) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("On this device", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(tracks.size.toString() + " audio tracks found", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
        }
        if (tracks.isEmpty()) Text("Tap Refresh to scan device audio.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(tracks, key = { it.id }) { track ->
                TrackRow(track, onClick = { onPlay(track) }, trailing = {
                    IconButton(onClick = { onPlay(track) }) { Icon(Icons.Default.PlayArrow, "Play") }
                })
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    jamendoId: String,
    onJamendoId: (String) -> Unit,
    audiusKey: String,
    onAudiusKey: (String) -> Unit,
    onSave: () -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Music providers", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Enable additional catalogs with official developer credentials. No private cookies or account sessions are collected.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Text("Jamendo Client ID", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(jamendoId, onJamendoId, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Developer client_id") })
            Text("Register an app in the Jamendo developer portal. Read API requests require a client_id.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Text("Audius read-only API key", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(audiusKey, onAudiusKey, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), placeholder = { Text("Optional API key") })
            Text("Only read endpoints are called. The API secret is not requested. The key is stored locally for catalog and stream requests.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("Save settings") } }
        item {
            Text("No-key source", fontWeight = FontWeight.SemiBold)
            Text("Internet Archive results are included only when the item metadata contains a Creative Commons or public-domain license URL.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NowPlayingScreen(
    track: MusicTrack,
    playing: Boolean,
    position: Long,
    duration: Long,
    shuffle: Boolean,
    repeatMode: Int,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onLyrics: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        Text("NOW PLAYING", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        AsyncImage(
            model = track.artworkUrl,
            contentDescription = "Album artwork",
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp))
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(track.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(track.album.ifBlank { track.provider }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        }
        Column(Modifier.fillMaxWidth()) {
            Slider(
                value = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { if (duration > 0L) onSeek((duration * it).toLong()) },
                enabled = duration > 0L
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(position), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onShuffle) {
                Icon(Icons.Default.Shuffle, "Shuffle", tint = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "Previous", modifier = Modifier.size(34.dp)) }
            FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(68.dp)) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", modifier = Modifier.size(34.dp))
            }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Next", modifier = Modifier.size(34.dp)) }
            IconButton(onClick = onRepeat) {
                Icon(
                    if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    "Repeat",
                    tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        OutlinedButton(onClick = onLyrics, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Lyrics, null)
            Spacer(Modifier.width(8.dp))
            Text("Open synchronized lyrics")
        }
    }
}

private fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1_000L
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}

@Composable
private fun MiniPlayer(
    track: MusicTrack,
    playing: Boolean,
    position: Long,
    duration: Long,
    onPlayPause: () -> Unit,
    onOpen: () -> Unit,
    onLyrics: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(track.artworkUrl, null, modifier = Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onPlayPause) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause") }
                IconButton(onClick = onLyrics) { Icon(Icons.Default.Lyrics, "Lyrics") }
            }
            LinearProgressIndicator(
                progress = { if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(3.dp)
            )
        }
    }
}

@Composable
private fun TrackRow(track: MusicTrack, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            track.artworkUrl,
            contentDescription = null,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(track.provider, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
        trailing()
    }
}

@Composable
private fun ActionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Surface(modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


private fun enqueueAuthorizedDownload(context: Context, track: MusicTrack): Long {
    require(track.downloadAllowed) { "The provider has not authorized this download." }
    val source = track.downloadUrl ?: error("The provider did not supply a download URL.")
    val uri = Uri.parse(source)
    val extension = uri.lastPathSegment
        ?.substringAfterLast('.', "mp3")
        ?.lowercase()
        ?.takeIf { it.matches(Regex("[a-z0-9]{2,5}")) }
        ?: "mp3"
    val safeBase = (track.title + "_" + track.id)
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
        .take(90)
        .ifBlank { "rsoundveil_track" }
    val request = DownloadManager.Request(uri)
        .setTitle(track.title)
        .setDescription("Downloading from " + track.provider)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(false)
        .setDestinationInExternalPublicDir(
            Environment.DIRECTORY_MUSIC,
            "rsoundveil/" + safeBase + "." + extension
        )
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    return manager.enqueue(request)
}

private fun scanAudio(context: Context): List<MusicTrack> {
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val columns = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DURATION
    )
    return runCatching {
        context.contentResolver.query(
            collection, columns, MediaStore.Audio.Media.IS_MUSIC + " != 0", null,
            MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    add(
                        MusicTrack(
                            id = id.toString(),
                            title = cursor.getString(titleColumn) ?: "Unknown track",
                            artist = cursor.getString(artistColumn) ?: "Unknown artist",
                            album = cursor.getString(albumColumn).orEmpty(),
                            durationMs = cursor.getLong(durationColumn),
                            provider = "On device",
                            localUri = Uri.withAppendedPath(collection, id.toString()).toString()
                        )
                    )
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())
}
