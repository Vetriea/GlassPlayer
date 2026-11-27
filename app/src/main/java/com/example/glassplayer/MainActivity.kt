package com.example.glassplayer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class Song(val id: Long, val title: String, val artist: String, val uri: Uri)

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        player = ExoPlayer.Builder(this).build()
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App(player) } }
    }
    override fun onDestroy() { player.release(); super.onDestroy() }
}

fun loadSongs(ctx: Context): List<Song> {
    val out = mutableListOf<Song>()
    val col = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST)
    ctx.contentResolver.query(
        col, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
        "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
    )?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            val artist = c.getString(2)?.takeIf { it != "<unknown>" } ?: "Unknown artist"
            out += Song(id, c.getString(1) ?: "Unknown", artist, ContentUris.withAppendedId(col, id))
        }
    }
    return out
}

fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

@Composable
fun Cover(s: Song, mod: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, s.id) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(s.uri, Size(512, 512), null) }.getOrNull()
        }
    }
    Box(mod.background(Color(0xFF2C2C2E))) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
fun Glass(mod: Modifier, shape: Shape = RoundedCornerShape(31.dp), content: @Composable BoxScope.() -> Unit) {
    Box(
        mod.clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(.26f), Color.White.copy(.08f))))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(.55f), Color.White.copy(.08f))), shape),
        contentAlignment = Alignment.Center, content = content
    )
}

@Composable
fun App(player: ExoPlayer) {
    val ctx = LocalContext.current
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(perm) }

    var songs by remember { mutableStateOf(emptyList<Song>()) }
    LaunchedEffect(granted) {
        if (granted) {
            songs = withContext(Dispatchers.IO) { loadSongs(ctx) }
            player.setMediaItems(songs.map { MediaItem.fromUri(it.uri) })
            player.prepare()
        }
    }

    var idx by remember { mutableIntStateOf(-1) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(1L) }
    var drag by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) { idx = player.currentMediaItemIndex }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            if (!drag) pos = player.currentPosition
            dur = player.duration.coerceAtLeast(1L)
            delay(300)
        }
    }
    BackHandler(full) { full = false }

    val red = Color(0xFFFA2D48)
    val cur = songs.getOrNull(idx)
    val ease = FastOutSlowInEasing

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Text("Library", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 8.dp))
            if (songs.isEmpty()) Text(
                if (granted) "No music found on this phone." else "Allow music access to see your songs.",
                color = Color.Gray, modifier = Modifier.padding(20.dp)
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 130.dp)) {
                itemsIndexed(songs) { n, s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { player.seekTo(n, 0L); player.play() }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Cover(s, Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(s.title, color = if (n == idx) red else Color.White, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.artist, color = Color.Gray, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = cur != null && !full,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(450, easing = ease)) { it } + fadeIn(tween(450)),
            exit = slideOutVertically(tween(400, easing = ease)) { it } + fadeOut(tween(300))
        ) {
            cur?.let { s ->
                Glass(Modifier.navigationBarsPadding().padding(14.dp).fillMaxWidth().height(62.dp)) {
                    Row(Modifier.fillMaxSize().clickable { full = true }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(s, Modifier.size(46.dp).clip(CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.artist, color = Color.White.copy(.6f), fontSize = 12.sp, maxLines = 1)
                        }
                        Text(if (playing) "⏸" else "▶", color = Color.White, fontSize = 22.sp,
                            modifier = Modifier.clickable { if (playing) player.pause() else player.play() }.padding(14.dp))
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = full && cur != null,
            modifier = Modifier.fillMaxSize(),
            enter = slideInVertically(tween(550, easing = ease)) { it },
            exit = slideOutVertically(tween(450, easing = ease)) { it }
        ) {
            cur?.let { s ->
                val sc by animateFloatAsState(if (playing) 1f else 0.88f, tween(500, easing = ease), label = "cover")
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Cover(s, Modifier.fillMaxSize().blur(80.dp).alpha(.55f))
                    Column(
                        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(Modifier.size(40.dp, 5.dp).clip(CircleShape).background(Color.White.copy(.4f)).clickable { full = false })
                        Spacer(Modifier.height(32.dp))
                        Cover(s, Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer { scaleX = sc; scaleY = sc }.clip(RoundedCornerShape(18.dp)))
                        Spacer(Modifier.height(32.dp))
                        Text(s.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                        Text(s.artist, color = Color.White.copy(.65f), fontSize = 18.sp, maxLines = 1, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(16.dp))
                        Slider(
                            value = pos.toFloat().coerceIn(0f, dur.toFloat()),
                            onValueChange = { drag = true; pos = it.toLong() },
                            onValueChangeFinished = { player.seekTo(pos); drag = false },
                            valueRange = 0f..dur.toFloat(),
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(.25f))
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(fmt(pos), color = Color.White.copy(.6f), fontSize = 12.sp)
                            Text(fmt(dur), color = Color.White.copy(.6f), fontSize = 12.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                            Text("⏮", color = Color.White, fontSize = 32.sp, modifier = Modifier.clickable { player.seekToPreviousMediaItem() }.padding(12.dp))
                            Glass(Modifier.size(84.dp), CircleShape) {
                                Box(Modifier.fillMaxSize().clickable { if (playing) player.pause() else player.play() }, Alignment.Center) {
                                    Text(if (playing) "⏸" else "▶", color = Color.White, fontSize = 34.sp)
                                }
                            }
                            Text("⏭", color = Color.White, fontSize = 32.sp, modifier = Modifier.clickable { player.seekToNextMediaItem() }.padding(12.dp))
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}
