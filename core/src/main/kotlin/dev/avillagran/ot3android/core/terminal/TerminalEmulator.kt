package dev.avillagran.ot3android.core.terminal

/** A bounded VT screen. Parser state (including CSI/OSC and surrogate pairs) spans wire events. */
class TerminalEmulator(cols: Int = 80, rows: Int = 24, private val historyLimit: Int = 2000) {
    data class Cell(val text: String = " ", val fg: Int = 7, val bg: Int = 0, val bold: Boolean = false, val underline: Boolean = false, val inverse: Boolean = false)
    data class Frame(val lines: List<List<Cell>>, val cols: Int, val rows: Int, val cursorX: Int, val cursorY: Int, val cursorVisible: Boolean) {
        fun text() = lines.joinToString("\n") { line -> line.joinToString("") { it.text }.trimEnd() }
    }
    var cols = cols.coerceIn(1, 1000); private set
    var rows = rows.coerceIn(1, 500); private set
    private var screen = mutableListOf<MutableList<Cell>>()
    private val history = ArrayDeque<List<Cell>>()
    private var primary: MutableList<MutableList<Cell>>? = null
    private var primaryCursor = 0 to 0
    private val replies = ArrayDeque<String>()
    fun drainReplies(): List<String> = replies.toList().also { replies.clear() }
    private var x = 0; private var y = 0
    private var saved = 0 to 0
    private var top = 0; private var bottom = this.rows - 1
    private var style = Cell()
    private var mode = 0
    private val sequence = StringBuilder()
    private var high: Char? = null
    private var wrap = true; private var pendingWrap = false
    private var visible = true
    var bracketedPaste = false; private set
    var applicationCursor = false; private set
    init { reset() }
    private fun blank() = MutableList(cols) { Cell() }
    fun reset() {
        screen = MutableList(rows) { blank() }; history.clear(); primary = null; replies.clear()
        x = 0; y = 0; top = 0; bottom = rows - 1; saved = 0 to 0; style = Cell()
        mode = 0; sequence.clear(); high = null; wrap = true; pendingWrap = false; visible = true
        bracketedPaste = false; applicationCursor = false
    }
    fun resize(columns: Int, lines: Int) {
        val c = columns.coerceIn(1, 1000); val r = lines.coerceIn(1, 500)
        if (c == cols && r == rows) return
        cols = c
        fun fit(buffer: MutableList<MutableList<Cell>>) {
            buffer.forEach { line -> while (line.size < c) line.add(Cell()); while (line.size > c) line.removeAt(line.lastIndex) }
            while (buffer.size > r) {
                val removed = buffer.removeAt(0)
                if (primary == null && buffer === screen) addHistory(removed)
                if (buffer === screen) y--
            }
            while (buffer.size < r) buffer.add(blank())
        }
        fit(screen); primary?.let(::fit); rows = r; top = 0; bottom = r - 1
        x = x.coerceIn(0, c - 1); y = y.coerceIn(0, r - 1); pendingWrap = false
    }
    private fun addHistory(line: List<Cell>) {
        history.addLast(line.toList()); while (history.size > historyLimit || history.sumOf { it.size } > 128_000) history.removeFirst()
    }
    fun frame() = Frame((if (primary == null) history.toList() else emptyList()) + screen.map { it.toList() }, cols, rows, x, y, visible)
    fun feed(text: String) {
        for (ch in text) {
            val h = high
            if (h != null) {
                high = null
                if (ch.isLowSurrogate()) { accept(Character.toCodePoint(h, ch)); continue }
                accept(0xfffd)
            }
            if (ch.isHighSurrogate()) high = ch else accept(if (ch.isLowSurrogate()) 0xfffd else ch.code)
        }
    }
    private fun accept(cp: Int) {
        if (mode == 3) { if (cp == 7) mode = 0 else if (cp == 27) mode = 4; return }
        if (mode == 4) { mode = if (cp == 92) 0 else 3; return }
        if (mode == 5) { mode = 0; return } // charset designation
        if (cp == 27) { mode = 1; sequence.clear(); return }
        if (mode == 1) {
            mode = 0
            when (cp.toChar()) {
                '[' -> { mode = 2; sequence.clear() }
                ']' , 'P', '^', '_' -> mode = 3
                '(', ')' -> mode = 5
                '7' -> saved = x to y
                '8' -> { x = saved.first.coerceIn(0, cols - 1); y = saved.second.coerceIn(0, rows - 1) }
                'D' -> lineFeed()
                'E' -> { x = 0; lineFeed() }
                'M' -> if (y == top) { screen.removeAt(bottom); screen.add(top, blank()) } else y = (y - 1).coerceAtLeast(0)
                'c' -> reset()
            }; return
        }
        if (mode == 2) {
            if (cp in 0x40..0x7e) { csi(cp.toChar(), sequence.toString()); mode = 0; sequence.clear() }
            else if (sequence.length < 256) sequence.append(cp.toChar()) else { mode = 0; sequence.clear() }
            return
        }
        when (cp) {
            0, 7 -> Unit
            8 -> { x = (x - 1).coerceAtLeast(0); pendingWrap = false }
            9 -> { x = ((x / 8 + 1) * 8).coerceAtMost(cols - 1); pendingWrap = false }
            10, 11, 12 -> lineFeed()
            13 -> { x = 0; pendingWrap = false }
            else -> if (cp >= 32 && cp != 127) print(cp)
        }
    }
    private fun lineFeed() {
        pendingWrap = false
        if (y == bottom) {
            val line = screen.removeAt(top)
            if (top == 0 && bottom == rows - 1 && primary == null) addHistory(line)
            screen.add(bottom, blank())
        } else y = (y + 1).coerceAtMost(rows - 1)
    }
    private fun print(cp: Int) {
        val type = Character.getType(cp)
        if (type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() || cp == 0x200d || cp in 0xfe00..0xfe0f) {
            var px = if (pendingWrap) x else (x - 1).coerceAtLeast(0)
            if (px > 0 && screen[y][px].text.isEmpty()) px--
            screen[y][px] = screen[y][px].copy(text = screen[y][px].text + String(Character.toChars(cp))); return
        }
        val wide = cp in 0x1100..0x115f || cp in 0x2e80..0xa4cf || cp in 0xac00..0xd7a3 || cp in 0xf900..0xfaff || cp in 0xfe10..0xfe6f || cp in 0xff01..0xff60 || cp in 0x1f300..0x1faff || cp in 0x20000..0x3ffff
        val width = if (wide && cols > 1) 2 else 1
        if (wrap && (pendingWrap || x + width > cols)) { x = 0; lineFeed() }
        if (screen[y][x].text.isEmpty() && x > 0) screen[y][x - 1] = Cell()
        if (x + 1 < cols && screen[y][x + 1].text.isEmpty()) screen[y][x + 1] = Cell()
        screen[y][x] = style.copy(text = String(Character.toChars(cp)))
        if (width == 2 && x + 1 < cols) screen[y][x + 1] = style.copy(text = "")
        if (x + width >= cols) { x = cols - 1; pendingWrap = wrap } else x += width
    }
    private fun csi(final: Char, raw: String) {
        val privateMode = raw.startsWith('?')
        val p = raw.removePrefix("?").split(';').map { it.toIntOrNull() ?: 0 }
        fun n(i: Int = 0, default: Int = 1) = p.getOrElse(i) { default }.let { if (it == 0) default else it }.coerceAtMost(1000)
        val erase = style.copy(text = " ")
        fun clearLine(row: Int, from: Int, until: Int) { for (i in from.coerceAtLeast(0) until until.coerceAtMost(cols)) screen[row][i] = erase }
        when (final) {
            'A' -> y = (y - n()).coerceAtLeast(0)
            'B', 'e' -> y = (y + n()).coerceAtMost(rows - 1)
            'C', 'a' -> x = (x + n()).coerceAtMost(cols - 1)
            'D' -> x = (x - n()).coerceAtLeast(0)
            'E' -> { y = (y + n()).coerceAtMost(rows - 1); x = 0 }
            'F' -> { y = (y - n()).coerceAtLeast(0); x = 0 }
            'G', '`' -> x = (n() - 1).coerceIn(0, cols - 1)
            'd' -> y = (n() - 1).coerceIn(0, rows - 1)
            'H', 'f' -> { y = (n() - 1).coerceIn(0, rows - 1); x = (n(1) - 1).coerceIn(0, cols - 1) }
            'J' -> when (p[0]) {
                0 -> { clearLine(y, x, cols); for (r in y + 1 until rows) clearLine(r, 0, cols) }
                1 -> { for (r in 0 until y) clearLine(r, 0, cols); clearLine(y, 0, x + 1) }
                2 -> for (r in 0 until rows) clearLine(r, 0, cols)
                3 -> history.clear()
            }
            'K' -> when (p[0]) { 0 -> clearLine(y, x, cols); 1 -> clearLine(y, 0, x + 1); 2 -> clearLine(y, 0, cols) }
            'X' -> clearLine(y, x, x + n())
            'P' -> repeat(n().coerceAtMost(cols - x)) { screen[y].removeAt(x); screen[y].add(erase) }
            '@' -> repeat(n().coerceAtMost(cols - x)) { screen[y].add(x, erase); screen[y].removeAt(cols) }
            'L' -> if (y in top..bottom) repeat(n().coerceAtMost(bottom - y + 1)) { screen.removeAt(bottom); screen.add(y, blank()) }
            'M' -> if (y in top..bottom) repeat(n().coerceAtMost(bottom - y + 1)) { screen.removeAt(y); screen.add(bottom, blank()) }
            'S' -> repeat(n().coerceAtMost(rows)) { val line = screen.removeAt(top); if (top == 0 && primary == null) addHistory(line); screen.add(bottom, blank()) }
            'T' -> repeat(n().coerceAtMost(rows)) { screen.removeAt(bottom); screen.add(top, blank()) }
            'r' -> { top = (n() - 1).coerceIn(0, rows - 1); bottom = (n(1, rows) - 1).coerceIn(top, rows - 1); x = 0; y = top }
            's' -> saved = x to y
            'u' -> { x = saved.first.coerceIn(0, cols - 1); y = saved.second.coerceIn(0, rows - 1) }
            'n' -> when (p[0]) { 5 -> reply("\u001b[0n"); 6 -> reply("\u001b[${y + 1};${x + 1}R") }
            'c' -> reply("\u001b[?1;2c")
            'm' -> sgr(p)
            'h', 'l' -> if (privateMode) for (v in p) when (v) {
                1 -> applicationCursor = final == 'h'
                7 -> wrap = final == 'h'
                25 -> visible = final == 'h'
                2004 -> bracketedPaste = final == 'h'
                47, 1047, 1049 -> if (final == 'h' && primary == null) {
                    primary = screen; primaryCursor = x to y; screen = MutableList(rows) { blank() }; x = 0; y = 0
                } else if (final == 'l' && primary != null) {
                    screen = primary!!; primary = null; x = primaryCursor.first.coerceIn(0, cols - 1); y = primaryCursor.second.coerceIn(0, rows - 1)
                }
            }
        }
        if (final !in setOf('m', 'n', 'c', 'h', 'l')) pendingWrap = false
    }
    private fun reply(data: String) { if (replies.size < 16) replies.addLast(data) }
    private fun sgr(p: List<Int>) {
        var i = 0
        while (i < p.size) {
            when (val v = p[i]) {
                0 -> style = Cell()
                1 -> style = style.copy(bold = true)
                4 -> style = style.copy(underline = true)
                7 -> style = style.copy(inverse = true)
                22 -> style = style.copy(bold = false)
                24 -> style = style.copy(underline = false)
                27 -> style = style.copy(inverse = false)
                in 30..37 -> style = style.copy(fg = v - 30)
                in 40..47 -> style = style.copy(bg = v - 40)
                in 90..97 -> style = style.copy(fg = v - 90 + 8)
                in 100..107 -> style = style.copy(bg = v - 100 + 8)
                39 -> style = style.copy(fg = 7)
                49 -> style = style.copy(bg = 0)
                38, 48 -> {
                    var color: Int? = null
                    if (p.getOrNull(i + 1) == 5 && i + 2 < p.size) { color = p[i + 2].coerceIn(0, 255); i += 2 }
                    else if (p.getOrNull(i + 1) == 2 && i + 4 < p.size) {
                        color = 0x1000000 or (p[i + 2].coerceIn(0, 255) shl 16) or (p[i + 3].coerceIn(0, 255) shl 8) or p[i + 4].coerceIn(0, 255); i += 4
                    }
                    if (color != null) style = if (v == 38) style.copy(fg = color) else style.copy(bg = color)
                }
            }; i++
        }
    }
}
