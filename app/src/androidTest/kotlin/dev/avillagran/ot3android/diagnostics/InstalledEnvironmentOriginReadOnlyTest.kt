package dev.avillagran.ot3android.diagnostics

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.core.data.local.Ot3Database
import dev.avillagran.ot3android.core.transport.NativeT3Transport
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URI

/** Opt-in route identity diagnosis: origin host/port only, never credentials or stored URL text. */
class InstalledEnvironmentOriginReadOnlyTest {
    @Test fun reportSanitizedOriginsAndPublicDescriptors() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ot3a.installedOriginReadOnly") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(instrumentation is InstalledCatalogReadOnlyRunner)
        val context = instrumentation.targetContext
        assertEquals(Application::class.java, context.applicationContext.javaClass)
        val report = JSONObject().put("applicationGraphStarted", false).put("authenticatedRequests", false)
        val rows = JSONArray()
        val path = context.getDatabasePath(Ot3Database.NAME)
        assertTrue(path.isFile)
        SQLiteDatabase.openDatabase(path.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            assertTrue(db.isReadOnly)
            db.rawQuery("SELECT label,environmentId,baseUrl FROM environments LIMIT 32", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val row = JSONObject().put("savedLabel", cursor.getString(0)).put("savedEnvironmentId", cursor.getString(1))
                    try {
                        val uri = URI(cursor.getString(2))
                        check(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank())
                        // Rebuild an origin; userinfo, paths, query and fragments never enter output or GET.
                        val origin = URI(uri.scheme, null, uri.host, uri.port, null, null, null).toASCIIString()
                        row.put("scheme", uri.scheme).put("host", uri.host).put("port", uri.port)
                        val transport = NativeT3Transport()
                        try {
                            val descriptor = transport.fetchDescriptor(origin)
                            val id = descriptor.optString("environmentId")
                            row.put("publicDescriptorId", id).put("publicDescriptorLabel", descriptor.optString("label").take(80))
                                .put("matchesSavedIdentity", id == cursor.getString(1))
                        } catch (error: T3Exception) { row.put("descriptorStatus", error.code) }
                        finally { transport.shutdown() }
                    } catch (_: Exception) { row.put("originStatus", "unavailable") }
                    rows.put(row)
                }
            }
        }
        report.put("environments", rows)
        instrumentation.sendStatus(0, Bundle().apply { putString("ot3a.origin.preflight", report.toString()) })
    }
}
