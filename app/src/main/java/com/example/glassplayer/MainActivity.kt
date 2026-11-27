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
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.Dp
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

val Red = Color(0xFFFA2D48)

@Composable
fun FadeUp(content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(550, easing = FastOutSlowInEasing), label = "fadeUp")
    Box(Modifier.graphicsLayer { alpha = a; translationY = (1f - a) * 40f }) { content() }
}

@Composable
fun ScreenTitle(t: String) {
    Text(t, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 12.dp))
}

@Composable
fun SectionTitle(t: String) {
    Text(t, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(20.dp, 22.dp, 20.dp, 12.dp))
}

@Composable
fun FeatureCard(s: Song, onClick: () -> Unit) {
    Column(Modifier.width(300.dp).clickable { onClick() }) {
        Box(Modifier.size(300.dp).clip(RoundedCornerShape(14.dp))) {
            Cover(s, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(.8f)))))
            Text(s.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(16.dp))
        }
        Text(s.artist, color = Color.Gray, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun SquareCard(s: Song, onClick: () -> Unit) {
    Column(Modifier.width(150.dp).clickable { onClick() }) {
        Cover(s, Modifier.size(150.dp).clip(RoundedCornerShape(10.dp)))
        Text(s.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(s.artist, color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun HomeScreen(
    home: List<Pair<String, List<Song>>>, homeMsg: String, songs: List<Song>, bottom: Dp,
    onRemote: (Song) -> Unit, onLocal: (Int) -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(bottom = bottom)) {
        item { ScreenTitle("Listen Now") }
        if (home.isEmpty()) item { Text(homeMsg, color = Color.Gray, modifier = Modifier.padding(20.dp)) }
        home.forEachIndexed { si, (title, list) ->
            item(key = title) {
                FadeUp {
                    Column {
                        if (si > 0) SectionTitle(title)
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(list) { s -> if (si == 0) FeatureCard(s) { onRemote(s) } else SquareCard(s) { onRemote(s) } }
                        }
                    }
                }
            }
        }
        if (songs.isNotEmpty()) item(key = "phone") {
            FadeUp {
                Column {
                    SectionTitle("From your phone")
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        itemsIndexed(songs.take(12)) { n, s -> SquareCard(s) { onLocal(n) } }
                    }
                }
            }
        }
    }
}

val GENRES = listOf(
    Triple("Pop", 0xFFFF6A88, 0xFFFF99AC), Triple("Chill", 0xFF4FACFE, 0xFF00F2FE),
    Triple("Dance", 0xFFA18CD1, 0xFFFBC2EB), Triple("Indie", 0xFF43E97B, 0xFF38F9D7),
    Triple("Late night", 0xFF667EEA, 0xFF764BA2), Triple("Focus", 0xFFF6D365, 0xFFFDA085),
    Triple("Hip-hop", 0xFFFA709A, 0xFFFEE140), Triple("Rock", 0xFF30CFD0, 0xFF330867)
)

@Composable
fun BrowseScreen(bottom: Dp, onGenre: (String) -> Unit) {
    Column {
        ScreenTitle("Browse")
        FadeUp {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottom),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(GENRES) { g ->
                    Box(
                        Modifier.height(92.dp).clip(RoundedCornerShape(14.dp))
                            .background(Brush.linearGradient(listOf(Color(g.second), Color(g.third))))
                            .clickable { onGenre(g.first) }.padding(14.dp)
                    ) { Text(g.first, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(songs: List<Song>, granted: Boolean, cur: Song?, bottom: Dp, onLocal: (Int) -> Unit) {
    Column {
        ScreenTitle("Library")
        if (songs.isEmpty()) Text(
            if (granted) "No music found on this phone." else "Allow music access to see your songs.",
            color = Color.Gray, modifier = Modifier.padding(20.dp)
        )
        LazyColumn(contentPadding = PaddingValues(bottom = bottom)) {
            itemsIndexed(songs) { n, s -> SongRow(s, cur?.id == s.id) { onLocal(n) } }
        }
    }
}

@Composable
fun SearchScreen(
    query: String, onQuery: (String) -> Unit, status: String, results: List<Song>, cur: Song?, bottom: Dp,
    onSearch: () -> Unit, onRemote: (Song) -> Unit
) {
    Column {
        ScreenTitle("Search")
        BasicTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
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
        LazyColumn(contentPadding = PaddingValues(bottom = bottom)) {
            itemsIndexed(results) { _, s -> SongRow(s, cur?.id == s.id) { onRemote(s) } }
        }
    }
}

@Composable
fun TabBar(tab: Int, onTab: (Int) -> Unit, mod: Modifier) {
    val names = listOf("Listen Now", "Browse", "Library", "Search")
    val icons = listOf("◉", "▦", "♫", "⌕")
    Glass(mod.height(66.dp), RoundedCornerShape(33.dp)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(6.dp)) {
            val w = maxWidth / 4
            val x by animateDpAsState(w * tab, tween(450, easing = FastOutSlowInEasing), label = "pill")
            Box(Modifier.offset(x = x).width(w).fillMaxHeight().clip(RoundedCornerShape(27.dp)).background(Color.White.copy(.18f)))
            Row(Modifier.fillMaxSize()) {
                names.forEachIndexed { n, t ->
                    val c = animateColorAsState(if (tab == n) Red else Color.White.copy(.6f), tween(300), label = "tabc").value
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null
                        ) { onTab(n) },
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(icons[n], color = c, fontSize = 18.sp)
                        Text(t, color = c, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
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
    var home by remember { mutableStateOf(emptyList<Pair<String, List<Song>>>()) }
    var homeMsg by remember { mutableStateOf("Loading…") }
    LaunchedEffect(Unit) {
        for (q in listOf("Top hits", "Chill vibes", "Late night", "Workout")) {
            val r = withContext(Dispatchers.IO) { runCatching { YT.search(q) } }
            r.onSuccess { l -> if (l.isNotEmpty()) home = home + (q to l.take(10)) }
        }
        if (home.isEmpty()) homeMsg = "Couldn't load recommendations. Check your internet."
    }

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

    fun searchFor(g: String) { query = "$g music"; tab = 3; doSearch() }
    val red = Red
    val cur = queue.getOrNull(idx)
    val bottomPad = if (cur != null && !full) 200.dp else 120.dp
    val ease = FastOutSlowInEasing

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            Crossfade(tab, animationSpec = tween(350), label = "pages") { t ->
                when (t) {
                    0 -> HomeScreen(home, homeMsg, songs, bottomPad, { playRemote(it) }, { playLocal(it) })
                    1 -> BrowseScreen(bottomPad) { searchFor(it) }
                    2 -> LibraryScreen(songs, granted, cur, bottomPad) { playLocal(it) }
                    else -> SearchScreen(query, { query = it }, status, results, cur, bottomPad, { doSearch() }, { playRemote(it) })
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
                Glass(Modifier.navigationBarsPadding().padding(start = 14.dp, end = 14.dp, bottom = 92.dp).fillMaxWidth().height(60.dp)) {
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
            visible = !full,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(450, easing = ease)) { it } + fadeIn(tween(450)),
            exit = slideOutVertically(tween(400, easing = ease)) { it } + fadeOut(tween(300))
        ) {
            TabBar(tab, { tab = it }, Modifier.navigationBarsPadding().padding(14.dp).fillMaxWidth())
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
