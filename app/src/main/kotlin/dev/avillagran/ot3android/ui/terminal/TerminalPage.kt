package dev.avillagran.ot3android.ui.terminal

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.appGraph
import dev.avillagran.ot3android.core.terminal.TerminalSession
import kotlinx.coroutines.channels.Channel

internal class TerminalViewModel(private val saved: androidx.lifecycle.SavedStateHandle) : ViewModel() {
    private var session: TerminalSession? = null
    fun session(threadId: String) = session ?: TerminalSession(threadId, resumeInitially = saved["terminalAttempted"] ?: false,
        onAttempt = { saved["terminalAttempted"] = true }).also { session = it }
}

@Composable
internal fun TerminalPage(environmentId: String, threadId: String, onClose: () -> Unit, command: String? = null) {
    val connections = LocalContext.current.appGraph.connectionManager
    val connection by remember(environmentId) { connections.connectionFlow(environmentId) }.collectAsStateWithLifecycle(initialValue = null)
    val owner: TerminalViewModel = viewModel(key = "terminal:$environmentId:$threadId")
    val session = owner.session(threadId)
    val state by session.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(session, connection, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            connection?.let { session.collect(it.transport) }
        }
    }
    val input = remember(session, connection) { Channel<Pair<Long, String>>(64) }
    DisposableEffect(input) { onDispose { input.close() } }
    LaunchedEffect(session, input) { for ((epoch, text) in input) session.write(text, epoch) }
    var dropped by remember(connection) { mutableStateOf(false) }
    val send: (String) -> Unit = { text ->
        if (!input.trySend(session.inputEpoch to text).isSuccess) dropped = true
    }
    val sizes = remember(session, connection) { Channel<Pair<Int, Int>>(Channel.CONFLATED) }
    DisposableEffect(sizes) { onDispose { sizes.close() } }
    LaunchedEffect(session, sizes) { for ((c, r) in sizes) session.resize(c, r) }
    androidx.activity.compose.BackHandler(onBack = onClose)
    TerminalSurface(environmentId, threadId, state, command,
        onSend = send, onPaste = { send(session.paste(it)) },
        onArrow = { send(session.arrow(it)) }, onSize = { c, r -> sizes.trySend(c to r) },
        dropped = dropped, onClose = onClose)
}

@Composable
internal fun TerminalSurface(
    environmentId: String, threadId: String, state: TerminalSession.View, command: String? = null,
    onSend: (String) -> Unit, onPaste: (String) -> Unit, onArrow: (Char) -> Unit,
    onSize: (Int, Int) -> Unit, dropped: Boolean = false, onClose: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val writable = state.connected && state.operate && !state.finished
    var ctrl by remember { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf(command) }
    var paste by remember { mutableStateOf<String?>(null) }
    var inputTooLarge by remember { mutableStateOf(false) }
    val send: (String) -> Unit = { text ->
        val data = if (ctrl) {
            ctrl = false
            text.firstOrNull()?.let { first ->
                (if (first.code in 64..127 || first == ' ') (first.code and 31).toChar() else first).toString() + text.drop(1)
            }.orEmpty()
        } else text
        onSend(data)
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().testTag("terminal_screen")) {
        Text(stringResource(R.string.terminal_title), style = MaterialTheme.typography.titleLarge)
        Text("$environmentId · $threadId", style = MaterialTheme.typography.labelSmall)
        SelectionContainer { Text(state.cwd, style = MaterialTheme.typography.labelSmall, maxLines = 2) }
        if (!state.operate) Text(stringResource(R.string.terminal_read_only))
        if (!state.connected && !state.finished && !state.error) Text(stringResource(R.string.terminal_disconnected))
        if (state.finished) Text(stringResource(R.string.terminal_exited, state.exit))
        if (state.error) Text(stringResource(R.string.terminal_error))
        if (inputTooLarge) Text(stringResource(R.string.terminal_input_too_large))
        if (state.uncertainWrite || dropped) Text(stringResource(R.string.terminal_uncertain))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(state.frame.text())) }, modifier = Modifier.testTag("terminal_copy")) { Text(stringResource(R.string.terminal_copy)) }
            TextButton(onClick = {
                clipboard.getText()?.text?.let { text ->
                    inputTooLarge = text.length > 65524 || text.toByteArray(Charsets.UTF_8).size > 65524
                    if (!inputTooLarge) paste = text
                }
            }, enabled = writable, modifier = Modifier.testTag("terminal_paste")) { Text(stringResource(R.string.terminal_paste)) }
            TextButton(onClick = { pending = "" }, enabled = writable, modifier = Modifier.testTag("terminal_run")) { Text(stringResource(R.string.terminal_run)) }
            TextButton(onClick = onClose, modifier = Modifier.testTag("terminal_close")) { Text(stringResource(R.string.terminal_close)) }
        }
        AndroidView(factory = { TerminalView(it) }, update = { view ->
            view.send = send; view.dimensions = onSize; view.writable = writable; view.frame = state.frame
            view.applicationCursor = state.applicationCursor
            view.contentDescription = state.frame.text()
            view.reportSize()
        }, modifier = Modifier.weight(1f).fillMaxWidth().testTag("terminal_output"))
        Row(Modifier.horizontalScroll(rememberScrollState()).testTag("terminal_keys")) {
            TextButton(onClick = { send("\u001b") }, enabled = writable) { Text("Esc") }
            FilterChip(selected = ctrl, onClick = { ctrl = !ctrl }, enabled = writable, label = { Text("Ctrl") })
            TextButton(onClick = { send("\t") }, enabled = writable) { Text("Tab") }
            listOf("↑" to 'A', "↓" to 'B', "→" to 'C', "←" to 'D').forEach { (label, final) -> TextButton(onClick = { onArrow(final) }, enabled = writable) { Text(label) } }
            TextButton(onClick = { send("\r") }, enabled = writable) { Text("↵") }
        }
    }
    pending?.let { value ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text(stringResource(R.string.terminal_run)) },
            text = { Column {
                Text(stringResource(R.string.terminal_confirm))
                Text("$environmentId · $threadId\n${state.cwd}")
                OutlinedTextField(value, onValueChange = { pending = it }, modifier = Modifier.testTag("terminal_command"))
            } },
            confirmButton = { TextButton(enabled = writable && value.isNotBlank(), onClick = {
                if (pending != null) {
                    pending = null // Consume confirmation before transport; never repeat after uncertain delivery.
                    val data = value.replace("\r\n", "\n") + "\r"
                    inputTooLarge = data.length > 65536 || data.toByteArray(Charsets.UTF_8).size > 65536
                    if (!inputTooLarge) onSend(data)
                }
            }, modifier = Modifier.testTag("terminal_confirm")) { Text(stringResource(R.string.terminal_execute)) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.terminal_cancel)) } })
    }
    paste?.let { value ->
        AlertDialog(onDismissRequest = { paste = null }, title = { Text(stringResource(R.string.terminal_paste)) },
            text = { Column { Text(stringResource(R.string.terminal_confirm)); SelectionContainer { Text(value, Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) } } },
            confirmButton = { TextButton(enabled = writable, onClick = { if (paste != null) { paste = null; onPaste(value) } }) { Text(stringResource(R.string.terminal_paste)) } },
            dismissButton = { TextButton(onClick = { paste = null }) { Text(stringResource(R.string.terminal_cancel)) } })
    }
}
