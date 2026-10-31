package dev.avillagran.ot3android.core.transport

import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The real Rust core through the AAR's adapter and the host JNI library; no network.
 *
 * The exact pins (0.3.7, 125 functions, 14 streams, 127 domain functions) are intentional: bumping
 * libs/lib-t3-rust.lock must fail here until the lib's changes are read and the pins move
 * with them (docs/DECISIONS.md).
 */
class NativeSmokeTest {

    private lateinit var transport: NativeT3Transport

    @Before
    fun setUp() {
        assumeHostJni()
        transport = NativeT3Transport()
    }

    @After
    fun tearDown() {
        if (::transport.isInitialized) runBlocking { transport.shutdown() }
    }

    @Test
    fun capabilitiesReportTheTargetedLib() = runBlocking {
        val capabilities = transport.capabilities()

        assertEquals(3, capabilities.getInt("abiVersion"))
        assertEquals("0.3.7", capabilities.getString("libraryVersion"))
        assertEquals(125, capabilities.getInt("functions"))
        assertEquals(127, capabilities.getInt("domainFunctions"))
        val operations = capabilities.getJSONArray("operations").strings()
        assertEquals(operations.toString(), 24, operations.size)
        assertTrue(operations.toString(), operations.containsAll(listOf("call", "describe", "domain", "wakeSubscriptions")))
    }

    @Test
    fun describeListsTheTypedRegistry() = runBlocking {
        val api = transport.describe()

        assertEquals(125, api.getJSONArray("functions").length())
        assertEquals(14, api.getJSONArray("streams").length())
        val domain = api.getJSONArray("domain")
        assertEquals(127, domain.length())
        val names = List(domain.length()) { domain.getJSONObject(it).getString("fn") }
        assertTrue(names.toString(), names.containsAll(listOf("projection.apply", "timeline.view", "sidebar.view", "projects.identity", "awareness.view")))
        val modules = names.groupingBy { it.substringBefore('.') }.eachCount()
        assertEquals(modules.toString(), 34, modules.size)
        assertEquals(
            mapOf(
                "diffReview" to 2, "toolInspector" to 4, "planWorkflow" to 5, "checkpointRevert" to 8, "usageSummary" to 3,
                "quotaSources" to 5, "pullRequests" to 5, "branchPicker" to 6, "gitControls" to 7, "nativeSettings" to 7,
                "scopedSettings" to 10, "subagents" to 4, "theme" to 1, "i18n" to 6, "timeline" to 4,
            ),
            modules.filterKeys { it in V034_MODULES },
        )
    }

    @Test
    fun domainRunsWithoutAConnection() = runBlocking {
        assertEquals("O3", transport.domain("projects.monogram", JSONObject().put("name", "ot3-android")))
    }

    @Test
    fun domainValidatesArguments() {
        val unknown = assertThrows(T3Exception::class.java) { runBlocking { transport.domain("sidebar.nope", JSONObject()) } }
        assertEquals("unknown_function", unknown.code)
        val missing = assertThrows(T3Exception::class.java) { runBlocking { transport.domain("timeline.view", JSONObject()) } }
        assertEquals("invalid_projection", missing.code)
    }

    @Test
    fun callValidatesArgumentsBeforeConnecting() {
        val error = assertThrows(T3Exception::class.java) {
            runBlocking { transport.call("vcs.refreshStatus", JSONObject().put("cwd", "")) }
        }
        assertEquals("invalid_cwd", error.code)
    }

    @Test
    fun unknownFunctionIsAStableCode() {
        val error = assertThrows(T3Exception::class.java) { runBlocking { transport.call("vcs.nope") } }
        assertEquals("unknown_function", error.code)
    }

    @Test
    fun validCallWithoutConnectionIsNotConnected() {
        val error = assertThrows(T3Exception::class.java) {
            runBlocking { transport.call("vcs.refreshStatus", JSONObject().put("cwd", "/repo")) }
        }
        assertEquals("not_connected", error.code)
    }

    @Test
    fun invalidStreamArgumentsFailBeforeAnySocket() {
        val error = assertThrows(T3Exception::class.java) {
            runBlocking { transport.threadStream("").collect {} }
        }
        assertEquals("invalid_thread_id", error.code)
    }

    private fun JSONArray.strings() = List(length()) { getString(it) }

    private companion object {
        /** The modules v0.3.4 added or extended (timeline.minimap, i18n.t / catalog / resolve / languages). */
        val V034_MODULES = setOf(
            "diffReview", "toolInspector", "planWorkflow", "checkpointRevert", "usageSummary", "quotaSources", "pullRequests",
            "branchPicker", "gitControls", "nativeSettings", "scopedSettings", "subagents", "theme", "i18n", "timeline",
        )
    }
}
