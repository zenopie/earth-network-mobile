package network.erth.wallet.ui.compose.registration

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import network.erth.wallet.ui.compose.EarthLabel
import network.erth.wallet.ui.vendor.component.EarthButton
import network.erth.wallet.ui.vendor.component.EarthButtonDefaults
import network.erth.wallet.ui.vendor.theme.colors.EarthColors
import network.erth.wallet.ui.vendor.theme.typography.EarthTypography
import network.erth.wallet.wallet.passport.PassportSession

/**
 * Point the camera at the two lines at the bottom of the passport.
 *
 * The preview is an AndroidView around CameraX's PreviewView — there is no
 * Compose-native camera surface, and wrapping the one that exists is the
 * intended way round rather than a compromise.
 *
 * The MRZ is only read to unlock the chip. Nothing scanned here is stored or
 * sent: the passport's own data comes off the chip a step later, and this text
 * is thrown away as soon as BAC succeeds.
 */
@Composable
fun MrzCameraScreen(
    onDetected: (PassportSession.Mrz) -> Unit,
    onManualEntry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val detected = rememberUpdatedState(onDetected)

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val askCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) askCamera.launch(Manifest.permission.CAMERA)
    }

    Column(
        modifier
            .fillMaxSize()
            .background(EarthColors.Surfaces.bgPrimary),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (granted) {
                CameraPreview(onMrz = { detected.value(it) })
                // The guide is the whole instruction: two lines, bottom of the
                // page, fill the box. A paragraph explaining the machine-
                // readable zone is a paragraph nobody reads while holding a
                // passport against a phone.
                Box(
                    Modifier
                        .fillMaxWidth(0.9f)
                        .aspectRatio(4f)
                        .border(2.dp, Color.White, RoundedCornerShape(8.dp)),
                )
            } else {
                Text(
                    text = "Camera access is needed to read the passport's " +
                        "printed lines. Nothing is recorded.",
                    style = EarthTypography.textSm,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }
        }

        Column(Modifier.padding(24.dp)) {
            EarthLabel("Passport")
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Line up the two rows of letters and chevrons at the " +
                    "bottom of the photo page.",
                style = EarthTypography.textSm,
                color = EarthColors.Text.textSecondary,
            )
            Spacer(Modifier.height(16.dp))
            EarthButton(
                text = "Enter details by hand",
                onClick = onManualEntry,
                modifier = Modifier.fillMaxWidth(),
                colors = EarthButtonDefaults.secondaryColors(),
            )
        }
    }
}

@Composable
private fun CameraPreview(onMrz: (PassportSession.Mrz) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    // Once a valid MRZ is found the analyzer keeps running for a frame or two
    // before it can be torn down; without this the callback fires repeatedly
    // and pushes the same screen several times onto the stack.
    val done = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            recognizer.close()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(executor) { proxy: ImageProxy ->
                    val media = proxy.image
                    if (media == null || done.get()) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    recognizer.process(image)
                        .addOnSuccessListener { text ->
                            parseMrz(text.text)?.let {
                                if (done.compareAndSet(false, true)) onMrz(it)
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }

                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

/**
 * Pull the three BAC fields out of recognised text.
 *
 * Ported unchanged from the fragment, including its tolerance: OCR reliably
 * mangles this typeface, so it looks for any adjacent pair of lines where the
 * first begins "P<" and the second is long enough, rather than insisting the
 * whole zone parsed cleanly. The chip itself rejects a wrong key a moment
 * later, which is a better check than anything done here.
 */
internal fun parseMrz(text: String): PassportSession.Mrz? {
    val lines = text.split("\n")
    for (i in 0 until lines.size - 1) {
        val first = lines[i].replace("\\s".toRegex(), "").uppercase()
        val second = lines[i + 1].replace("\\s".toRegex(), "").uppercase()
        if (!first.startsWith("P<") || second.length < 36) continue

        val mrz = runCatching {
            PassportSession.Mrz(
                passportNumber = second.substring(0, 9).replace('<', ' ').trim(),
                dateOfBirth = second.substring(13, 19),
                dateOfExpiry = second.substring(21, 27),
            )
        }.getOrNull()

        if (mrz != null && mrz.isComplete) return mrz
    }
    return null
}
