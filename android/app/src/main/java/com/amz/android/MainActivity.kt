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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
            AMZMusicTheme {
                MusicHome(
                    tracks = tracks,
                    controller = mediaController,
                    hasPermission = hasPermission,
                    onRequestPermission = { permissionLauncher.launch(permission) }
                )
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
            MediaStore.Audio.Media.DURATION
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        try {
            contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor: Cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
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
private fun AMZMusicTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Color(0xFFB8F36B),
        onPrimary = Color(0xFF17210B),
        background = Color(0xFF090B0A),
        surface = Color(0xFF151916),
        surfaceVariant = Color(0xFF222923),
        onSurface = Color(0xFFF2F5EF),
        onSurfaceVariant = Color(0xFFA2ADA2)
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicHome(
    tracks: List<AudioTrack>,
    controller: MediaController?,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit
) {
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
                if (currentTrack != null) {
                    MiniPlayer(
                        track = currentTrack,
                        isPlaying = isPlaying,
                        onOpen = { showPlayer = true },
                        onPlayPause = { if (isPlaying) controller?.pause() else controller?.play() },
                        onNext = { controller?.seekToNextMediaItem() }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF101410)).padding(horizontal = 12.dp, vertical = 8.dp)
                        .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("آهنگ‌ها", "آلبوم‌ها", "هنرمندان", "پسندیده‌ها").forEachIndexed { index, label ->
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
                Column(modifier = Modifier.weight(1f)) {
                    Text("AMZ MUSIC", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("موسیقی، به سبک تو", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    modifier = Modifier.size(46.dp).clip(CircleShape).background(Color(0xFF1F2A20)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp))
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A241B))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(17.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("کتابخانه موسیقی تو", color = Color(0xFFB8F36B), fontSize = 13.sp)
                        Text("${tracks.size} آهنگ در گوشی", fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
                        Text("بدون اینترنت • بدون سرور", color = Color(0xFFAAB5A8), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Icon(Icons.Default.Audiotrack, contentDescription = null, tint = Color(0xFFB8F36B), modifier = Modifier.size(48.dp))
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                placeholder = { Text("جست‌وجوی آهنگ، خواننده یا آلبوم") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when (selectedTab) { 1 -> "آلبوم‌ها"; 2 -> "هنرمندان"; 3 -> "آهنگ‌های پسندیده"; else -> "همه آهنگ‌ها" },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text("${filtered.size} مورد", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
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
                    title = "برای نمایش موسیقی‌ها اجازه دسترسی لازم است",
                    action = "اجازه دسترسی",
                    onAction = onRequestPermission
                )
            } else if (tracks.isEmpty()) {
                EmptyLibrary(
                    title = "هنوز آهنگی پیدا نشد",
                    action = "بررسی دوباره",
                    onAction = onRequestPermission
                )
            } else if (filtered.isEmpty()) {
                EmptyLibrary(title = "موردی پیدا نشد", action = "پاک کردن جست‌وجو", onAction = { query = "" })
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    items(filtered, key = { it.id }) { track ->
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
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }

    if (showPlayer && currentTrack != null) {
        ModalBottomSheet(
            onDismissRequest = { showPlayer = false },
            containerColor = Color(0xFF111712),
            dragHandle = { }
        ) {
            FullPlayer(
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
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
            .background(if (active) Color(0xFF1C281D) else Color.Transparent)
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
        modifier = modifier.clip(RoundedCornerShape(13.dp))
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp)
            .clip(RoundedCornerShape(18.dp)).background(Color(0xFF202920))
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
            Text("در حال پخش", modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.SemiBold)
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
