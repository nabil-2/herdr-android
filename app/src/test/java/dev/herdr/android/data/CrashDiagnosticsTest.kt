package dev.herdr.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashDiagnosticsTest {
    @Test fun messagesCauseMessagesAndUnsafeSourcePathsAreExcluded() {
        val secret = "A".repeat(43)
        val token = "B".repeat(42) + "A"
        val cause = IllegalArgumentException("https://private.example/pair?code=" + secret).apply {
            stackTrace = arrayOf(
                StackTraceElement("dev.herdr.android.HerdrViewModel", "connect", "HerdrViewModel.kt", 123),
                StackTraceElement("dev.herdr.android.Unsafe", "dispatch", "https://private.example/" + secret, 1),
            )
        }
        val error = RuntimeException("QR payload code=" + secret + " token=" + token, cause)
        val report = CrashDiagnostics.sanitize(error, "0.4.0", 36)
        assertFalse(report.contains(secret))
        assertFalse(report.contains(token))
        assertFalse(report.contains("private.example"))
        assertFalse(report.contains("QR payload"))
        assertTrue(report.contains("exceptionClass: java.lang.RuntimeException"))
        assertTrue(report.contains("causeClass: java.lang.IllegalArgumentException"))
        assertTrue(report.contains("dev.herdr.android.HerdrViewModel.connect(HerdrViewModel.kt:123)"))
        assertTrue(report.contains("Unknown Source"))
    }

    @Test fun framesAndBytesAreBoundedAndUntrustedMetadataCannotAddDiagnosticLines() {
        val error = IllegalStateException("secret").apply {
            stackTrace = Array(1000) {
                StackTraceElement("dev.herdr.android." + "A".repeat(200), "bad\nmessage", "bad\nname.kt", it)
            }
        }
        val report = CrashDiagnostics.sanitize(error, "version\nsecret", 999999)
        assertEquals(20, report.lineSequence().count { it.startsWith("at ") })
        assertTrue(report.toByteArray(Charsets.UTF_8).size <= CrashDiagnostics.MAX_BYTES)
        assertFalse(report.contains("secret"))
        assertFalse(report.contains("bad"))
        assertTrue(report.contains("appVersion: unknown"))
        assertTrue(report.contains("androidSdk: 1000"))
    }
}
