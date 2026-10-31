package dev.avillagran.ot3android.core.terminal

import dev.avillagran.ot3android.core.transport.T3Transport
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class TerminalBindingsTest {
    private var session = JSONObject("""{"authenticated":true,"scopes":["terminal:operate"]}""")
    private val sent = mutableListOf<Pair<String, JSONObject>>()
    private var disconnect = false
    private val host = Proxy.newProxyInstance(T3Transport::class.java.classLoader, arrayOf(T3Transport::class.java)) { _, method, args ->
        when (method.name) {
            "authSession" -> session
            "call" -> { sent += args!![0] as String to args[1] as JSONObject; if (disconnect) error("disconnected"); null }
            else -> error("unexpected operation ${method.name}")
        }
    } as T3Transport

    @Test fun readOnlyCannotWriteOrResize() = runTest {
        session = JSONObject("""{"authenticated":true,"scopes":["terminal:read","terminal:write"]}""")
        try { TerminalBindings.write(host, "t", "pty", "id\r"); fail() } catch (_: IllegalStateException) { }
        try { TerminalBindings.resize(host, "t", "pty", 80, 24); fail() } catch (_: IllegalStateException) { }
        assertTrue(sent.isEmpty())
    }
    @Test fun unauthenticatedOrMissingScopesFailClosed() {
        assertFalse(TerminalBindings.canOperate(JSONObject("""{"authenticated":false,"scopes":["terminal:operate"]}""")))
        assertFalse(TerminalBindings.canOperate(JSONObject("""{"authenticated":true}""")))
        assertTrue(TerminalBindings.canOperate(JSONObject("""{"authenticated":true,"permissions":["terminal:operate"]}""")))
    }
    @Test fun unknownDeliveryIsNeverRetried() = runTest {
        disconnect = true
        try { TerminalBindings.write(host, "t", "pty", "printf 東京\r"); fail() } catch (_: IllegalStateException) { }
        assertEquals(1, sent.size)
        assertEquals("terminal.write", sent.single().first)
        assertEquals("printf 東京\r", sent.single().second.getString("data"))
    }
    @Test fun resizeUsesTheSameThreadAndTerminalWithoutShellExecution() = runTest {
        TerminalBindings.resize(host, "thread", "term-1", 120, 18)
        val (fn, payload) = sent.single()
        assertEquals("terminal.resize", fn)
        assertEquals("thread", payload.getString("threadId"))
        assertEquals("term-1", payload.getString("terminalId"))
        assertEquals(120, payload.getInt("cols"))
        assertEquals(18, payload.getInt("rows"))
    }
}
