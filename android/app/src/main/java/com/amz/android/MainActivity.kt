package com.amz.android

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import java.util.Locale

private data class AudioTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val dateAdded: Long,
    val uri: Uri
)

class MainActivity : ComponentActivity() {
    private var mediaController by mutableStateOf<MediaController?>(null)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val tracks = mutableStateListOf<AudioTrack>()
    private var hasPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) loadAudio()
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            refreshUi()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshUi()
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            refreshUi()
        }
    }

    private fun refreshUi() {
        // Compose observes the controller state through its own lightweight ticker.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        hasPermission = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) loadAudio() else permissionLauncher.launch(permission)

        val token = SessionToken(this, ComponentName(this, MusicPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
                mediaController?.addListener(playerListener)
            } catch (_: Exception) {
                mediaController = null
            }
        }, ContextCompat.getMainExecutor(this))

        setContent {
            val preferences = getSharedPreferences("amz_music_settings", MODE_PRIVATE)
            var language by remember { mutableStateOf(preferences.getString("language", "fa") ?: "fa") }
            var themeChoice by remember { mutableStateOf(preferences.getString("theme", "dark") ?: "dark") }
            var accentChoice by remember { mutableStateOf(preferences.getString("accent", "gold") ?: "gold") }

            CompositionLocalProvider(
                LocalLayoutDirection provides if (language == "fa") LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                AMZMusicTheme(themeChoice, accentChoice) {
                    MusicHome(
                        tracks = tracks,
                        controller = mediaController,
                        hasPermission = hasPermission,
                        language = language,
                        themeChoice = themeChoice,
                        accentChoice = accentChoice,
                        onSettingsChange = { newLanguage, newTheme, newAccent ->
                            language = newLanguage
                            themeChoice = newTheme
                            accentChoice = newAccent
                            preferences.edit()
                                .putString("language", newLanguage)
                                .putString("theme", newTheme)
                                .putString("accent", newAccent)
                                .apply()
                        },
                        onRequestPermission = { permissionLauncher.launch(permission) }
                    )
                }
            }
        }
    }

    private fun loadAudio() {
        val found = mutableListOf<AudioTrack>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC, ${MediaStore.Audio.Media._ID} DESC"
        try {
            contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor: Cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "آهنگ بدون نام"
                    val artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() } ?: "خواننده ناشناس"
                    val album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() } ?: "آلبوم نامشخص"
                    found += AudioTrack(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        albumId = cursor.getLong(albumIdCol),
                        duration = cursor.getLong(durationCol),
                        dateAdded = cursor.getLong(dateAddedCol),
                        uri = Uri.withAppendedPath(collection, id.toString())
                    )
                }
            }
        } catch (_: SecurityException) {
            hasPermission = false
        }
        tracks.clear()
        tracks.addAll(found)
    }

    override fun onDestroy() {
        mediaController?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        mediaController = null
        super.onDestroy()
    }
}

@Composable
private fun AMZMusicTheme(
    themeChoice: String,
    accentChoice: String,
    content: @Composable () -> Unit
) {
    val dark = when (themeChoice) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val accent = when (accentChoice) {
        "blue" -> Color(0xFF65A9FF)
        "purple" -> Color(0xFFB69CFF)
        "pink" -> Color(0xFFFF8EB8)
        "gold" -> Color(0xFFC6A56A)
        else -> Color(0xFFC6A56A)
    }
    val colors = if (dark) {
        darkColorScheme(
            primary = accent,
            onPrimary = Color(0xFF211B11),
            background = Color(0xFF101114),
            surface = Color(0xFF191B20),
            surfaceVariant = Color(0xFF24272D),
            onSurface = Color(0xFFF1EEE8),
            onSurfaceVariant = Color(0xFFA6A39C)
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            background = Color(0xFFF4F1EA),
            surface = Color(0xFFFFFDF8),
            surfaceVariant = Color(0xFFEAE4D8),
            onSurface = Color(0xFF24221E),
            onSurfaceVariant = Color(0xFF706B62)
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicHome(
    tracks: List<AudioTrack>,
    controller: MediaController?,
    hasPermission: Boolean,
    language: String,
    themeChoice: String,
    accentChoice: String,
    onSettingsChange: (String, String, String) -> Unit,
    onRequestPermission: () -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var showPlayer by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(controller?.isPlaying == true) }
    var currentIndex by remember { mutableStateOf(controller?.currentMediaItemIndex ?: -1) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var shuffle by remember { mutableStateOf(controller?.shuffleModeEnabled == true) }
    var repeatMode by remember { mutableStateOf(controller?.repeatMode ?: Player.REPEAT_MODE_OFF) }
    val favorites = remember { mutableStateListOf<Long>() }

    LaunchedEffect(controller) {
        while (true) {
            if (controller != null) {
                isPlaying = controller.isPlaying
                currentIndex = controller.currentMediaItemIndex
                position = controller.currentPosition.coerceAtLeast(0L)
                duration = controller.duration.takeIf { it > 0L } ?: 0L
                shuffle = controller.shuffleModeEnabled
                repeatMode = controller.repeatMode
            }
            delay(400)
        }
    }

    val currentTrack = controller?.currentMediaItem?.mediaMetadata?.let { metadata ->
        tracks.firstOrNull { it.title == metadata.title?.toString() && it.artist == metadata.artist?.toString() }
    } ?: tracks.getOrNull(currentIndex)

    val filtered = when (selectedTab) {
        1 -> tracks.distinctBy { it.album }.filter { it.album.contains(query, true) || it.artist.contains(query, true) }
        2 -> tracks.distinctBy { it.artist }.filter { it.artist.contains(query, true) }
        3 -> tracks.filter { it.id in favorites }
        else -> tracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column {
                AnimatedVisibility(
                    visible = currentTrack != null,
                    enter = fadeIn() + slideInVertically { it / 3 },
                    exit = fadeOut() + slideOutVertically { it / 3 }
                ) {
                    currentTrack?.let { track -> MiniPlayer(
                        track = track,
                        isPlaying = isPlaying,
                        onOpen = { showPlayer = true },
                        onPlayPause = { if (isPlaying) controller?.pause() else controller?.play() },
                        onNext = { controller?.seekToNextMediaItem() }
                    ) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 8.dp)
                        .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(txt(language, "آهنگ‌ها", "Songs"), txt(language, "آلبوم‌ها", "Albums"), txt(language, "هنرمندان", "Artists"), txt(language, "پسندیده‌ها", "Favorites")).forEachIndexed { index, label ->
                        Column(
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                                .clickable { selectedTab = index }.padding(vertical = 7.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = when (index) {
                                    1 -> Icons.Default.Album
                                    2 -> Icons.Default.Headphones
                                    3 -> Icons.Default.Favorite
                                    else -> Icons.Default.MusicNote
                                },
                                contentDescription = null,
                                tint = if (selectedTab == index) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                            Text(label, fontSize = 10.sp, color = if (selectedTab == index) MaterialTheme.colorScheme.primary else Color.Gray)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AMZBrandMark(Modifier.size(54.dp))
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("AMZ MUSIC", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(txt(language, "موسیقی، به سبک تو", "Music, your way"), fontSize = 27.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(
                    onClick = { showSettings = true },
                    modifier = Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = txt(language, "تنظیمات", "Settings"), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(17.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(txt(language, "کتابخانه موسیقی تو", "Your music library"), color = Color(0xFFC6A56A), fontSize = 13.sp)
                        Text(txt(language, "${tracks.size} آهنگ در گوشی", "${tracks.size} songs on device"), fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
                        Text(txt(language, "بدون اینترنت • بدون سرور", "Offline • No server needed"), color = Color(0xFFAAB5A8), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Icon(Icons.Default.Audiotrack, contentDescription = null, tint = Color(0xFFC6A56A), modifier = Modifier.size(48.dp))
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                placeholder = { Text(txt(language, "جست‌وجوی آهنگ، خواننده یا آلبوم", "Search songs, artists or albums")) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when (selectedTab) { 1 -> txt(language, "آلبوم‌ها", "Albums"); 2 -> txt(language, "هنرمندان", "Artists"); 3 -> txt(language, "آهنگ‌های پسندیده", "Favorites"); else -> txt(language, "تازه اضافه‌شده‌ها", "Recently added") },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(txt(language, "${filtered.size} مورد", "${filtered.size} items"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                if (selectedTab == 0 && tracks.isNotEmpty()) {
                    IconButton(onClick = {
                        controller?.setMediaItems(tracks.map { it.toMediaItem() })
                        controller?.prepare()
                        controller?.play()
                    }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "پخش همه", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (!hasPermission) {
                EmptyLibrary(
                    title = txt(language, "برای نمایش موسیقی‌ها اجازه دسترسی لازم است", "Audio permission is required to show music"),
                    action = txt(language, "اجازه دسترسی", "Grant permission"),
                    onAction = onRequestPermission
                )
            } else if (tracks.isEmpty()) {
                EmptyLibrary(
                    title = txt(language, "هنوز آهنگی پیدا نشد", "No music found yet"),
                    action = txt(language, "بررسی دوباره", "Refresh library"),
                    onAction = onRequestPermission
                )
            } else if (filtered.isEmpty()) {
                EmptyLibrary(title = txt(language, "موردی پیدا نشد", "No results found"), action = txt(language, "پاک کردن جست‌وجو", "Clear search"), onAction = { query = "" })
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    items(filtered, key = { it.id }) { track ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn() + slideInVertically { it / 6 }
                        ) {
                        val trackIndex = tracks.indexOfFirst { it.id == track.id }
                        TrackRow(
                            track = track,
                            active = currentTrack?.id == track.id,
                            favorite = track.id in favorites,
                            onClick = {
                                controller?.setMediaItems(tracks.map { it.toMediaItem() }, trackIndex, 0L)
                                controller?.prepare()
                                controller?.play()
                                showPlayer = true
                            },
                            onFavorite = {
                                if (track.id in favorites) favorites.remove(track.id) else favorites.add(track.id)
                            }
                        )
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }

    if (showPlayer && currentTrack != null) {
        ModalBottomSheet(
            onDismissRequest = { showPlayer = false },
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = { }
        ) {
            FullPlayer(
                language = language,
                track = currentTrack,
                isPlaying = isPlaying,
                position = position,
                duration = duration,
                shuffle = shuffle,
                repeatMode = repeatMode,
                favorite = currentTrack.id in favorites,
                onBack = { showPlayer = false },
                onPlayPause = { if (isPlaying) controller?.pause() else controller?.play() },
                onPrevious = { controller?.seekToPreviousMediaItem() },
                onNext = { controller?.seekToNextMediaItem() },
                onSeek = { controller?.seekTo(it.toLong()) },
                onShuffle = { controller?.shuffleModeEnabled = !shuffle },
                onRepeat = {
                    controller?.repeatMode = when (repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                },
                onFavorite = {
                    if (currentTrack.id in favorites) favorites.remove(currentTrack.id) else favorites.add(currentTrack.id)
                }
            )
        }
    }

    if (showSettings) {
        SettingsSheet(
            language = language,
            themeChoice = themeChoice,
            accentChoice = accentChoice,
            onDismiss = { showSettings = false },
            onChange = onSettingsChange
        )
    }

}

private fun AudioTrack.toMediaItem(): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .build()
    return MediaItem.Builder().setMediaId(id.toString()).setUri(uri).setMediaMetadata(metadata).build()
}


@Composable
private fun AMZBrandMark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(17.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF29251F), Color(0xFF111317))))
            .padding(2.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(Color(0xFF17191D)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("AMZ", color = Color(0xFFC6A56A), fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(7, 13, 9, 16, 6).forEach { height ->
                    Box(Modifier.width(3.dp).height(height.dp).clip(CircleShape).background(Color(0xFFC6A56A)))
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(title: String, action: String, onAction: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color(0xFF637064), modifier = Modifier.size(54.dp))
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        Button(onClick = onAction, modifier = Modifier.padding(top = 12.dp)) { Text(action) }
    }
}

@Composable
private fun TrackRow(
    track: AudioTrack,
    active: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().animateContentSize().clip(RoundedCornerShape(15.dp))
            .background(if (active) Color(0xFF29251F) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumTile(track.albumId, Modifier.size(49.dp))
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = if (active) Color(0xFFB8F36B) else MaterialTheme.colorScheme.onSurface)
            Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Text(formatTime(track.duration), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        IconButton(onClick = onFavorite) {
            Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "پسندیدن", tint = if (favorite) Color(0xFFB8F36B) else Color.Gray, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun AlbumTile(seed: Long, modifier: Modifier = Modifier) {
    val palettes = listOf(
        listOf(Color(0xFF334D3B), Color(0xFF18251B)),
        listOf(Color(0xFF4B385F), Color(0xFF241C30)),
        listOf(Color(0xFF72523A), Color(0xFF2B2118)),
        listOf(Color(0xFF31536A), Color(0xFF172733))
    )
    val colors = palettes[(kotlin.math.abs(seed) % palettes.size).toInt()]
    Box(
        modifier = modifier.animateContentSize().clip(RoundedCornerShape(13.dp))
            .background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.82f), modifier = Modifier.size(25.dp))
    }
}

@Composable
private fun MiniPlayer(
    track: AudioTrack,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().animateContentSize().padding(horizontal = 12.dp, vertical = 7.dp)
            .clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen).padding(9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumTile(track.albumId, Modifier.size(43.dp))
        Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFFAAB5A8), fontSize = 11.sp)
        }
        IconButton(onClick = onPlayPause) {
            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFFB8F36B))
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Default.SkipNext, contentDescription = null)
        }
    }
}

@Composable
private fun FullPlayer(
    language: String,
    track: AudioTrack,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    shuffle: Boolean,
    repeatMode: Int,
    favorite: Boolean,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onFavorite: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "بازگشت") }
            Text(txt(language, "در حال پخش", "Now playing"), modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onFavorite) {
                Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = null, tint = if (favorite) Color(0xFFB8F36B) else Color.White)
            }
        }
        Spacer(Modifier.height(25.dp))
        AlbumTile(track.albumId, Modifier.fillMaxWidth().height(290.dp))
        Text(track.title, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 28.dp))
        Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, modifier = Modifier.padding(top = 7.dp))
        Spacer(Modifier.height(22.dp))
        Slider(
            value = if (duration > 0) position.coerceIn(0, duration).toFloat() else 0f,
            onValueChange = onSeek,
            valueRange = 0f..(duration.coerceAtLeast(1L).toFloat()),
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position), color = Color.Gray, fontSize = 11.sp)
            Text(formatTime(duration), color = Color.Gray, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onShuffle) {
                Icon(Icons.Default.Shuffle, contentDescription = "تصادفی", tint = if (shuffle) Color(0xFFB8F36B) else Color.Gray)
            }
            IconButton(onClick = onPrevious, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "قبلی", modifier = Modifier.size(32.dp))
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(70.dp).clip(CircleShape).background(Color(0xFFB8F36B))) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "پخش یا توقف", tint = Color(0xFF17210B), modifier = Modifier.size(38.dp))
            }
            IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Default.SkipNext, contentDescription = "بعدی", modifier = Modifier.size(32.dp))
            }
            IconButton(onClick = onRepeat) {
                Icon(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, contentDescription = "تکرار", tint = if (repeatMode != Player.REPEAT_MODE_OFF) Color(0xFFB8F36B) else Color.Gray)
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1000L)
    return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
 
private fun txt(language: String, persian: String, english: String): String =
    if (language == "en") english else persian

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    language: String,
    themeChoice: String,
    accentChoice: String,
    onDismiss: () -> Unit,
    onChange: (String, String, String) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(txt(language, "تنظیمات", "Settings"), fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
            }
            Text(txt(language, "شخصی‌سازی برنامه", "Personalization"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            SettingsGroupTitle(txt(language, "زبان برنامه", "App language"))
            SettingsChoiceRow(
                options = listOf("fa" to "فارسی", "en" to "English"),
                selected = language,
                onSelect = { onChange(it, themeChoice, accentChoice) }
            )
            SettingsGroupTitle(txt(language, "پوسته برنامه", "App theme"))
            SettingsChoiceRow(
                options = listOf(
                    "system" to txt(language, "هماهنگ با سیستم", "System"),
                    "light" to txt(language, "روشن", "Light"),
                    "dark" to txt(language, "تاریک", "Dark")
                ),
                selected = themeChoice,
                onSelect = { onChange(language, it, accentChoice) }
            )
            SettingsGroupTitle(txt(language, "رنگ تأکیدی", "Accent color"))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(
                    "gold" to Color(0xFFC6A56A),
                    "blue" to Color(0xFF65A9FF),
                    "purple" to Color(0xFFB69CFF),
                    "pink" to Color(0xFFFF8EB8)
                ).forEach { (key, color) ->
                    Box(
                        modifier = Modifier.size(42.dp).clip(CircleShape)
                            .background(color)
                            .clickable { onChange(language, themeChoice, key) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (accentChoice == key) {
                            Icon(Icons.Default.Favorite, contentDescription = null, tint = Color(0xFF182019), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            androidx.compose.material3.HorizontalDivider()
            SettingsGroupTitle(txt(language, "درباره برنامه", "About"))
            Text("AMZ Music  •  1.2", fontWeight = FontWeight.SemiBold)
            Text("Created by Amin", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text(
                txt(language, "موزیک‌پلیر آفلاین برای موسیقی‌های داخل گوشی", "An offline music player for audio stored on your device"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun SettingsGroupTitle(title: String) {
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
}

@Composable
private fun SettingsChoiceRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (key, label) ->
            val active = key == selected
            Surface(
                modifier = Modifier.weight(1f).clickable { onSelect(key) },
                shape = RoundedCornerShape(14.dp),
                color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                border = if (active) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
            ) {
                Text(
                    label,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontSize = 12.sp,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
