package cat.mandala.pen

import android.content.ContentValues
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.InputDevice
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import java.io.File
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Línies en vectorial renderitzades per a una càmera concreta. */
class Crisp(val bmp: Bitmap, val scale: Float, val ox: Float, val oy: Float, val rev: Int, val color: Int)

class UiState {
    var crisp by mutableStateOf<Crisp?>(null)
    var tool by mutableStateOf(Tool.FILL)
    var sectors by mutableIntStateOf(12)
    var mirror by mutableStateOf(true)
    var symmetric by mutableStateOf(true)
    var guides by mutableStateOf(false)
    var size by mutableFloatStateOf(6f) // dp en pantalla
    var look by mutableStateOf(Looks.all[0])
    var ink by mutableIntStateOf(Looks.palette[0])
    var panel by mutableStateOf(false)
    var drawing by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var gallery by mutableStateOf(false)
    var menu by mutableStateOf(Menu.NONE)
    val anchors = mutableStateMapOf<Menu, Float>()
    var rev by mutableIntStateOf(0)
    var designId: String? = null
    var hover by mutableStateOf<Offset?>(null)
    /** Si és fals, un sol dit no dibuixa (només navega amb dos dits): rebuig de palma. */
    var fingerDraws by mutableStateOf(true)

    // càmera: vista = món * scale + (ox, oy)
    var scale by mutableFloatStateOf(1f)
    var ox by mutableFloatStateOf(0f)
    var oy by mutableFloatStateOf(0f)
    var viewW = 0
    var viewH = 0
    var fitScale = 1f

    val symSectors get() = if (symmetric) sectors else 1
    val symMirror get() = symmetric && mirror

    fun fit() {
        if (viewW == 0 || viewH == 0) return
        fitScale = min(viewW, viewH).toFloat() / Board.SIZE * 0.97f
        scale = fitScale
        ox = (viewW - Board.SIZE * scale) / 2f
        oy = (viewH - Board.SIZE * scale) / 2f
    }

    /** Zoom amb centre a la vista (botons − / +). */
    fun zoomBy(f: Float) {
        if (viewW == 0) return
        val ns = (scale * f).coerceIn(fitScale * 0.6f, fitScale * 8f)
        val wx = (viewW / 2f - ox) / scale
        val wy = (viewH / 2f - oy) / scale
        scale = ns
        ox = viewW / 2f - wx * ns
        oy = viewH / 2f - wy * ns
        clampPan()
    }

    fun clampPan() {
        val w = Board.SIZE * scale
        ox = ox.coerceIn(viewW * 0.15f - w, viewW * 0.85f)
        oy = oy.coerceIn(viewH * 0.15f - w, viewH * 0.85f)
    }

    fun toWorld(p: Offset) = Offset((p.x - ox) / scale, (p.y - oy) / scale)
    fun toView(w: Offset) = Offset(w.x * scale + ox, w.y * scale + oy)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // tauletes (>=600dp): sempre horitzontal; mòbils: lliure
        if (resources.configuration.smallestScreenWidthDp >= 600) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { MandalaApp() } }
    }
}

@Composable
fun MandalaApp() {
    val board = remember { Board() }
    val ui = remember { UiState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val store = remember { Designs(ctx) }
    var playJob by remember { mutableStateOf<Job?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var shareSnap by remember { mutableStateOf<Bitmap?>(null) }

    val savedMsg = stringResource(R.string.saved)
    val failMsg = stringResource(R.string.save_failed)

    fun saveCurrent(): Boolean {
        if (!board.hasArt) return false
        val id = ui.designId ?: System.currentTimeMillis().toString().also { ui.designId = it }
        return try {
            store.save(id, board, Board.Meta(Looks.all.indexOf(ui.look), ui.sectors, ui.mirror), ui.look)
            true
        } catch (e: Exception) { false }
    }

    /** Pàgina nova. Abans desa l'actual (si [keepCurrent]). */
    fun generate(keepCurrent: Boolean = true) {
        val old = playJob
        old?.cancel()
        if (keepCurrent) saveCurrent()
        ui.designId = null
        playJob = scope.launch {
            old?.cancelAndJoin()
            board.resetPage()
            board.lineColor = ui.look.line
            val g = Generator.generate(System.nanoTime(), Board.SIZE / 2f * 0.95f, 5.5f)
            ui.sectors = g.sectors; ui.mirror = true; ui.symmetric = true
            for (ring in g.rings) board.playArt(ring)
        }
    }

    fun openDesign(id: String) {
        val old = playJob
        old?.cancel()
        if (id != ui.designId) saveCurrent()
        playJob = scope.launch {
            old?.cancelAndJoin()
            ui.busy = true
            var ok = false
            try {
                val meta = withContext(Dispatchers.Default) { store.load(id, board) }
                if (meta != null) {
                    ui.look = Looks.all[meta.look.coerceIn(0, Looks.all.lastIndex)]
                    ui.sectors = meta.sectors; ui.mirror = meta.mirror; ui.symmetric = true
                    board.lineColor = ui.look.line
                    withContext(Dispatchers.Default) { board.redrawAll() }
                    ui.designId = id
                    ok = true
                }
            } finally { ui.busy = false }
            ui.gallery = false
            if (!ok) generate(false)
        }
    }

    fun autoPaint() {
        val old = playJob
        old?.cancel()
        playJob = scope.launch {
            old?.cancelAndJoin()
            ui.busy = true
            val fills = try {
                withContext(Dispatchers.Default) {
                    board.planAutoPaint(ui.sectors, Looks.palette, Random(System.nanoTime()))
                }
            } finally { ui.busy = false }
            board.playFills(fills)
        }
    }

    fun exportPng() {
        val snap = board.snapshot(ui.look)
        scope.launch {
            val ok = withContext(Dispatchers.IO) { savePng(ctx, snap) }
            Toast.makeText(ctx, if (ok) savedMsg else failMsg, Toast.LENGTH_SHORT).show()
        }
    }

    fun share(snap: Bitmap) {
        scope.launch {
            val uri = withContext(Dispatchers.IO) {
                try {
                    val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
                    val f = File(dir, "mandala.png")
                    f.outputStream().use { snap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                } catch (e: Exception) { null }
            }
            if (uri == null) { Toast.makeText(ctx, failMsg, Toast.LENGTH_SHORT).show(); return@launch }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(send, null))
        }
    }

    // autoguardat en sortir de l'app
    val activity = ctx as ComponentActivity
    DisposableEffect(activity) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) saveCurrent() }
        activity.lifecycle.addObserver(obs)
        onDispose { activity.lifecycle.removeObserver(obs) }
    }

    // en arrencar, reprèn l'últim disseny
    LaunchedEffect(Unit) {
        ui.fingerDraws = !hasStylus()
        val last = store.list().firstOrNull()
        if (last != null) openDesign(last.id) else generate(false)
    }

    BackHandler(ui.gallery) { ui.gallery = false }

    val cfg = LocalConfiguration.current
    val wide = cfg.screenWidthDp >= 640

    Box(Modifier.fillMaxSize().background(Color(ui.look.bg))) {
        if (wide) {
            TabletLayout(
                ui, board,
                Actions(
                    onNew = { generate() }, onAuto = ::autoPaint,
                    onDesigns = { saveCurrent(); ui.gallery = true },
                    onShare = { shareSnap = board.snapshot(ui.look) },
                    onLook = { l -> ui.look = l; board.lineColor = l.line; board.relineArt() },
                ),
                onUserStart = { playJob?.cancel() },
            )
        } else {
            DrawingSurface(board, ui) { playJob?.cancel() }
            TopBar(
                ui, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),
                onNew = { generate() }, onAuto = ::autoPaint,
                onDesigns = { saveCurrent(); ui.panel = false; ui.gallery = true },
                onShare = { shareSnap = board.snapshot(ui.look) },
                onLook = { l -> ui.look = l; board.lineColor = l.line; board.relineArt() },
            )
            BottomBar(ui, board, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp))
        }
        if (ui.gallery) {
            DesignsPanel(
                ui, store,
                onOpen = ::openDesign,
                onDelete = { id -> pendingDelete = id },
                onSaveNow = {
                    val ok = saveCurrent()
                    if (!ok) Toast.makeText(ctx, failMsg, Toast.LENGTH_SHORT).show()
                    ok
                },
                onExport = ::exportPng,
            )
        }
        pendingDelete?.let { id ->
            ConfirmDeleteDialog(
                ui, store, id,
                onConfirm = {
                    store.delete(id); ui.rev++
                    if (id == ui.designId) generate(false)
                    pendingDelete = null
                },
                onDismiss = { pendingDelete = null },
            )
        }
        shareSnap?.let { snap ->
            SharePreviewDialog(ui, snap, onShare = { shareSnap = null; share(snap) }, onDismiss = { shareSnap = null })
        }
    }
}

@Composable
fun DrawingSurface(board: Board, ui: UiState, onUserStart: () -> Unit) {
    val density = LocalDensity.current.density
    val pagePaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    val bmpPaint = remember { android.graphics.Paint().apply { isFilterBitmap = true } }

    // quan la càmera s'atura, redibuixa les línies en vectorial a la resolució exacta de la pantalla
    val lineColor = ui.look.line
    LaunchedEffect(ui.scale, ui.ox, ui.oy, board.artRev, board.artBusy, lineColor) {
        if (board.artBusy || ui.viewW == 0 || ui.viewH == 0) return@LaunchedEffect
        delay(120)
        val strokes = board.artSnapshot()
        if (strokes.isEmpty()) { ui.crisp = null; return@LaunchedEffect }
        val s = ui.scale; val ox = ui.ox; val oy = ui.oy; val rev = board.artRev
        val w = ui.viewW; val h = ui.viewH
        val bmp = withContext(Dispatchers.Default) { board.renderArt(strokes, w, h, s, ox, oy, lineColor) }
        ui.crisp = Crisp(bmp, s, ox, oy, rev, lineColor)
    }

    Canvas(
        Modifier.fillMaxSize()
            .onSizeChanged { ui.viewW = it.width; ui.viewH = it.height; ui.fit() }
            .pointerInput(board) {
                val slop = 10f * density
                awaitPointerEventScope {
                    var activeId: PointerId? = null
                    var cur: Stroke? = null
                    var last: P? = null
                    var sx = 0f; var sy = 0f; var sp = 0.5f
                    var stylus = false
                    var gesturing = false
                    var fillId: PointerId? = null
                    var fillStart = Offset.Zero
                    var fillMoved = false

                    fun widthFor(pr: Float): Float {
                        val base = ui.size * density / ui.scale
                        return when {
                            cur?.tool == Tool.ERASER -> base * 3.5f
                            stylus -> base * (0.25f + 1.5f * pr.coerceIn(0f, 1f).pow(1.3f))
                            else -> base
                        }
                    }

                    fun addPoint(pos: Offset, pr: Float, first: Boolean) {
                        val s = cur ?: return
                        val w = ui.toWorld(pos)
                        val x = w.x - Board.SIZE / 2f
                        val y = w.y - Board.SIZE / 2f
                        if (first) { sx = x; sy = y; sp = pr } else {
                            sx += (x - sx) * 0.6f; sy += (y - sy) * 0.6f; sp += (pr - sp) * 0.35f
                        }
                        val p = P(sx, sy, widthFor(sp))
                        last?.let { board.paintSeg(s, it, p) } ?: board.paintSeg(s, p, p)
                        s.pts.add(p); last = p
                    }

                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ui.busy) { ev.changes.forEach { it.consume() }; continue }

                        // --- pessigar / arrossegar amb dos dits: zoom i desplaçament
                        val touches = ev.changes.filter { it.type == PointerType.Touch && it.pressed }
                        if (touches.size >= 2 && !(cur != null && stylus)) {
                            if (cur != null) { // el traç amb dit en curs era el primer dit de la maniobra
                                cur = null; last = null; activeId = null; ui.drawing = false
                                board.rebuildPaint()
                            }
                            fillId = null
                            gesturing = true
                            val a = touches[0]; val b = touches[1]
                            val pc = (a.previousPosition + b.previousPosition) / 2f
                            val cc = (a.position + b.position) / 2f
                            val pd = (a.previousPosition - b.previousPosition).getDistance()
                            val cd = (a.position - b.position).getDistance()
                            val ns = (if (pd > 1f) ui.scale * cd / pd else ui.scale)
                                .coerceIn(ui.fitScale * 0.6f, ui.fitScale * 8f)
                            val wx = (pc.x - ui.ox) / ui.scale
                            val wy = (pc.y - ui.oy) / ui.scale
                            ui.scale = ns
                            ui.ox = cc.x - wx * ns
                            ui.oy = cc.y - wy * ns
                            ui.clampPan()
                            ui.panel = false
                            ev.changes.forEach { it.consume() }
                            continue
                        }
                        if (gesturing) {
                            if (touches.isEmpty()) gesturing = false
                            ev.changes.forEach { it.consume() }
                            continue
                        }

                        for (ch in ev.changes) {
                            val isStylus = ch.type == PointerType.Stylus || ch.type == PointerType.Eraser
                            // rebuig de palma: amb llapis disponible, un sol dit no dibuixa
                            if (ch.type == PointerType.Touch && !ui.fingerDraws) continue

                            if (!ch.pressed && !ch.previousPressed) {
                                ui.hover = if (isStylus && ev.type != PointerEventType.Exit) ch.position else null
                                continue
                            }
                            ui.hover = null

                            if (ch.pressed && !ch.previousPressed && activeId == null && fillId == null) {
                                onUserStart()
                                stylus = isStylus
                                ui.panel = false
                                val erase = ch.type == PointerType.Eraser || ui.tool == Tool.ERASER ||
                                    ev.buttons.isSecondaryPressed || ev.buttons.isTertiaryPressed
                                if (!erase && ui.tool == Tool.FILL) {
                                    fillId = ch.id; fillStart = ch.position; fillMoved = false
                                } else {
                                    val tool = if (erase) Tool.ERASER else if (ui.tool == Tool.FILL) Tool.PEN else ui.tool
                                    cur = Stroke(ui.ink, ui.symSectors, ui.symMirror, tool)
                                    last = null; activeId = ch.id; ui.drawing = true
                                    addPoint(ch.position, if (isStylus) ch.pressure else 0.7f, true)
                                }
                                ch.consume()
                            } else if (ch.id == fillId) {
                                if (ch.pressed) {
                                    if ((ch.position - fillStart).getDistance() > slop) fillMoved = true
                                } else {
                                    if (!fillMoved) {
                                        val w = ui.toWorld(fillStart)
                                        board.fillAt(w.x, w.y, ui.ink, ui.symSectors, ui.symMirror)
                                    }
                                    fillId = null
                                }
                                ch.consume()
                            } else if (ch.id == activeId) {
                                if (ch.pressed) {
                                    val n = ch.historical.size
                                    ch.historical.forEachIndexed { i, h ->
                                        val pr = if (stylus) sp + (ch.pressure - sp) * (i + 1) / (n + 1f) else 0.7f
                                        addPoint(h.position, pr, false)
                                    }
                                    addPoint(ch.position, if (stylus) ch.pressure else 0.7f, false)
                                } else {
                                    cur?.let { board.commit(Op(strokes = listOf(it))) }
                                    cur = null; last = null; activeId = null
                                    ui.drawing = false
                                }
                                ch.consume()
                                board.touch()
                            }
                        }
                    }
                }
            }
    ) {
        val look = ui.look
        drawRect(Color(look.bg))
        @Suppress("UNUSED_EXPRESSION") board.version
        val s = ui.scale
        drawIntoCanvas { cv ->
            val n = cv.nativeCanvas
            n.save()
            n.translate(ui.ox, ui.oy)
            n.scale(s, s)
            pagePaint.color = look.page
            n.drawRoundRect(0f, 0f, Board.SIZE.toFloat(), Board.SIZE.toFloat(), 24f, 24f, pagePaint)
            n.drawBitmap(board.paintBmp, 0f, 0f, bmpPaint)
            val cr = ui.crisp
            val crispOk = cr != null && cr.scale == s && cr.ox == ui.ox && cr.oy == ui.oy &&
                cr.rev == board.artRev && cr.color == look.line
            if (!crispOk) n.drawBitmap(board.art, 0f, 0f, bmpPaint)
            n.restore()
            if (crispOk) n.drawBitmap(cr!!.bmp, 0f, 0f, null)
        }

        val c = ui.toView(Offset(Board.SIZE / 2f, Board.SIZE / 2f))
        if (ui.guides) {
            val g = Color(look.fg).copy(alpha = 0.12f)
            val n = ui.symSectors
            val lines = if (ui.symMirror) n * 2 else n
            val stepA = (if (ui.symMirror) PI / n else 2 * PI / n).toFloat()
            val len = Board.SIZE * s
            for (i in 0 until lines) {
                val a = stepA * i
                drawLine(g, c, Offset(c.x + len * cos(a), c.y + len * sin(a)), strokeWidth = density)
            }
        }

        // fantasma de simetria sota la punta del llapis en flotar
        ui.hover?.let { h ->
            val dx = h.x - c.x; val dy = h.y - c.y
            val n = ui.symSectors
            val ghost = Color(ui.ink).copy(alpha = 0.6f)
            val rad = max(ui.size * density * 0.5f, 3f)
            for (i in 0 until n) for (m in 0 until (if (ui.symMirror) 2 else 1)) {
                val a = 2 * PI.toFloat() / n * i
                val yy = if (m == 1) -dy else dy
                drawCircle(ghost, rad, Offset(c.x + dx * cos(a) - yy * sin(a), c.y + dx * sin(a) + yy * cos(a)))
            }
        }
    }
}

@Composable
fun TopBar(
    ui: UiState, modifier: Modifier,
    onNew: () -> Unit, onAuto: () -> Unit, onDesigns: () -> Unit, onShare: () -> Unit, onLook: (Look) -> Unit,
) {
    val look = ui.look
    val fg = Color(look.fg)
    val accent = Color(ui.ink)
    val fade by animateFloatAsState(if (ui.drawing) 0.15f else 1f, label = "fade")
    Column(modifier.alpha(fade).widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().shadow(10.dp, RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp)).background(Color(look.panel))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolBtn("✺", stringResource(R.string.new_page), false, fg, accent, Modifier.weight(1f), onClick = onNew)
            ToolBtn("❀", stringResource(R.string.auto_paint), false, fg, accent, Modifier.weight(1f), onClick = onAuto)
            ToolBtn("▦", stringResource(R.string.designs), false, fg, accent, Modifier.weight(1f), onClick = onDesigns)
            ToolBtn("⇪", stringResource(R.string.share), false, fg, accent, Modifier.weight(1f), onClick = onShare)
            ToolBtn("⚙", stringResource(R.string.settings), ui.panel, fg, accent, Modifier.weight(1f)) { ui.panel = !ui.panel }
        }
        AnimatedVisibility(ui.panel, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            SettingsPanel(ui, onLook)
        }
    }
}

@Composable
fun BottomBar(ui: UiState, board: Board, modifier: Modifier) {
    val look = ui.look
    val fg = Color(look.fg)
    val accent = Color(ui.ink)
    val fade by animateFloatAsState(if (ui.drawing) 0.15f else 1f, label = "fade")
    Column(
        modifier.alpha(fade).widthIn(max = 640.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LazyRow(
            Modifier.fillMaxWidth().shadow(10.dp, RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp)).background(Color(look.panel)),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(Looks.palette) { c ->
                val sel = c == ui.ink
                Box(
                    Modifier.size(if (sel) 40.dp else 32.dp).clip(CircleShape).background(Color(c))
                        .border(if (sel) 3.dp else 1.dp, fg.copy(alpha = if (sel) 0.9f else 0.25f), CircleShape)
                        .clickable { ui.ink = c }
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(32.dp))
                .clip(RoundedCornerShape(32.dp)).background(Color(look.panel))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolBtn("🪣", stringResource(R.string.tool_fill), ui.tool == Tool.FILL, fg, accent, Modifier.weight(1f)) { ui.tool = Tool.FILL }
            ToolBtn("✎", stringResource(R.string.tool_brush), ui.tool == Tool.PEN, fg, accent, Modifier.weight(1f)) { ui.tool = Tool.PEN }
            ToolBtn("✦", stringResource(R.string.tool_neon), ui.tool == Tool.NEON, fg, accent, Modifier.weight(1f)) { ui.tool = Tool.NEON }
            ToolBtn("◌", stringResource(R.string.tool_erase), ui.tool == Tool.ERASER, fg, accent, Modifier.weight(1f)) { ui.tool = Tool.ERASER }
            ToolBtn("❋", stringResource(R.string.sym_short), ui.symmetric, fg, accent, Modifier.weight(1f)) { ui.symmetric = !ui.symmetric }
            ToolBtn("↶", stringResource(R.string.undo), false, fg, accent, Modifier.weight(1f), board.undoCount > 0) { board.undo() }
            ToolBtn("↷", stringResource(R.string.redo), false, fg, accent, Modifier.weight(1f), board.redoCount > 0) { board.redo() }
        }
    }
}

@Composable
fun ToolBtn(
    glyph: String, label: String, selected: Boolean, fg: Color, accent: Color,
    modifier: Modifier = Modifier, enabled: Boolean = true, big: Boolean = false, onClick: () -> Unit,
) {
    val bg = if (selected) accent.copy(alpha = 0.25f) else Color.Transparent
    val tint = if (selected) fg else fg.copy(alpha = 0.85f)
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 6.dp)
            .alpha(if (enabled) 1f else 0.3f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(glyph, color = tint, fontSize = if (big) 28.sp else 22.sp, fontWeight = FontWeight.Medium)
        Text(label, color = tint, fontSize = if (big) 12.sp else 10.sp, maxLines = 1)
    }
}

@Composable
fun SettingsPanel(ui: UiState, onLook: (Look) -> Unit) {
    val look = ui.look
    val fg = Color(look.fg)
    val accent = Color(ui.ink)
    Column(
        Modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp)).background(Color(look.panel))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = fg.copy(alpha = 0.18f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.symmetry, ui.sectors), color = fg, fontSize = 13.sp, modifier = Modifier.width(110.dp))
            Slider(ui.sectors.toFloat(), { ui.sectors = it.toInt() }, valueRange = 2f..24f, steps = 21, colors = colors, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.brush_size, "%.0f".format(ui.size)), color = fg, fontSize = 13.sp, modifier = Modifier.width(110.dp))
            Slider(ui.size, { ui.size = it }, valueRange = 1f..30f, colors = colors, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(stringResource(R.string.sym_paint), ui.symmetric, fg, accent) { ui.symmetric = !ui.symmetric }
            Pill(stringResource(R.string.mirror), ui.mirror, fg, accent) { ui.mirror = !ui.mirror }
            Pill(stringResource(R.string.guides), ui.guides, fg, accent) { ui.guides = !ui.guides }
            Pill(stringResource(R.string.finger_draws), ui.fingerDraws, fg, accent) { ui.fingerDraws = !ui.fingerDraws }
        }
        Spacer(Modifier.height(2.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            items(Looks.all) { l -> Pill(stringResource(l.nameRes), l === look, fg, accent) { onLook(l) } }
            item { Pill(stringResource(R.string.reset_view), false, fg, accent) { ui.fit() } }
        }
    }
}

@Composable
fun Pill(text: String, on: Boolean, fg: Color, accent: Color, onClick: () -> Unit) {
    Text(
        text, color = if (on) Color(0xFF101018) else fg, fontSize = 13.sp, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (on) accent else fg.copy(alpha = 0.10f))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp)
    )
}

/** El dispositiu declara una font de llapis (S Pen, T-Pen de la NXTPAPER...). */
fun hasStylus(): Boolean = InputDevice.getDeviceIds().any { id ->
    InputDevice.getDevice(id)?.supportsSource(InputDevice.SOURCE_STYLUS) == true
}

fun savePng(ctx: Context, bmp: Bitmap): Boolean = try {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "mandala_${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MandalaPen")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
    ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    true
} catch (e: Exception) { false }
