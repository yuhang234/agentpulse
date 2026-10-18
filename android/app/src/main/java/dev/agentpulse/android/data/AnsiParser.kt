package dev.agentpulse.android.data

/**
 * Minimal, dependency-free ANSI SGR parser for terminal output.
 *
 * The monitoring service can return pane output in `ansi` format (report 3.1 / 5.3), e.g.
 * `"\u001b[38;5;244m───\u001b[0m"` or `"\u001b[36mGemini 3.8 Flash · high\u001b[0m"`. This parser
 * turns each line into styled spans (foreground colour + bold) that Compose can render into an
 * `AnnotatedString`. Non-SGR escape sequences (cursor moves, erases, OSC titles) are stripped.
 */
object AnsiParser {

    /** A run of text sharing one style. [argb] is null for the terminal default foreground. */
    data class AnsiSpan(
        val text: String,
        val argb: Int? = null,
        val bold: Boolean = false,
    )

    private const val ESC = '\u001b'

    fun parseLines(lines: List<String>): List<List<AnsiSpan>> = lines.map { parseLine(it) }

    fun parseLine(line: String): List<AnsiSpan> {
        val spans = ArrayList<AnsiSpan>()
        val builder = StringBuilder()
        var currentColor: Int? = null
        var currentBold = false

        fun flush() {
            if (builder.isNotEmpty()) {
                spans.add(AnsiSpan(builder.toString(), currentColor, currentBold))
                builder.clear()
            }
        }

        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c != ESC) {
                builder.append(c)
                i += 1
                continue
            }
            // Escape sequence.
            if (i + 1 >= line.length) {
                i += 1
                continue
            }
            when (val next = line[i + 1]) {
                '[' -> {
                    val end = findCsiEnd(line, i + 2)
                    if (end < 0) {
                        // Unterminated CSI: drop the rest of the escape and keep text.
                        i = line.length
                    } else {
                        val body = line.substring(i + 2, end)
                        val finalChar = line[end]
                        if (finalChar == 'm') {
                            flush()
                            applySgr(body, current = SgrState(currentColor, currentBold))
                                .let { (color, bold) ->
                                    currentColor = color
                                    currentBold = bold
                                }
                        }
                        i = end + 1
                    }
                }

                ']' -> {
                    // OSC ... terminated by BEL or ST
                    var j = i + 2
                    while (j < line.length && line[j] != '\u0007') {
                        if (line[j] == ESC && j + 1 < line.length && line[j + 1] == '\\') {
                            j += 1
                            break
                        }
                        j += 1
                    }
                    i = (j + 1).coerceAtMost(line.length)
                }

                else -> {
                    // Two-character escape (e.g. ESC ( B ); skip it.
                    i = if (next == '(' || next == ')') (i + 3).coerceAtMost(line.length) else i + 2
                }
            }
        }
        flush()
        return spans
    }

    private data class SgrState(val color: Int?, val bold: Boolean)

    private fun findCsiEnd(line: String, from: Int): Int {
        var j = from
        while (j < line.length) {
            val ch = line[j]
            if (ch in '@'..'~') return j
            j += 1
        }
        return -1
    }

    /** Applies a single SGR parameter list to the running state. */
    private fun applySgr(params: String, current: SgrState): SgrState {
        var color = current.color
        var bold = current.bold

        val parts = if (params.isEmpty()) listOf("0") else params.split(';')
        var idx = 0
        while (idx < parts.size) {
            val code = parts[idx].toIntOrNull() ?: 0
            when {
                code == 0 -> {
                    color = null
                    bold = false
                }

                code == 1 -> bold = true
                code == 22 -> bold = false

                code in 30..37 -> color = standardColor(code - 30, bright = false)
                code in 90..97 -> color = standardColor(code - 90, bright = true)
                code == 39 -> color = null

                code == 38 -> {
                    val consumed = parseExtendedColor(parts, idx + 1)
                    if (consumed != null) {
                        color = consumed.argb
                        idx += consumed.consumedParams
                    }
                }
                // Background colours (40-47, 48;...; 49) - the app renders on its own terminal
                // background, so they are intentionally ignored.
                code in 40..47 || code == 49 -> Unit
                code == 48 -> {
                    val consumed = parseExtendedColor(parts, idx + 1)
                    if (consumed != null) idx += consumed.consumedParams
                }
                else -> Unit
            }
            idx += 1
        }
        return SgrState(color, bold)
    }

    private data class ExtendedColor(val argb: Int, val consumedParams: Int)

    /**
     * Parses `38;5;N` (256-colour) and `38;2;R;G;B` (truecolour) tails.
     * Returns the colour plus how many params beyond `38` were consumed.
     */
    private fun parseExtendedColor(parts: List<String>, start: Int): ExtendedColor? {
        val kind = parts.getOrNull(start)?.toIntOrNull() ?: return null
        return when (kind) {
            5 -> {
                val n = parts.getOrNull(start + 1)?.toIntOrNull() ?: return null
                ExtendedColor(color256(n), 2)
            }

            2 -> {
                val r = parts.getOrNull(start + 1)?.toIntOrNull() ?: return null
                val g = parts.getOrNull(start + 2)?.toIntOrNull() ?: return null
                val b = parts.getOrNull(start + 3)?.toIntOrNull() ?: return null
                ExtendedColor(rgb(r, g, b), 4)
            }

            else -> null
        }
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        0xFF000000.toInt() or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** 256-colour palette approximation: 0-15 standard/bright, 16-231 cube, 232-255 greyscale. */
    private fun color256(n: Int): Int = when {
        n in 0..7 -> standardColor(n, bright = false)
        n in 8..15 -> standardColor(n - 8, bright = true)
        n in 16..231 -> {
            val c = n - 16
            val r = (c / 36) % 6
            val g = (c / 6) % 6
            val b = c % 6
            rgb(r * 40 + 55, g * 40 + 55, b * 40 + 55)
        }

        n in 232..255 -> {
            val v = 8 + (n - 232) * 10
            rgb(v, v, v)
        }

        else -> 0xFFFFFFFF.toInt()
    }

    private fun standardColor(index: Int, bright: Boolean): Int = if (bright) {
        BRIGHT_PALETTE[index % BRIGHT_PALETTE.size]
    } else {
        NORMAL_PALETTE[index % NORMAL_PALETTE.size]
    }

    // Tuned to stay readable on the OLED terminal background (#080B0F).
    private val NORMAL_PALETTE = intArrayOf(
        0xFF3B4048.toInt(), // black -> dark grey, readable on black
        0xFFF43F5E.toInt(), // red (rose)
        0xFF34D399.toInt(), // green
        0xFFFBBF24.toInt(), // yellow (amber)
        0xFF60A5FA.toInt(), // blue
        0xFFA78BFA.toInt(), // magenta
        0xFF38BDF8.toInt(), // cyan (pulse primary)
        0xFFC9D1D9.toInt(), // white -> light grey
    )

    private val BRIGHT_PALETTE = intArrayOf(
        0xFF6E7681.toInt(), // bright black
        0xFFFB7185.toInt(), // bright red
        0xFF6EE7B7.toInt(), // bright green
        0xFFFDE68A.toInt(), // bright yellow
        0xFF93C5FD.toInt(), // bright blue
        0xFFC4B5FD.toInt(), // bright magenta
        0xFF7DD3FC.toInt(), // bright cyan
        0xFFF0F6FC.toInt(), // bright white (text primary)
    )
}
