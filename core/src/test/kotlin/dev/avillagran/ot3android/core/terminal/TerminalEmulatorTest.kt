package dev.avillagran.ot3android.core.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalEmulatorTest {
    @Test fun ansiStateSpansChunksAndPreservesStyle() {
        val t = TerminalEmulator(20, 2)
        t.feed("\u001b[3"); t.feed("1mred\u001b[0m plain")
        assertEquals("red plain", t.frame().lines.first().joinToString("") { it.text }.trimEnd())
        assertEquals(1, t.frame().lines[0][0].fg); assertEquals(7, t.frame().lines[0][4].fg)
    }
    @Test fun utf8WireTextAndSplitSurrogateAreNotLost() {
        val t = TerminalEmulator(20, 2)
        t.feed("café 東"); t.feed("京 \uD83D"); t.feed("\uDE80 e"); t.feed("\u0301")
        assertTrue(t.frame().text().startsWith("café 東京 🚀 é"))
        assertEquals("", t.frame().lines[0][6].text)
    }
    @Test fun overwriteAndEraseMatchProgressOutput() {
        val t = TerminalEmulator(20, 2)
        t.feed("long progress\rOK\u001b[K")
        assertEquals("OK", t.frame().text().trim())
    }
    @Test fun cursorMovementAndTrueColor() {
        val t = TerminalEmulator(10, 3)
        t.feed("\u001b[2;3H\u001b[38;2;1;2;3mX\u001b[4mY")
        assertEquals("X", t.frame().lines[1][2].text)
        assertEquals(0x1010203, t.frame().lines[1][2].fg)
        assertTrue(t.frame().lines[1][3].underline)
    }
    @Test fun oscSplitIsNotPrintedOrExecuted() {
        val t = TerminalEmulator(20, 2)
        t.feed("safe\u001b]52;c;secret"); t.feed("\u001b"); t.feed("\\ok")
        assertEquals("safeok", t.frame().text().trim())
    }
    @Test fun alternateScreenRestoresPrimaryAndCursor() {
        val t = TerminalEmulator(10, 2)
        t.feed("shell\u001b[?1049hvim\u001b[?1049l!")
        assertEquals("shell!", t.frame().text().trim())
    }
    @Test fun scrollbackIsBoundedAndSnapshotResetDropsParserState() {
        val t = TerminalEmulator(10, 2, 3)
        repeat(30) { t.feed("$it\r\n") }
        assertEquals(5, t.frame().lines.size)
        t.feed("\u001b[31"); t.reset(); t.feed("fresh")
        assertEquals(2, t.frame().lines.size); assertEquals("fresh", t.frame().text().trim()); assertEquals(7, t.frame().lines[0][0].fg)
    }
    @Test fun resizeKeepsBoundsAndTextWhileImeShrinksViewport() {
        val t = TerminalEmulator(80, 24)
        t.feed("\u001b[24;1Hprompt")
        t.resize(40, 8)
        assertEquals(40, t.frame().cols); assertEquals(8, t.frame().rows)
        assertEquals(7, t.frame().cursorY); assertTrue(t.frame().text().contains("prompt"))
        t.resize(80, 24); assertTrue(t.frame().text().contains("prompt"))
    }
    @Test fun bracketedPasteAndApplicationCursorModesPersist() {
        val t = TerminalEmulator()
        t.feed("\u001b[?2004h\u001b[?1h"); assertTrue(t.bracketedPaste); assertTrue(t.applicationCursor)
        t.reset(); assertFalse(t.bracketedPaste); assertFalse(t.applicationCursor)
    }
    @Test fun scrollRegionDoesNotAddHistoryOrCorruptOutsideRows() {
        val t = TerminalEmulator(8, 4)
        t.feed("header\u001b[4;1Hfooter\u001b[2;3r\u001b[3;1Hx\n")
        assertEquals("header", t.frame().lines.first().joinToString("") { it.text }.trimEnd())
        assertEquals("footer", t.frame().lines.last().joinToString("") { it.text }.trimEnd())
        assertEquals(4, t.frame().lines.size)
    }
    @Test fun sgrAtRightMarginDoesNotCancelAutowrap() {
        val t = TerminalEmulator(3, 2)
        t.feed("abc\u001b[31md")
        assertEquals("abc\nd", t.frame().text())
        assertEquals(1, t.frame().lines[1][0].fg)
    }
    @Test fun terminalQueriesProduceBoundedRepliesWithoutRenderingThem() {
        val t = TerminalEmulator(10, 2)
        t.feed("abc\u001b[6n\u001b[5n")
        assertEquals(listOf("\u001b[1;4R", "\u001b[0n"), t.drainReplies())
        assertEquals("abc", t.frame().text().trim())
        repeat(100) { t.feed("\u001b[6n") }
        assertEquals(16, t.drainReplies().size)
    }
}
