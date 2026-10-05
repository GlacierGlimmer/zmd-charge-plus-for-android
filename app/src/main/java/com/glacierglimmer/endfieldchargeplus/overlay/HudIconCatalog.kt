package com.glacierglimmer.endfieldchargeplus.overlay

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Canvas path data for every icon of the ECP catalog.
 *
 * The 35 identifiers and their geometry strings are a verbatim port of
 * `Customization/IconCatalog.cs` from the desktop edition (also quoted in
 * `docs/audit/01-windows-core.md` §8 "Icon catalog"). The desktop edition hands those strings to
 * Avalonia's `StreamGeometry.Parse`; Android has no such mini-language, so this object implements
 * the subset of the SVG/Avalonia path grammar the catalog actually uses
 * (`M m L l H h V v C c S s Q q T t A a Z z`) and converts it into an [android.graphics.Path].
 *
 * Port decisions, kept explicit so the port stays auditable:
 *  * Avalonia's `StreamGeometry` fills with the even-odd rule, so every produced path uses
 *    [Path.FillType.EVEN_ODD]. That is what makes a glyph such as `cpu` (outer die + inner core)
 *    render as an outline with a hole instead of a filled block.
 *  * A degenerate arc whose end point coincides with its start point (for example `clock`'s
 *    `A10,10 0 1 1 11.99,2`) is drawn as the full 360° ellipse ring the desktop renderer shows;
 *    the SVG "omit the arc" rule would leave the clock, coin, globe and fan glyphs open.
 *  * Every glyph is normalised into a square design box and centred on the origin, so callers draw
 *    it with `canvas.translate(centreX, centreY); canvas.drawPath(...)`. This also absorbs the
 *    geometries that are not authored inside `0..24` (`refresh` spans `-2..26`).
 *  * An unknown or blank icon name renders a neutral placeholder (a hollow rounded square with a
 *    centre dot) instead of crashing. The desktop edition fell back to `bolt`; the Android HUD
 *    must stay diagnosable, so it shows "no icon known" rather than an unrelated glyph.
 *
 * All returned paths are freshly allocated: the cached instances are never handed out, so a caller
 * may transform the result freely.
 */
object HudIconCatalog {

    /** Design box every glyph is normalised into before scaling. */
    const val DESIGN_BOX = 24f

    /** Keeps the scaled glyph slightly inside its box so strokes are never clipped. */
    private const val INSET_FRACTION = 0.06f

    /** Identifier rendered for an unknown or blank name. */
    const val PLACEHOLDER_NAME = "placeholder"

    /**
     * Identifier list in the exact export order of `IconCatalog.Names` (35 entries), which is also
     * the order the settings icon picker shows.
     */
    val names: List<String> = listOf(
        "bolt", "cpu", "memory", "network", "disk", "clock", "api", "gpu", "battery",
        "laptop", "monitor", "server", "database", "cloud", "wifi", "signal", "ethernet",
        "download", "upload", "thermometer", "fan", "gauge", "calendar", "timer",
        "wallet", "coin", "terminal", "code", "globe", "activity", "settings",
        "shield", "power", "link", "refresh",
    )

    /**
     * The path source strings, verbatim from `Customization/IconCatalog.cs:12-48`. Kept public so
     * the icon catalog stays inspectable from diagnostics and from the JVM tests that verify every
     * name parses.
     */
    val geometrySources: Map<String, String> = linkedMapOf(
        // Original presets — kept unchanged.
        "bolt" to "M10,1 L4,11 L9,11 L7,19 L16,8 L11,8 Z",
        "cpu" to "M5,5 L19,5 L19,19 L5,19 Z M9,9 L15,9 L15,15 L9,15 Z M8,1 L8,4 M12,1 L12,4 M16,1 L16,4 M8,20 L8,23 M12,20 L12,23 M16,20 L16,23 M1,8 L4,8 M1,12 L4,12 M1,16 L4,16 M20,8 L23,8 M20,12 L23,12 M20,16 L23,16",
        "memory" to "M3,6 L21,6 L21,18 L3,18 Z M6,9 L18,9 L18,14 L6,14 Z M6,18 L6,21 M10,18 L10,21 M14,18 L14,21 M18,18 L18,21",
        "network" to "M12,3 C7,3 3,6 1,9 L4,12 C6,10 9,8 12,8 C15,8 18,10 20,12 L23,9 C21,6 17,3 12,3 Z M12,10 C9,10 7,11 5,14 L8,17 C9,16 10,15 12,15 C14,15 15,16 16,17 L19,14 C17,11 15,10 12,10 Z M12,18 C10.9,18 10,18.9 10,20 C10,21.1 10.9,22 12,22 C13.1,22 14,21.1 14,20 C14,18.9 13.1,18 12,18 Z",
        "disk" to "M4,3 L20,3 L20,21 L4,21 Z M7,6 L17,6 L17,14 L7,14 Z M7,17 L9,17 M12,17 L17,17",
        "clock" to "M12,2 A10,10 0 1 1 11.99,2 M12,6 L12,12 L16,14",
        "api" to "M7,4 L17,4 L17,8 L20,8 L20,16 L17,16 L17,20 L7,20 L7,16 L4,16 L4,8 L7,8 Z M9,9 L15,9 L15,15 L9,15 Z",
        "gpu" to "M3,6 L21,6 L21,18 L3,18 Z M7,9 A3,3 0 1 1 6.99,9 M14,9 L18,9 M14,12 L18,12 M14,15 L18,15",
        "battery" to "M6,6 L18,6 L18,18 L6,18 Z M10,3 L14,3 L14,6",
        // Additional presets.
        "laptop" to "M4,5 L20,5 L20,16 L4,16 Z M2,18 L22,18 L20,21 L4,21 Z",
        "monitor" to "M3,4 L21,4 L21,17 L3,17 Z M9,19 L15,19 L15,21 L9,21 Z",
        "server" to "M3,3 L21,3 L21,9 L3,9 Z M3,10 L21,10 L21,16 L3,16 Z M3,17 L21,17 L21,23 L3,23 Z M6,6 A1,1 0 1 1 5.99,6 M6,13 A1,1 0 1 1 5.99,13 M6,20 A1,1 0 1 1 5.99,20",
        "database" to "M4,5 A8,3 0 1 1 20,5 A8,3 0 1 1 4,5 Z M4,5 L4,12 C4,14 7.6,15.5 12,15.5 C16.4,15.5 20,14 20,12 L20,5 C18.4,7 15.2,8 12,8 C8.8,8 5.6,7 4,5 Z M4,12 L4,19 C4,21 7.6,22.5 12,22.5 C16.4,22.5 20,21 20,19 L20,12 C18.4,14 15.2,15 12,15 C8.8,15 5.6,14 4,12 Z",
        "cloud" to "M7,19 C4.2,19 2,16.8 2,14 C2,11.5 3.8,9.4 6.2,9 C7.2,5.9 10,4 13.2,4 C17.2,4 20.4,7.1 20.5,11 C22.5,11.5 24,13.2 24,15.3 C24,17.4 22.3,19 20.2,19 Z",
        "wifi" to "M2,8 C7.5,3.5 16.5,3.5 22,8 L19.5,10.8 C15.4,7.6 8.6,7.6 4.5,10.8 Z M6.5,13 C9.6,10.5 14.4,10.5 17.5,13 L15,15.8 C13.3,14.5 10.7,14.5 9,15.8 Z M12,18 A2,2 0 1 1 11.99,18 Z",
        "signal" to "M3,18 L6,18 L6,21 L3,21 Z M8,14 L11,14 L11,21 L8,21 Z M13,9 L16,9 L16,21 L13,21 Z M18,4 L21,4 L21,21 L18,21 Z",
        "ethernet" to "M4,3 L20,3 L20,13 L16,13 L16,17 L13,17 L13,21 L11,21 L11,17 L8,17 L8,13 L4,13 Z M7,6 L9,6 L9,10 L7,10 Z M11,6 L13,6 L13,10 L11,10 Z M15,6 L17,6 L17,10 L15,10 Z",
        "download" to "M10,3 L14,3 L14,12 L18,12 L12,18 L6,12 L10,12 Z M4,20 L20,20 L20,22 L4,22 Z",
        "upload" to "M12,3 L18,9 L14,9 L14,18 L10,18 L10,9 L6,9 Z M4,20 L20,20 L20,22 L4,22 Z",
        "thermometer" to "M9,4 A3,3 0 0 1 15,4 L15,14.2 A5,5 0 1 1 9,14.2 Z M11,5 L13,5 L13,15.2 C14.2,15.6 15,16.7 15,18 A3,3 0 1 1 9,18 C9,16.7 9.8,15.6 11,15.2 Z",
        "fan" to "M12,9 A3,3 0 1 1 11.99,9 Z M12,2 C16,2 18,5 16,8 C15,9.4 13.6,9.8 12.7,10.1 C13.4,7.7 12.9,5.3 12,2 Z M22,12 C22,16 19,18 16,16 C14.6,15 14.2,13.6 13.9,12.7 C16.3,13.4 18.7,12.9 22,12 Z M12,22 C8,22 6,19 8,16 C9,14.6 10.4,14.2 11.3,13.9 C10.6,16.3 11.1,18.7 12,22 Z M2,12 C2,8 5,6 8,8 C9.4,9 9.8,10.4 10.1,11.3 C7.7,10.6 5.3,11.1 2,12 Z",
        "gauge" to "M3,18 A9,9 0 1 1 21,18 L18,18 A6,6 0 1 0 6,18 Z M12,12 L18,8 L14,14 Z",
        "calendar" to "M4,4 L7,4 L7,2 L9,2 L9,4 L15,4 L15,2 L17,2 L17,4 L20,4 L20,21 L4,21 Z M6,8 L18,8 L18,19 L6,19 Z",
        "timer" to "M9,2 L15,2 L15,4 L9,4 Z M17,5 L19,3 L21,5 L19,7 Z M12,5 A8,8 0 1 1 11.99,5 M12,8 L12,13 L16,13",
        "wallet" to "M3,5 L19,5 L19,8 L21,8 L21,19 L3,19 Z M16,11 L21,11 L21,16 L16,16 Z M18,13 A1,1 0 1 1 17.99,13",
        "coin" to "M12,2 A10,10 0 1 1 11.99,2 M10,6 L14,6 L14,8 L11,8 C10.4,8 10,8.4 10,9 C10,9.6 10.4,10 11,10 L13,10 C15.2,10 17,11.8 17,14 C17,16.2 15.2,18 13,18 L13,20 L11,20 L11,18 L7,18 L7,16 L13,16 C13.6,16 14,15.6 14,15 C14,14.4 13.6,14 13,14 L11,14 C8.8,14 7,12.2 7,10 C7,7.8 8.8,6 11,6 Z",
        "terminal" to "M3,4 L21,4 L21,20 L3,20 Z M6,8 L10,12 L6,16 L8,16 L12,12 L8,8 Z M12,16 L18,16 L18,18 L12,18 Z",
        "code" to "M8,5 L2,12 L8,19 L10,17 L6,12 L10,7 Z M16,5 L14,7 L18,12 L14,17 L16,19 L22,12 Z M13,4 L15,4 L11,20 L9,20 Z",
        "globe" to "M12,2 A10,10 0 1 1 11.99,2 M2,12 L22,12 M12,2 C8,5 8,19 12,22 C16,19 16,5 12,2 Z",
        "activity" to "M2,13 L6,13 L9,6 L13,19 L16,11 L22,11 L22,14 L18,14 L13,23 L9,11 L8,16 L2,16 Z",
        "settings" to "M10,2 L14,2 L15,5 C16,5.4 17,6 17.8,6.7 L21,6 L23,10 L20.5,12 C20.6,12.7 20.6,13.3 20.5,14 L23,16 L21,20 L17.8,19.3 C17,20 16,20.6 15,21 L14,24 L10,24 L9,21 C8,20.6 7,20 6.2,19.3 L3,20 L1,16 L3.5,14 C3.4,13.3 3.4,12.7 3.5,12 L1,10 L3,6 L6.2,6.7 C7,6 8,5.4 9,5 Z M12,9 A4,4 0 1 1 11.99,9 Z",
        "shield" to "M12,2 L21,6 L20,13 C19.5,17.5 16.7,20.7 12,23 C7.3,20.7 4.5,17.5 4,13 L3,6 Z M11,7 L13,7 L13,12 L17,12 L17,14 L11,14 Z",
        "power" to "M11,2 L13,2 L13,12 L11,12 Z M7,5 C3.8,6.8 2,10 2,13.5 C2,19 6.5,23 12,23 C17.5,23 22,19 22,13.5 C22,10 20.2,6.8 17,5 L15.5,7.6 C17.7,8.8 19,11 19,13.5 C19,17.4 15.9,20 12,20 C8.1,20 5,17.4 5,13.5 C5,11 6.3,8.8 8.5,7.6 Z",
        "link" to "M7,7 C4.2,7 2,9.2 2,12 C2,14.8 4.2,17 7,17 L10,17 L10,14 L7,14 C5.9,14 5,13.1 5,12 C5,10.9 5.9,10 7,10 L11,10 L11,7 Z M13,7 L17,7 C19.8,7 22,9.2 22,12 C22,14.8 19.8,17 17,17 L13,17 L13,14 L17,14 C18.1,14 19,13.1 19,12 C19,10.9 18.1,10 17,10 L13,10 Z M8,11 L16,11 L16,13 L8,13 Z",
        "refresh" to "M12,3 C16.4,3 20,6 20.8,10 L17.5,10 L22,15 L26,10 L23.8,10 C22.9,4.3 18,0 12,0 C7,0 2.7,3 1,7 L4,8.2 C5.3,5.1 8.4,3 12,3 Z M12,21 C7.6,21 4,18 3.2,14 L6.5,14 L2,9 L-2,14 L0.2,14 C1.1,19.7 6,24 12,24 C17,24 21.3,21 23,17 L20,15.8 C18.7,18.9 15.6,21 12,21 Z",
    )

    private val tokenPattern = Regex("[MmLlHhVvCcSsQqTtAaZz]|-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")

    private val cache = HashMap<String, Path>()

    private val placeholderPath: Path by lazy { buildPlaceholder() }

    /** True when [name] is one of the 35 catalog identifiers (case-insensitive, trimmed). */
    fun isKnown(name: String?): Boolean = geometrySources.containsKey(normalizeName(name))

    /** True when [name] is a blank/unknown identifier that will render the placeholder. */
    fun isPlaceholder(name: String?): Boolean = !isKnown(name)

    /** The raw geometry string of [name], or null for an unknown name. */
    fun geometrySource(name: String?): String? = geometrySources[normalizeName(name)]

    /**
     * A freshly allocated path for [name], normalised into a square of [sizePx] and **centred on
     * the origin**, so the caller can draw it after `canvas.translate(centreX, centreY)`.
     *
     * Unknown and blank names produce the neutral placeholder: this method never throws and never
     * returns an empty path.
     */
    fun path(name: String?, sizePx: Float): Path {
        val base = basePath(name)
        val factor = sizePx / DESIGN_BOX
        val out = Path(base)
        val matrix = Matrix()
        matrix.setScale(factor, factor)
        out.transform(matrix)
        out.fillType = Path.FillType.EVEN_ODD
        return out
    }

    /** True when [name] resolves to a non-empty path. Used by diagnostics and the JVM tests. */
    fun hasGeometry(name: String?): Boolean {
        val bounds = RectF()
        path(name, DESIGN_BOX).computeBounds(bounds, true)
        return bounds.width() > 0f && bounds.height() > 0f
    }

    @Synchronized
    private fun basePath(name: String?): Path {
        val key = normalizeName(name)
        if (key.isEmpty() || !geometrySources.containsKey(key)) return Path(placeholderPath)
        cache[key]?.let { return it }
        val parsed = normalize(parse(geometrySources.getValue(key)))
        cache[key] = parsed
        return parsed
    }

    private fun normalizeName(name: String?): String = name?.trim()?.lowercase().orEmpty()

    private fun buildPlaceholder(): Path {
        val path = Path()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRoundRect(RectF(-10.5f, -10.5f, 10.5f, 10.5f), 4f, 4f, Path.Direction.CW)
        path.addCircle(0f, 0f, 3.5f, Path.Direction.CW)
        return path
    }

    /** Scales [raw] so its largest dimension fits the inset design box, centred on the origin. */
    private fun normalize(raw: Path): Path {
        val bounds = RectF()
        raw.computeBounds(bounds, true)
        if (bounds.width() <= 0f || bounds.height() <= 0f) return Path(placeholderPath)
        val usable = DESIGN_BOX * (1f - 2f * INSET_FRACTION)
        val factor = usable / max(bounds.width(), bounds.height())
        val matrix = Matrix()
        matrix.postTranslate(-bounds.centerX(), -bounds.centerY())
        matrix.postScale(factor, factor)
        val out = Path()
        raw.transform(matrix, out)
        out.fillType = Path.FillType.EVEN_ODD
        return out
    }

    /**
     * Parses the Avalonia/SVG path mini-language subset used by the catalog.
     *
     * Supported: `M m L l H h V v C c S s Q q T t A a Z z`, including implicit repetition of the
     * previous command's parameter groups (after `M`/`m` the extra pairs are implicit `L`/`l`, as
     * the grammar requires). Unsupported letters are skipped instead of throwing, so a future
     * catalog entry can never crash the renderer.
     */
    internal fun parse(data: String): Path {
        val path = Path()
        path.fillType = Path.FillType.EVEN_ODD
        val tokens = tokenPattern.findAll(data).map { it.value }.toList()
        var index = 0
        var command = ' '
        var x = 0.0
        var y = 0.0
        var startX = 0.0
        var startY = 0.0
        var controlX = 0.0
        var controlY = 0.0

        fun hasNumber(): Boolean =
            index < tokens.size && !(tokens[index].length == 1 && tokens[index][0].isLetter())

        fun number(): Double = tokens[index++].toDouble()

        while (index < tokens.size) {
            val token = tokens[index]
            if (token.length == 1 && token[0].isLetter()) {
                command = token[0]
                index++
            } else if (command == ' ') {
                index++
                continue
            }
            val upper = command.uppercaseChar()
            val relative = command.isLowerCase()
            var consumed = false

            when (upper) {
                'M', 'L' -> if (hasNumber()) {
                    var nx = number()
                    var ny = number()
                    if (relative) {
                        nx += x
                        ny += y
                    }
                    if (upper == 'M') {
                        path.moveTo(nx.toFloat(), ny.toFloat())
                        startX = nx
                        startY = ny
                        // Extra coordinate pairs after a moveto are implicit linetos.
                        command = if (relative) 'l' else 'L'
                    } else {
                        path.lineTo(nx.toFloat(), ny.toFloat())
                    }
                    x = nx
                    y = ny
                    consumed = true
                }

                'H' -> if (hasNumber()) {
                    var nx = number()
                    if (relative) nx += x
                    path.lineTo(nx.toFloat(), y.toFloat())
                    x = nx
                    consumed = true
                }

                'V' -> if (hasNumber()) {
                    var ny = number()
                    if (relative) ny += y
                    path.lineTo(x.toFloat(), ny.toFloat())
                    y = ny
                    consumed = true
                }

                'C' -> if (hasNumber()) {
                    var c1x = number()
                    var c1y = number()
                    var c2x = number()
                    var c2y = number()
                    var nx = number()
                    var ny = number()
                    if (relative) {
                        c1x += x; c1y += y; c2x += x; c2y += y; nx += x; ny += y
                    }
                    path.cubicTo(
                        c1x.toFloat(), c1y.toFloat(),
                        c2x.toFloat(), c2y.toFloat(),
                        nx.toFloat(), ny.toFloat(),
                    )
                    controlX = c2x
                    controlY = c2y
                    x = nx
                    y = ny
                    consumed = true
                }

                'S' -> if (hasNumber()) {
                    // The first control point mirrors the previous cubic control point.
                    val c1x = 2 * x - controlX
                    val c1y = 2 * y - controlY
                    var c2x = number()
                    var c2y = number()
                    var nx = number()
                    var ny = number()
                    if (relative) {
                        c2x += x; c2y += y; nx += x; ny += y
                    }
                    path.cubicTo(
                        c1x.toFloat(), c1y.toFloat(),
                        c2x.toFloat(), c2y.toFloat(),
                        nx.toFloat(), ny.toFloat(),
                    )
                    controlX = c2x
                    controlY = c2y
                    x = nx
                    y = ny
                    consumed = true
                }

                'Q' -> if (hasNumber()) {
                    var qx = number()
                    var qy = number()
                    var nx = number()
                    var ny = number()
                    if (relative) {
                        qx += x; qy += y; nx += x; ny += y
                    }
                    path.quadTo(qx.toFloat(), qy.toFloat(), nx.toFloat(), ny.toFloat())
                    controlX = qx
                    controlY = qy
                    x = nx
                    y = ny
                    consumed = true
                }

                'T' -> if (hasNumber()) {
                    val qx = 2 * x - controlX
                    val qy = 2 * y - controlY
                    var nx = number()
                    var ny = number()
                    if (relative) {
                        nx += x; ny += y
                    }
                    path.quadTo(qx.toFloat(), qy.toFloat(), nx.toFloat(), ny.toFloat())
                    controlX = qx
                    controlY = qy
                    x = nx
                    y = ny
                    consumed = true
                }

                'A' -> if (hasNumber()) {
                    var rx = number()
                    var ry = number()
                    val rotation = number()
                    val largeArc = number() != 0.0
                    val sweep = number() != 0.0
                    var nx = number()
                    var ny = number()
                    if (relative) {
                        nx += x; ny += y
                    }
                    arcTo(path, x, y, rx, ry, rotation, largeArc, sweep, nx, ny)
                    x = nx
                    y = ny
                    consumed = true
                }

                'Z' -> {
                    path.close()
                    x = startX
                    y = startY
                    consumed = true
                    if (index < tokens.size && !(tokens[index].length == 1 && tokens[index][0].isLetter())) index++
                }
            }

            if (!consumed && index < tokens.size &&
                !(tokens[index].length == 1 && tokens[index][0].isLetter())
            ) {
                // Malformed or unsupported parameter: drop one token so the loop always advances.
                index++
            }
        }
        return path
    }

    /**
     * SVG endpoint-to-centre arc conversion (SVG 1.1 appendix F.6.5) followed by
     * [Path.arcTo], which shares the same y-down angle convention as the SVG `sweep` flag.
     */
    private fun arcTo(
        path: Path,
        x1: Double,
        y1: Double,
        rx: Double,
        ry: Double,
        rotationDegrees: Double,
        largeArc: Boolean,
        sweep: Boolean,
        x2: Double,
        y2: Double,
    ) {
        if (rx == 0.0 || ry == 0.0) {
            path.lineTo(x2.toFloat(), y2.toFloat())
            return
        }
        val radiusX = abs(rx)
        val radiusY = abs(ry)
        val phi = Math.toRadians(rotationDegrees)
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)

        val dx = (x1 - x2) / 2.0
        val dy = (y1 - y2) / 2.0
        val x1p = cosPhi * dx + sinPhi * dy
        val y1p = -sinPhi * dx + cosPhi * dy

        // Scale the radii up when they are too small to span the chord.
        val lambda = (x1p * x1p) / (radiusX * radiusX) + (y1p * y1p) / (radiusY * radiusY)
        val scale = if (lambda > 1.0) sqrt(lambda) else 1.0
        val rxs = radiusX * scale
        val rys = radiusY * scale

        val denominator = rxs * rxs * y1p * y1p + rys * rys * x1p * x1p
        val numerator = rxs * rxs * rys * rys - rxs * rxs * y1p * y1p - rys * rys * x1p * x1p
        val sign = if (largeArc != sweep) 1.0 else -1.0
        val coefficient = if (denominator == 0.0) 0.0 else sign * sqrt(max(0.0, numerator / denominator))
        val cxp = coefficient * rxs * y1p / rys
        val cyp = coefficient * -rys * x1p / rxs
        val cx = cosPhi * cxp - sinPhi * cyp + (x1 + x2) / 2.0
        val cy = sinPhi * cxp + cosPhi * cyp + (y1 + y2) / 2.0

        val ux = (x1p - cxp) / rxs
        val uy = (y1p - cyp) / rys
        val vx = (-x1p - cxp) / rxs
        val vy = (-y1p - cyp) / rys
        val startAngle = atan2(uy, ux)
        var deltaAngle = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        if (!sweep && deltaAngle > 0.0) deltaAngle -= 2 * Math.PI
        if (sweep && deltaAngle < 0.0) deltaAngle += 2 * Math.PI

        val oval = RectF(
            (cx - rxs).toFloat(),
            (cy - rys).toFloat(),
            (cx + rxs).toFloat(),
            (cy + rys).toFloat(),
        )
        path.arcTo(
            oval,
            Math.toDegrees(startAngle).toFloat(),
            Math.toDegrees(deltaAngle).toFloat(),
            false,
        )
    }
}
