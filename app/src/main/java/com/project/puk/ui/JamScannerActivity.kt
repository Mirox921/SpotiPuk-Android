package com.project.puk.ui

import com.project.puk.core.Lang

import com.project.puk.ui.theme.EmberIcons
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.project.puk.ui.theme.SpotifyTheme

class JamScannerActivity : ComponentActivity() {
    private lateinit var preview: BarcodeView
    private var cameraGranted by mutableStateOf(false)
    private var cameraError by mutableStateOf(false)
    private var invalidCode by mutableStateOf(false)
    private var torch by mutableStateOf(false)

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
        if (granted && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            preview.resume()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        preview = BarcodeView(this).apply {
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            setMarginFraction(0.08)
            addStateListener(object : CameraPreview.StateListener {
                override fun previewSized() = Unit
                override fun previewStarted() = Unit
                override fun previewStopped() = Unit
                override fun cameraClosed() = Unit
                override fun cameraError(error: Exception) {
                    this@JamScannerActivity.cameraError = true
                }
            })
        }
        scan()
        setContent {
            SpotifyTheme { ScannerScreen() }
        }
        if (!cameraGranted && savedInstanceState == null) requestCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        if (cameraGranted && !cameraError) preview.resume()
    }

    override fun onPause() {
        preview.pause()
        super.onPause()
    }

    private fun scan() {
        preview.decodeContinuous { result ->
            // Jam 1.0 scans the online invite QR (spotipuk://jam/join/<code>):
            // the raw text is returned to the caller for parsing.
            preview.stopDecoding()
            setResult(RESULT_OK, Intent().putExtra(Intents.Scan.RESULT, result.text))
            finish()
        }
    }

    @Composable
    private fun ScannerScreen() {
        val colors = MaterialTheme.colorScheme
        Column(
            Modifier.fillMaxSize().background(colors.background).safeDrawingPadding().padding(horizontal = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Lang.t(Lang.Key.JAM_SCAN_QR_CODE), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                IconButton(onClick = { finish() }) { Icon(EmberIcons.Close, Lang.t(Lang.Key.PLAYER_CLOSE), tint = colors.onSurface) }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (cameraGranted && !cameraError) {
                    Box(Modifier.widthIn(max = 380.dp).fillMaxWidth().aspectRatio(1f)
                        .clip(RoundedCornerShape(24.dp)).background(colors.surfaceContainerHigh)) {
                        AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
                        Canvas(Modifier.fillMaxSize().padding(18.dp)) {
                            val length = 24.dp.toPx()
                            val stroke = 3.dp.toPx()
                            for (x in listOf(0f, size.width)) {
                                for (y in listOf(0f, size.height)) {
                                    val start = Offset(x, y)
                                    drawLine(colors.primary, start, Offset(x + if (x == 0f) length else -length, y), stroke, StrokeCap.Round)
                                    drawLine(colors.primary, start, Offset(x, y + if (y == 0f) length else -length), stroke, StrokeCap.Round)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        if (invalidCode) Lang.t(Lang.Key.JAM_NOT_JAM_INVITE) else Lang.t(Lang.Key.JAM_SCAN_QR_HINT),
                        color = if (invalidCode) colors.error else colors.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
                        Spacer(Modifier.height(16.dp))
                        FilledTonalIconButton(onClick = { torch = !torch; preview.setTorch(torch) }) {
                            Icon(
                                if (torch) Icons.Default.FlashlightOff else Icons.Default.FlashlightOn,
                                if (torch) Lang.t(Lang.Key.JAM_TORCH_OFF) else Lang.t(Lang.Key.JAM_TORCH_ON),
                            )
                        }
                    }
                } else {
                    Text(if (cameraError) Lang.t(Lang.Key.JAM_CAMERA_OPEN_FAILED) else Lang.t(Lang.Key.JAM_CAMERA_PERMISSION_HINT), color = colors.onSurface)
                    TextButton(onClick = {
                        if (cameraError) {
                            cameraError = false
                            preview.resume()
                        } else requestCamera.launch(Manifest.permission.CAMERA)
                    }) { Text(Lang.t(Lang.Key.LIB_RETRY)) }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}
