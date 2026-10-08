package dev.herdr.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun PairingCamera(onCode: (String) -> Unit, onError: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentCode = rememberUpdatedState(onCode)
    val currentError = rememberUpdatedState(onError)
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    DisposableEffect(lifecycleOwner, previewView) {
        val active = AtomicBoolean(true)
        val detected = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "pairing-qr").apply { isDaemon = true } }
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var analysis: ImageAnalysis? = null
        providerFuture.addListener({
            if (active.get()) {
                try {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        currentError.value("Camera permission is needed to scan. You can paste a pairing code instead.")
                    } else {
                        val cameraProvider = providerFuture.get()
                        provider = cameraProvider
                        val selector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                        val cameraPreview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
                        val cameraAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                                ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                            ).build())
                            .build()
                        preview = cameraPreview
                        analysis = cameraAnalysis
                        var lastFrameNanos = 0L
                        cameraAnalysis.setAnalyzer(executor) { image ->
                            try {
                                val now = System.nanoTime()
                                if (active.get() && !detected.get() && now - lastFrameNanos >= 150_000_000L) {
                                    lastFrameNanos = now
                                    val plane = image.planes[0]
                                    val bytes = copyLuminance(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
                                    val decoded = decodePairingQr(bytes, image.width, image.height)
                                    if (decoded != null && detected.compareAndSet(false, true)) mainExecutor.execute {
                                        if (active.get()) currentCode.value(decoded)
                                    }
                                }
                            } catch (_: Exception) {
                                // A transient camera frame may be incomplete. Never expose frame contents.
                            } finally {
                                image.close()
                            }
                        }
                        cameraProvider.bindToLifecycle(lifecycleOwner, selector, cameraPreview, cameraAnalysis)
                    }
                } catch (_: Exception) {
                    currentError.value("Camera unavailable. Try scanning again, or paste the pairing code.")
                }
            }
        }, mainExecutor)
        onDispose {
            active.set(false)
            analysis?.clearAnalyzer()
            val useCases = listOfNotNull(preview, analysis).toTypedArray()
            if (useCases.isNotEmpty()) provider?.unbind(*useCases)
            executor.shutdownNow()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier.semantics { contentDescription = "Camera preview for pairing code" })
}
