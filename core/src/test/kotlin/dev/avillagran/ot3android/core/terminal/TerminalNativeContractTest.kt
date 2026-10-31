package dev.avillagran.ot3android.core.terminal

import dev.avillagran.ot3android.core.transport.NativeT3Transport
import dev.avillagran.ot3android.core.transport.assumeHostJni
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Lock terminal helpers and distinct read-only stream to the pinned native release. No socket is opened. */
class TerminalNativeContractTest {
    @Before fun nativeAvailable() = assumeHostJni()
    @Test fun terminalBindingsAndReadOnlyStreamAreExported() = runBlocking {
        NativeT3Transport().use { host ->
            val registry = host.describe()
            fun names(values: JSONArray) = (0 until values.length()).map { values.getJSONObject(it).getString("fn") }
            assertTrue(names(registry.getJSONArray("functions")).containsAll(listOf("terminal.open", "terminal.write", "terminal.resize")))
            assertTrue(names(registry.getJSONArray("streams")).containsAll(listOf("terminal.attach", "terminal.observe")))
            assertTrue(names(registry.getJSONArray("domain")).containsAll(listOf("terminal.context", "terminal.attach", "terminal.output")))
            val operations = host.capabilities().getJSONArray("operations")
            assertFalse((0 until operations.length()).any { operations.getString(it).startsWith("terminal") })
        }
    }
}
