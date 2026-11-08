package cat.mandala.pen

import android.content.Context
import android.graphics.Bitmap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

class DesignInfo(val id: String, val modified: Long)

/** Els dissenys desats viuen a filesDir/designs: `<id>.mnd` (dades editables) i `<id>.png` (miniatura). */
class Designs(ctx: Context) {
    private val dir = File(ctx.filesDir, "designs").apply { mkdirs() }

    fun list(): List<DesignInfo> =
        dir.listFiles { f -> f.extension == "mnd" }
            ?.map { DesignInfo(it.nameWithoutExtension, it.lastModified()) }
            ?.sortedByDescending { it.modified } ?: emptyList()

    fun thumbFile(id: String) = File(dir, "$id.png")

    fun save(id: String, board: Board, meta: Board.Meta, look: Look) {
        val tmp = File(dir, "$id.tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream())).use { board.write(it, meta) }
        val dst = File(dir, "$id.mnd")
        dst.delete(); tmp.renameTo(dst)
        val thumb = board.thumbnail(look, 384)
        thumbFile(id).outputStream().use { thumb.compress(Bitmap.CompressFormat.PNG, 90, it) }
        thumb.recycle()
    }

    /** Carrega les dades a [board] (sense dibuixar). Retorna null si el fitxer no es pot llegir. */
    fun load(id: String, board: Board): Board.Meta? = try {
        DataInputStream(BufferedInputStream(File(dir, "$id.mnd").inputStream())).use { board.read(it) }
    } catch (e: Exception) { null }

    fun delete(id: String) {
        File(dir, "$id.mnd").delete(); thumbFile(id).delete()
    }
}
