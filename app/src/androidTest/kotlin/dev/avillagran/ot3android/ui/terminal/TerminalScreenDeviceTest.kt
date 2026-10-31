package dev.avillagran.ot3android.ui.terminal

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.avillagran.ot3android.core.terminal.TerminalEmulator
import dev.avillagran.ot3android.core.terminal.TerminalSession
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TerminalScreenDeviceTest {
    @get:Rule val rule = createComposeRule()
    @Test fun readOnlyCannotTypeOrExecuteAndCanClose() {
        var closed = 0
        rule.setContent { Ot3Theme { TerminalSurface("remote-host", "remote-thread", TerminalSession.View(TerminalEmulator().frame()),
            onSend = { error("read-only write") }, onPaste = { error("read-only paste") }, onArrow = {}, onSize = { _, _ -> }, onClose = { closed++ }) } }
        rule.onNodeWithText("remote-host · remote-thread").assertIsDisplayed()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithTag("terminal_run").assertIsNotEnabled()
        rule.onNodeWithTag("terminal_close").performClick()
        rule.runOnIdle { assertEquals(1, closed) }
    }
    @Test fun codeIsNeverSentBeforeConfirmation() {
        val sent = mutableListOf<String>()
        rule.setContent { Ot3Theme { TerminalSurface("host", "thread", TerminalSession.View(TerminalEmulator().frame(), "/worktree", true, true), "echo café",
            sent::add, sent::add, {}, { _, _ -> }, onClose = {}) } }
        rule.runOnIdle { assertTrue(sent.isEmpty()) }
        rule.onNodeWithTag("terminal_confirm").performClick()
        rule.runOnIdle { assertEquals(listOf("echo café\r"), sent) }
    }
    @Test fun disconnectedPageBuildsItsSavedStateOwnerAndCloses() {
        var closed = 0
        rule.setContent { Ot3Theme { TerminalPage("00000000-0000-0000-0000-000000000015", "fixture-thread", onClose = { closed++ }) } }
        rule.onNodeWithTag("terminal_screen").assertExists()
        rule.onNodeWithTag("terminal_run").assertIsNotEnabled()
        rule.onNodeWithTag("terminal_close").performClick()
        rule.runOnIdle { assertEquals(1, closed) }
    }
    @Test fun androidInputConnectionBuffersCompositionAndHonorsReadOnly() {
        val sent = mutableListOf<String>()
        rule.runOnIdle {
            val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            val view = TerminalView(context)
            view.writable = true; view.send = sent::add
            val input = view.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!
            input.setComposingText("に", 1); input.setComposingText("日本", 1)
            assertTrue(sent.isEmpty())
            input.commitText("日本🚀", 1); input.finishComposingText(); input.deleteSurroundingText(1, 0)
            assertEquals(listOf("日本🚀", "\u007f"), sent)
            view.writable = false; input.commitText("blocked", 1)
            assertEquals(2, sent.size); assertNull(view.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
        }
    }
}
