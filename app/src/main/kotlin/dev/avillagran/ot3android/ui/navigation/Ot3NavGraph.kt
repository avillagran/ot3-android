package dev.avillagran.ot3android.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import dev.avillagran.ot3android.R

/**
 * What each implemented destination draws. The app's set builds screens from view models;
 * tests pass a set of fakes so the real graph and navigation rules run without the app graph.
 */
interface Ot3Screens {
    /** [isTop]: Home is the visible entry (no sheet or detail above it), so its back handler may run. */
    @Composable
    fun Home(entry: NavBackStackEntry, isTop: Boolean)

    @Composable
    fun Thread(route: Thread)

    @Composable
    fun NewChat(entry: NavBackStackEntry) { NotYetScreen("NewThread") }

    @Composable
    fun NewChatDraft(entry: NavBackStackEntry, route: NewThreadDraft) { NotYetScreen("NewThreadDraft") }

    @Composable
    fun GeneralPreferences() {}

    @Composable
    fun ConnectionsPreferences() {}

    @Composable
    fun SourceControlPreferences() {}

    @Composable
    fun ProvidersPreferences() {}

    @Composable
    fun ProjectPreferences(route: ProjectPreferences) {}

    @Composable
    fun Git(route: ThreadGit) {}

    @Composable
    fun Terminal(route: ThreadTerminal) {}

    @Composable
    fun Changes(route: ThreadChanges) {}

    @Composable
    fun Maintenance(route: HostMaintenance) {}

    @Composable
    fun Settings()

    @Composable
    fun About(licenses: Boolean) {}

    @Composable
    fun SettingsPlaceholder(name: String, title: Int) { NotYetScreen(name) }

    @Composable
    fun Appearance() { NotYetScreen("Appearance") }

    @Composable
    fun Notifications() { NotYetScreen("Notifications") }

    @Composable
    fun Usage(initialTab: String, environmentId: String?) { NotYetScreen("Usage") }

    @Composable
    fun PullRequests() { NotYetScreen("PullRequests") }

    @Composable
    fun PullRequest(route: PullRequest) { NotYetScreen("PullRequest") }

    @Composable
    fun Environments()

    @Composable
    fun AddEnvironment(route: AddEnvironment)

    @Composable
    fun PairEnvironment()

    @Composable
    fun ScanPairingCode()

    @Composable
    fun ThreadActions(entry: NavBackStackEntry, route: ThreadActionsSheet)

    @Composable
    fun ProjectActions(entry: NavBackStackEntry, route: ProjectActionsSheet)

    @Composable
    fun ThreadListOptions(entry: NavBackStackEntry)

    @Composable
    fun SnoozeUntil(entry: NavBackStackEntry, route: SnoozeUntilSheet)
}

/**
 * The whole graph of UI_SPEC §2.1: a nested graph per tab, the sheets of §2.2 inside the Chats
 * graph (they belong to Home), and the "not yet" placeholder for destinations a later PR builds.
 * [isTop] tells whether an entry is the current one.
 */
fun NavGraphBuilder.ot3Graph(screens: Ot3Screens, isTop: (NavBackStackEntry) -> Boolean) {
    navigation<ChatsGraph>(startDestination = Home) {
        composable<Home> { entry -> screens.Home(entry, isTop(entry)) }
        composable<Thread> { entry ->
            val route = entry.toRoute<Thread>()
            CompositionLocalProvider(LocalSelectedThreadKey provides "${route.environmentId}/${route.threadId}") {
                ThreadWorkspace(sidebar = { screens.Home(entry, false) }) {
                    screens.Thread(route)
                }
            }
        }
        composable<ThreadTerminal> { screens.Terminal(it.toRoute()) }
        composable<ThreadGit> { screens.Git(it.toRoute()) }
        composable<ThreadChanges> { screens.Changes(it.toRoute()) }
        composable<NewThread> { entry -> screens.NewChat(entry) }
        composable<NewThreadDraft> { entry -> screens.NewChatDraft(entry, entry.toRoute()) }
        composable<AddProject> { NotYetScreen("AddProject") }
        composable<CommandPalette> { NotYetScreen("CommandPalette") }
        sheet<ThreadActionsSheet> { entry -> screens.ThreadActions(entry, entry.toRoute()) }
        sheet<ProjectActionsSheet> { entry -> screens.ProjectActions(entry, entry.toRoute()) }
        sheet<ThreadListOptionsSheet> { entry -> screens.ThreadListOptions(entry) }
        sheet<SnoozeUntilSheet> { entry -> screens.SnoozeUntil(entry, entry.toRoute()) }
    }
    navigation<PullRequestsGraph>(startDestination = PullRequests) {
        composable<PullRequests> { screens.PullRequests() }
        composable<PullRequest> { screens.PullRequest(it.toRoute()) }
        composable<PullRequestFile> { NotYetScreen("PullRequestFile") }
    }
    navigation<SettingsGraph>(startDestination = Settings) {
        composable<Appearance> { screens.Appearance() }
        composable<Notifications> { screens.Notifications() }
        composable<Settings> { screens.Settings() }
        composable<GeneralPreferences> { screens.GeneralPreferences() }
        composable<SourceControlPreferences> { screens.SourceControlPreferences() }
        composable<ProvidersPreferences> { screens.ProvidersPreferences() }
        composable<ConnectionsPreferences> { screens.ConnectionsPreferences() }
        composable<ProjectPreferences> { screens.ProjectPreferences(it.toRoute()) }
        composable<Environments> { screens.Environments() }
        composable<AddEnvironment> { entry -> screens.AddEnvironment(entry.toRoute()) }
        composable<PairEnvironment> { screens.PairEnvironment() }
        composable<ScanPairingCode> { screens.ScanPairingCode() }
        composable<Usage> { screens.Usage("usage", null) }
        composable<QuotaLimits> { val route = it.toRoute<QuotaLimits>(); screens.Usage("limits", route.environmentId) }
        composable<Archive> { screens.Maintenance(HostMaintenance("ARCHIVE")) }
        composable<HostMaintenance> { screens.Maintenance(it.toRoute()) }
        composable<Licenses> { screens.About(true) }
        composable<About> { screens.About(false) }
    }
}

/** A destination whose screen comes in a later PR. [name] is the route, for tests and bug reports. */
@Composable
fun NotYetScreen(name: String) {
    Box(Modifier.fillMaxSize().padding(24.dp).testTag(NavTags.notYet(name)), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.not_yet), style = MaterialTheme.typography.bodyLarge)
    }
}

object NavTags {
    const val BAR = "nav_bar"

    fun tab(tab: TopTab) = "nav_tab_${tab.name}"

    fun notYet(name: String) = "not_yet_$name"
}
