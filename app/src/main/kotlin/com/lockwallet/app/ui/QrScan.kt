package com.lockwallet.app.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.lockwallet.core.solana.SolanaUri
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QR reader for a recipient address (Phase H): CameraX preview + local ZXing decode on a background executor.
 * No Google Play services, no network; frames never leave the analyzer. CAMERA is requested only when the user
 * taps "Ler QR". Only an address is returned; Solana Pay transaction requests are refused.
 */
@Composable
fun QrScanner(onAddress: (String) -> Unit, onMessage: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val done = AtomicBoolean(false)
    val executor = androidx.compose.runtime.remember { Executors.newSingleThreadExecutor() }
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
      androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(28.dp))) {
        AndroidView(factory = { c ->
            val view = PreviewView(c)
            val future = ProcessCameraProvider.getInstance(c)
            future.addListener({
                val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                val reader = QRCodeReader()
                val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
                analysis.setAnalyzer(executor) { img ->
                    try {
                        if (done.get()) return@setAnalyzer
                        val plane = img.planes[0]
                        val buf = plane.buffer
                        val data = ByteArray(buf.remaining()).also { buf.get(it) }
                        val src = PlanarYUVLuminanceSource(data, plane.rowStride, img.height, 0, 0, img.width, img.height, false)
                        val text = try { reader.decode(BinaryBitmap(HybridBinarizer(src)), hints).text } catch (_: ReaderException) { null } finally { reader.reset() }
                        if (text != null && done.compareAndSet(false, true)) {
                            val r = SolanaUri.parse(text)
                            ContextCompat.getMainExecutor(c).execute {
                                when (r) {
                                    is SolanaUri.Result.Address -> onAddress(r.key.toBase58())
                                    SolanaUri.Result.TransactionRequest -> onMessage(S.QR_TX_REQUEST)
                                    SolanaUri.Result.Invalid -> onMessage(S.QR_INVALID)
                                }
                            }
                        }
                    } catch (_: RuntimeException) {
                    } finally { img.close() }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }.onFailure { ContextCompat.getMainExecutor(c).execute { onMessage(S.QR_CAMERA_FAILED) } }
            }, ContextCompat.getMainExecutor(c))
            view
        }, modifier = Modifier.fillMaxWidth().height(300.dp))
        // Viewfinder brackets (white over the camera image in both themes).
        androidx.compose.foundation.Canvas(Modifier.matchParentSize().padding(36.dp)) {
            val l = size.minDimension * 0.18f; val w = 4.dp.toPx(); val col = androidx.compose.ui.graphics.Color.White
            val cs = listOf(androidx.compose.ui.geometry.Offset(0f, 0f) to (1f to 1f), androidx.compose.ui.geometry.Offset(size.width, 0f) to (-1f to 1f),
                androidx.compose.ui.geometry.Offset(0f, size.height) to (1f to -1f), androidx.compose.ui.geometry.Offset(size.width, size.height) to (-1f to -1f))
            cs.forEach { (o, d) ->
                drawLine(col, o, o.copy(x = o.x + d.first * l), w, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(col, o, o.copy(y = o.y + d.second * l), w, androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
      }
        Body(S.QR_HINT)
        Secondary("Fechar câmera", icon = Ic.CLOSE) { onClose() }
    }
    DisposableEffect(Unit) {
        onDispose {
            done.set(true)
            runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() }
            executor.shutdown()
        }
    }
}
