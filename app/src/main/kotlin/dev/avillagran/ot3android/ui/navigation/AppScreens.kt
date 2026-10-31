package dev.avillagran.ot3android.ui.navigation

import androidx.compose.foundation.layout.padding
import android.content.ClipData
import androidx.compose.ui.res.stringResource
import dev.avillagran.ot3android.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import dev.avillagran.ot3android.appGraph
import dev.avillagran.ot3android.appViewModel
import dev.avillagran.ot3android.core.home.ActionContext
import dev.avillagran.ot3android.core.home.ProjectAction
import dev.avillagran.ot3android.core.home.ThreadAction
import dev.avillagran.ot3android.core.home.ThreadActions
import dev.avillagran.ot3android.core.home.ThreadMenuTarget
import dev.avillagran.ot3android.ui.connect.ConnectScreen
import dev.avillagran.ot3android.ui.connect.ConnectViewModel
import dev.avillagran.ot3android.ui.environments.EnvironmentsScreen
import dev.avillagran.ot3android.ui.environments.EnvironmentsViewModel
import dev.avillagran.ot3android.ui.home.HomeIntent
import dev.avillagran.ot3android.ui.home.HomeScreen
import dev.avillagran.ot3android.ui.home.HomeViewModel
import dev.avillagran.ot3android.ui.home.ProjectActionsContent
import dev.avillagran.ot3android.ui.home.SnoozeUntilContent
import dev.avillagran.ot3android.ui.home.ThreadActionsContent
import dev.avillagran.ot3android.ui.home.ThreadListOptionsContent
import dev.avillagran.ot3android.ui.pair.PairScreen
import dev.avillagran.ot3android.ui.pair.PairViewModel
import dev.avillagran.ot3android.ui.pair.QrScanScreen
import dev.avillagran.ot3android.ui.settings.SettingsEntry
import dev.avillagran.ot3android.ui.settings.SettingsScreen
import dev.avillagran.ot3android.ui.thread.ThreadScreen
import dev.avillagran.ot3android.ui.thread.ThreadViewModel
import kotlinx.coroutines.launch

/** The app's screens, built from view models over the [dev.avillagran.ot3android.AppGraph]. */
class AppScreens(private val navigator: Ot3Navigator) : Ot3Screens {

    /** Home's view model, shared by Home and its sheets: scoped to the Chats graph's entry. */
    @Composable
    private fun homeViewModel(entry: NavBackStackEntry): HomeViewModel {
        val owner = remember(entry) { navigator.navController.getBackStackEntry(ChatsGraph) }
        val graph = LocalContext.current.appGraph
        return viewModel(viewModelStoreOwner = owner) {
            HomeViewModel(graph.environmentRepository, graph.connectionManager, graph.homePreferences, sender = graph.messageSender)
        }
    }

    @Composable
    override fun Home(entry: NavBackStackEntry, isTop: Boolean) {
        val viewModel = homeViewModel(entry)
        val state by viewModel.state.collectAsStateWithLifecycle()
        val actionError by viewModel.actionError.collectAsStateWithLifecycle()
        actionError?.let { code -> androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.actionError.value = null },
            title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(dev.avillagran.ot3android.R.string.ot3_row_error)) },
            text = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(dev.avillagran.ot3android.R.string.home_action_unconfirmed)) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { viewModel.actionError.value = null }) {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(dev.avillagran.ot3android.R.string.ot3_dialog_close)) } }) }
        val presentation by LocalContext.current.appGraph.projectPresentationState.collectAsStateWithLifecycle()
        val metadata = remember(presentation) {
            val value = org.json.JSONObject(presentation)
            fun map(field: String): Map<String, String> = value.optJSONObject(field)?.let { obj -> obj.keys().asSequence().associateWith { obj.getString(it) } }.orEmpty()
            dev.avillagran.ot3android.ui.home.layouts.SidebarMetadata(threadGroups = map("threadGroups"), projectGroups = map("groupProjects"))
        }
        HomeScreen(state, isTop, metadata = metadata, onIntent = { intent -> onHomeIntent(intent, viewModel) }, quotaStrip = { dev.avillagran.ot3android.ui.usage.QuotaStripHost(onOpenLimits = { navigator.push(QuotaLimits()) }) })
    }

    @Composable
    override fun NewChat(entry: NavBackStackEntry) {
        val home = homeViewModel(entry)
        val state by home.state.collectAsStateWithLifecycle()
        val graph = LocalContext.current.appGraph
        val environments by graph.environmentRepository.environments.collectAsStateWithLifecycle(initialValue = emptyList())
        val connectionStates by graph.connectionManager.states.collectAsStateWithLifecycle(initialValue = emptyMap())
        val current = state
        val options = current?.projects?.values.orEmpty().map { project ->
            val environmentId = project.key.substringBefore('/')
            dev.avillagran.ot3android.ui.newchat.NewChatProjectOption(environmentId,
                environments.firstOrNull { it.id == environmentId }?.label ?: environmentId,
                project.key.substringAfter('/'), project.title,
                project.workspaceRoot, connectionStates[environmentId] is dev.avillagran.ot3android.core.connection.ConnectionState.Connected,
                current?.actionContexts?.get(environmentId)?.canOperate == true)
        }
        dev.avillagran.ot3android.ui.newchat.NewChatProjectPicker(options, current == null,
            onSelect = { navigator.push(NewThreadDraft(it.environmentId, it.projectId)) },
            onBack = { navigator.back() }, onRetry = home::refresh)
    }

    @Composable
    override fun NewChatDraft(entry: NavBackStackEntry, route: NewThreadDraft) {
        val graph = LocalContext.current.appGraph
        val model: dev.avillagran.ot3android.ui.newchat.NewChatDraftViewModel = viewModel(viewModelStoreOwner = entry) {
            dev.avillagran.ot3android.ui.newchat.NewChatDraftViewModel(route.environmentId, route.projectId, route.branch,
                graph.connectionManager, graph.messageSender, graph.outboxRepository, createSavedStateHandle())
        }
        val state by model.state.collectAsStateWithLifecycle()
        val environments by graph.environmentRepository.environments.collectAsStateWithLifecycle(initialValue = emptyList())
        androidx.compose.runtime.LaunchedEffect(state.createdThreadId) {
            state.createdThreadId?.let { navigator.openThread(route.environmentId, it) }
        }
        dev.avillagran.ot3android.ui.newchat.NewChatDraftScreen(state, { navigator.back() }, model::text, model::title,
            model::select, model::option, model::runtime, model::refresh, model::create,
            environmentLabel = environments.firstOrNull { it.id == route.environmentId }?.label.orEmpty(), onDiscard = model::discard)
    }

    private fun onHomeIntent(intent: HomeIntent, viewModel: HomeViewModel) {
        when (intent) {
            is HomeIntent.SetQuery -> viewModel.setQuery(intent.text)
            HomeIntent.ClearSearch -> viewModel.clearSearch()
            is HomeIntent.OpenRow -> navigator.openThread(intent.row.environmentId, intent.row.threadId)
            is HomeIntent.OpenPullRequest -> intent.row.pullRequestBadge?.optJSONObject("current")?.let { pr ->
                val projectId = pr.optString("projectId").takeIf { it.isNotBlank() } ?: intent.row.projectId
                if (projectId != null && pr.optString("repository").isNotBlank() && pr.optInt("number") > 0) {
                    navigator.push(PullRequest(intent.row.environmentId, projectId, pr.getString("repository"), pr.getInt("number"), pr.optString("host").takeIf { it.isNotBlank() }))
                }
            }
            is HomeIntent.Swipe -> when (intent.action) {
                dev.avillagran.ot3android.core.home.SwipeAction.Snooze -> navigator.openSheet(SnoozeUntilSheet(intent.row.environmentId, intent.row.threadId))
                dev.avillagran.ot3android.core.home.SwipeAction.Archive -> navigator.openSheet(ThreadActionsSheet(intent.row.environmentId, intent.row.threadId, ThreadAction.Archive.name))
                else -> viewModel.manage(intent.row, when (intent.action) {
                    dev.avillagran.ot3android.core.home.SwipeAction.Settle -> ThreadAction.MarkSettled
                    dev.avillagran.ot3android.core.home.SwipeAction.MoveToActive -> ThreadAction.MoveToActive
                    else -> ThreadAction.WakeNow
                })
            }
            is HomeIntent.RowActions -> navigator.openSheet(ThreadActionsSheet(intent.row.environmentId, intent.row.threadId))
            is HomeIntent.ProjectActions -> intent.row.projectId?.let { navigator.openSheet(ProjectActionsSheet(intent.row.environmentId, it)) }
            is HomeIntent.OpenMessage -> navigator.openThread(intent.match.environmentId, intent.match.threadId)
            is HomeIntent.ToggleShelf -> viewModel.toggleShelf(intent.shelf)
            HomeIntent.ShowMoreSettled -> viewModel.showMoreSettled()
            HomeIntent.OpenFilters -> navigator.openSheet(ThreadListOptionsSheet)
            HomeIntent.ToggleGrouping -> viewModel.toggleGrouping()
            HomeIntent.Refresh -> viewModel.refresh()
            HomeIntent.OpenEnvironments -> navigator.openEnvironments()
            HomeIntent.AddEnvironment -> navigator.addEnvironment()
            is HomeIntent.EditEnvironment -> navigator.addEnvironment(intent.environmentId)
            HomeIntent.AddProject -> navigator.push(AddProject())
            HomeIntent.NewThread -> navigator.push(NewThread)
            HomeIntent.CommandPalette -> navigator.push(CommandPalette)
        }
    }

    @Composable
    override fun Thread(route: Thread) {
        val graph = LocalContext.current.appGraph
        DisposableEffect(route.environmentId) {
            val claim = graph.liveThemeSource.showOnScreen(route.environmentId)
            graph.appScope.launch { graph.selectionPreferences.setLastEnvironmentId(route.environmentId) }
            onDispose { claim.close() }
        }
        DisposableEffect(route.environmentId, route.threadId) {
            val claim = graph.followVisibility.claim(dev.avillagran.ot3android.notifications.ThreadRef(route.environmentId, route.threadId))
            onDispose { claim.close() }
        }
        // `prompt` prefill remains part of the composer continuation.
        ThreadScreen(
            viewModel = appViewModel { graph ->
                ThreadViewModel(
                    route.environmentId,
                    route.threadId,
                    graph.connectionManager,
                    graph.draftRepository,
                    graph.outboxRepository,
                    graph.messageSender,
                    graph.appScope,
                    graph.composerPreferences,
                    graph.threadCache,
                    gitJournal = graph.gitJournal, checkoutAdmission = graph.checkoutAdmission,
                    clientPreferences = graph.generalClientPreferences,
                    attachmentDirectory = graph.applicationContext.noBackupFilesDir.resolve("composer-attachments-v1"),
                    cacheAuthority = { graph.environmentRepository.cacheAuthority(route.environmentId) },
                    cacheAuthorityUpdates = graph.environmentRepository.cacheAuthorities(route.environmentId),
                )
            },
            onBack = { navigator.back() },
            onTerminal = { navigator.push(ThreadTerminal(route.environmentId, route.threadId)) },
            onRunCode = { navigator.push(ThreadTerminal(route.environmentId, route.threadId, it)) },
            onGit = { navigator.push(ThreadGit(route.environmentId, route.threadId)) },
            onChanges = { navigator.push(ThreadChanges(route.environmentId, route.threadId, "working-tree")) },
            onOpenThread = { navigator.openThread(route.environmentId, it) },
            onOpenReferenceThread = { environment, thread -> navigator.openThread(environment, thread) },
            quotaStrip = { dev.avillagran.ot3android.ui.usage.QuotaStripHost(route.environmentId, compact = false, onOpenLimits = { navigator.push(QuotaLimits(route.environmentId)) }) },
        )
    }

    @Composable
    override fun Terminal(route: ThreadTerminal) {
        dev.avillagran.ot3android.ui.terminal.TerminalPage(route.environmentId, route.threadId, onClose = { navigator.back() }, command = route.command)
    }

    @Composable
    override fun Git(route: ThreadGit) { GitOperationsPage(navigator, route) }

    @Composable
    override fun Changes(route: ThreadChanges) { WorkingChangesPage(navigator, route) }

    @Composable
    override fun Settings() {
        SettingsScreen(onBack = { navigator.home() }, contributions = listOf(
            dev.avillagran.ot3android.ui.settings.PreferencesRouteContribution(dev.avillagran.ot3android.ui.settings.desktopPreferencesPages.first { it.id == "providers" }) { navigator.push(ProvidersPreferences) },
            dev.avillagran.ot3android.ui.settings.PreferencesRouteContribution(dev.avillagran.ot3android.ui.settings.desktopPreferencesPages.first { it.id == "general" }) { navigator.push(GeneralPreferences) },
            dev.avillagran.ot3android.ui.settings.PreferencesRouteContribution(dev.avillagran.ot3android.ui.settings.desktopPreferencesPages.first { it.id == "projects" }) { navigator.push(ProjectPreferences()) },
            dev.avillagran.ot3android.ui.settings.PreferencesRouteContribution(dev.avillagran.ot3android.ui.settings.desktopPreferencesPages.first { it.id == "connections" }) { navigator.push(ConnectionsPreferences) },
            dev.avillagran.ot3android.ui.settings.PreferencesRouteContribution(dev.avillagran.ot3android.ui.settings.desktopPreferencesPages.first { it.id == "source-control" }) { navigator.push(SourceControlPreferences) },
        ) + listOf(
            "storage" to dev.avillagran.ot3android.R.string.maintenance_storage,
            "archived" to dev.avillagran.ot3android.R.string.maintenance_archive,
            "schedules" to dev.avillagran.ot3android.R.string.maintenance_schedules,
            "sessions" to dev.avillagran.ot3android.R.string.maintenance_sessions,
            "diagnostics" to dev.avillagran.ot3android.R.string.maintenance_diagnostics,
        ).map { (id, label) ->
            dev.avillagran.ot3android.ui.settings.PreferencesRouteContribution(
                dev.avillagran.ot3android.ui.settings.PreferencesPage(id, label, "P17")) {
                navigator.push(HostMaintenance(if (id == "archived") "ARCHIVE" else id.uppercase(java.util.Locale.ROOT)))
            }
        }, onOpen = { entry ->
            when (entry) {
                SettingsEntry.Environments -> navigator.push(ConnectionsPreferences)
                SettingsEntry.Appearance -> navigator.push(Appearance)
                SettingsEntry.Notifications -> navigator.push(Notifications)
                SettingsEntry.Usage -> navigator.push(Usage)
                SettingsEntry.Archive -> navigator.push(Archive)
                SettingsEntry.Licenses -> navigator.push(Licenses)
                SettingsEntry.About -> navigator.push(About)
            }
        })
    }

    @Composable
    override fun Maintenance(route: HostMaintenance) { HostMaintenancePage(navigator, route) }

    @Composable
    override fun ProvidersPreferences() { ProvidersPreferencesPage(navigator) }

    @Composable
    override fun SourceControlPreferences() { SourceControlPreferencesPage(navigator) }

    @Composable
    override fun ConnectionsPreferences() {
        val graph = LocalContext.current.appGraph
        val identity by graph.bridgeCatalog.identity.collectAsStateWithLifecycle()
        dev.avillagran.ot3android.ui.settings.connections.ConnectionsRouteContribution.Content(
            identity = identity,
            onBack = { navigator.back() }, onEnvironments = { navigator.push(Environments) },
            onPair = { navigator.push(PairEnvironment) })
    }

    @Composable
    override fun GeneralPreferences() { ScopedPreferencesPage(navigator, projects = false) }

    @Composable
    override fun ProjectPreferences(route: ProjectPreferences) { ScopedPreferencesPage(navigator, projects = true, route) }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    override fun About(licenses: Boolean) {
        dev.avillagran.ot3android.ui.settings.about.AboutScreen(onBack = { navigator.back() }, initialLicenses = licenses)
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    override fun SettingsPlaceholder(name: String, title: Int) {
        androidx.compose.material3.Scaffold(topBar = {
            androidx.compose.material3.TopAppBar(
                title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(title)) },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = { navigator.back() }) {
                        androidx.compose.material3.Icon(
                            androidx.compose.ui.res.painterResource(dev.avillagran.ot3android.R.drawable.ic_arrow_back),
                            androidx.compose.ui.res.stringResource(dev.avillagran.ot3android.R.string.action_back),
                        )
                    }
                },
            )
        }) { padding ->
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier
                .padding(padding)) { NotYetScreen(name) }
        }
    }

    @Composable
    override fun Usage(initialTab: String, environmentId: String?) {
        val graph = LocalContext.current.appGraph
        val model = viewModel {
            dev.avillagran.ot3android.ui.usage.UsageViewModel(
                graph.environmentRepository.environments,
                graph.connectionManager::connectionFlow,
                createSavedStateHandle().apply { if (!contains("tab")) set("tab", initialTab); if (!contains("environment")) set("environment", environmentId) },
                refreshProviders = graph.providerRefreshGate::refresh,
                quotaSource = graph.quotaSource,
            )
        }
        val state by model.state.collectAsStateWithLifecycle()
        dev.avillagran.ot3android.ui.usage.UsageScreen(
            state, model::selectEnvironment, model::selectPeriod, model::refresh,
            onBack = { navigator.back() }, onRefreshPrices = model::refreshPrices, onTab = model::selectTab,
        )
    }

    @Composable
    override fun PullRequests() {
        val graph = LocalContext.current.appGraph
        val model = viewModel {
            dev.avillagran.ot3android.ui.pullrequests.PullRequestsViewModel(
                graph.environmentRepository.environments, graph.connectionManager::connectionFlow,
                projects = { id -> kotlinx.coroutines.flow.flow {
                    val supervisor = graph.connectionManager.supervisor(id)
                    if (supervisor == null) emit(emptyList())
                    else supervisor.shell.collect { emit(it?.projects.orEmpty()) }
                } }, saved = createSavedStateHandle(),
            )
        }
        val state by model.state.collectAsStateWithLifecycle()
        dev.avillagran.ot3android.ui.pullrequests.PullRequestsScreen(state, model::selectEnvironment, model::selectProject,
            model::selectState, model::selectInvolvement, model::setQuery, model::refresh, model::loadMore,
            onOpen = { id, entry -> navigator.push(PullRequest(id, entry.getString("projectId"), entry.getString("repository"), entry.getInt("number"), entry.getString("host"))) }, listState = model.listState)
    }

    @Composable
    override fun PullRequest(route: PullRequest) {
        val graph = LocalContext.current.appGraph
        val model = viewModel {
            dev.avillagran.ot3android.ui.pullrequests.PullRequestViewModel(route.environmentId,
                org.json.JSONObject().put("projectId", route.projectId).put("repository", route.repository).put("number", route.number).put("host", route.host),
                graph.connectionManager::connectionFlow, createSavedStateHandle(),
                initiallyUnresolved = graph.pullRequestWriteUnresolved(route),
                writeReceipt = { graph.setPullRequestWriteUnresolved(route, it) })
        }
        val state by model.state.collectAsStateWithLifecycle()
        dev.avillagran.ot3android.ui.pullrequests.PullRequestScreen(route.number, route.repository, state, model::selectTab, model::refresh, { navigator.back() }, onWrite = model::write, onMoreDiff = model::moreDiff)
    }

    @Composable
    override fun Notifications() {
        val graph = LocalContext.current.appGraph
        dev.avillagran.ot3android.ui.settings.NotificationsScreen(graph.followPreferences) { navigator.back() }
    }

    @Composable
    override fun Appearance() {
        dev.avillagran.ot3android.ui.settings.AppearanceSettingsScreen(
            viewModel = appViewModel { graph ->
                dev.avillagran.ot3android.ui.settings.AppearanceSettingsViewModel(
                    graph.themePreferences, graph.liveThemeSource, graph.environmentRepository,
                    loadThemes = { graph.themeCatalog().themes },
                )
            },
            onBack = { navigator.back() },
        )
    }

    @Composable
    override fun Environments() {
        val graph = LocalContext.current.appGraph
        val bridgeStatus by graph.bridgeCatalog.status.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        var administration by remember { mutableStateOf(false) }
        if (administration) dev.avillagran.ot3android.ui.environments.BridgeAdministration(graph.bridgeCatalog) { administration = false }
        EnvironmentsScreen(
            onBack = { navigator.back() },
            viewModel = appViewModel { graph -> EnvironmentsViewModel(graph.environmentRepository, graph.connectionManager) },
            // Rows expand in place with UI-6; until then a tap edits, as the edit action does.
            onOpen = { id -> navigator.addEnvironment(id) },
            onAdd = { navigator.addEnvironment() },
            onEdit = { id -> navigator.addEnvironment(id) },
            onAppearance = { navigator.push(Appearance) },
            bridgeStatus = bridgeStatus,
            onRefreshBridge = { scope.launch { graph.bridgeCatalog.reconnect() } },
            onAdministerBridge = { administration = true },
            onPairBridge = { navigator.push(PairEnvironment) },
        )
    }

    @Composable
    override fun AddEnvironment(route: AddEnvironment) {
        ConnectScreen(
            viewModel = appViewModel { graph ->
                ConnectViewModel(graph.serverProbe, graph.environmentRepository, route.environmentId, graph.connectionManager::forget)
            },
            onSaved = { navigator.environmentSaved() },
            onBack = { navigator.back() },
            onPair = if (route.environmentId == null) {
                { navigator.push(PairEnvironment) }
            } else {
                null
            },
        )
    }

    @Composable
    override fun PairEnvironment() {
        val inbox = LocalContext.current.appGraph.pairingInbox
        PairScreen(
            viewModel = appViewModel { graph -> PairViewModel(graph.pairingService(), graph::pairBridge, dev.avillagran.ot3android.core.bridge.NativeBridge::isAvailable) },
            inbox = inbox.link,
            takeFromInbox = inbox::take,
            onScan = { navigator.push(ScanPairingCode) },
            onPaired = { navigator.environmentSaved() },
            onBack = { navigator.back() },
        )
    }

    @Composable
    override fun ScanPairingCode() {
        val inbox = LocalContext.current.appGraph.pairingInbox
        QrScanScreen(
            onScanned = { text ->
                // Back to the pairing screen first, so it is the one that takes the link.
                navigator.back()
                inbox.offer(text, scanned = true)
            },
            onBack = { navigator.back() },
            accept = { dev.avillagran.ot3android.ui.pair.BridgeInvitation.isLink(it) || dev.avillagran.ot3android.core.pairing.PairingService.isPairingLink(it) },
        )
    }

    @Composable
    override fun ThreadActions(entry: NavBackStackEntry, route: ThreadActionsSheet) {
        val viewModel = homeViewModel(entry)
        val state by viewModel.state.collectAsStateWithLifecycle()
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()
        val graph = LocalContext.current.appGraph
        val live by remember(route.environmentId) { graph.connectionManager.connectionFlow(route.environmentId) }.collectAsStateWithLifecycle(initialValue = null)
        val operations = live?.let { graph.threadMenuOwner(route.environmentId, route.threadId, it.transport) }
        val operationState by (operations?.state ?: kotlinx.coroutines.flow.flowOf(dev.avillagran.ot3android.ui.settings.projects.ThreadMenuOperationState())).collectAsStateWithLifecycle(initialValue = dev.avillagran.ot3android.ui.settings.projects.ThreadMenuOperationState())
        val supervisor by androidx.compose.runtime.produceState<dev.avillagran.ot3android.core.connection.ConnectionSupervisor?>(null, route.environmentId) { value = graph.connectionManager.supervisor(route.environmentId) }
        val shell by (supervisor?.shell ?: kotlinx.coroutines.flow.flowOf(null)).collectAsStateWithLifecycle(initialValue = null)
        val regenerating = shell?.thread(route.threadId)?.raw?.opt("titleRegeneration")?.let { it != org.json.JSONObject.NULL && it != false } == true
        androidx.compose.runtime.LaunchedEffect(operations, regenerating) { operations?.observeTitleRegeneration(regenerating) }
        dev.avillagran.ot3android.ui.settings.projects.ThreadMenuRecoveryContent(operationState) { scope.launch { operations?.retry() } }
        val current = state ?: return
        val row = current.rows["${route.environmentId}/${route.threadId}"] ?: return
        val target = ThreadMenuTarget(
            row = row,
            projectScoped = row.projectKey != null && row.projectKey in current.projectScope,
            working = row.working,
            autoSettleDisabled = row.autoSettleDisabled,
            canSnooze = row.canSnooze,
            titleRegenerating = regenerating || operationState.titleRegenerating,
        )
        var pending by rememberSaveable(row.key) { mutableStateOf(route.initialAction?.let { ThreadAction.valueOf(it) }) }
        pending?.let { action -> dev.avillagran.ot3android.ui.home.ThreadManagementDialog(row, action,
            onDismiss = { pending = null }, onConfirm = { title ->
                scope.launch { operations?.execute(row, action, title = title, confirmed = true) }
                pending = null
                navigator.closeSheet(entry)
            }) }
        val presentation by graph.projectPresentationState.collectAsStateWithLifecycle()
        androidx.compose.foundation.layout.Column {
        ProjectGroupMenu(graph.projectPresentation.names(),
            org.json.JSONObject(presentation).optJSONObject("threadGroups")?.optString(row.key)?.takeIf { it.isNotBlank() }, true) { name ->
            graph.updateProjectPresentation { it.setThreadGroup(row.key, name) }
        }
        val writeRevision by graph.consumerWrites.changes.collectAsStateWithLifecycle()
        var pinBusy by remember { mutableStateOf(false) }
        var pinFailed by remember { mutableStateOf(false) }
        val pinPending = remember(writeRevision, row.environmentId) { graph.consumerWrites.pending(row.environmentId) != null }
        if (pinFailed) androidx.compose.material3.Text(stringResource(R.string.thread_menus_refresh_required))
        if (pinPending) androidx.compose.material3.TextButton(enabled = !pinBusy && live != null, onClick = {
            pinBusy = true
            graph.appScope.launch {
                try { live?.let { connection -> graph.checkoutAdmission.command(row.environmentId) { graph.consumerWrites.retry(row.environmentId, connection.transport) } }; pinFailed = false }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { pinFailed = true }
                finally { pinBusy = false }
            }
        }) { androidx.compose.material3.Text(stringResource(R.string.thread_resend)) }
        val pinIds by androidx.compose.runtime.produceState<List<String>>(emptyList(), shell, row.environmentId) {
            value = try {
                val source = shell
                if (source == null) emptyList() else dev.avillagran.ot3android.core.shell.SidebarView.build(
                    dev.avillagran.ot3android.core.transport.DomainEngine.shared,
                    listOf(dev.avillagran.ot3android.core.shell.SidebarEnvironment(row.environmentId, shell = source)), System.currentTimeMillis())
                    .active.filter { it.pinned }.map { it.threadId }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
        }
        if (row.pinned && current.actionContexts[row.environmentId]?.canOperate == true &&
            current.actionContexts[row.environmentId]?.has(dev.avillagran.ot3android.core.home.ServerCapability.ThreadPinning) == true) {
            listOf(-1, 1).forEach { direction ->
                androidx.compose.material3.TextButton(enabled = !pinBusy && !pinPending && live != null && shell != null && pinIds.indexOf(row.threadId).let { it >= 0 && it + direction in pinIds.indices }, onClick = {
                    val captured = live ?: return@TextButton
                    val source = shell ?: return@TextButton
                    pinBusy = true
                    graph.appScope.launch {
                        try {
                            val access = dev.avillagran.ot3android.core.home.ThreadManagement.access(captured.transport)
                            check(access.canOperate && access.has(dev.avillagran.ot3android.core.home.ServerCapability.ThreadPinning))
                            val view = dev.avillagran.ot3android.core.shell.SidebarView.build(dev.avillagran.ot3android.core.transport.DomainEngine.shared,
                                listOf(dev.avillagran.ot3android.core.shell.SidebarEnvironment(row.environmentId, shell = source)), System.currentTimeMillis())
                            val ids = view.active.filter { it.pinned }.map { it.threadId }.toMutableList()
                            val index = ids.indexOf(row.threadId)
                            val destination = index + direction
                            if (index >= 0 && destination in ids.indices) {
                                java.util.Collections.swap(ids, index, destination)
                                val keys = org.json.JSONObject()
                                source.threads.forEach { thread -> thread.pinOrderKey?.let { keys.put(thread.id, it) } }
                                val commandIds = org.json.JSONObject()
                                ids.forEach { commandIds.put(it, "ot3a-" + java.util.UUID.randomUUID()) }
                                val commands = dev.avillagran.ot3android.core.transport.NativeParity().pins(org.json.JSONArray(ids), keys, row.threadId, commandIds)
                                graph.checkoutAdmission.command(row.environmentId) { graph.consumerWrites.submit(row.environmentId, captured.transport, "orchestration.dispatchCommand", commands) }
                            }
                            pinFailed = false
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { pinFailed = true }
                        finally { pinBusy = false }
                    }
                }) { androidx.compose.material3.Text(stringResource(if (direction < 0) R.string.ot3_action_move_up else R.string.ot3_action_move_down)) }
            }
        }
        ThreadActionsContent(row, ThreadActions.menu(target, (current.actionContexts[row.environmentId] ?: ActionContext.Interim).copy(projectSettingsAvailable = row.projectId != null, newThreadAvailable = row.projectId != null, busy = current.actionContexts[row.environmentId]?.busy == true || operationState.busy || operationState.retryCommandId != null || operations == null)).filter { it.action !in setOf(ThreadAction.MoveUp, ThreadAction.MoveDown) }) { action ->
            if (action in setOf(ThreadAction.Rename, ThreadAction.Archive, ThreadAction.Delete)) {
                pending = action
                return@ThreadActionsContent
            }
            if (action == ThreadAction.Snooze) {
                navigator.closeSheet(entry)
                navigator.openSheet(SnoozeUntilSheet(row.environmentId, row.threadId))
                return@ThreadActionsContent
            }
            if (action == ThreadAction.ProjectSettings && row.projectId != null) {
                navigator.closeSheet(entry)
                navigator.push(ProjectPreferences(row.environmentId, row.projectId))
                return@ThreadActionsContent
            }
            if (action == ThreadAction.NewThreadOnBranch && row.projectId != null) {
                navigator.closeSheet(entry)
                navigator.push(NewThreadDraft(row.environmentId, requireNotNull(row.projectId), row.branch))
                return@ThreadActionsContent
            }
            if (dev.avillagran.ot3android.core.home.ThreadManagement.supported(action)) scope.launch { operations?.execute(row, action) }
            when (action) {
                ThreadAction.CopyPath -> row.path
                ThreadAction.CopyBranch -> row.branch
                ThreadAction.CopyThreadId -> row.threadId
                else -> null
            }?.let { text -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(text, text))) } }
            when (action) {
                ThreadAction.FilterByProject -> viewModel.setProjectFilter(row.projectKey)
                ThreadAction.AllProjects -> viewModel.setProjectFilter(null)
                // Mutating actions are not offered until the lib's builders are reachable.
                else -> Unit
            }
            navigator.closeSheet(entry)
        }
        }
    }

    @Composable
    override fun ProjectActions(entry: NavBackStackEntry, route: ProjectActionsSheet) {
        val viewModel = homeViewModel(entry)
        val state by viewModel.state.collectAsStateWithLifecycle()
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()
        val project = state?.projects?.get("${route.environmentId}/${route.projectId}")
        val graph = LocalContext.current.appGraph
        val presentation by graph.projectPresentationState.collectAsStateWithLifecycle()
        androidx.compose.foundation.layout.Column {
        ProjectGroupMenu(graph.projectPresentation.names(),
            org.json.JSONObject(presentation).optJSONObject("groupProjects")?.optString("${route.environmentId}/${route.projectId}")?.takeIf { it.isNotBlank() }, project != null) { name ->
            graph.updateProjectPresentation { it.setProjectGroup("${route.environmentId}/${route.projectId}", name) }
        }
        ProjectActionsContent(project?.title, ThreadActions.projectMenu(project?.workspaceRoot, ActionContext.Interim.copy(projectSettingsAvailable = project != null)).filter { it.action in setOf(ProjectAction.CopyPath, ProjectAction.ProjectSettings) }) { action ->
            if (action == ProjectAction.ProjectSettings && project != null) {
                navigator.closeSheet(entry)
                navigator.push(ProjectPreferences(route.environmentId, route.projectId))
                return@ProjectActionsContent
            }
            val root = project?.workspaceRoot
            if (action == ProjectAction.CopyPath && root != null) {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(root, root))) }
            }
            navigator.closeSheet(entry)
        }
        }
    }

    @Composable
    override fun ThreadListOptions(entry: NavBackStackEntry) {
        val viewModel = homeViewModel(entry)
        val state by viewModel.state.collectAsStateWithLifecycle()
        val current = state ?: return
        // As ot3's filter menu, each choice applies and closes.
        ThreadListOptionsContent(
            options = current.filterOptions,
            filter = current.filter,
            onStatus = { viewModel.setStatusFilter(it); navigator.closeSheet(entry) },
            onProject = { key ->
                if (key == null) viewModel.setProjectFilter(null) else if (key != current.filter.projectKey) viewModel.setProjectFilter(key)
                navigator.closeSheet(entry)
            },
            onEnvironment = { viewModel.setEnvironmentFilter(it); navigator.closeSheet(entry) },
        )
    }

    @Composable
    override fun SnoozeUntil(entry: NavBackStackEntry, route: SnoozeUntilSheet) {
        val presets = remember(entry) { ThreadActions.snoozePresets(System.currentTimeMillis()) }
        val viewModel = homeViewModel(entry)
        val state by viewModel.state.collectAsStateWithLifecycle()
        val row = state?.rows?.get("${route.environmentId}/${route.threadId}") ?: return
        val context = LocalContext.current
        fun choose(at: Long) {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            viewModel.manage(row, ThreadAction.Snooze, until = format.format(java.util.Date(at)))
            navigator.closeSheet(entry)
        }
        SnoozeUntilContent(presets, enabled = state?.actionContexts?.get(row.environmentId)?.canOperate == true && row.canSnooze) { preset ->
            val at = preset.at
            if (at != null) choose(at)
            else {
                val calendar = java.util.Calendar.getInstance()
                android.app.DatePickerDialog(context, { _, year, month, day ->
                    android.app.TimePickerDialog(context, { _, hour, minute ->
                        calendar.set(year, month, day, hour, minute, 0)
                        choose(calendar.timeInMillis)
                    }, calendar.get(java.util.Calendar.HOUR_OF_DAY), calendar.get(java.util.Calendar.MINUTE), true).show()
                }, calendar.get(java.util.Calendar.YEAR), calendar.get(java.util.Calendar.MONTH), calendar.get(java.util.Calendar.DAY_OF_MONTH)).apply {
                    datePicker.minDate = System.currentTimeMillis()
                }.show()
            }
        }
    }
}
