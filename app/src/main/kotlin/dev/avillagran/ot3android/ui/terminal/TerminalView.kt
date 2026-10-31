package dev.avillagran.ot3android.ui.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import dev.avillagran.ot3android.core.terminal.TerminalEmulator
import kotlin.math.ceil
import kotlin.math.floor

/** Android's IME owns composition; only committed text crosses the PTY boundary. */
internal class TerminalView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE; textSize = 14 * resources.displayMetrics.scaledDensity
    }
    private val cellWidth get() = paint.measureText("M")
    private val cellHeight get() = ceil(paint.fontSpacing.toDouble()).toFloat()
    var send: (String) -> Unit = {}
    var dimensions: (Int, Int) -> Unit = { _, _ -> }
    var writable = false
        set(value) { if (field && !value) (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0); field = value }
    var frame: TerminalEmulator.Frame? = null
        set(value) { val previous = field; field = value; if (scroll == 0 || previous?.lines?.size != value?.lines?.size) scroll = scroll.coerceAtMost((value?.lines?.size ?: 0) - (value?.rows ?: 0)).coerceAtLeast(0); invalidate() }
    private var scroll = 0
    private var downY = 0f
    private var downX = 0f
    private var pan = 0f
    var applicationCursor = false
    private var moved = false
    init { isFocusable = true; isFocusableInTouchMode = true; setBackgroundColor(Color.BLACK) }
    fun reportSize() {
        if (width > 0 && height > 0) dimensions(floor(width / cellWidth).toInt().coerceIn(1, 1000), floor(height / cellHeight).toInt().coerceIn(1, 500))
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); reportSize() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val f = frame ?: return
        canvas.save(); canvas.translate(-pan, 0f)
        val visibleRows = (height / cellHeight).toInt().coerceAtLeast(1)
        val start = (f.lines.size - visibleRows - scroll).coerceAtLeast(0)
        for (r in start until (start + visibleRows).coerceAtMost(f.lines.size)) {
            val line = f.lines[r]
            for (c in line.indices) {
                val cell = line[c]; val left = c * cellWidth; val top = (r - start) * cellHeight
                paint.color = color(if (cell.inverse) cell.fg else cell.bg)
                canvas.drawRect(left, top, left + cellWidth, top + cellHeight, paint)
            }
            for (c in line.indices) {
                val cell = line[c]; val left = c * cellWidth; val top = (r - start) * cellHeight
                paint.color = color(if (cell.inverse) cell.bg else cell.fg)
                paint.isFakeBoldText = cell.bold; paint.isUnderlineText = cell.underline
                canvas.drawText(cell.text, left, top - paint.fontMetrics.ascent, paint)
            }
        }
        paint.isFakeBoldText = false; paint.isUnderlineText = false
        val cursorRow = f.lines.size - f.rows + f.cursorY - start
        if (f.cursorVisible && scroll == 0 && cursorRow in 0 until visibleRows) {
            paint.color = Color.LTGRAY; paint.style = Paint.Style.STROKE
            canvas.drawRect(f.cursorX * cellWidth, cursorRow * cellHeight, (f.cursorX + 1) * cellWidth, (cursorRow + 1) * cellHeight, paint)
            paint.style = Paint.Style.FILL
        }
        canvas.restore()
    }
    private fun color(index: Int): Int {
        if (index >= 0x1000000) return 0xff000000.toInt() or (index and 0xffffff)
        val base = intArrayOf(0xff101010.toInt(), 0xffcc5555.toInt(), 0xff55bb77.toInt(), 0xffddbb66.toInt(), 0xff6688dd.toInt(), 0xffbb77cc.toInt(), 0xff55bbbb.toInt(), 0xffdddddd.toInt(), 0xff777777.toInt(), 0xffff7777.toInt(), 0xff77dd99.toInt(), 0xffffdd88.toInt(), 0xff88aaff.toInt(), 0xffdd99ee.toInt(), 0xff77dddd.toInt(), Color.WHITE)
        if (index < 16) return base[index.coerceAtLeast(0)]
        if (index >= 232) { val v = 8 + (index.coerceAtMost(255) - 232) * 10; return Color.rgb(v, v, v) }
        val v = index - 16
        fun component(n: Int) = if (n == 0) 0 else 55 + n * 40
        return Color.rgb(component(v / 36), component(v / 6 % 6), component(v % 6))
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = event.y; downX = event.x; moved = false; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                val dx = downX - event.x
                if (kotlin.math.abs(dx) > cellWidth) { moved = true; pan = (pan + dx).coerceIn(0f, (((frame?.cols ?: 0) * cellWidth) - width).coerceAtLeast(0f)); downX = event.x; invalidate() }
                val delta = ((event.y - downY) / cellHeight).toInt()
                if (delta != 0) { moved = true; scroll = (scroll + delta).coerceIn(0, ((frame?.lines?.size ?: 0) - (height / cellHeight).toInt()).coerceAtLeast(0)); downY = event.y; invalidate() }
            }
            MotionEvent.ACTION_UP -> if (!moved) performClick()
        }; return true
    }
    override fun performClick(): Boolean {
        super.performClick()
        if (writable) { requestFocus(); (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
        return true
    }
    override fun onCheckIsTextEditor() = writable
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!writable) return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, false) {
            private var composing = ""
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean { composing = text?.toString().orEmpty(); return true }
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { composing = ""; emit(text?.toString().orEmpty().replace("\n", "\r")); return true }
            override fun finishComposingText(): Boolean { val text = composing; composing = ""; emit(text); return true }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (composing.isNotEmpty()) { composing = composing.dropLast(beforeLength.coerceAtMost(composing.length)); return true }
                emit("\u007f".repeat(beforeLength.coerceIn(0, 1000)) + "\u001b[3~".repeat(afterLength.coerceIn(0, 1000))); return true
            }
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) = deleteSurroundingText(beforeLength, afterLength)
            override fun sendKeyEvent(event: KeyEvent): Boolean = if (event.action == KeyEvent.ACTION_DOWN) key(event) else true
            override fun performEditorAction(actionCode: Int): Boolean { emit("\r"); return true }
        }
    }
    private fun emit(text: String) { if (writable && text.isNotEmpty()) { scroll = 0; send(text); invalidate() } }
    private fun key(event: KeyEvent): Boolean {
        fun arrow(final: Char) = "\u001b" + (if (applicationCursor) "O" else "[") + final
        val text = when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER -> "\r"
            KeyEvent.KEYCODE_DEL -> "\u007f"
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~"
            KeyEvent.KEYCODE_ESCAPE -> "\u001b"
            KeyEvent.KEYCODE_TAB -> "\t"
            KeyEvent.KEYCODE_DPAD_UP -> arrow('A')
            KeyEvent.KEYCODE_DPAD_DOWN -> arrow('B')
            KeyEvent.KEYCODE_DPAD_RIGHT -> arrow('C')
            KeyEvent.KEYCODE_DPAD_LEFT -> arrow('D')
            KeyEvent.KEYCODE_MOVE_HOME -> "\u001b[H"
            KeyEvent.KEYCODE_MOVE_END -> "\u001b[F"
            KeyEvent.KEYCODE_PAGE_UP -> "\u001b[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001b[6~"
            else -> {
                val cp = event.getUnicodeChar(event.metaState and KeyEvent.META_CTRL_MASK.inv())
                if (cp == 0) return super.onKeyDown(event.keyCode, event)
                val data = if (event.isCtrlPressed && (cp in 64..127 || cp == 32)) (cp and 31).toChar().toString() else String(Character.toChars(cp))
                if (event.isAltPressed) "\u001b$data" else data
            }
        }; emit(text); return true
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = if (writable) key(event) else super.onKeyDown(keyCode, event)
}
