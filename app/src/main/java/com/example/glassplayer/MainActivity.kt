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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val uri: Uri,
    val thumb: String? = null,
    val webUrl: String? = null
)

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
    if (s.thumb != null) {
        AsyncImage(
            model = s.thumb, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = mod.background(Color(0xFF2C2C2E))
        )
        return
    }
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
fun SongRow(s: Song, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(s, Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(s.title, color = if (active) Color(0xFFFA2D48) else Color.White, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.artist, color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun App(player: ExoPlayer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(perm) }

    var songs by remember { mutableStateOf(emptyList<Song>()) }
    LaunchedEffect(granted) {
        if (granted) songs = withContext(Dispatchers.IO) { loadSongs(ctx) }
    }

    var queue by remember { mutableStateOf(emptyList<Song>()) }
    var idx by remember { mutableIntStateOf(-1) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(1L) }
    var drag by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<Song>()) }
    var status by remember { mutableStateOf("") }

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

    fun playLocal(n: Int) {
        queue = songs
        idx = n
        player.setMediaItems(songs.map { MediaItem.fromUri(it.uri) }, n, 0L)
        player.prepare()
        player.play()
    }

    fun playRemote(s: Song) {
        scope.launch {
            status = "Loading…"
            val r = withContext(Dispatchers.IO) { runCatching { YT.streamUrl(s.webUrl!!) } }
            r.onSuccess { url ->
                queue = listOf(s)
                idx = 0
                player.setMediaItem(MediaItem.fromUri(url))
                player.prepare()
                player.play()
                status = ""
            }.onFailure { status = "Can't play: ${it.message}" }
        }
    }

    fun doSearch() {
        if (query.isBlank()) return
        scope.launch {
            status = "Searching…"
            val r = withContext(Dispatchers.IO) { runCatching { YT.search(query.trim()) } }
            r.onSuccess { results = it; status = if (it.isEmpty()) "No results" else "" }
                .onFailure { status = "Search failed: ${it.message}" }
        }
    }

    val red = Color(0xFFFA2D48)
    val cur = queue.getOrNull(idx)
    val ease = FastOutSlowInEasing

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Text(if (tab == 0) "Library" else "YouTube", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 12.dp))
            Glass(Modifier.padding(horizontal = 20.dp).width(220.dp).height(40.dp), RoundedCornerShape(20.dp)) {
                Row(Modifier.fillMaxSize()) {
                    listOf("Library", "YouTube").forEachIndexed { n, t ->
                        val c = animateColorAsState(if (tab == n) Color.White else Color.White.copy(.5f), tween(300), label = "tab").value
                        Box(Modifier.weight(1f).fillMaxHeight().clickable { tab = n }, Alignment.Center) {
                            Text(t, color = c, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Crossfade(tab, animationSpec = tween(350), label = "pages") { t ->
                if (t == 0) {
                    Column {
                        if (songs.isEmpty()) Text(
                            if (granted) "No music found on this phone." else "Allow music access to see your songs.",
                            color = Color.Gray, modifier = Modifier.padding(20.dp)
                        )
                        LazyColumn(contentPadding = PaddingValues(bottom = 130.dp)) {
                            itemsIndexed(songs) { n, s -> SongRow(s, cur?.id == s.id) { playLocal(n) } }
                        }
                    }
                } else {
                    Column {
                        BasicTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                            cursorBrush = SolidColor(Color.White),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth(),
                            decorationBox = { inner ->
                                Box(
                                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xFF1C1C1E)).padding(horizontal = 14.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (query.isEmpty()) Text("Songs, artists", color = Color.Gray)
                                    inner()
                                }
                            }
                        )
                        if (status.isNotEmpty()) Text(status, color = Color.Gray, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                        LazyColumn(contentPadding = PaddingValues(bottom = 130.dp)) {
                            itemsIndexed(results) { _, s -> SongRow(s, cur?.id == s.id) { playRemote(s) } }
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
