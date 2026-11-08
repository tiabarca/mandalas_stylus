package cat.mandala.pen

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.text.format.DateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Pantalla "Els meus dissenys": quadrícula de miniatures per obrir o esborrar. */
@Composable
fun DesignsPanel(
    ui: UiState, store: Designs,
    onOpen: (String) -> Unit, onDelete: (String) -> Unit, onSaveNow: () -> Boolean, onExport: () -> Unit,
) {
    val look = ui.look
    val fg = Color(look.fg)
    val accent = Color(ui.ink)
    val cfg = LocalConfiguration.current
    val wide = cfg.screenWidthDp >= 640
    val items = remember(ui.rev, ui.gallery) { store.list() }
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(flash) { if (flash) { delay(1600); flash = false } }
    val justNow = stringResource(R.string.just_now)

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable { ui.gallery = false },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.statusBarsPadding().navigationBarsPadding().padding(16.dp)
                .widthIn(max = if (wide) 960.dp else 640.dp).fillMaxWidth().fillMaxHeight(0.86f)
                .shadow(16.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp))
                .background(Color(look.panel)).clickable(enabled = false) {}
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.my_designs), color = fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Pill("✕", false, fg, accent) { ui.gallery = false }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(stringResource(if (flash) R.string.saved_check else R.string.save_now), true, fg, accent) {
                    if (onSaveNow()) flash = true
                    ui.rev++
                }
                Pill(stringResource(R.string.export_png), false, fg, accent) { onExport() }
            }
            if (items.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_designs), color = fg.copy(alpha = 0.6f), fontSize = 14.sp)
                }
            } else {
                LazyVerticalGrid(
                    GridCells.Adaptive(if (wide) 190.dp else 130.dp), Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items, key = { it.id }) { d ->
                        val thumb by produceState<ImageBitmap?>(null, d.id, d.modified) {
                            value = withContext(Dispatchers.IO) {
                                BitmapFactory.decodeFile(store.thumbFile(d.id).path)?.asImageBitmap()
                            }
                        }
                        val current = d.id == ui.designId
                        Box(
                            Modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp))
                                .background(fg.copy(alpha = 0.08f))
                                .border(if (current) 3.dp else 0.dp, accent, RoundedCornerShape(16.dp))
                                .clickable { onOpen(d.id) }
                        ) {
                            thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            val ago = System.currentTimeMillis() - d.modified
                            Text(
                                if (ago < 60_000) justNow
                                else DateUtils.getRelativeTimeSpanString(d.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                                color = Color.White, fontSize = 11.sp, maxLines = 1,
                                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.5f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                            Text(
                                "✕", color = Color.White, fontSize = 14.sp,
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))
                                    .clickable { onDelete(d.id) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
