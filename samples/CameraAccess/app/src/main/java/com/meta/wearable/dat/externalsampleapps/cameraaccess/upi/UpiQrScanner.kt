// UpiQrScanner - read a UPI QR code from a glasses photo and hand it to a UPI app.
//
// Flow: glasses photo -> ML Kit QR decode -> speak the payee through the glasses ->
// open GPay / PhonePe / BHIM / WhatsApp with the payment pre-filled. The user always
// confirms in the UPI app (UPI Lite is used by that app automatically for small amounts).

package com.meta.wearable.dat.externalsampleapps.cameraaccess.upi

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.Log
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class UpiQrScanner(context: Context) {

  companion object {
    private const val TAG = "CameraAccess:UpiQr"
  }

  private val appContext = context.applicationContext

  private val scanner =
      BarcodeScanning.getClient(
          BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
      )

  private var ttsReady = false
  private val tts: TextToSpeech =
      TextToSpeech(appContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
          ttsReady = true
          tts.language = Locale("en", "IN")
        }
      }

  /** Returns the raw `upi://pay?...` string if the image contains a UPI QR, else null. */
  suspend fun findUpiQr(bitmap: Bitmap): String? =
      suspendCancellableCoroutine { cont ->
        scanner
            .process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { codes ->
              val upi =
                  codes
                      .mapNotNull { it.rawValue?.trim() }
                      .firstOrNull { it.startsWith("upi://pay", ignoreCase = true) }
              if (cont.isActive) cont.resume(upi)
            }
            .addOnFailureListener { e ->
              Log.e(TAG, "QR decode failed", e)
              if (cont.isActive) cont.resume(null)
            }
      }

  /** Speaks the payee and opens the UPI app chooser. Returns false if the QR is unusable. */
  fun pay(raw: String): Boolean {
    val uri = Uri.parse(raw)
    val vpa = uri.getQueryParameter("pa")
    if (vpa.isNullOrBlank()) {
      speak("This QR code has no UPI ID. Not paying.")
      return false
    }
    val name = uri.getQueryParameter("pn")?.takeIf { it.isNotBlank() } ?: vpa
    val amount = uri.getQueryParameter("am")?.takeIf { it.isNotBlank() }

    speak(
        if (amount != null) "Paying $name, rupees $amount. Please confirm on your phone."
        else "Paying $name. Enter the amount on your phone."
    )

    // Pass the QR exactly as scanned: merchant QRs can carry signed fields that break if rebuilt.
    val pay = Intent(Intent.ACTION_VIEW, uri)
    val chooser =
        Intent.createChooser(pay, "Pay $name with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
      appContext.startActivity(chooser)
      true
    } catch (e: ActivityNotFoundException) {
      speak("No UPI app found on this phone.")
      false
    }
  }

  fun speak(text: String) {
    if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "upi")
  }

  fun close() {
    scanner.close()
    tts.shutdown()
  }
}
