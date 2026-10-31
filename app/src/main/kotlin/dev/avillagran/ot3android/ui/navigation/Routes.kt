package dev.avillagran.ot3android.ui.navigation

import kotlinx.serialization.Serializable

/*
 * Typed destinations of UI_SPEC §2.1 and the sheets of §2.2. `environmentId` is always the local
 * catalog id (`Environment.id`), not the server's environment UUID. Destinations without a
 * screen yet route to the "not yet" placeholder; their arguments are already the spec's.
 */

// Tab graphs: one back stack per tab (Chats, PRs, Settings).

@Serializable
data object ChatsGraph

@Serializable
data object PullRequestsGraph

@Serializable
data object SettingsGraph

// Chats.

/** Threads of every saved environment (4.1). Root of the Chats tab. */
@Serializable
data object Home

/** A thread and its composer (4.3, 4.4). Deep link: [ThreadDeepLink]. */
@Serializable
data class Thread(val environmentId: String, val threadId: String, val prompt: String? = null)

/** Changes and diff of a thread (4.6). */
@Serializable
data class ThreadChanges(val environmentId: String, val threadId: String, val source: String, val path: String? = null)

/** New thread: choose a project (4.7.1). */
@Serializable
data object NewThread

/** New thread draft in a project (4.7.2). */
@Serializable
data class NewThreadDraft(val environmentId: String, val projectId: String, val branch: String? = null)

/** Add project (4.7.4). */
@Serializable
data class AddProject(val environmentId: String? = null)

/** Command palette (4.11). */
@Serializable
data object CommandPalette

// PRs.

/** Pull request list (4.8). Root of the PRs tab. */
@Serializable
data object PullRequests

@Serializable
data class PullRequest(val environmentId: String, val projectId: String, val repository: String, val number: Int, val host: String? = null)

@Serializable
data class PullRequestFile(val environmentId: String, val projectId: String, val repository: String, val number: Int, val path: String)

// Settings.

/** Settings root (4.9). Root of the Settings tab. */
@Serializable
data object Settings

/** Follow the environment's live theme, or choose a bundled/manual theme. */
@Serializable
data object Appearance

@Serializable
data object Notifications

/** Saved environments (4.2). */
@Serializable
data object Environments

/**
 * Add an environment (4.2): [mode] picks the section (`pairing`, `advanced`), [pairingUrl]
 * prefills a pairing link. [environmentId] edits a saved one (today's URL + token form).
 */
@Serializable
data class AddEnvironment(val mode: String? = null, val pairingUrl: String? = null, val environmentId: String? = null)

/**
 * Pair with a link or a QR code (4.2). No arguments: a pairing link holds a one-time code, so
 * it travels through `PairingInbox`, never through the back stack.
 */
@Serializable
data object PairEnvironment

/** The pairing QR scanner; what it reads goes to `PairingInbox`. */
@Serializable
data object ScanPairingCode

@Serializable
data object Usage

@Serializable
data object Archive

@Serializable
data object Licenses

// Sheets (§2.2), each a back-stack entry of the sheet navigator.

/** Long press on a thread row: ot3's thread menu. */
@Serializable
data class ThreadActionsSheet(val environmentId: String, val threadId: String, val initialAction: String? = null)

/** Long press on a project chip or name. */
@Serializable
data class ProjectActionsSheet(val environmentId: String, val projectId: String)

/** Home's filter button: Status, Project, Environment. */
@Serializable
data object ThreadListOptionsSheet

/** Snooze presets for a thread (from the swipe's secondary action or the thread menu). */
@Serializable
data class SnoozeUntilSheet(val environmentId: String, val threadId: String)

@kotlinx.serialization.Serializable
data class QuotaLimits(val environmentId: String? = null)

@Serializable
data object GeneralPreferences
@Serializable
data object ConnectionsPreferences
@Serializable
data class ProjectPreferences(val environmentId: String? = null, val projectId: String? = null)

@Serializable
data object SourceControlPreferences

@Serializable
data object ProvidersPreferences

@kotlinx.serialization.Serializable
data object About

@Serializable
data class HostMaintenance(val page: String, val environmentId: String? = null, val projectId: String? = null)

@Serializable
data class ThreadGit(val environmentId: String, val threadId: String)

@kotlinx.serialization.Serializable
data class ThreadTerminal(val environmentId: String, val threadId: String, val command: String? = null)
