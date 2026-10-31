package dev.avillagran.ot3android.core.terminal

import dev.avillagran.ot3android.core.transport.T3Transport
import org.json.JSONObject

/** Terminal attach is a mutation, including on reconnect. Never use it to probe read access. */
object TerminalBindings {
    const val SCOPE = "terminal:operate"
    const val READ_SCOPE = "terminal:read"

    fun canOperate(session: JSONObject): Boolean = hasScope(session, SCOPE)
    fun canRead(session: JSONObject): Boolean = canOperate(session) || hasScope(session, READ_SCOPE)

    private fun hasScope(session: JSONObject, scope: String): Boolean {
        if (!session.optBoolean("authenticated")) return false
        val scopes = session.optJSONArray("permissions") ?: session.optJSONArray("scopes") ?: return false
        return (0 until scopes.length()).any { scopes.optString(it) == scope }
    }

    /** All writes go through Rust's validated typed API, once. No retry after unknown delivery. */
    suspend fun write(transport: T3Transport, threadId: String, terminalId: String, data: String) {
        check(canOperate(transport.authSession())) { "terminal_permission_required" }
        transport.call("terminal.write", JSONObject().put("threadId", threadId)
            .put("terminalId", terminalId).put("data", data))
    }

    suspend fun resize(transport: T3Transport, threadId: String, terminalId: String, cols: Int, rows: Int) {
        check(canOperate(transport.authSession())) { "terminal_permission_required" }
        transport.call("terminal.resize", JSONObject().put("threadId", threadId)
            .put("terminalId", terminalId).put("cols", cols).put("rows", rows))
    }
}
