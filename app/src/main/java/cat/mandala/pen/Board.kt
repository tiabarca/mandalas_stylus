package cat.mandala.pen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import java.io.DataInputStream
import java.io.DataOutputStream
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** Punt d'un traç, en coordenades de món relatives al centre de la pàgina. [w] és l'amplada en px de món. */
class P(val x: Float, val y: Float, val w: Float)

enum class Tool { PEN, NEON, ERASER, FILL }

class Stroke(val color: Int, val sectors: Int, val mirror: Boolean, val tool: Tool) {
    val pts = ArrayList<P>()
}

/** Reompliment: píxels llavor (índex y*SIZE+x) i color. */
class Fill(val seeds: IntArray, val color: Int)

/** Unitat desfeable: traços de pinzell o reompliments. */
class Op(val strokes: List<Stroke> = emptyList(), val fills: List<Fill> = emptyList())

class IntBuf(cap: Int = 1024) {
    var a = IntArray(cap); var n = 0
    fun add(v: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
    fun pop(): Int = a[--n]
}

/**
 * Pàgina de pintar. Dues capes de mida fixa en coordenades de "món" (SIZE x SIZE):
 *  - [art]: les línies del mandala (es dibuixen a sobre)
 *  - [paintBmp]: el color de l'usuari (a sota)
 * El zoom/desplaçament és només una transformació de vista aplicada en dibuixar.
 */
class Board {
    companion object { const val SIZE = 2048; private const val MAGIC = 0x4D4E4431 }

    val art: Bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    val paintBmp: Bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    private val artCv = Canvas(art)
    private val paintCv = Canvas(paintBmp)

    /** 1 = línia (barrera), 0 = lliure, 2 = visitat dins d'un reompliment en curs. */
    private val bar = ByteArray(SIZE * SIZE)
    private var barDirty = true

    private val artStrokes = ArrayList<Stroke>()
    var lineColor: Int = 0xFF2B2D42.toInt()

    private val ops = ArrayList<Op>()
    private val redoStack = ArrayList<Op>()

    var version by mutableIntStateOf(0); private set
    var undoCount by mutableIntStateOf(0); private set
    var redoCount by mutableIntStateOf(0); private set

    /** Canvia quan canvien les línies (per refer el dibuix vectorial) i mentre s'estan animant. */
    var artRev by mutableIntStateOf(0); private set
    var artBusy by mutableStateOf(false); private set

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = false }
    private val clear = PorterDuffXfermode(PorterDuff.Mode.CLEAR)

    fun touch() { version++ }
    private fun syncCounts() { undoCount = ops.size; redoCount = redoStack.size }

    // ---------------------------------------------------------------- pàgina i línies

    fun resetPage() {
        artStrokes.clear(); art.eraseColor(0); paintBmp.eraseColor(0)
        ops.clear(); redoStack.clear(); syncCounts(); barDirty = true; artRev++; touch()
    }

    /** Torna a dibuixar les línies (p. ex. en canviar el color de línia per l'ambient). */
    fun relineArt() {
        art.eraseColor(0)
        artStrokes.forEach { drawSlice(artCv, it, 0, 1, lineColor) }
        artRev++; touch()
    }

    /** Afegeix un anell de línies i l'anima en [frames] fotogrames. */
    suspend fun playArt(ring: List<Stroke>, frames: Int = 12) {
        artStrokes.addAll(ring); barDirty = true; artBusy = true; artRev++
        var f = 0
        try {
            while (f < frames) {
                withFrameNanos { }
                ring.forEach { drawSlice(artCv, it, f, frames, lineColor) }
                f++; touch()
            }
        } finally {
            while (f < frames) { ring.forEach { drawSlice(artCv, it, f, frames, lineColor) }; f++ }
            artBusy = false
            touch()
        }
    }

    fun artSnapshot(): List<Stroke> = ArrayList(artStrokes)

    /**
     * Dibuixa les línies en vectorial a la resolució exacta de la vista (zoom [scale], desplaçament [ox],[oy]).
     * Es pot cridar des d'un altre fil amb una còpia de [strokes] (artSnapshot).
     */
    fun renderArt(strokes: List<Stroke>, w: Int, h: Int, scale: Float, ox: Float, oy: Float, color: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.translate(ox, oy); c.scale(scale, scale)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; this.color = color
        }
        val m = Matrix()
        val cx = SIZE / 2f
        for (s in strokes) {
            if (s.pts.size < 2) continue
            val base = Path().apply {
                moveTo(s.pts[0].x, s.pts[0].y)
                for (i in 1 until s.pts.size) lineTo(s.pts[i].x, s.pts[i].y)
            }
            val all = Path()
            for (i in 0 until s.sectors) for (mm in 0 until (if (s.mirror) 2 else 1)) {
                m.reset()
                if (mm == 1) m.postScale(1f, -1f)
                m.postRotate(360f / s.sectors * i)
                m.postTranslate(cx, cx)
                all.addPath(base, m)
            }
            p.strokeWidth = s.pts[0].w
            c.drawPath(all, p)
        }
        return out
    }

    // ---------------------------------------------------------------- pinzell

    fun paintSeg(s: Stroke, a: P, b: P) = drawSeg(paintCv, s, a, b, null)

    private fun drawSlice(c: Canvas, s: Stroke, f: Int, k: Int, override: Int?) {
        val n = s.pts.size
        if (n == 1) { drawSeg(c, s, s.pts[0], s.pts[0], override); return }
        val from = f * (n - 1) / k
        val to = (f + 1) * (n - 1) / k
        for (i in from until to) drawSeg(c, s, s.pts[i], s.pts[i + 1], override)
    }

    private fun drawSeg(c: Canvas, s: Stroke, a: P, b: P, override: Int?) {
        val cx = SIZE / 2f
        val copies = if (s.mirror) 2 else 1
        for (i in 0 until s.sectors) for (m in 0 until copies) {
            c.save()
            c.translate(cx, cx)
            c.rotate(360f / s.sectors * i)
            if (m == 1) c.scale(1f, -1f)
            line(c, s, a, b, override ?: s.color)
            c.restore()
        }
    }

    private fun line(c: Canvas, s: Stroke, a: P, b: P, color: Int) {
        val w = (a.w + b.w) / 2f
        paint.xfermode = null
        paint.strokeCap = Paint.Cap.ROUND
        when (s.tool) {
            Tool.PEN, Tool.FILL -> {
                paint.color = color; paint.strokeWidth = w
                c.drawLine(a.x, a.y, b.x, b.y, paint)
            }
            Tool.NEON -> {
                paint.strokeCap = Paint.Cap.BUTT
                paint.color = withAlpha(color, 40); paint.strokeWidth = w * 3.4f
                c.drawLine(a.x, a.y, b.x, b.y, paint)
                paint.color = withAlpha(color, 90); paint.strokeWidth = w * 1.9f
                c.drawLine(a.x, a.y, b.x, b.y, paint)
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = mixWhite(color, 0.55f); paint.strokeWidth = w * 0.8f
                c.drawLine(a.x, a.y, b.x, b.y, paint)
            }
            Tool.ERASER -> {
                paint.xfermode = clear; paint.color = 0; paint.strokeWidth = w
                c.drawLine(a.x, a.y, b.x, b.y, paint)
                paint.xfermode = null
            }
        }
    }

    // ---------------------------------------------------------------- historial

    fun commit(op: Op) { ops.add(op); redoStack.clear(); syncCounts() }

    /** Redibuixa el color des de zero (desfer o descartar un traç en curs). */
    fun rebuildPaint() {
        paintBmp.eraseColor(0)
        ops.forEach { replay(it) }
        touch()
    }

    private fun replay(op: Op) {
        op.strokes.forEach { drawSlice(paintCv, it, 0, 1, null) }
        op.fills.forEach { applyFill(it) }
    }

    fun undo() {
        if (ops.isEmpty()) return
        redoStack.add(ops.removeAt(ops.lastIndex)); syncCounts(); rebuildPaint()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val op = redoStack.removeAt(redoStack.lastIndex)
        ops.add(op); syncCounts(); replay(op); touch()
    }

    fun clearPaint() { ops.clear(); redoStack.clear(); syncCounts(); rebuildPaint() }

    // ---------------------------------------------------------------- reompliment

    private fun ensureBarrier() {
        if (!barDirty) return
        val row = IntArray(SIZE)
        for (y in 0 until SIZE) {
            art.getPixels(row, 0, SIZE, 0, y, SIZE, 1)
            val o = y * SIZE
            for (x in 0 until SIZE) bar[o + x] = if ((row[x] ushr 24) >= 200) 1 else 0
        }
        barDirty = false
    }

    /** Inunda des de [sx],[sy] (marca amb 2 i apila runs y,x0,x1 a [runs]). */
    private fun flood(sx: Int, sy: Int, runs: IntBuf) {
        val st = IntBuf(); st.add(sy * SIZE + sx)
        val free: Byte = 0
        while (st.n > 0) {
            val idx = st.pop()
            if (bar[idx] != free) continue
            val y = idx / SIZE; val x = idx - y * SIZE; val o = y * SIZE
            var lx = x; while (lx > 0 && bar[o + lx - 1] == free) lx--
            var rx = x; while (rx < SIZE - 1 && bar[o + rx + 1] == free) rx++
            for (i in lx..rx) bar[o + i] = 2
            runs.add(y); runs.add(lx); runs.add(rx)
            for (ny in intArrayOf(y - 1, y + 1)) {
                if (ny < 0 || ny >= SIZE) continue
                val no = ny * SIZE
                var inSeg = false
                for (i in lx..rx) {
                    val f = bar[no + i] == free
                    if (f && !inSeg) { st.add(no + i); inSeg = true } else if (!f) inSeg = false
                }
            }
        }
    }

    private fun unmark(runs: IntBuf) {
        var i = 0
        while (i < runs.n) {
            val o = runs.a[i] * SIZE
            for (x in runs.a[i + 1]..runs.a[i + 2]) bar[o + x] = 0
            i += 3
        }
    }

    private fun applyFill(f: Fill) {
        ensureBarrier()
        val runs = IntBuf(2048)
        for (idx in f.seeds) if (bar[idx] == 0.toByte()) flood(idx % SIZE, idx / SIZE, runs)
        fillPaint.color = f.color
        var i = 0
        while (i < runs.n) {
            val y = runs.a[i].toFloat()
            paintCv.drawRect(runs.a[i + 1] - 1f, y - 1f, runs.a[i + 2] + 2f, y + 2f, fillPaint)
            i += 3
        }
        unmark(runs)
    }

    private fun findSeed(x: Int, y: Int): Int? {
        fun ok(px: Int, py: Int) = px in 0 until SIZE && py in 0 until SIZE && bar[py * SIZE + px] == 0.toByte()
        if (ok(x, y)) return y * SIZE + x
        // tolerància: si toca la línia, busca prop
        for (d in intArrayOf(4, 8, 12)) for (k in 0 until 8) {
            val a = k * PI / 4
            val px = x + (d * cos(a)).toInt(); val py = y + (d * sin(a)).toInt()
            if (ok(px, py)) return py * SIZE + px
        }
        return null
    }

    /** Reomple la regió sota el punt de món [wx],[wy] i les seves còpies simètriques. */
    fun fillAt(wx: Float, wy: Float, color: Int, n: Int, mirror: Boolean): Boolean {
        ensureBarrier()
        val c = SIZE / 2f
        val dx = wx - c; val dy = wy - c
        val seeds = IntBuf(16)
        for (i in 0 until n) for (m in 0 until (if (mirror) 2 else 1)) {
            val a = (2 * PI / n * i).toFloat()
            val yy = if (m == 1) -dy else dy
            val rx = dx * cos(a) - yy * sin(a)
            val ry = dx * sin(a) + yy * cos(a)
            findSeed((c + rx).toInt(), (c + ry).toInt())?.let { seeds.add(it) }
        }
        if (seeds.n == 0) return false
        val f = Fill(seeds.a.copyOf(seeds.n), color)
        applyFill(f); commit(Op(fills = listOf(f))); touch()
        return true
    }

    // ---------------------------------------------------------------- autocolor

    /** Troba totes les regions tancades i els assigna color segons anell i sector. Pesat: fora del fil principal. */
    fun planAutoPaint(sectors: Int, colors: List<Int>, rnd: Random): List<Fill> {
        ensureBarrier()
        class Reg(val seed: Int, val dist: Float, val color: Int)
        val regs = ArrayList<Reg>()
        val runs = IntBuf(1 shl 14)
        val c = SIZE / 2f
        val maxArea = 0.3 * PI * c * c
        val off = rnd.nextInt(colors.size)
        val n = sectors.coerceAtLeast(1)
        var y = 5
        while (y < SIZE) {
            var x = 5
            while (x < SIZE) {
                if (bar[y * SIZE + x] == 0.toByte()) {
                    runs.n = 0
                    flood(x, y, runs)
                    var area = 0L; var sx = 0L; var sy = 0L; var rsum = 0.0; var border = false
                    var i = 0
                    while (i < runs.n) {
                        val ry = runs.a[i]; val lx = runs.a[i + 1]; val rx = runs.a[i + 2]
                        val len = rx - lx + 1
                        area += len; sx += (lx + rx).toLong() * len / 2; sy += ry.toLong() * len
                        rsum += hypot((lx + rx) / 2f - c, ry - c) * len
                        if (lx == 0 || rx == SIZE - 1 || ry == 0 || ry == SIZE - 1) border = true
                        i += 3
                    }
                    if (!border && area in 150..maxArea.toLong()) {
                        val cx = sx.toFloat() / area; val cy = sy.toFloat() / area
                        val dist = (rsum / area).toFloat()
                        val ang = (atan2(cy - c, cx - c) + 2 * PI) % (2 * PI)
                        val sector = (ang / (2 * PI / n)).toInt()
                        val bin = (dist / (c * 0.96f) * 26).toInt()
                        regs.add(Reg(y * SIZE + x, dist, colors[(off + bin + (sector % 2) * 3) % colors.size]))
                    }
                }
                x += 10
            }
            y += 10
        }
        for (i in bar.indices) if (bar[i] == 2.toByte()) bar[i] = 0
        regs.sortBy { it.dist }
        return regs.map { Fill(intArrayOf(it.seed), it.color) }
    }

    /** Aplica els reompliments en onada del centre cap a fora. */
    suspend fun playFills(fills: List<Fill>) {
        if (fills.isEmpty()) return
        commit(Op(fills = fills))
        val batch = (fills.size / 80).coerceAtLeast(1)
        var i = 0
        try {
            while (i < fills.size) {
                withFrameNanos { }
                val end = minOf(i + batch, fills.size)
                while (i < end) applyFill(fills[i++])
                touch()
            }
        } finally {
            while (i < fills.size) applyFill(fills[i++])
            touch()
        }
    }

    // ---------------------------------------------------------------- persistència

    class Meta(val look: Int, val sectors: Int, val mirror: Boolean)

    val hasArt: Boolean get() = artStrokes.isNotEmpty()

    fun write(o: DataOutputStream, meta: Meta) {
        o.writeInt(MAGIC); o.writeInt(1)
        o.writeInt(meta.look); o.writeInt(meta.sectors); o.writeBoolean(meta.mirror)
        o.writeInt(artStrokes.size); artStrokes.forEach { writeStroke(o, it) }
        o.writeInt(ops.size)
        for (op in ops) {
            o.writeInt(op.strokes.size); op.strokes.forEach { writeStroke(o, it) }
            o.writeInt(op.fills.size)
            for (f in op.fills) {
                o.writeInt(f.color); o.writeInt(f.seeds.size); f.seeds.forEach { o.writeInt(it) }
            }
        }
    }

    /** Carrega les dades (sense dibuixar). Després cal cridar [redrawAll]. */
    fun read(i: DataInputStream): Meta {
        require(i.readInt() == MAGIC) { "format desconegut" }
        i.readInt() // versió
        val meta = Meta(i.readInt(), i.readInt(), i.readBoolean())
        artStrokes.clear(); ops.clear(); redoStack.clear()
        repeat(i.readInt()) { artStrokes.add(readStroke(i)) }
        repeat(i.readInt()) {
            val strokes = List(i.readInt()) { readStroke(i) }
            val fills = List(i.readInt()) {
                val color = i.readInt()
                Fill(IntArray(i.readInt()) { i.readInt() }, color)
            }
            ops.add(Op(strokes, fills))
        }
        barDirty = true
        syncCounts()
        return meta
    }

    fun redrawAll() { relineArt(); rebuildPaint() }

    private fun writeStroke(o: DataOutputStream, s: Stroke) {
        o.writeInt(s.color); o.writeInt(s.sectors); o.writeBoolean(s.mirror)
        o.writeByte(s.tool.ordinal); o.writeInt(s.pts.size)
        for (p in s.pts) { o.writeFloat(p.x); o.writeFloat(p.y); o.writeFloat(p.w) }
    }

    private fun readStroke(i: DataInputStream): Stroke {
        val s = Stroke(i.readInt(), i.readInt(), i.readBoolean(), Tool.values()[i.readByte().toInt()])
        repeat(i.readInt()) { s.pts.add(P(i.readFloat(), i.readFloat(), i.readFloat())) }
        return s
    }

    fun thumbnail(look: Look, px: Int): Bitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(look.page)
        c.scale(px / SIZE.toFloat(), px / SIZE.toFloat())
        val p = Paint().apply { isFilterBitmap = true }
        c.drawBitmap(paintBmp, 0f, 0f, p)
        c.drawBitmap(art, 0f, 0f, p)
        return out
    }

    // ---------------------------------------------------------------- exportar

    fun snapshot(look: Look): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(look.page)
        c.drawBitmap(paintBmp, 0f, 0f, null)
        c.drawBitmap(art, 0f, 0f, null)
        return out
    }

    private fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)
    private fun mixWhite(color: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val v = (color shr shift) and 0xFF
            return (v + (255 - v) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
