package com.hermexapp.android.features.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Full-screen QR scanner for the 6.6 pairing flow (operator-approved 2026-07-21).
 *
 * Entry point for the "Scan QR or paste pairing URL" affordance on the
 * Connect page. Behaviour:
 *
 *  1. **Camera available + permission granted** → live CameraX preview with a
 *     reticle, torch toggle, and a "paste instead" escape hatch. The first
 *     decoded QR code fires [onQrDetected] exactly once; the parent then
 *     calls `viewModel.pairFromText(text)` and removes this composable.
 *  2. **No camera hardware** → inline message routing to the paste dialog.
 *  3. **Permission denied** → inline message with a "paste instead" button
 *     (the paste dialog is always reachable, so a denied camera never
 *     blocks pairing).
 *
 * Every camera frame is decoded **locally** by [QrDecoder] (ZXing) and
 * discarded immediately — nothing is recorded, cached, or transmitted.
 */
@Composable
fun CameraQrScannerView(
    modifier: Modifier = Modifier,
    onQrDetected: (String) -> Unit,
    onUsePasteInstead: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val hasCamera = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var permissionGranted by remember {
        mutableStateOf(
            hasCamera &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
    }

    // Request permission once on first composition when a camera exists but
    // we don't yet have the grant. LaunchedEffect(Unit) fires exactly once, so
    // a denial does not re-trigger an immediate re-request loop; the user can
    // still reach pairing via the paste fallback either way.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (hasCamera && !permissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    when {
        !hasCamera -> CameraFallbackMessage(
            message = "This device has no camera. Paste your pairing URL instead.",
            onUsePasteInstead = onUsePasteInstead,
            onDismiss = onDismiss,
        )
        !permissionGranted -> CameraFallbackMessage(
            message = "Camera permission is off. You can still pair by pasting the URL.",
            onUsePasteInstead = onUsePasteInstead,
            onDismiss = onDismiss,
        )
        else -> CameraScannerScreen(
            modifier = modifier,
            onQrDetected = onQrDetected,
            onUsePasteInstead = onUsePasteInstead,
            onDismiss = onDismiss,
        )
    }
}

/**
 * The live camera scanner: CameraX preview + [ImageAnalysis] frame decode +
 * reticle overlay + torch toggle + paste fallback.
 */
@Composable
private fun CameraScannerScreen(
    modifier: Modifier,
    onQrDetected: (String) -> Unit,
    onUsePasteInstead: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // rememberUpdatedState so the DisposableEffect (keyed on lifecycleOwner)
    // always calls the *current* onQrDetected, not a stale capture.
    val currentOnQrDetected by rememberUpdatedState(onQrDetected)

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    var detected by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(lifecycleOwner) {
        val executor = ContextCompat.getMainExecutor(context)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            if (detected) return@addListener
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(executor) { imageProxy ->
                            if (detected) {
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            val result = QrDecoder.decodeImageProxy(imageProxy)
                            if (result != null) {
                                detected = true
                                currentOnQrDetected(result)
                            }
                        }
                    }

                cameraProvider.unbindAll()
                val boundCamera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                )
                camera = boundCamera
                hasFlash = boundCamera.cameraInfo.hasFlashUnit()
            } catch (_: Exception) {
                cameraError = true
            }
        }, executor)

        onDispose {
            // Explicit unbind for immediate cleanup when the composable is
            // removed (CameraX also unbinds on lifecycle destroy, but this
            // releases the camera instantly so the paste dialog / navigation
            // doesn't hold the hardware).
            try {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            } catch (_: Exception) {
                // Provider may not be ready yet — safe to ignore.
            }
        }
    }

    if (cameraError) {
        CameraFallbackMessage(
            message = "Camera unavailable. Paste your pairing URL instead.",
            onUsePasteInstead = onUsePasteInstead,
            onDismiss = onDismiss,
        )
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        ScannerReticle()

        // Top bar: close (left) + torch (right, only when the camera has a flash).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close scanner",
                    tint = Color.White,
                )
            }
            if (hasFlash) {
                // Text toggle (not an icon): the core Material icon set ships
                // no flash/brightness glyph, and adding material-icons-extended
                // just for one toggle is not worth the dependency. A labelled
                // button is unambiguous and fully accessible.
                TextButton(
                    onClick = {
                        torchOn = !torchOn
                        camera?.cameraControl?.enableTorch(torchOn)
                    },
                ) {
                    Text(
                        if (torchOn) "Torch: ON" else "Torch: off",
                        color = Color.White,
                    )
                }
            }
        }

        // Bottom: instruction + paste fallback (always reachable).
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Point the camera at the QR code shown by `python -m jkp pair`",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
            TextButton(onClick = onUsePasteInstead) {
                Text("Paste URL instead", color = Color.White)
            }
        }
    }
}

/**
 * Reticle overlay: a semi-transparent dark mask with a clear square scan
 * window in the centre, plus white corner brackets at the window's corners.
 * Drawn with plain [Canvas] primitives — no external assets, no blend-mode
 * tricks (the four surrounding rectangles leave the centre naturally clear).
 */
@Composable
private fun ScannerReticle() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val scanSize = minOf(size.width, size.height) * 0.7f
        val left = (size.width - scanSize) / 2f
        val top = (size.height - scanSize) / 2f
        val right = left + scanSize
        val bottom = top + scanSize
        val cornerLen = scanSize * 0.12f
        val strokeWidth = 4.dp.toPx()
        val overlayColor = Color.Black.copy(alpha = 0.5f)
        val bracketColor = Color.White

        // Dark mask: four rectangles around the clear scan window.
        drawRect(overlayColor, topLeft = Offset.Zero, size = Size(size.width, top))
        drawRect(overlayColor, topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
        drawRect(overlayColor, topLeft = Offset(0f, top), size = Size(left, scanSize))
        drawRect(overlayColor, topLeft = Offset(right, top), size = Size(size.width - right, scanSize))

        // Corner brackets (L-shaped strokes at each corner of the scan window).
        // Top-left
        drawLine(bracketColor, Offset(left, top), Offset(left + cornerLen, top), strokeWidth)
        drawLine(bracketColor, Offset(left, top), Offset(left, top + cornerLen), strokeWidth)
        // Top-right
        drawLine(bracketColor, Offset(right, top), Offset(right - cornerLen, top), strokeWidth)
        drawLine(bracketColor, Offset(right, top), Offset(right, top + cornerLen), strokeWidth)
        // Bottom-left
        drawLine(bracketColor, Offset(left, bottom), Offset(left + cornerLen, bottom), strokeWidth)
        drawLine(bracketColor, Offset(left, bottom), Offset(left, bottom - cornerLen), strokeWidth)
        // Bottom-right
        drawLine(bracketColor, Offset(right, bottom), Offset(right - cornerLen, bottom), strokeWidth)
        drawLine(bracketColor, Offset(right, bottom), Offset(right, bottom - cornerLen), strokeWidth)
    }
}

/**
 * Inline fallback shown when the camera is unavailable or permission was
 * denied. Always offers the paste-URL path so pairing is never blocked, and
 * a dismiss button to return to the Connect page.
 */
@Composable
private fun CameraFallbackMessage(
    message: String,
    onUsePasteInstead: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onUsePasteInstead) {
                Text("Paste pairing URL")
            }
            TextButton(onClick = onDismiss) {
                Text("Back")
            }
        }
    }
}
