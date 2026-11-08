package cat.mandala.pen

import androidx.annotation.StringRes

/** Ambient visual: taula, color de la pàgina, color de línia i tema de la interfície. Tot en ARGB. */
class Look(
    @StringRes val nameRes: Int,
    val bg: Int,
    val page: Int,
    val line: Int,
    val dark: Boolean,
) {
    val fg: Int get() = if (dark) 0xFFEDEAF5.toInt() else 0xFF2B2D42.toInt()
    val panel: Int get() = if (dark) 0xFF1C1B2B.toInt() else 0xFFFFFBF2.toInt()
}

object Looks {
    val all = listOf(
        Look(R.string.look_paper, 0xFFE3D8C3.toInt(), 0xFFFFFBF0.toInt(), 0xFF2B2D42.toInt(), false),
        Look(R.string.look_night, 0xFF070B16.toInt(), 0xFF101733.toInt(), 0xFFE8EAF6.toInt(), true),
        Look(R.string.look_fire, 0xFF120707.toInt(), 0xFF2A100C.toInt(), 0xFFFFE8CC.toInt(), true),
        Look(R.string.look_forest, 0xFF050E09.toInt(), 0xFF0F2519.toInt(), 0xFFE3F2E1.toInt(), true),
        Look(R.string.look_lavender, 0xFF0E0A1A.toInt(), 0xFF211A3D.toInt(), 0xFFEDE4FF.toInt(), true),
    )

    /** Colors per pintar (també els fa servir l'autocolor). */
    val palette: List<Int> = listOf(
        0xFFE76F51, 0xFFF4A261, 0xFFE9C46A, 0xFF90BE6D, 0xFF2A9D8F, 0xFF8ECAE6,
        0xFF457B9D, 0xFF9B5DE5, 0xFFF15BB5, 0xFFE5989B, 0xFFFFFFFF, 0xFF264653,
    ).map { it.toInt() }
}
