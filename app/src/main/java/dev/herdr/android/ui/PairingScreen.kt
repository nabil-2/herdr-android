package dev.herdr.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.android.data.PairingException
import dev.herdr.android.data.PairingInvitation
import dev.herdr.android.data.PairingPayload

internal enum class PairingEntryMode { SCAN, PASTE, IMAGE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PairingScreen(
    initialMode: PairingEntryMode,
    cameraGranted: Boolean,
    cameraDenied: Boolean,
    pairing: Boolean,
    error: String?,
    onRequestCamera: () -> Unit,
    onPair: (String) -> Unit,
    onClearError: () -> Unit,
    onClose: () -> Unit,
    imageChooser: (((PairingImageResult) -> Unit) -> Unit)? = null,
) {
    // Invitations and their one-use secrets are intentionally never saved to a Bundle.
    var payload by remember { mutableStateOf<String?>(null) }
    var invitation by remember { mutableStateOf<PairingInvitation?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var showPaste by remember { mutableStateOf(initialMode == PairingEntryMode.PASTE) }
    var scanMode by remember { mutableStateOf(initialMode == PairingEntryMode.SCAN) }
    var entryMode by remember { mutableStateOf(initialMode) }
    var cameraSession by remember { mutableIntStateOf(0) }
    var readingImage by remember { mutableStateOf(false) }
    var initialImageLaunched by rememberSaveable { mutableStateOf(false) }
    val review: (String) -> String? = { raw ->
        try {
            val parsed = PairingPayload.parse(raw)
            onClearError()
            invitation = parsed
            payload = raw
            localError = null
            null
        } catch (failure: PairingException) {
            failure.message ?: "This is not a Herdr pairing code. Request a new code on your computer."
        }
    }
    val startScan: () -> Unit = {
        invitation = null
        payload = null
        showPaste = false
        localError = null
        onClearError()
        scanMode = true
        entryMode = PairingEntryMode.SCAN
        cameraSession++
        onRequestCamera()
    }
    val imageResult: (PairingImageResult) -> Unit = { result ->
        readingImage = false
        when (result) {
            is PairingImageResult.Code -> localError = review(result.value)
            is PairingImageResult.Error -> localError = result.message
            PairingImageResult.Cancelled -> Unit
        }
    }
    val systemImagePicker = rememberPairingImagePicker(onReading = { readingImage = true }, onResult = imageResult)
    val chooseImage: () -> Unit = {
        invitation = null
        payload = null
        showPaste = false
        scanMode = false
        entryMode = PairingEntryMode.IMAGE
        localError = null
        onClearError()
        readingImage = true
        if (imageChooser == null) systemImagePicker() else imageChooser(imageResult)
    }
    LaunchedEffect(Unit) {
        if (initialMode == PairingEntryMode.IMAGE && !initialImageLaunched) {
            initialImageLaunched = true
            chooseImage()
        }
    }
    val useNewCode: () -> Unit = {
        invitation = null
        payload = null
        localError = null
        onClearError()
        cameraSession++
        if (entryMode == PairingEntryMode.IMAGE) chooseImage()
        else if (entryMode == PairingEntryMode.PASTE) { scanMode = false; showPaste = true }
    }
    BackHandler(onBack = onClose)
    Scaffold(
        containerColor = CanvasColor,
        topBar = {
            TopAppBar(
                title = { Text("Pair a server", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onClose) { SymbolIcon(Symbol.BACK, "Cancel pairing") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CanvasColor),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val offer = invitation
            if (offer != null) {
                SymbolIcon(Symbol.SERVER, modifier = Modifier.size(40.dp))
                Text("Connect to ${offer.name}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("This phone will be able to view terminals and send commands to this computer.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(offer.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(offer.url, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("pairing-host"))
                    }
                }
                if (offer.isPossiblyExpired()) Text(
                    "This code may have expired. If pairing fails, request a fresh code on your computer.",
                    color = Amber, style = MaterialTheme.typography.bodySmall,
                )
                if (error != null) ErrorNotice(error, onClearError)
                Button(
                    onClick = { payload?.let(onPair) }, enabled = !pairing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("confirm-pairing"),
                ) {
                    if (pairing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("  Pairing…")
                    } else Text("Pair & connect")
                }
                OutlinedButton(onClick = useNewCode, enabled = !pairing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Use a new code") }
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
            } else {
                Text("Pair your computer.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Open the pairing code on your server. Scan it, choose a screenshot, or paste the code. Connect both devices to the same Tailscale network first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large).background(Color.Black)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large),
                    contentAlignment = Alignment.Center,
                ) {
                    if (scanMode && cameraGranted && !showPaste && localError == null) {
                        key(cameraSession) {
                            PairingCamera(
                                onCode = { raw -> localError = review(raw) },
                                onError = { localError = it },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Box(Modifier.fillMaxSize().padding(48.dp).border(2.dp, Mint, MaterialTheme.shapes.medium))
                    } else {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (readingImage) CircularProgressIndicator(Modifier.size(40.dp))
                            else SymbolIcon(Symbol.QR, modifier = Modifier.size(56.dp))
                            Text(
                                if (readingImage) "Reading QR screenshot…" else if (!scanMode) "Choose a screenshot, scan, or paste a code" else if (cameraDenied) "Camera access is off" else if (!cameraGranted) "Camera permission needed" else "Ready for a fresh code",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (localError != null) ErrorNotice(localError!!, { localError = null; cameraSession++ })
                if (error != null) ErrorNotice(error, onClearError)
                if (cameraDenied && scanMode) Text("Allow camera access to scan, or choose a screenshot or paste the code.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!scanMode || !cameraGranted || localError != null) Button(onClick = startScan, enabled = !readingImage, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (localError != null) "Scan again" else "Scan pairing code")
                }
                OutlinedButton(onClick = chooseImage, enabled = !readingImage, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("choose-pairing-image")) { Text("Choose QR screenshot") }
                OutlinedButton(onClick = { showPaste = true; entryMode = PairingEntryMode.PASTE; onClearError() }, enabled = !readingImage, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("paste-pairing-code")) { Text("Paste pairing code") }
                Text("Codes are read on this device. No images are saved or uploaded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (showPaste) PastePairingDialog(
        onDismiss = { showPaste = false },
        onReview = { raw ->
            val failure = review(raw)
            if (failure == null) showPaste = false
            failure
        },
    )
}

@Composable
private fun PastePairingDialog(onDismiss: () -> Unit, onReview: (String) -> String?) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste pairing code") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Paste the complete pairing code text from your computer. You will review the server before connecting.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(4097); error = null },
                    label = { Text("Pairing code (JSON)") }, maxLines = 4,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().testTag("pairing-payload"),
                )
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { error = onReview(text) }, enabled = text.isNotBlank(), modifier = Modifier.testTag("review-pairing")) { Text("Review code") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
