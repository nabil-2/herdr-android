package dev.herdr.android.data

import android.content.Context
import android.os.Build
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** A single bounded internal record. Messages, request data and credentials are never recorded. */
internal class CrashDiagnostics(context: Context, fileName: String = "last-app-crash.txt") {
    private val file = File(context.applicationContext.filesDir, fileName).also {
        require(fileName.matches(Regex("[A-Za-z0-9_.-]{1,96}")) && fileName !in setOf(".", ".."))
    }

    fun record(error: Throwable, appVersion: String, sdk: Int): Boolean = runCatching {
        val bytes = sanitize(error, appVersion, sdk).toByteArray(Charsets.UTF_8)
        val closeOnExec = if (Build.VERSION.SDK_INT >= 27) OsConstants.O_CLOEXEC else 0
        val descriptor = Os.open(file.absolutePath,
            OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_NOFOLLOW or closeOnExec or OsConstants.O_NONBLOCK, 384)
        try {
            val stat = Os.fstat(descriptor)
            check(stat.st_uid == Process.myUid() && (stat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFREG && stat.st_nlink == 1L)
            Os.fchmod(descriptor, 384)
            Os.ftruncate(descriptor, 0)
            var offset = 0
            while (offset < bytes.size) {
                val written = Os.write(descriptor, bytes, offset, bytes.size - offset)
                check(written > 0)
                offset += written
            }
            Os.fsync(descriptor)
        } finally { Os.close(descriptor) }
        true
    }.getOrDefault(false)

    fun read(): String? = runCatching {
        val closeOnExec = if (Build.VERSION.SDK_INT >= 27) OsConstants.O_CLOEXEC else 0
        val descriptor = Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or closeOnExec or OsConstants.O_NONBLOCK, 0)
        try {
            val stat = Os.fstat(descriptor)
            if (stat.st_uid != Process.myUid() || (stat.st_mode and OsConstants.S_IFMT) != OsConstants.S_IFREG ||
                (stat.st_mode and (OsConstants.S_IRWXG or OsConstants.S_IRWXO)) != 0 ||
                stat.st_nlink != 1L || stat.st_size !in 1..MAX_BYTES.toLong()) return@runCatching null
            val buffer = ByteArray(MAX_BYTES + 1)
            var size = 0
            while (size < buffer.size) {
                val count = Os.read(descriptor, buffer, size, buffer.size - size)
                if (count <= 0) break
                size += count
            }
            if (size !in 1..MAX_BYTES) return@runCatching null
            val report = buffer.copyOf(size).toString(Charsets.UTF_8)
            report.takeIf(::validRecord)
        } finally { Os.close(descriptor) }
    }.getOrNull()

    fun clear(): Boolean = try {
        Os.remove(file.absolutePath)
        true
    } catch (error: ErrnoException) {
        error.errno == OsConstants.ENOENT
    } catch (_: Exception) {
        false
    }

    companion object {
        internal const val MAX_BYTES = 8192
        private const val MAX_FRAMES = 20
        private const val HEADER = "Herdr Pocket crash diagnostic"
        private val installed = AtomicBoolean(false)
        private val className = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
        private val methodName = Regex("(?:[A-Za-z_$][A-Za-z0-9_$-]*|<init>|<clinit>)")
        private val sourceName = Regex("[A-Za-z0-9_$. -]{1,96}\\.(?:kt|java|c|cpp)")
        private val versionName = Regex("[A-Za-z0-9][A-Za-z0-9.+_-]{0,31}")
        private val frameLine = Regex("at [A-Za-z_$][A-Za-z0-9_$.]*\\.(?:[A-Za-z_$][A-Za-z0-9_$-]*|<init>|<clinit>)\\((?:[A-Za-z0-9_$. -]{1,96}\\.(?:kt|java|c|cpp)|Unknown Source):-?[0-9]{1,10}\\)")

        fun install(context: Context) {
            try { installHandler(context) } catch (_: Exception) {
                // A diagnostics setup failure must not prevent the app from starting.
            }
        }

        private fun installHandler(context: Context) {
            val previous = Thread.getDefaultUncaughtExceptionHandler() ?: return
            if (!installed.compareAndSet(false, true)) return
            val diagnostics = CrashDiagnostics(context)
            @Suppress("DEPRECATION")
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown" }.getOrDefault("unknown")
            val recording = AtomicBoolean(false)
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                try {
                    if (recording.compareAndSet(false, true)) diagnostics.record(error, version, Build.VERSION.SDK_INT)
                } catch (_: Throwable) {
                    // Diagnostics must never replace Android's fatal-error behavior.
                } finally {
                    previous.uncaughtException(thread, error)
                }
            }
        }

        /** Deliberately never calls Throwable.toString(), message, or printStackTrace(). */
        internal fun sanitize(error: Throwable, appVersion: String, sdk: Int): String {
            val causes = mutableListOf<Throwable>()
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            var current: Throwable? = error
            while (causes.size < 4) {
                val item = current ?: break
                if (!seen.add(item)) break
                causes += item
                current = runCatching { item.cause }.getOrNull()
            }
            val report = StringBuilder()
            fun append(line: String) {
                if (report.length + line.length + 1 <= MAX_BYTES) report.append(line).append('\n')
            }
            append(HEADER)
            append("appVersion: " + appVersion.takeIf { versionName.matches(it) }.orEmpty().ifEmpty { "unknown" })
            append("androidSdk: " + sdk.coerceIn(1, 1000))
            append("exceptionClass: " + safeClass(error.javaClass.name))
            causes.drop(1).forEach { append("causeClass: " + safeClass(it.javaClass.name)) }
            var remaining = MAX_FRAMES
            // Root-cause frames are usually most useful when Android wraps an error.
            for (cause in causes.asReversed()) {
                for (frame in cause.stackTrace) {
                    if (remaining == 0) break
                    val owner = safeClass(frame.className)
                    val method = frame.methodName.takeIf { it.length <= 96 && methodName.matches(it) } ?: "unknown"
                    val source = frame.fileName?.takeIf { sourceName.matches(it) } ?: "Unknown Source"
                    append("at " + owner + "." + method + "(" + source + ":" + frame.lineNumber + ")")
                    remaining--
                }
            }
            return report.toString()
        }

        private fun safeClass(value: String): String = value.takeIf { it.length <= 160 && className.matches(it) } ?: "unknown"

        private fun validRecord(value: String): Boolean {
            if (value.any { it != '\n' && it.code !in 32..126 }) return false
            val lines = value.trimEnd('\n').split('\n')
            if (lines.size !in 4..(4 + 3 + MAX_FRAMES) || lines[0] != HEADER ||
                !lines[1].startsWith("appVersion: ") || !versionName.matches(lines[1].removePrefix("appVersion: ")) ||
                !lines[2].startsWith("androidSdk: ") || lines[2].removePrefix("androidSdk: ").toIntOrNull()?.let { it in 1..1000 } != true ||
                !lines[3].startsWith("exceptionClass: ") || !className.matches(lines[3].removePrefix("exceptionClass: "))) return false
            var causes = 0
            var frames = 0
            for (line in lines.drop(4)) {
                when {
                    line.startsWith("causeClass: ") && frames == 0 && ++causes <= 3 ->
                        if (!className.matches(line.removePrefix("causeClass: "))) return false
                    frameLine.matches(line) && ++frames <= MAX_FRAMES -> Unit
                    else -> return false
                }
            }
            return true
        }
    }
}
