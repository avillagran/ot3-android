package dev.avillagran.ot3android.ui.terminal

import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.terminal.TerminalEmulator
import dev.avillagran.ot3android.core.terminal.TerminalSession
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class TerminalScreenTest {
    @get:Rule val rule = createComposeRule()
    private val sent = mutableListOf<String>()
    private fun surface(operate: Boolean = true, command: String? = null) {
        rule.setContent { Ot3Theme { TerminalSurface("host-a", "thread-a", TerminalSession.View(TerminalEmulator().frame(), "/remote/worktree", true, operate), command,
            sent::add, sent::add, {}, { _, _ -> }, onClose = {}) } }
    }
    @Test fun readOnlyHasOutputButNoInputOrExecution() {
        surface(false)
        rule.onNodeWithText("host-a · thread-a").assertExists()
        rule.onNodeWithText("Read-only terminal").assertExists()
        rule.onNodeWithTag("terminal_run").assertIsNotEnabled()
        rule.onNodeWithTag("terminal_paste").assertIsNotEnabled()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertTrue(sent.isEmpty())
    }
    @Test fun runCodeRequiresExplicitConfirmationAndConsumesItOnce() {
        surface(command = "printf 東京")
        assertTrue(sent.isEmpty())
        rule.onNodeWithText("host-a · thread-a\n/remote/worktree").assertExists()
        rule.onNodeWithTag("terminal_confirm").performClick()
        rule.runOnIdle { assertEquals(listOf("printf 東京\r"), sent) }
        rule.onNodeWithTag("terminal_confirm").assertDoesNotExist()
    }
    @Test fun cancelledCommandSendsNothing() {
        surface(command = "rm file")
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertTrue(sent.isEmpty()) }
    }
    @Test fun imeCompositionIsCommittedOnceAndDeleteIsBackspace() {
        val view = TerminalView(ApplicationProvider.getApplicationContext<Context>())
        view.writable = true; view.send = sent::add
        val input = view.onCreateInputConnection(EditorInfo())!!
        input.setComposingText("に", 1); input.setComposingText("日本", 1)
        assertTrue(sent.isEmpty())
        input.commitText("日本", 1); input.finishComposingText()
        input.deleteSurroundingText(1, 0)
        assertEquals(listOf("日本", "\u007f"), sent)
        view.writable = false
        assertNull(view.onCreateInputConnection(EditorInfo()))
        input.commitText("blocked", 1); assertEquals(2, sent.size)
    }
    @Test fun layoutReportsImeAndRotationDimensions() {
        val view = TerminalView(ApplicationProvider.getApplicationContext<Context>())
        val sizes = mutableListOf<Pair<Int, Int>>()
        view.dimensions = { c, r -> sizes += c to r }
        view.layout(0, 0, 800, 1200); view.layout(0, 0, 800, 500); view.layout(0, 0, 1200, 400)
        assertEquals(3, sizes.size)
        assertEquals(sizes[0].first, sizes[1].first)
        assertTrue(sizes[1].second < sizes[0].second)
        assertTrue(sizes[2].first > sizes[1].first)
    }
    @Test fun pasteConfirmationShowsAndSendsTheWholeBoundedTextOnce() {
        surface()
        val text = "a".repeat(4100) + "; echo tail"
        rule.runOnIdle {
            val context = ApplicationProvider.getApplicationContext<Context>()
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("fixture", text))
        }
        rule.onNodeWithTag("terminal_paste").performClick()
        rule.onNodeWithText(text).assertExists()
        rule.runOnIdle { assertTrue(sent.isEmpty()) }
        rule.onAllNodesWithText("Paste").onLast().performClick()
        rule.runOnIdle { assertEquals(listOf(text), sent) }
    }
    @Test fun oversizedPasteIsRejectedWithoutTruncatingOrSendingIt() {
        surface()
        rule.runOnIdle {
            val context = ApplicationProvider.getApplicationContext<Context>()
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("fixture", "x".repeat(65537)))
        }
        rule.onNodeWithTag("terminal_paste").performClick()
        rule.onNodeWithText("Input is too large. Send less than 64 KiB at a time.").assertExists()
        rule.runOnIdle { assertTrue(sent.isEmpty()) }
    }
    @Test fun rapidRepeatedConfirmationCallbackCannotDuplicateACommand() {
        surface(command = "echo once")
        val confirm = rule.onNodeWithTag("terminal_confirm").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        rule.runOnIdle { confirm(); confirm() }
        rule.runOnIdle { assertEquals(listOf("echo once\r"), sent) }
    }
}
