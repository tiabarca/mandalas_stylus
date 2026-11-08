package cat.mandala.pen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Menús desplegables de la tauleta. */
enum class Menu { NONE, COLOR, SIZE, OPTIONS }

private val RAIL_W = 84.dp
private val GAP = 10.dp

private fun Modifier.card(look: Look) =
    shadow(8.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(Color(look.panel))

class Actions(
    val onNew: () -> Unit, val onAuto: () -> Unit, val onDesigns: () -> Unit, val onShare: () -> Unit,
    val onLook: (Look) -> Unit,
)

/**
 * Diseño de tauleta (sempre horitzontal): el llenç ocupa tot el centre.
 * Esquerra: eines + Color i Gruix (despleguen una bombolla). Dreta: una sola barra amb les accions
 * i "Opcions" (bombolla amb simetria, mirall, guies, zoom i ambients).
 */
@Composable
fun TabletLayout(ui: UiState, board: Board, actions: Actions, onUserStart: () -> Unit) {
    val look = ui.look
    val cfg = LocalConfiguration.current
    Box(Modifier.fillMaxSize().background(lerp(Color(look.bg), Color.Black, 0.10f))) {
        Row(
            Modifier.fillMaxSize().safeDrawingPadding().padding(GAP),
            horizontalArrangement = Arrangement.spacedBy(GAP),
        ) {
            LeftRail(ui, board)
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp))) {
                DrawingSurface(board, ui) { ui.menu = Menu.NONE; onUserStart() }
                // toc al llenç amb una bombolla oberta = només la tanca (els raíls segueixen actius)
                if (ui.menu != Menu.NONE) {
                    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { ui.menu = Menu.NONE } })
                }
            }
            RightRail(ui, actions)
        }

        if (ui.menu != Menu.NONE) {
            val sideInset = GAP + RAIL_W + 8.dp
            when (ui.menu) {
                Menu.COLOR -> Flyout(ui, Menu.COLOR, left = true, inset = sideInset, estH = 250.dp, screenH = cfg.screenHeightDp) {
                    PaletteGrid(ui, perRow = 4, swatch = 52.dp)
                }
                Menu.SIZE -> Flyout(ui, Menu.SIZE, left = true, inset = sideInset, estH = 170.dp, screenH = cfg.screenHeightDp) {
                    SizeSlider(ui)
                }
                Menu.OPTIONS -> Flyout(ui, Menu.OPTIONS, left = false, inset = sideInset, estH = 480.dp, screenH = cfg.screenHeightDp, fromTop = true) {
                    OptionsContent(ui, actions)
                }
                Menu.NONE -> {}
            }
        }
    }
}

/** Bombolla ancorada al botó que l'ha obert (posició vertical) i enganxada al costat del raíl. */
@Composable
private fun Flyout(
    ui: UiState, anchor: Menu, left: Boolean, inset: Dp, estH: Dp, screenH: Int,
    fromTop: Boolean = false, content: @Composable ColumnScope.() -> Unit,
) {
    val d = LocalDensity.current
    val top = if (fromTop) GAP else (with(d) { (ui.anchors[anchor] ?: 0f).toDp() } - 12.dp)
        .coerceIn(GAP, (screenH.dp - estH - GAP).coerceAtLeast(GAP))
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(if (left) Alignment.TopStart else Alignment.TopEnd)
                .padding(top = top, start = if (left) inset else 0.dp, end = if (left) 0.dp else inset)
                .widthIn(max = 320.dp)
                .heightIn(max = screenH.dp - top - GAP)
                .card(ui.look)
                .pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

// ---------------------------------------------------------------- raíls

@Composable
private fun LeftRail(ui: UiState, board: Board) {
    val look = ui.look
    val fg = Color(look.fg)
    val accent = Color(ui.ink)
    val m = Modifier.fillMaxWidth()
    Column(
        Modifier.width(RAIL_W).fillMaxHeight().card(look).verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ToolBtn("🪣", stringResource(R.string.tool_fill), ui.tool == Tool.FILL, fg, accent, m, big = true) { ui.tool = Tool.FILL }
        ToolBtn("✎", stringResource(R.string.tool_brush), ui.tool == Tool.PEN, fg, accent, m, big = true) { ui.tool = Tool.PEN }
        ToolBtn("✦", stringResource(R.string.tool_neon), ui.tool == Tool.NEON, fg, accent, m, big = true) { ui.tool = Tool.NEON }
        ToolBtn("◌", stringResource(R.string.tool_erase), ui.tool == Tool.ERASER, fg, accent, m, big = true) { ui.tool = Tool.ERASER }
        ToolBtn("❋", stringResource(R.string.sym_short), ui.symmetric, fg, accent, m, big = true) { ui.symmetric = !ui.symmetric }
        Divider(ui)
        // Color: mostra el color actual i desplega la paleta
        MenuBtn(ui, Menu.COLOR, stringResource(R.string.color)) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(accent).border(2.dp, fg.copy(alpha = 0.6f), CircleShape))
        }
        // Gruix: mostra un punt amb la mida del pinzell
        MenuBtn(ui, Menu.SIZE, stringResource(R.string.thickness)) {
            val dot = (ui.size * 1.1f + 6f).coerceIn(6f, 34f).dp
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(dot).clip(CircleShape).background(fg))
            }
        }
        Divider(ui)
        ToolBtn("↶", stringResource(R.string.undo), false, fg, accent, m, board.undoCount > 0, big = true) { board.undo() }
        ToolBtn("↷", stringResource(R.string.redo), false, fg, accent, m, board.redoCount > 0, big = true) { board.redo() }
    }
}

@Composable
private fun RightRail(ui: UiState, a: Actions) {
    val look = ui.look
    val fg = Color(look.fg)
    val accent = Color(ui.ink)
    val m = Modifier.fillMaxWidth()
    Column(
        Modifier.width(RAIL_W).fillMaxHeight().card(look).verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ActionBtn("✺", stringResource(R.string.new_page), fg, accent, true, m) { ui.menu = Menu.NONE; a.onNew() }
        ToolBtn("❀", stringResource(R.string.auto_paint), false, fg, accent, m, big = true) { ui.menu = Menu.NONE; a.onAuto() }
        ToolBtn("▦", stringResource(R.string.designs), false, fg, accent, m, big = true) { ui.menu = Menu.NONE; a.onDesigns() }
        ToolBtn("⇪", stringResource(R.string.share), false, fg, accent, m, big = true) { ui.menu = Menu.NONE; a.onShare() }
        Divider(ui)
        MenuBtn(ui, Menu.OPTIONS, stringResource(R.string.options)) {
            Text("⚙", color = fg, fontSize = 28.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Botó del raíl que obre/tanca una bombolla i en recorda la posició vertical. */
@Composable
private fun MenuBtn(ui: UiState, menu: Menu, label: String, icon: @Composable () -> Unit) {
    val fg = Color(ui.look.fg)
    val selected = ui.menu == menu
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(if (selected) Color(ui.ink).copy(alpha = 0.25f) else Color.Transparent)
            .onGloballyPositioned { ui.anchors[menu] = it.positionInRoot().y }
            .clickable { ui.menu = if (selected) Menu.NONE else menu }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Text(label, color = fg.copy(alpha = 0.9f), fontSize = 12.sp, maxLines = 1)
    }
}

// ---------------------------------------------------------------- contingut de les bombolles

@Composable
private fun PaletteGrid(ui: UiState, perRow: Int, swatch: Dp) {
    val fg = Color(ui.look.fg)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Looks.palette.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { c ->
                    val sel = c == ui.ink
                    Box(
                        Modifier.size(swatch).clip(CircleShape).background(Color(c))
                            .border(if (sel) 4.dp else 1.dp, fg.copy(alpha = if (sel) 0.9f else 0.25f), CircleShape)
                            .clickable { ui.ink = c; ui.menu = Menu.NONE }
                    )
                }
            }
        }
    }
}

@Composable
private fun sliderColors(ui: UiState): SliderColors {
    val fg = Color(ui.look.fg)
    val accent = Color(ui.ink)
    return SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = fg.copy(alpha = 0.18f))
}

@Composable
private fun SizeSlider(ui: UiState) {
    val fg = Color(ui.look.fg)
    Text(stringResource(R.string.brush_size, "%.0f".format(ui.size)), color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    Slider(ui.size, { ui.size = it }, valueRange = 1f..30f, colors = sliderColors(ui), modifier = Modifier.width(260.dp))
}

@Composable
private fun OptionsContent(ui: UiState, a: Actions) {
    val fg = Color(ui.look.fg)
    val accent = Color(ui.ink)
    Column(Modifier.width(264.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text(stringResource(R.string.symmetry, ui.sectors), color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Slider(ui.sectors.toFloat(), { ui.sectors = it.toInt() }, valueRange = 2f..24f, steps = 21, colors = sliderColors(ui))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(stringResource(R.string.mirror), ui.mirror, fg, accent) { ui.mirror = !ui.mirror }
            Pill(stringResource(R.string.guides), ui.guides, fg, accent) { ui.guides = !ui.guides }
        }
        Pill(stringResource(R.string.finger_draws), ui.fingerDraws, fg, accent) { ui.fingerDraws = !ui.fingerDraws }
        Divider(ui)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.zoom), color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Pill("−", false, fg, accent) { ui.zoomBy(1f / 1.5f) }
            Pill("＋", false, fg, accent) { ui.zoomBy(1.5f) }
            Pill(stringResource(R.string.reset_view), false, fg, accent) { ui.fit() }
        }
        Divider(ui)
        Looks.all.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { l -> Pill(stringResource(l.nameRes), l === ui.look, fg, accent) { a.onLook(l) } }
            }
        }
    }
}

@Composable
private fun Divider(ui: UiState) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(ui.look.fg).copy(alpha = 0.12f)))
}

@Composable
private fun ActionBtn(
    glyph: String, label: String, fg: Color, accent: Color, primary: Boolean,
    modifier: Modifier, onClick: () -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(22.dp))
            .background(if (primary) accent else fg.copy(alpha = 0.08f))
            .clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val tint = if (primary) Color(0xFF101018) else fg
        Text(glyph, color = tint, fontSize = 28.sp)
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
