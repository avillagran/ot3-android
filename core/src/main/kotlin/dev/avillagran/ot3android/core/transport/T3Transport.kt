package dev.avillagran.ot3android.core.transport

import dev.avillagran.t3.T3Batch
import dev.avillagran.t3.T3Exception
import dev.avillagran.t3.ThreadUpdate
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.io.Closeable

/**
 * One connection to a T3 environment, mirroring lib-t3-rust's `T3Client`. Protocol logic
 * (validation, typed functions, streams, resume cursors) lives in the Rust library; this
 * layer only moves calls off the main thread and turns stream polls into flows.
 *
 * Failures with a stable code are [T3Exception] from the lib, passed through unchanged;
 * the UI maps [T3Exception.code]. Nothing here reconnects or retries: the host owns that.
 *
 * Results are the lib's own JSON (`org.json`); `describe()` documents every function.
 */
data class DraftAttachmentBytes(val bytes: ByteArray, val mimeType: String)

interface T3Transport : DomainCalls, Closeable {

    /**
     * Connects (or switches) this handle. A failed switch keeps the previous session and its
     * streams. A successful one ends every open stream with `closed = true, error = null`,
     * indistinguishable from a host cancel: a supervisor must not back off on that update.
     */
    suspend fun connect(route: ConnectRoute)

    /**
     * Drops the session but keeps the handle, so [connect] can follow. Open streams end with
     * `closed = true, error = null`, as after [connect]; a supervisor must not back off on that.
     */
    suspend fun disconnect()

    /** Authenticated session permissions, read through Rust's connected request operation. */
    suspend fun authSession(): JSONObject = throw UnsupportedOperationException("authSession")

    /** ABI and library versions, operations and limits. Offline. */
    suspend fun capabilities(): JSONObject

    /** The typed API registry: functions, arguments, scopes, replay rules and streams. Offline. */
    suspend fun describe(): JSONObject

    /**
     * A pure domain function from `describe().domain` ([DomainCalls]). Offline: it needs no
     * [connect] and keeps working after [disconnect]. UI state builders that hold no
     * transport use [DomainEngine.shared] instead.
     */
    override suspend fun domain(fn: String, args: JSONObject): Any?

    /**
     * A typed function from [describe], validated in Rust before any socket and sent once.
     * [timeoutMs] (1..300000) overrides the function's deadline.
     */
    suspend fun call(fn: String, args: JSONObject = JSONObject(), timeoutMs: Int? = null): Any?

    /** Validated, bounded attachment image bytes through Rust's signed asset grant. */
    suspend fun image(id: String, name: String, mimeType: String): ByteArray =
        throw UnsupportedOperationException("image")

    /** Video/audio attachment, bounded to Desktop's 50 MiB. */
    suspend fun mediaAttachment(id: String, name: String, mimeType: String): ByteArray =
        throw UnsupportedOperationException("mediaAttachment")

    /** Host Markdown image via the existing authenticated signed-asset channel. */
    suspend fun mediaImage(threadId: String, path: String): ByteArray =
        throw UnsupportedOperationException("mediaImage")

    suspend fun draftAttachmentBytes(id: String, name: String): DraftAttachmentBytes = throw UnsupportedOperationException("draftAttachmentBytes")
    suspend fun draftAttachment(id: String, name: String): JSONObject = throw UnsupportedOperationException("draftAttachment")

    suspend fun registryIconBytes(url: String, requestId: String): ByteArray = throw UnsupportedOperationException("registryIconBytes")
    suspend fun cancelRegistryIcon(requestId: String) { }
    suspend fun historyPage(threadId: String, cursor: String): JSONObject = throw UnsupportedOperationException("historyPage")
    suspend fun pullRequestDiff(reference: JSONObject, cursor: String? = null): JSONObject = throw UnsupportedOperationException("pullRequestDiff")
    suspend fun document(resource: JSONObject): JSONObject = throw UnsupportedOperationException("document")
    fun commandOutput(reference: JSONObject): Flow<T3Batch> = throw UnsupportedOperationException("commandOutput")

    /** Cold terminal subscription. Observation uses terminal.observe; cancellation closes the stream. */
    fun terminalStream(observe: Boolean, args: JSONObject): Flow<T3Batch> =
        throw UnsupportedOperationException("terminalStream")

    /**
     * The environment's shell (projects and thread rows) as one HTTP snapshot, with the
     * orchestration protocol header the lib adds. [shellStream] keeps it current.
     */
    suspend fun shell(): JSONObject

    /**
     * The environment descriptor at [baseUrl], with no connection and no credential. With
     * [environmentId], fails with `t3_environment_mismatch` unless the descriptor names it.
     */
    suspend fun fetchDescriptor(baseUrl: String, environmentId: String? = null): JSONObject

    /**
     * Exchanges a one-time [pairingCode] for a long-lived bearer session, with no credential:
     * the lib reads the descriptor at [baseUrl] (its `environmentId` must be a UUID, and equal
     * to [environmentId] when given), then `POST /oauth/token`. With [connect], this handle
     * also connects with the new token, which ends its open streams like [connect].
     * Codes: `invalid_pairing_code`, `invalid_pairing_client`, `invalid_endpoint`,
     * `unreachable`, `invalid_t3_environment_id`, `t3_environment_mismatch`,
     * `pairing_rejected` (any refusal: unknown, used or expired code), `unsupported_pairing_token`.
     */
    suspend fun pairBearer(
        baseUrl: String,
        pairingCode: String,
        environmentId: String? = null,
        client: PairingClient = PairingClient(),
        connect: Boolean = false,
    ): PairedBearer

    /** The restored bounded window of a thread (up to 64 MiB), to paint before its stream. */
    suspend fun boundedThread(threadId: String): JSONObject

    /**
     * The environment's shell stream (projects and thread rows). Cold: each collection opens
     * one subscription and closes it when collection ends. See [threadStream] for the rules.
     */
    fun shellStream(afterSequence: Long? = null, completionMarker: Boolean = false): Flow<ThreadUpdate>

    /** Cold typed server configuration subscription; includes native usage limit sources.
     * Emits the terminal batch and completes. Opening failures throw; hosts own reconnects.
     */
    fun serverConfigStream(): Flow<T3Batch> = throw UnsupportedOperationException("serverConfigStream")

    /**
     * A thread stream. Cold: each collection opens one subscription and closes it when the
     * collector stops. Updates that carry nothing new are skipped. When the stream ends, the
     * closed update (with its `error`) is emitted last and the flow completes; resume by
     * collecting again with `afterSequence` = that update's `cursor`, after the host's backoff.
     * A poll that fails with [T3Exception] ends the stream the same way, with that code as the
     * error; `error = null` means the stream was cancelled ([connect], [disconnect], [close]).
     * Only opening the stream (validation, `not_connected`, `subscription_limit`) throws.
     *
     * [compactTurnItems] stays false: the lib does not restore compact stream snapshots yet
     * (docs/LIB_REQUESTS.md, phase 1 item 2).
     */
    fun threadStream(
        threadId: String,
        afterSequence: Long? = null,
        completionMarker: Boolean = false,
        boundedSnapshot: Boolean = true,
        compactTurnItems: Boolean = false,
    ): Flow<ThreadUpdate>

    /**
     * Probes every open stream, e.g. on resume or a network change: a dead socket then ends
     * with `stream_timeout` within about 3 s. Returns how many streams were probed.
     */
    suspend fun wakeSubscriptions(): Int

    /**
     * Frees the handle without blocking the caller; idempotent. Open streams end with
     * `closed = true, error = null`, indistinguishable from a host cancel (a supervisor must
     * not back off on that). Any call after it is a caller bug (`IllegalStateException`).
     */
    override fun close()
}

/**
 * How [T3Transport.connect] reaches an environment. Credentials never appear in [toString].
 *
 * `options` is an `org.json.JSONObject`, which has reference equality: two routes with equal
 * option contents are not `==` unless they share the same instance. Compare routes without
 * options, or keep one options instance per environment.
 */
sealed class ConnectRoute {

    /**
     * URL + bearer token. [options]: `requestTimeoutMs`, `connectTimeoutMs`,
     * `poolIdleTimeoutMs`, `maxBytes`; anything else fails with `invalid_connection_options`.
     */
    data class Bearer(
        val baseUrl: String,
        val token: String,
        val options: JSONObject? = null,
    ) : ConnectRoute() {
        override fun toString() = "Bearer(baseUrl=$baseUrl, token=<redacted>, options=$options)"
    }

    /** T3 Connect relay with a current cloud JWT; same [options] as [Bearer]. */
    data class Relay(
        val relayUrl: String,
        val cloudJwt: String,
        val environmentId: String,
        val clientId: String = "t3-mobile",
        val options: JSONObject? = null,
    ) : ConnectRoute() {
        override fun toString() =
            "Relay(relayUrl=$relayUrl, cloudJwt=<redacted>, environmentId=$environmentId, clientId=$clientId, " +
                "options=$options)"
    }
}
