package dev.herdr.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.herdr.android.data.CrashDiagnostics

/** Diagnostics stay on this device until the user explicitly copies them. */
@Composable
internal fun ConnectionDiagnosticsNotice(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val diagnostics = remember(context) { CrashDiagnostics(context) }
    var report by remember(diagnostics) { mutableStateOf(diagnostics.read()) }
    var notice by remember { mutableStateOf<String?>(null) }
    val current = report ?: return
    OutlinedCard(modifier.fillMaxWidth().testTag("connection-diagnostics")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Previous app error", style = MaterialTheme.typography.titleSmall)
            Text("Copy a limited diagnostic to help investigate.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row {
                TextButton(onClick = {
                    notice = if (runCatching {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Herdr Pocket diagnostic", current))
                    }.isSuccess) "Diagnostic copied" else "Could not copy the diagnostic."
                }, modifier = Modifier.testTag("copy-connection-diagnostic")) { Text("Copy diagnostic") }
                TextButton(onClick = {
                    if (diagnostics.clear()) report = null else notice = "Could not delete the diagnostic."
                }, modifier = Modifier.testTag("delete-connection-diagnostic")) { Text("Delete") }
            }
        }
    }
}
