package space.orkestrate.phone.ui

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * Full-screen QR scanner modeled on opencode-android's PairingScanner.
 * Uses CameraX + ZXing; never logs scanned payloads.
 */
@Composable
fun ScanScreen(
  cameraGranted: Boolean,
  error: String?,
  onBack: () -> Unit,
  onPaste: () -> Unit,
  onEnterCode: () -> Unit,
  onGrantCamera: () -> Unit,
  onScanned: (String) -> Unit,
  previewSlot: (@Composable () -> Unit)? = null,
) {
  Box(Modifier.fillMaxSize().background(Ork.canvas)) {
    if (previewSlot != null) {
      previewSlot()
    } else if (cameraGranted) {
      CameraQrPreview(onScanned = onScanned)
    }

    // Viewfinder chrome
    Column(Modifier.fillMaxSize()) {
      Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Ork.gutter),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OrkIconButton(OrkIcons.arrowLeft, "Back", onBack)
        Spacer(Modifier.weight(1f))
        OrkPill("Paste", onPaste, kind = PillKind.Quiet, small = true, icon = OrkIcons.clipboard)
      }

      if (!cameraGranted && previewSlot == null) {
        Column(
          Modifier.weight(1f).fillMaxWidth().padding(horizontal = Ork.gutter),
          verticalArrangement = Arrangement.Center,
        ) {
          OrkHeading("Camera access.", "Needed to scan your agent's pairing code.")
          Spacer(Modifier.height(24.dp))
          OrkPill("Allow camera", onGrantCamera, wide = true, icon = OrkIcons.camera)
          Spacer(Modifier.height(10.dp))
          OrkPill("Enter code manually", onEnterCode, kind = PillKind.Quiet, wide = true)
        }
      } else {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
          ViewfinderBrackets(Modifier.size(240.dp))
        }
        Column(
          Modifier.fillMaxWidth().padding(horizontal = Ork.gutter, vertical = 28.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          if (error != null) {
            OrkErrorLine(error, Modifier.padding(bottom = 12.dp))
          }
          Text("Point at the QR code your agent shows.", style = OrkType.label)
          Spacer(Modifier.height(8.dp))
          OrkLink("Enter code manually", onEnterCode)
        }
      }
    }
  }
}

@Composable
private fun ViewfinderBrackets(modifier: Modifier = Modifier) {
  Canvas(modifier) {
    val len = 28.dp.toPx()
    val stroke = 2.5.dp.toPx()
    val w = size.width
    val h = size.height
    val c = Color.White.copy(alpha = 0.85f)
    // Top-left
    drawLine(c, Offset(0f, 0f), Offset(len, 0f), stroke, StrokeCap.Round)
    drawLine(c, Offset(0f, 0f), Offset(0f, len), stroke, StrokeCap.Round)
    // Top-right
    drawLine(c, Offset(w, 0f), Offset(w - len, 0f), stroke, StrokeCap.Round)
    drawLine(c, Offset(w, 0f), Offset(w, len), stroke, StrokeCap.Round)
    // Bottom-left
    drawLine(c, Offset(0f, h), Offset(len, h), stroke, StrokeCap.Round)
    drawLine(c, Offset(0f, h), Offset(0f, h - len), stroke, StrokeCap.Round)
    // Bottom-right
    drawLine(c, Offset(w, h), Offset(w - len, h), stroke, StrokeCap.Round)
    drawLine(c, Offset(w, h), Offset(w, h - len), stroke, StrokeCap.Round)
  }
}

@Composable
private fun CameraQrPreview(onScanned: (String) -> Unit) {
  val ctx = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  var delivered by remember { mutableStateOf(false) }
  val executor = remember { Executors.newSingleThreadExecutor() }
  val reader = remember {
    MultiFormatReader().apply {
      setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }
  }

  DisposableEffect(Unit) {
    onDispose { executor.shutdown() }
  }

  AndroidView(
    modifier = Modifier.fillMaxSize(),
    factory = { context ->
      val previewView = PreviewView(context)
      val future = ProcessCameraProvider.getInstance(context)
      future.addListener({
        try {
          val provider = future.get()
          val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
          }
          val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
          analysis.setAnalyzer(executor) { image ->
            if (!delivered) {
              decodeQr(reader, image)?.let { text ->
                delivered = true
                previewView.post { onScanned(text) }
              }
            }
            image.close()
          }
          provider.unbindAll()
          provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        } catch (e: Exception) {
          Log.d("phone", "camera bind fail: ${e.message}")
        }
      }, ContextCompat.getMainExecutor(context))
      previewView
    },
  )
}

private fun decodeQr(reader: MultiFormatReader, image: ImageProxy): String? {
  return try {
    val plane = image.planes[0]
    val buf = plane.buffer
    val bytes = ByteArray(buf.remaining())
    buf.get(bytes)
    val source = PlanarYUVLuminanceSource(
      bytes, plane.rowStride, image.height, 0, 0, image.width, image.height, false,
    )
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    reader.decodeWithState(bitmap).text
  } catch (_: Exception) {
    null
  } finally {
    reader.reset()
  }
}
