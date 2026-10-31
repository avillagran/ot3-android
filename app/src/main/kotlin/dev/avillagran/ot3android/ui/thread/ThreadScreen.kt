package dev.avillagran.ot3android.ui.thread

import kotlinx.coroutines.launch

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.SolidColor
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import dev.avillagran.ot3android.ui.kit.Ot3Spinner
import dev.avillagran.ot3android.ui.kit.rememberKitClock
import dev.avillagran.ot3android.ui.kit.Ot3IconButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.thread.PendingCommand
import dev.avillagran.ot3android.core.thread.RuntimeRequest
import dev.avillagran.ot3android.core.thread.ThreadConnection
import dev.avillagran.ot3android.core.thread.ThreadMessage
import dev.avillagran.ot3android.ui.common.ConnectionChip
import dev.avillagran.ot3android.ui.common.StatusLabel
import dev.avillagran.ot3android.ui.common.errorText

object ThreadScreenTags {
    const val ROOT = "thread_screen"
    const val TITLE = "thread_title"
    const val TIMELINE = "thread_timeline"
    const val LOADING = "thread_loading"
    const val EMPTY = "thread_empty"
    const val WORKING = "thread_working"
    const val INTERRUPT = "thread_interrupt"
    const val STALE = "thread_stale"
    const val STREAM_STATE = "thread_stream_state"
    const val STREAM_RETRY = "thread_stream_retry"
    const val COMPOSER = "thread_composer"
    const val SEND = "thread_send"
    const val SENDING = "thread_sending"
    fun message(id: String) = "thread_message_$id"
    fun streaming(id: String) = "thread_streaming_$id"
    fun request(id: String) = "thread_request_$id"
    fun unconfirmed(id: String) = "thread_unconfirmed_$id"
    fun resend(id: String) = "thread_resend_$id"
}

@Composable
fun ThreadScreen(viewModel: ThreadViewModel, onBack: () -> Unit, modifier: Modifier = Modifier, quotaStrip: @Composable () -> Unit = {}, onGit: (() -> Unit)? = null, onChanges: (() -> Unit)? = null, onOpenThread: ((String) -> Unit)? = null, onOpenReferenceThread: ((String, String) -> Unit)? = null, onTerminal: (() -> Unit)? = null, onRunCode: ((String) -> Unit)? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val checkpoint by viewModel.checkpoint.collectAsStateWithLifecycle()
    val checkpointMessages by viewModel.checkpointMessages.collectAsStateWithLifecycle()
    if (checkpoint.open) androidx.compose.ui.window.Dialog(onDismissRequest = viewModel::closeCheckpoint,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxWidth().fillMaxHeight(.9f).safeDrawingPadding()) {
            dev.avillagran.ot3android.ui.git.CheckpointRollbackSurface(
                checkpoint.preview?.let { org.json.JSONObject(it) }, checkpoint.record?.let { org.json.JSONObject(it) },
                state.connected && !checkpoint.busy, canRestoreFiles = checkpoint.canRestoreFiles,
                recoveryPrepared = !checkpoint.busy && !checkpoint.failed,
                onConfirm = viewModel::confirmCheckpoint, onRefresh = viewModel::refreshCheckpoint,
                onRecover = viewModel::recoverCheckpoint, onClose = viewModel::closeCheckpoint,
                errorText = if (checkpoint.failed) stringResource(R.string.ot3_git_checkpoint_unavailable) else null)
        }
    }
    val imageConnection by viewModel.imageConnection.collectAsStateWithLifecycle()
    val diffSettings by androidx.compose.ui.platform.LocalContext.current.devGraph().diffReviewSettings.collectAsStateWithLifecycle()
    val graph = androidx.compose.ui.platform.LocalContext.current.devGraph()
    val clientSettings by graph.generalClientPreferences.settings.collectAsStateWithLifecycle(initialValue = dev.avillagran.ot3android.core.settings.GeneralClientPreferences.defaults)
    val mathRendering by androidx.compose.ui.platform.LocalContext.current.devGraph().mathRenderingEnabled.collectAsStateWithLifecycle()
    ReaderReferenceHost(imageConnection, state.view.threadId, viewModel.localEnvironmentId, graph,
        { environment, thread -> if (onOpenReferenceThread != null) onOpenReferenceThread(environment, thread) else if (environment == viewModel.localEnvironmentId) onOpenThread?.invoke(thread) }) {
    androidx.compose.runtime.CompositionLocalProvider(dev.avillagran.ot3android.ui.thread.rendering.LocalCodeActions provides dev.avillagran.ot3android.ui.thread.rendering.CodeActions(run = onRunCode?.let { run -> { block: org.json.JSONObject -> run(block.getString("command")) } }), LocalRegistryTransport provides imageConnection?.transport, LocalLiveInspector provides LiveInspector(imageConnection) { viewModel.isImageConnectionCurrent(imageConnection) }, LocalReaderCheckpoint provides ReaderCheckpoint(checkpointMessages, viewModel::previewCheckpoint), LocalReaderTimeFormat provides clientSettings.optString("timestampFormat", "locale"), LocalReaderReview provides if (viewModel.durableComposerAvailable) ReaderReviewSettings(diffSettings, graph::setDiffReviewSettings) else ReaderReviewSettings(), LocalReaderMath provides mathRendering, LocalReaderStreaming provides state.view.working, LocalReaderImages provides ReaderImages(imageConnection, { viewModel.isImageConnectionCurrent(imageConnection) }, owner = viewModel.imageOwner(imageConnection), read = viewModel::readImage)) {
        ThreadContent(
            state = state,
            editorIdentity = viewModel.editorIdentity,
            quotaStrip = quotaStrip,
            onDraftChange = viewModel::onDraftChange,
            onDraftChangeTagged = { text, token -> viewModel.onDraftChange(text, token) },
            onReadingPosition = viewModel::saveReadingPosition,
            onSend = viewModel::send,
            onInterrupt = viewModel::interrupt,
            onResend = viewModel::retry,
            onDiscard = viewModel::discard,
            onRetryStream = viewModel::retryStream,
            onDismissNotice = viewModel::dismissNotice,
            onBack = onBack,
            modifier = modifier,
            onInspect = viewModel::inspect,
            onReadDiff = viewModel::readDiff,
            onQueueCommand = viewModel::queueCommand,
            onEditQueued = viewModel::editQueued,
            onCancelQueueEdit = viewModel::cancelQueueEdit,
            onRefreshModels = viewModel::refreshModels,
            onSelectModel = viewModel::selectModel,
            onSelectModelOption = viewModel::selectModelOption,
            onFavoriteModel = viewModel::favoriteModel,
            onApprovalDecision = viewModel::respondApproval,
            onQuestionResponse = viewModel::respondQuestion,
            onRuntimeMode = viewModel::selectRuntimeMode,
            composerSlot = if (viewModel.durableComposerAvailable) ({ ConnectedComposer(viewModel, state) }) else null,
            onTerminal = onTerminal,
            onGit = onGit,
            onChanges = onChanges,
            onOpenThread = onOpenThread,
            transcriptSecrets = viewModel.transcriptSecrets(imageConnection),
        )
    }
    }
}

/** Stateless thread screen: top bar, banners, the timeline (newest at the bottom) and the composer. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ThreadContent(
    state: ThreadUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    onResend: (commandId: String) -> Unit,
    onDiscard: (commandId: String) -> Unit,
    onRetryStream: () -> Unit,
    onDismissNotice: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onInspect: suspend (org.json.JSONObject) -> org.json.JSONObject? = { null },
    onReadDiff: suspend (org.json.JSONObject, Boolean) -> org.json.JSONObject? = { _, _ -> null },
    onQueueCommand: (org.json.JSONObject) -> Unit = {},
    onEditQueued: (org.json.JSONObject) -> Unit = {},
    onCancelQueueEdit: () -> Unit = {},
    onRefreshModels: () -> Unit = {},
    onSelectModel: (org.json.JSONObject) -> Unit = {},
    onSelectModelOption: (String, Any) -> Unit = { _, _ -> },
    onFavoriteModel: (org.json.JSONObject, Boolean) -> Unit = { _, _ -> },
    onApprovalDecision: (String, String) -> Unit = { _, _ -> },
    onQuestionResponse: (String, String, org.json.JSONObject) -> Unit = { _, _, _ -> },
    onRuntimeMode: (String) -> Unit = {},
    quotaStrip: @Composable () -> Unit = {},
    composerSlot: (@Composable () -> Unit)? = null,
    onGit: (() -> Unit)? = null,
    onChanges: (() -> Unit)? = null,
    onOpenThread: ((String) -> Unit)? = null,
    transcriptSecrets: dev.avillagran.ot3android.ui.thread.reader.TranscriptSecrets? = null,
    editorIdentity: String = state.view.threadId,
    onDraftChangeTagged: ((String, String) -> Unit)? = null,
    onReadingPosition: (dev.avillagran.ot3android.core.data.prefs.ReadingPosition) -> Unit = {},
    onTerminal: (() -> Unit)? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    val notice = state.notice
    val noticeText = notice?.let { noticeText(it) }
    val resendLabel = stringResource(R.string.thread_resend)
    LaunchedEffect(notice) {
        if (notice == null || noticeText == null) return@LaunchedEffect
        val unconfirmed = notice as? ThreadNotice.Unconfirmed
        val result = snackbar.showSnackbar(
            message = noticeText,
            actionLabel = if (unconfirmed != null) resendLabel else null,
            withDismissAction = true,
            duration = if (unconfirmed != null) SnackbarDuration.Indefinite else SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed && unconfirmed != null) onResend(unconfirmed.commandId)
        onDismissNotice()
    }

    var expanded by androidx.compose.runtime.saveable.rememberSaveable(editorIdentity) { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val availableEditorHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp -
        with(density) { WindowInsets.ime.getBottom(this).toDp() }
    // Leave room for the reader when the IME occupies most of a landscape window.
    // The same options remain available from the header overflow.
    val compactComposer = availableEditorHeight < 420.dp ||
        (androidx.compose.foundation.layout.WindowInsets.isImeVisible &&
            androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
    val compactEditorToolbar = expanded && availableEditorHeight < 220.dp
    var findRequest by remember { mutableIntStateOf(0) }
    val editKey = state.queueEdit?.let { org.json.JSONObject(it).getString("runId") }
    val editors = androidx.compose.runtime.saveable.rememberSaveable(editorIdentity, saver = ComposerEditors.StateSaver) { ComposerEditors() }
    val editor = editors.editor(editKey, state.draft)
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(state.draft, state.draftToken, editor) { editor.synchronize(state.draft, state.draftToken) }
    val hasQuestion = (state.view.timeline?.optJSONArray("questions")?.length() ?: 0) > 0
    LaunchedEffect(hasQuestion) { if (hasQuestion) expanded = false }
    fun toggleEditor() {
        editor.newSurface()
        if (expanded) { editor.releaseFocus(); keyboard?.hide(); focus.clearFocus() }
        else editor.requestFocus = true
        expanded = !expanded
    }
    val edit: (String, String) -> Unit = { text, token -> if (onDraftChangeTagged != null) onDraftChangeTagged(text, token) else onDraftChange(text) }
    var followLatestRequest by androidx.compose.runtime.saveable.rememberSaveable(editorIdentity) { mutableIntStateOf(0) }
    fun submit() { if (state.canSend && !state.sending) { editor.submitted(); followLatestRequest++; onSend() } }
    androidx.activity.compose.BackHandler(enabled = expanded) { toggleEditor() }
    val timelineState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val toolbar: @Composable () -> Unit = {
        ComposerModels(state.modelCatalog, state.modelSelection, state.modelsLoading,
            state.connected && !state.sending && state.queueEdit == null,
            onRefreshModels, onSelectModel, onSelectModelOption, onFavoriteModel) {
            ComposerPermissions(state.modelCatalog, state.modelSelection, state.view.runtimeMode,
                state.connected && !state.view.stale && !state.sending && state.queueEdit == null && state.unconfirmed.isEmpty(), onRefreshModels, onRuntimeMode)
        }
    }
    Scaffold(
        modifier = modifier.testTag(ThreadScreenTags.ROOT),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ot3Theme.colors.background),
                title = {
                    Column {
                        Text(
                            if (expanded) stringResource(R.string.ot3_composer_edit) else state.title ?: stringResource(R.string.sidebar_untitled),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag(ThreadScreenTags.TITLE),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ConnectionChip(state.connection)
                            if (state.view.working) {
                                Spacer(Modifier.width(8.dp))
                                WorkingLabel()
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (expanded) toggleEditor() else {
                        editor.releaseFocus()
                        focus.clearFocus()
                        keyboard?.hide()
                        onBack()
                    } }, modifier = Modifier.testTag("composer_back")) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(if (expanded) R.string.ot3_composer_back else R.string.action_back))
                    }
                },
                actions = {
                    if (!expanded) Ot3IconButton("search", stringResource(R.string.transcript_find), { findRequest++ }, modifier = Modifier.testTag("transcript_find"))
                    if (expanded) Ot3IconButton("arrow-up", stringResource(R.string.thread_send), ::submit, modifier = Modifier.testTag(ThreadScreenTags.SEND), enabled = state.canSend && !state.sending, tint = Ot3Theme.colors.accent)
                    var quotaMenu by remember { mutableStateOf(false) }
                    var showComposerOptions by remember { mutableStateOf(false) }
                    Box {
                        Ot3IconButton("ellipsis-vertical", stringResource(R.string.home_more), {
                            showComposerOptions = expanded || compactComposer
                            if (compactComposer && !expanded) { editor.releaseFocus(); focus.clearFocus(); keyboard?.hide() }
                            quotaMenu = true
                        }, modifier = Modifier.testTag("thread_overflow"))
                        androidx.compose.material3.DropdownMenu(expanded = quotaMenu, onDismissRequest = { quotaMenu = false }) {
                            if (showComposerOptions) Box(Modifier.width(320.dp).testTag("composer_overflow_options")) { toolbar() }
                            if (onTerminal != null) androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(R.string.terminal_title)) },
                                onClick = { quotaMenu = false; onTerminal() })
                            if (onGit != null) androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(R.string.ot3_git_title)) },
                                onClick = { quotaMenu = false; onGit() })
                            if (onChanges != null) androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(R.string.ot3_git_review)) },
                                onClick = { quotaMenu = false; onChanges() })
                            dev.avillagran.ot3android.ui.usage.QuotaToggleMenuItem { quotaMenu = false }
                        }
                    }

                    if (state.view.working) {
                        IconButton(
                            onClick = onInterrupt,
                            enabled = state.canInterrupt,
                            modifier = Modifier.testTag(ThreadScreenTags.INTERRUPT),
                        ) {
                            Icon(painterResource(R.drawable.ic_stop), stringResource(R.string.thread_interrupt))
                        }
                    }
                    if (dev.avillagran.ot3android.ui.navigation.LocalWorkspaceSidebarAction.current) {
                        Spacer(Modifier.width(48.dp))
                    }
                },
            )
        },
        bottomBar = {
            if (!expanded) Column(Modifier.navigationBarsPadding().imePadding()
                .then(if (compactComposer) Modifier.heightIn(max = availableEditorHeight * .30f)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()) else Modifier)) {
                ThreadQueue(
                    state.view.timeline?.optJSONObject("queue"),
                    state.connected && !state.view.stale && !state.sending && state.unconfirmed.isEmpty(),
                    onQueueCommand,
                    onEditQueued,
                    editingRunId = state.queueEdit?.let { org.json.JSONObject(it).optString("runId") },
                    dragOwner = state.view.threadId to state.queueConnection,
                    feedback = {
                        state.notice?.let { Text(noticeText(it), Modifier.padding(horizontal = 16.dp), color = Ot3Theme.colors.failed) }
                        state.unconfirmed.forEach { UnconfirmedRow(it, state.connected, onResend, onDiscard) }
                    },
                )
                if (state.queueEdit != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.queue_editing), Modifier.weight(1f), color = Ot3Theme.colors.dim)
                        TextButton(onClick = onCancelQueueEdit, enabled = !state.queueBusy) {
                            Text(stringResource(R.string.queue_cancel_edit))
                        }
                    }
                }
                ThreadTasks(state.view.timeline?.optJSONObject("tasks"))
                ApprovalCard(state.view.timeline?.optJSONArray("approvals"),
                    state.connected && !state.view.stale && !state.sending && state.unconfirmed.isEmpty(),
                    state.respondingRequest, state.answeredRequests, onApprovalDecision)
                QuestionCard(state.view.timeline?.optJSONArray("questions"),
                    state.connected && !state.view.stale && !state.sending && state.unconfirmed.isEmpty(),
                    state.respondingRequest, state.answeredRequests, onResponse = onQuestionResponse)
                if (!hasQuestion) {
                    if (composerSlot != null && state.queueEdit == null) { if (!compactComposer) toolbar(); composerSlot() }
                    else ComposerEditor(editor, false, state.canSend, state.sending, edit, ::submit, ::toggleEditor, toolbar = { if (!compactComposer) toolbar() }, compact = compactComposer)
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).then(if (expanded) Modifier.imePadding() else Modifier)) {
            if (expanded) androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val feedbackHeight = (maxHeight * .25f).coerceAtMost(120.dp)
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().heightIn(max = feedbackHeight).then(Modifier.verticalScroll(rememberScrollState()))) {
                        StreamState(state.view.connectionState, state.view.stale, onRetryStream)
                        state.unconfirmed.forEach { UnconfirmedRow(it, state.connected, onResend, onDiscard) }
                        if (state.queueEdit != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.queue_editing), Modifier.weight(1f), color = Ot3Theme.colors.dim)
                            TextButton(onClick = onCancelQueueEdit, enabled = !state.queueBusy) { Text(stringResource(R.string.queue_cancel_edit)) }
                        }
                        state.notice?.let { if (it is ThreadNotice.Failed) Text(noticeText(it), color = Ot3Theme.colors.failed, modifier = Modifier.padding(horizontal = 12.dp)) }
                        ApprovalCard(state.view.timeline?.optJSONArray("approvals"), state.connected && !state.view.stale && !state.sending && state.unconfirmed.isEmpty(), state.respondingRequest, state.answeredRequests, onApprovalDecision)
                    }
                    ComposerEditor(editor, true, state.canSend, state.sending, edit, ::submit, ::toggleEditor,
                        Modifier.weight(1f).testTag("composer_expanded"), toolbar = { if (!compactEditorToolbar) toolbar() })
                }
            } else {
                quotaStrip()
                StreamState(state.view.connectionState, state.view.stale, onRetryStream)
                state.view.pendingRequests.filter { state.view.timeline?.has(if (it.kind == "user_input") "questions" else "approvals") != true }
                    .forEach { PendingRequestBanner(it) }
                state.unconfirmed.forEach { UnconfirmedRow(it, state.connected, onResend, onDiscard) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        !state.view.loaded -> Ot3Spinner(Modifier.align(Alignment.Center).testTag(ThreadScreenTags.LOADING), size = 24.dp)
                        (state.view.timeline?.optJSONArray("timeline")?.length() ?: 0) == 0 && state.view.messages.isEmpty() -> Text(stringResource(R.string.thread_empty), modifier = Modifier.align(Alignment.Center).testTag(ThreadScreenTags.EMPTY))
                        else -> if (state.readingPositionLoaded) timelineState.SaveableStateProvider(editorIdentity) {
                            TimelineReader(state.view.timeline, state.view.messages, onInspect, onReadDiff, state.readingPosition, onReadingPosition, followLatestRequest,
                                actions = dev.avillagran.ot3android.ui.thread.reader.TranscriptActions(
                                    secrets = transcriptSecrets,
                                    openThread = onOpenThread,
                                    quote = if (state.queueEdit == null && !state.sending) ({ text ->
                                        val quoted = text.lineSequence().joinToString("\n") { "> $it" }
                                        val draft = editor.value.text
                                        val appended = if (draft.isBlank()) "$quoted\n\n" else "$draft\n\n$quoted\n\n"
                                        edit(appended, java.util.UUID.randomUUID().toString())
                                    }) else null,
                                ), findRequest = findRequest)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkingLabel() {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(ThreadScreenTags.WORKING)) {
        Ot3Spinner(size = 12.dp, tint = Ot3Theme.colors.working)
        Spacer(Modifier.width(4.dp))
        StatusLabel(stringResource(R.string.status_working), colors.tertiaryContainer, colors.onTertiaryContainer)
    }
}

@Composable
private fun StreamState(connection: ThreadConnection, stale: Boolean, onRetry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val text = when (connection) {
        // The handle's stream slots are taken (other open threads, or the sidebar): say so.
        is ThreadConnection.Retrying -> if (connection.error == "subscription_limit") {
            stringResource(R.string.thread_stream_subscription_limit)
        } else {
            stringResource(R.string.thread_stream_retrying, errorText(connection.error))
        }
        is ThreadConnection.Failed -> stringResource(R.string.thread_stream_failed, errorText(connection.error))
        else -> null
    }
    if (text == null && !stale) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        if (stale) {
            StatusLabel(
                stringResource(if (connection == ThreadConnection.Waiting) R.string.thread_cache_offline else R.string.thread_stale),
                colors.secondaryContainer,
                colors.onSecondaryContainer,
                Modifier.testTag(ThreadScreenTags.STALE),
            )
            Spacer(Modifier.width(8.dp))
        }
        if (text != null) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).testTag(ThreadScreenTags.STREAM_STATE),
            )
            TextButton(onClick = onRetry, modifier = Modifier.testTag(ThreadScreenTags.STREAM_RETRY)) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
private fun PendingRequestBanner(request: RuntimeRequest) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.tertiaryContainer,
        contentColor = colors.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().testTag(ThreadScreenTags.request(request.id)),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            val title = if (request.kind == "user_input") {
                stringResource(R.string.thread_request_answer)
            } else {
                stringResource(R.string.thread_request_approval, request.kind ?: "?")
            }
            Text(title, style = MaterialTheme.typography.titleSmall)
            request.prompt?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            Text(stringResource(R.string.thread_request_elsewhere), style = MaterialTheme.typography.bodySmall)
        }
    }
    HorizontalDivider()
}

@Composable
private fun UnconfirmedRow(
    command: PendingCommand,
    connected: Boolean,
    onResend: (String) -> Unit,
    onDiscard: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.errorContainer,
        contentColor = colors.onErrorContainer,
        modifier = Modifier.fillMaxWidth().testTag(ThreadScreenTags.unconfirmed(command.commandId)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
            Text(
                command.text?.let { stringResource(R.string.thread_unconfirmed, it) }
                    ?: stringResource(R.string.thread_unconfirmed_plain),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onResend(command.commandId) },
                enabled = connected,
                modifier = Modifier.testTag(ThreadScreenTags.resend(command.commandId)),
            ) { Text(stringResource(R.string.thread_resend)) }
            TextButton(onClick = { onDiscard(command.commandId) }) { Text(stringResource(R.string.thread_discard)) }
        }
    }
}

/** The longest rejection reason a snackbar shows; the server's text can be a whole schema error. */
private const val REASON_MAX = 200

private fun String.truncated(max: Int): String = if (length <= max) this else take(max - 1).trimEnd() + "…"

/** Snackbar text for a notice; codes come from the lib and never carry credentials. */
@Composable
private fun noticeText(notice: ThreadNotice): String = when (notice) {
    is ThreadNotice.Unconfirmed -> stringResource(R.string.thread_notice_unknown)
    is ThreadNotice.Failed -> when (notice.code) {
        "rpc_outcome_unknown" -> stringResource(R.string.thread_notice_unknown)
        "rpc_rejected" -> notice.reason?.let { stringResource(R.string.thread_notice_rejected, it.truncated(REASON_MAX)) }
            ?: stringResource(R.string.thread_notice_rejected_plain)
        "write_not_authorized" -> stringResource(R.string.thread_notice_scope, notice.requiredScope ?: "orchestration:operate")
        "not_connected" -> stringResource(R.string.thread_notice_not_connected)
        "command_pending" -> stringResource(R.string.queue_command_pending)
        else -> errorText(notice.code)
    }
}

@Composable
private fun ConnectedComposer(model: ThreadViewModel, state: ThreadUiState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = state.composerScope
    if (scope == null) { ConnectionChip(state.connection); return }
    val session = remember(scope) { dev.avillagran.ot3android.core.composer.ComposerSession(
        dev.avillagran.ot3android.core.composer.ComposerSnapshot(scope, text = state.draft,
            selection = dev.avillagran.ot3android.core.composer.ComposerSelection(state.draft.length, state.draft.length))) }
    var snapshot by remember(scope) { mutableStateOf(session.snapshot) }
    var history by remember(scope) { mutableStateOf<Pair<dev.avillagran.ot3android.core.composer.ComposerSnapshot, List<String>>?>(null) }
    var completionRead by remember(scope) { mutableStateOf<Triple<dev.avillagran.ot3android.core.composer.ComposerSnapshot, org.json.JSONObject, org.json.JSONArray>?>(null) }
    var quickReplies by remember(scope) { mutableStateOf(false) }
    val graph = context.devGraph()
    val worker = rememberCoroutineScope()
    var sharedOpen by remember(scope) { mutableStateOf(false) }
    val draftPreferences = remember { context.getSharedPreferences("shared-drafts", android.content.Context.MODE_PRIVATE) }
    var authority by remember { mutableStateOf(draftPreferences.getString("authority", null)) }
    DisposableEffect(draftPreferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "authority") authority = prefs.getString("authority", null)
        }
        draftPreferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { draftPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SideEffect { graph.activateDraftAuthority(authority) }
    val synchronizer = remember(authority, scope) { authority?.takeIf { it.isNotBlank() && scope.queueMessage == null }?.let { graph.sharedDraftWorker(it, model.localEnvironmentId, scope) } }
    LaunchedEffect(authority) { sharedOpen = false }
    val shared = synchronizer?.repository
    SideEffect { model.sharedDraftRepository = shared; model.sharedDraftSynchronizer = synchronizer }

    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val client by graph.generalClientPreferences.settings.collectAsStateWithLifecycle(initialValue = dev.avillagran.ot3android.core.settings.GeneralClientPreferences.defaults)
    LaunchedEffect(state.draft) {
        if (session.snapshot.text != state.draft) session.replace(session.snapshot, state.draft,
            dev.avillagran.ot3android.core.composer.ComposerSelection(state.draft.length, state.draft.length))
        snapshot = session.snapshot
    }
    val current by androidx.compose.runtime.rememberUpdatedState(state)
    fun publish() { snapshot = session.snapshot; model.onDraftChange(snapshot.text) }
    val unavailable = !state.connected || state.view.stale || state.sending || state.unconfirmed.isNotEmpty()
    val attachments by model.attachmentDraft.collectAsStateWithLifecycle()
    val pending by model.attachmentPending.collectAsStateWithLifecycle()
    val storageError by model.attachmentStorageError.collectAsStateWithLifecycle()
    if (storageError) Text(stringResource(R.string.composer_uncertain), color = MaterialTheme.colorScheme.error)
    var draftOptions by remember(scope) { mutableStateOf<org.json.JSONObject?>(null) }
    val displayed = snapshot.copy(attachments = attachments, loading = unavailable || storageError, sending = state.sending, uncertainCommand = pending)
    SideEffect { synchronizer?.pause(snapshot.composing || !state.composer.loaded || state.sending || pending != null || attachments.size > 16 || attachments.any { it.status != dev.avillagran.ot3android.core.composer.UploadStatus.Ready }) }
    val sharedFailed by (synchronizer?.failed ?: kotlinx.coroutines.flow.flowOf(false)).collectAsStateWithLifecycle(initialValue = false)
    val sharedConflicts by (synchronizer?.conflicts ?: kotlinx.coroutines.flow.flowOf(0)).collectAsStateWithLifecycle(initialValue = 0)
    var sharedBound by remember(synchronizer) { mutableStateOf(false) }
    var sharedAdoptionFailed by remember(synchronizer) { mutableStateOf(false) }
    if (sharedFailed || sharedAdoptionFailed || (shared != null && attachments.size > 16)) Text(stringResource(R.string.shared_draft_failed), color = MaterialTheme.colorScheme.error)
    if (sharedConflicts > 0) Text(stringResource(R.string.shared_draft_conflict), color = MaterialTheme.colorScheme.error)
    LaunchedEffect(synchronizer, snapshot.text, snapshot.selection, snapshot.composing, attachments, state.modelSelection, state.composer.loaded, state.sending, pending) {
        if (snapshot.text == state.draft && state.composer.loaded && synchronizer != null && !snapshot.composing && pending == null && !state.sending && attachments.size <= 16 && attachments.all { it.status == dev.avillagran.ot3android.core.composer.UploadStatus.Ready && it.descriptor != null }) {
            val draftPayload = org.json.JSONObject().put("schema", 1).put("text", snapshot.text)
                .put("attachments", org.json.JSONArray(attachments.map { file ->
                    val descriptor = org.json.JSONObject(file.descriptor!!)
                    org.json.JSONObject().put("id", descriptor.getString("id")).put("name", descriptor.getString("name")).put("sourceEnvironment", scope.environment)
                })).put("selection", org.json.JSONArray().put(snapshot.selection.start).put(snapshot.selection.end))
                .apply { state.modelSelection?.let { raw ->
                    val source = org.json.JSONObject(raw)
                    val safe = org.json.JSONObject()
                    source.optString("instanceId").takeIf { it.isNotEmpty() }?.let { safe.put("providerId", it) }
                    source.optString("model").takeIf { it.isNotEmpty() }?.let { safe.put("modelId", it) }
                    listOf("effort", "reasoningEffort", "speedMode").forEach { key ->
                        source.opt(key)?.takeIf { it is String || it is Boolean || it is Number }?.let { safe.put(key, it) }
                    }
                    source.optJSONArray("options")?.let { options ->
                        for (i in 0 until options.length()) options.getJSONObject(i).let { option ->
                            val key = option.optString("id")
                            if (key in setOf("effort", "reasoningEffort", "speedMode")) option.opt("value")?.takeIf { it is String || it is Boolean || it is Number }?.let { safe.put(key, it) }
                        }
                    }
                    put("modelSelection", safe)
                } }
                .put("preferences", org.json.JSONObject(draftOptions?.toString() ?: "{}").put("interactionMode", draftOptions?.optString("interactionMode")?.takeIf(String::isNotBlank) ?: if (snapshot.interaction == dev.avillagran.ot3android.core.composer.ComposerInteraction.Plan) "plan" else "default")
                    .put("deliveryMode", draftOptions?.optString("deliveryMode")?.takeIf { it in setOf("queue", "steer", "auto") } ?: client.optString("followUpBehavior", "queue")))
            if (sharedBound) synchronizer.edit(draftPayload) else { sharedBound = true; synchronizer.bind(draftPayload) }
        }
    }
    val currentPending by rememberUpdatedState(pending)
    LaunchedEffect(synchronizer) {
        synchronizer?.remote?.collect { update ->
            if (update != null && synchronizer.isCurrent(update) && !session.snapshot.composing && !current.sending && currentPending == null) {
                val doc = org.json.JSONObject(update.document)
                val text = if (doc.optBoolean("deleted")) "" else doc.getJSONObject("payload").getString("text")
                val expected = session.snapshot
                val payload = if (doc.optBoolean("deleted")) org.json.JSONObject().put("text", "").put("attachments", org.json.JSONArray()) else doc.getJSONObject("payload")
                try {
                    val changed = text != expected.text
                    val adopted = model.adoptSharedAttachments(payload, expected.text) {
                        synchronizer.isCurrent(update) && session.snapshot.version == expected.version &&
                            (!changed || session.replace(expected, text, dev.avillagran.ot3android.core.composer.ComposerSelection(text.length, text.length)))
                    }
                    if (adopted) {
                        draftOptions = payload.optJSONObject("preferences")
                        model.adoptSharedModel(payload)
                        if (changed) publish()
                        synchronizer.adopted(update)
                        sharedAdoptionFailed = false
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { sharedAdoptionFailed = true }

            }
        }
    }
    if (pending != null) {
        Row {
            TextButton(onClick = { model.retry(pending!!) }, enabled = state.connected && !state.sending) { Text(stringResource(R.string.thread_resend)) }
            TextButton(onClick = { model.discard(pending!!) }, enabled = !state.sending) { Text(stringResource(R.string.thread_discard)) }
        }
    }
    if (state.view.timeline?.optBoolean("hasMoreHistory") == true) TextButton(onClick = model::loadOlderHistory, enabled = state.connected) { Text(stringResource(R.string.ot3_pr_more)) }
    LaunchedEffect(snapshot.version, state.connected) {
        completionRead = null
        val captured = session.snapshot
        if (captured.transformable && current.connected) {
            try {
                val result = model.composerCompletions(captured)
                if (result != null && session.snapshot.version == captured.version) completionRead = Triple(captured, result.first, result.second)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { completionRead = null }
        }
    }
    dev.avillagran.ot3android.ui.thread.composer.ComposerParity(displayed,
        completions = completionRead?.third?.let { rows -> (0 until rows.length()).map { i -> rows.getJSONObject(i).let { row ->
            dev.avillagran.ot3android.core.composer.ComposerCompletion(i.toString(), row.getString("label"), row.optString("description")) } } }.orEmpty(),
        capabilities = buildSet {
            add(dev.avillagran.ot3android.ui.thread.composer.ComposerAction.Attach)
            if (state.view.working) add(dev.avillagran.ot3android.ui.thread.composer.ComposerAction.OppositeOnce)
            if (shared != null && scope.queueMessage == null) add(dev.avillagran.ot3android.ui.thread.composer.ComposerAction.ProjectDrafts)
            add(dev.avillagran.ot3android.ui.thread.composer.ComposerAction.History)
            add(dev.avillagran.ot3android.ui.thread.composer.ComposerAction.ContinueList)
            add(dev.avillagran.ot3android.ui.thread.composer.ComposerAction.Undo)
            add(dev.avillagran.ot3android.ui.thread.composer.ComposerAction.Redo)
            if (client.optBoolean("quickRepliesButton", true)) add(dev.avillagran.ot3android.ui.thread.composer.ComposerAction.QuickReplies)
        }, onEdit = { expected, text, selection, composing ->
            if (!unavailable && expected.scope == session.snapshot.scope) {
                session.edit(text, selection, composing); publish()
            }
        }, onRequest = { request ->
            val sameContent = request.expected.scope == session.snapshot.scope && request.expected.text == session.snapshot.text &&
                request.expected.attachments == attachments && !session.snapshot.composing
            val scopeOnly = request is dev.avillagran.ot3android.ui.thread.composer.ComposerRequest.ImportUri ||
                request is dev.avillagran.ot3android.ui.thread.composer.ComposerRequest.RemoveAttachment
            // Un cambio de foco puede publicar selección antes del clic; no cambia el contenido enviado.
            if (!unavailable && request.expected.scope == session.snapshot.scope && (sameContent || scopeOnly)) {
                when (request) {
                    is dev.avillagran.ot3android.ui.thread.composer.ComposerRequest.Action -> when(request.action) {
                        dev.avillagran.ot3android.ui.thread.composer.ComposerAction.ProjectDrafts -> sharedOpen = true
                        dev.avillagran.ot3android.ui.thread.composer.ComposerAction.History -> {
                            val captured = session.snapshot
                            worker.launch {
                                val choices = model.recallableDrafts()
                                if (session.snapshot.version == captured.version) history = captured to choices
                            }
                        }
                        dev.avillagran.ot3android.ui.thread.composer.ComposerAction.ContinueList -> {
                            val captured = session.snapshot
                            worker.launch {
                                val result = dev.avillagran.ot3android.core.composer.NativeComposer().transform(org.json.JSONObject()
                                    .put("action", "list").put("text", captured.text).put("cursor", captured.selection.end).put("composing", captured.composing))
                                if (result.optBoolean("changed")) {
                                    val cursor = result.getInt("cursor")
                                    session.replace(captured, result.getString("text"), dev.avillagran.ot3android.core.composer.ComposerSelection(cursor, cursor)); publish()
                                }
                            }
                        }
                        dev.avillagran.ot3android.ui.thread.composer.ComposerAction.Undo -> { session.undo(); publish() }
                        dev.avillagran.ot3android.ui.thread.composer.ComposerAction.Redo -> { session.undo(true); publish() }
                        dev.avillagran.ot3android.ui.thread.composer.ComposerAction.QuickReplies -> quickReplies = true
                        else -> Unit
                    }
                    is dev.avillagran.ot3android.ui.thread.composer.ComposerRequest.Complete -> completionRead?.let { (captured, trigger, rows) ->
                        val row = request.id.toIntOrNull()?.let { rows.optJSONObject(it) }
                        if (row != null && captured.version == session.snapshot.version) worker.launch {
                            val result = dev.avillagran.ot3android.core.composer.NativeComposer().transform(org.json.JSONObject()
                                .put("action", "replace").put("text", captured.text).put("expectedText", captured.text)
                                .put("start", trigger.getInt("start")).put("cursor", trigger.getInt("end"))
                                .put("expectedRange", trigger.getString("expectedRange")).put("insert", row.getString("insert")).put("composing", captured.composing))
                            if (result.optBoolean("changed")) {
                                val cursor = result.getInt("cursor")
                                if (session.replace(captured, result.getString("text"), dev.avillagran.ot3android.core.composer.ComposerSelection(cursor, cursor))) publish()
                            }
                        }
                    }
                    is dev.avillagran.ot3android.ui.thread.composer.ComposerRequest.Send -> model.dispatchAttachments(request.delivery == dev.avillagran.ot3android.core.composer.ComposerDelivery.OppositeOnce)
                    is dev.avillagran.ot3android.ui.thread.composer.ComposerRequest.ImportUri -> model.importAttachment(context, request.uri)
                    is dev.avillagran.ot3android.ui.thread.composer.ComposerRequest.RemoveAttachment -> model.removeAttachment(request.id)
                    else -> Unit
                }
            }
        })
    if (shared != null && scope.queueMessage == null) TextButton(onClick = { sharedOpen = true }) { Text(stringResource(R.string.shared_draft_title)) }
    if (sharedOpen && shared != null) dev.avillagran.ot3android.ui.thread.composer.SharedDraftPanel(shared, displayed,
        onAdopt = { expected, text ->
            if (session.replace(expected, text, dev.avillagran.ot3android.core.composer.ComposerSelection(text.length, text.length))) publish()
        }, onClose = { sharedOpen = false }, targets = model.sharedDraftTargets(),
        prepareTransfer = { payload, target -> model.copySharedAttachments(context, payload, target) })
    history?.let { (captured, choices) -> androidx.compose.material3.AlertDialog(onDismissRequest = { history = null },
        title = { Text(stringResource(R.string.composer_history)) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            choices.take(100).forEach { text -> TextButton(onClick = {
                if (session.replace(captured, text, dev.avillagran.ot3android.core.composer.ComposerSelection(text.length, text.length))) publish()
                history = null
            }) { Text(text) } }
        } }, confirmButton = { TextButton(onClick = { history = null }) { Text(stringResource(R.string.ot3_dialog_close)) } }) }
    if (quickReplies) androidx.compose.material3.AlertDialog(onDismissRequest = { quickReplies = false },
        title = { Text(stringResource(R.string.composer_quick_replies)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val language = client.optString("quickReplyLanguage", "auto").let {
                    if (it == "auto") locale.language else it
                }
                client.optString("quickReplies.$language", client.optString("quickReplies.en")).lineSequence().filter { it.isNotBlank() }.forEach { text ->
                    TextButton(onClick = {
                        if (session.snapshot.transformable && current.connected) {
                            session.replace(session.snapshot, text, dev.avillagran.ot3android.core.composer.ComposerSelection(text.length, text.length)); publish()
                        }
                        quickReplies = false
                    }) { Text(text) }
                }
            }
        }, confirmButton = { TextButton(onClick = { quickReplies = false }) { Text(stringResource(R.string.ot3_dialog_close)) } })
}

private fun android.content.Context.devGraph() = (applicationContext as dev.avillagran.ot3android.Ot3Application).graph
