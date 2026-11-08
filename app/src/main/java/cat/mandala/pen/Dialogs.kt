package cat.mandala.pen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Demana confirmació abans d'eliminar un disseny, ensenyant-ne la miniatura. */
@Composable
fun ConfirmDeleteDialog(ui: UiState, store: Designs, id: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val look = ui.look
    val fg = Color(look.fg)
    val thumb by produceState<ImageBitmap?>(null, id) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(store.thumbFile(id).path)?.asImageBitmap() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(look.panel),
        shape = RoundedCornerShape(28.dp),
        title = { Text(stringResource(R.string.delete_title), color = fg, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                thumb?.let {
                    Image(it, null, Modifier.size(160.dp).clip(RoundedCornerShape(16.dp)))
                }
                Text(stringResource(R.string.delete_msg), color = fg.copy(alpha = 0.8f), fontSize = 14.sp)
            }
        },
        confirmButton = {
            TextButton(onConfirm) { Text(stringResource(R.string.delete), color = Color(0xFFD64545), fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onDismiss) { Text(stringResource(R.string.cancel), color = fg) }
        },
    )
}

/** Vista prèvia exacta de la imatge que es compartirà. */
@Composable
fun SharePreviewDialog(ui: UiState, snap: Bitmap, onShare: () -> Unit, onDismiss: () -> Unit) {
    val look = ui.look
    val fg = Color(look.fg)
    val preview = remember(snap) { Bitmap.createScaledBitmap(snap, 800, 800, true).asImageBitmap() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(look.panel),
        shape = RoundedCornerShape(28.dp),
        title = { Text(stringResource(R.string.share_title), color = fg, fontWeight = FontWeight.SemiBold) },
        text = { Image(preview, null, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp))) },
        confirmButton = {
            TextButton(onShare) { Text(stringResource(R.string.share), color = Color(ui.ink), fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onDismiss) { Text(stringResource(R.string.cancel), color = fg) }
        },
    )
}
