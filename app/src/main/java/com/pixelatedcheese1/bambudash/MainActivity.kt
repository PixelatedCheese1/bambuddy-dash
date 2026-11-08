package com.pixelatedcheese1.bambudash

import android.app.Application
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

class Vm(app: Application) : AndroidViewModel(app) {
    private val sp = app.getSharedPreferences("bb", 0)
    var url by mutableStateOf(sp.getString("url", "") ?: "")
    var key by mutableStateOf(sp.getString("key", "") ?: "")
    var api by mutableStateOf<BambuddyApi?>(null)
    var st by mutableStateOf(Status())
    var pidPref by mutableStateOf(sp.getString("pid", "") ?: "")
    var pid by mutableIntStateOf(pidPref.toIntOrNull() ?: 0)
    var err by mutableStateOf<String?>(null)
    var title by mutableStateOf("")
    var startMs by mutableLongStateOf(0L)
    var recents by mutableStateOf(listOf<Recent>())
    private var poll: Job? = null

    init { connect() }

    fun save(u: String, k: String, p: String) {
        url = u; key = k; pidPref = p.trim(); pid = pidPref.toIntOrNull() ?: 0
        sp.edit().putString("url", u).putString("key", k).putString("pid", pidPref).apply(); connect()
    }

    private fun connect() {
        poll?.cancel()
        if (url.isBlank() || key.isBlank()) return
        val a = BambuddyApi(url, key); api = a
        poll = viewModelScope.launch {
            var lastKey: Int? = null
            while (isActive) {
                try {
                    if (pid == 0) pid = a.printerIds().firstOrNull() ?: 0
                    if (pid == 0) err = "No printers found in Bambuddy" else {
                        val s = a.status(pid); st = s; err = if (s.connected) null else "Printer offline"
                        if (s.state == PState.IDLE) lastKey = null
                        else if ((s.archiveId ?: -1) != lastKey) { // new job: name it and derive its start time once
                            lastKey = s.archiveId ?: -1
                            title = s.archiveId?.let { runCatching { a.archiveName(it) }.getOrNull() } ?: ""
                            val total = s.remainingMin * 60_000.0 / (1 - s.progress).coerceAtLeast(0.01f)
                            startMs = (System.currentTimeMillis() - total * s.progress).toLong()
                        }
                    }
                } catch (e: Exception) { err = e.message ?: "Can't reach Bambuddy" }
                delay(3000)
            }
        }
        loadRecents()
    }

    fun loadRecents() { val a = api ?: return; viewModelScope.launch { runCatching { recents = a.recent() } } }
    private fun run(f: suspend (BambuddyApi) -> Unit) = viewModelScope.launch {
        try { api?.let { f(it); err = null; st = it.status(pid) } } catch (e: Exception) { err = e.message }
    }
    fun act(cmd: String) = run { it.control(pid, cmd) }
    fun start(r: Recent) = run { it.startPrint(r.id, pid, st.awaitingClear) }
}

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            val cs = if (Build.VERSION.SDK_INT >= 31) { if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx) }
                     else if (dark) darkColorScheme() else lightColorScheme()
            MaterialExpressiveTheme(colorScheme = cs, motionScheme = MotionScheme.expressive()) { Dash(viewModel()) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Dash(vm: Vm) {
    val ctx = LocalContext.current
    val s = vm.st
    val api = vm.api
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    var cfg by remember { mutableStateOf(vm.url.isBlank()) }
    var ask by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val loader = remember(api) { ImageLoader.Builder(ctx).apply { api?.let { okHttpClient(it.http) } }.build() }
    val tf = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    fun hm(m: Int) = "${m / 60}h ${m % 60}m"
    val idle = s.state == PState.IDLE
    val now = System.currentTimeMillis()
    val shown = if (idle) vm.recents.firstOrNull()?.id else s.archiveId
    val shownName = (if (idle) vm.recents.firstOrNull()?.name else vm.title).orEmpty().ifBlank { "No job" }
    val img = shown?.let { api?.thumbUrl(it) } ?: if (!idle && vm.pid != 0) api?.coverUrl(vm.pid, vm.title) else null
    fun startMsg(r: Recent) = "Start “${r.name}”?" + if (s.awaitingClear) "\n\nThe build plate must be empty — it will be marked as cleared." else ""

    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Row(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(56.dp, 56.dp, 56.dp, 16.dp)).background(cs.surfaceContainerHigh)) {
                if (img != null) AsyncImage(
                    model = ImageRequest.Builder(ctx).data(img).crossfade(true).build(),
                    imageLoader = loader, contentDescription = "Current print", contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize())
                Surface(Modifier.align(Alignment.TopStart).padding(16.dp), shape = CircleShape, color = cs.primaryContainer) {
                    Text(when (s.state) { PState.PRINTING -> "Printing"; PState.PAUSED -> "Paused"; else -> "Idle" },
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
                }
                IconButton({ cfg = true }, Modifier.align(Alignment.TopEnd).padding(8.dp)) { Icon(Icons.Filled.Settings, "Settings") }
                vm.err?.let {
                    Surface(Modifier.align(Alignment.BottomStart).padding(16.dp), shape = RoundedCornerShape(20.dp), color = cs.errorContainer) {
                        Text(it, Modifier.padding(12.dp), maxLines = 2, style = MaterialTheme.typography.bodySmall, color = cs.onErrorContainer)
                    }
                }
            }
            Column(Modifier.weight(1.25f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(if (idle) "READY · LAST PRINT" else "NOW PRINTING", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                        Text(shownName, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text("${(s.progress * 100).roundToInt()}%", style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Black, color = cs.primary)
                }
                Surface(shape = RoundedCornerShape(36.dp), color = cs.surfaceContainer) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LinearWavyProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(),
                            amplitude = { if (s.state == PState.PRINTING) 1f else 0f })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TimeCol("Started", if (idle) "—" else tf.format(Date(vm.startMs)))
                            TimeCol("Remaining", if (idle) "—" else hm(s.remainingMin))
                            TimeCol("Finishes", if (idle) "—" else tf.format(Date(now + s.remainingMin * 60_000L)))
                        }
                        Text("Layer ${s.layer}/${s.total}  ·  Nozzle ${s.nozzle}°  ·  Bed ${s.bed}°",
                            style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    }
                }
                Row(Modifier.height(80.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        when (s.state) {
                            PState.PRINTING -> vm.act("pause")
                            PState.PAUSED -> vm.act("resume")
                            else -> vm.recents.firstOrNull()?.let { r -> ask = startMsg(r) to { vm.start(r) } } ?: run { menu = true }
                        }
                    }, modifier = Modifier.weight(1.6f).fillMaxHeight(), shapes = ButtonDefaults.shapes()) {
                        Icon(if (s.state == PState.PRINTING) Icons.Filled.Pause else Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(when (s.state) { PState.PRINTING -> "Pause"; PState.PAUSED -> "Resume"; else -> "Start" })
                    }
                    Button(onClick = { ask = "Stop this print?" to { vm.act("stop") } }, modifier = Modifier.weight(1f).fillMaxHeight(),
                        enabled = !idle, shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.buttonColors(containerColor = cs.errorContainer, contentColor = cs.onErrorContainer)) {
                        Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop")
                    }
                    FilledTonalIconButton(onClick = { vm.loadRecents(); menu = true }, modifier = Modifier.size(80.dp), shapes = IconButtonDefaults.shapes(),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer)) {
                        Icon(Icons.Filled.History, "Recent prints")
                    }
                }
            }
        }
    }

    ask?.let { (title, go) ->
        AlertDialog(onDismissRequest = { ask = null }, title = { Text(title) },
            confirmButton = { TextButton({ go(); ask = null }) { Text("Yes") } },
            dismissButton = { TextButton({ ask = null }) { Text("Cancel") } })
    }
    if (menu) ModalBottomSheet(onDismissRequest = { menu = false }) {
        Text("Recent prints", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        LazyColumn {
            items(vm.recents) { r ->
                ListItem(headlineContent = { Text(r.name) }, supportingContent = { Text(hm(r.minutes)) },
                    leadingContent = {
                        AsyncImage(model = api?.thumbUrl(r.id), imageLoader = loader, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh))
                    },
                    modifier = Modifier.clickable { menu = false; ask = startMsg(r) to { vm.start(r) } })
            }
        }
    }
    if (cfg) {
        var u by remember { mutableStateOf(vm.url) }
        var k by remember { mutableStateOf(vm.key) }
        var p by remember { mutableStateOf(vm.pidPref) }
        AlertDialog(onDismissRequest = { if (vm.url.isNotBlank()) cfg = false }, title = { Text("Connect to Bambuddy") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(u, { u = it }, label = { Text("Server, e.g. http://192.168.1.50:8000") }, singleLine = true)
                    OutlinedTextField(k, { k = it }, label = { Text("API key (bb_…)") }, singleLine = true)
                    OutlinedTextField(p, { p = it }, label = { Text("Printer ID (optional, default: first)") }, singleLine = true)
                }
            },
            confirmButton = { TextButton({ vm.save(u, k, p); cfg = false }) { Text("Save") } })
    }
}

@Composable
fun TimeCol(label: String, value: String) = Column {
    Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}
