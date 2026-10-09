package dev.herdr.android.data

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CrashDiagnosticsDeviceTest {
    @Test fun ownerPrivateRecordCanBeReadAndDeletedWithoutRecordingMessages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "diagnostic-test-" + UUID.randomUUID() + ".txt"
        val diagnostics = CrashDiagnostics(context, name)
        try {
            assertNull(diagnostics.read())
            assertTrue(diagnostics.record(IllegalArgumentException("synthetic secret invitation"), "0.4.0", 36))
            val report = diagnostics.read()!!
            assertFalse(report.contains("synthetic secret invitation"))
            assertTrue(report.contains("java.lang.IllegalArgumentException"))
            assertEquals(384, Os.stat(File(context.filesDir, name).absolutePath).st_mode and 511)
            assertTrue(diagnostics.clear())
            assertNull(diagnostics.read())
        } finally { diagnostics.clear() }
    }

    @Test fun oversizedUnstructuredAndSymlinkRecordsAreRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "diagnostic-test-" + UUID.randomUUID() + ".txt"
        val file = File(context.filesDir, name)
        val target = File(context.filesDir, name + ".target")
        val diagnostics = CrashDiagnostics(context, name)
        try {
            file.writeText("x".repeat(CrashDiagnostics.MAX_BYTES + 1))
            Os.chmod(file.absolutePath, 384)
            assertNull(diagnostics.read())
            file.writeText("private token is not a structured crash record")
            assertNull(diagnostics.read())
            file.delete()
            target.writeText("synthetic target")
            Os.symlink(target.absolutePath, file.absolutePath)
            assertFalse(diagnostics.record(IllegalStateException(), "0.4.0", 36))
            assertNull(diagnostics.read())
            assertEquals("synthetic target", target.readText())
        } finally { file.delete(); target.delete() }
    }
}
