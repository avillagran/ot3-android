package dev.avillagran.ot3android.core.terminal

import dev.avillagran.ot3android.core.transport.T3Transport
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** One owner per environment/thread/terminal. Transport changes never replay user input. */
class TerminalSession(
    val threadId: String, val terminalId: String = "term-1",
    resumeInitially: Boolean = false, private val onAttempt: () -> Unit = {},
) {
    data class View(
        val frame: TerminalEmulator.Frame, val cwd: String = "", val connected: Boolean = false,
        val operate: Boolean = false, val finished: Boolean = false, val exit: String = "",
        val error: Boolean = false, val uncertainWrite: Boolean = false, val applicationCursor: Boolean = false,
    )
    val emulator = TerminalEmulator()
    private val mutable = MutableStateFlow(View(emulator.frame()))
    val state = mutable.asStateFlow()
    private var transport: T3Transport? = null
    private var output = JSONObject()
    private var remoteSize: Pair<Int, Int>? = null
    private var attempted = resumeInitially
    private var generation = 0
    var inputEpoch = 0L; private set
    private fun publish() { mutable.value = mutable.value.copy(frame = emulator.frame(), applicationCursor = emulator.applicationCursor) }

    /** Cancel collection when the page stops. Any later subscription is a resume, even after uncertain attach. */
    suspend fun collect(host: T3Transport) {
        val owner = ++generation
        transport = host
        var retry = 0
        try {
            while (currentCoroutineContext().isActive && owner == generation) {
                try {
                    val auth = host.authSession()
                    check(TerminalBindings.canRead(auth)) { "terminal_permission_required" }
                    val operate = TerminalBindings.canOperate(auth)
                    val context = if (operate) host.domain("terminal.context", JSONObject().put("shell", host.shell())
                        .put("threadId", threadId).put("fallback", "")) as JSONObject else JSONObject()
                    mutable.value = mutable.value.copy(cwd = context.optString("cwd", mutable.value.cwd), operate = operate, error = false)
                    val args = if (operate) host.domain("terminal.attach", JSONObject().put("threadId", threadId)
                        .put("terminalId", terminalId).put("context", context).put("cols", emulator.cols)
                        .put("rows", emulator.rows).put("resume", attempted)) as JSONObject
                    else JSONObject().put("threadId", threadId).put("terminalId", terminalId)
                    remoteSize = if (operate) args.getInt("cols") to args.getInt("rows") else null
                    attempted = true
                    onAttempt()
                    inputEpoch++
                    host.terminalStream(!operate, args).collect stream@{ batch ->
                        for (i in 0 until batch.items.length()) {
                            val event = batch.items.getJSONObject(i)
                            val reduced = host.domain("terminal.output", JSONObject().put("state", output).put("event", event)) as JSONObject
                            if (owner != generation) return@stream
                            output = reduced.getJSONObject("state")
                            val snapshot = event.optJSONObject("snapshot")
                            if (snapshot != null && !operate) emulator.resize(snapshot.optInt("cols", emulator.cols), snapshot.optInt("rows", emulator.rows))
                            if (reduced.optBoolean("replace")) emulator.reset()
                            if (!reduced.isNull("text")) {
                                val text = reduced.getString("text")
                                var offset = 0
                                while (offset < text.length) {
                                    val end = (offset + 8192).coerceAtMost(text.length)
                                    emulator.feed(text.substring(offset, end)); offset = end
                                    yield()
                                }
                            }
                            val exit = listOf("exitCode", "exitSignal").mapNotNull { key ->
                                reduced.opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()
                            }.joinToString(" / ")
                            mutable.value = mutable.value.copy(cwd = snapshot?.optString("cwd", mutable.value.cwd) ?: mutable.value.cwd, connected = !batch.closed,
                                finished = reduced.optBoolean("finished"), exit = exit,
                                error = !reduced.isNull("error"))
                            publish(); retry = 0
                            val replies = emulator.drainReplies()
                            if (operate && !reduced.optBoolean("replace")) for (reply in replies) write(reply)
                        }
                        if (batch.closed) mutable.value = mutable.value.copy(connected = false)
                    }
                    mutable.value = mutable.value.copy(connected = false)
                    if (mutable.value.finished) break
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    mutable.value = mutable.value.copy(connected = false, error = true)
                    // Unsupported observe and authorization failures stay explicit; never fall back to attach.
                    if (e is IllegalStateException || e is T3Exception && e.code in setOf("forbidden", "unauthorized", "permission_denied", "unknown_function", "unknown_method", "method_not_found")) break
                }
                delay((500L shl retry.coerceAtMost(4)).coerceAtMost(8000)); retry++
            }
        } finally {
            if (owner == generation) { transport = null; mutable.value = mutable.value.copy(connected = false) }
        }
    }
    suspend fun resize(cols: Int, rows: Int) {
        // An observer keeps the actual host dimensions and never sends a mutation.
        if (!mutable.value.operate && attempted) return
        if (cols != emulator.cols || rows != emulator.rows) { emulator.resize(cols, rows); publish() }
        val host = transport ?: return
        if (!mutable.value.connected || mutable.value.finished) return
        val size = emulator.cols to emulator.rows
        if (size == remoteSize) return
        remoteSize = size
        try { TerminalBindings.resize(host, threadId, terminalId, size.first, size.second) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = mutable.value.copy(error = true) }
    }
    suspend fun write(data: String, epoch: Long = inputEpoch) {
        if (epoch != inputEpoch) return
        if (data.isEmpty()) return
        val host = transport ?: return
        if (!mutable.value.operate || !mutable.value.connected || mutable.value.finished) return
        if (data.toByteArray(Charsets.UTF_8).size > 65536) { mutable.value = mutable.value.copy(error = true); return }
        try { TerminalBindings.write(host, threadId, terminalId, data) }
        catch (e: CancellationException) { mutable.value = mutable.value.copy(uncertainWrite = true); throw e }
        catch (_: Exception) { mutable.value = mutable.value.copy(uncertainWrite = true) }
    }
    fun paste(text: String): String = if (emulator.bracketedPaste) "\u001b[200~$text\u001b[201~" else text
    fun arrow(final: Char) = "\u001b" + (if (emulator.applicationCursor) "O" else "[") + final
}
