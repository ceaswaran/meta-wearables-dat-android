// UpiQrScanner - read a UPI QR code from a glasses photo and hand it to a UPI app.
//
// Flow: glasses photo -> ML Kit QR decode -> speak the payee through the glasses ->
// open GPay / PhonePe / BHIM / WhatsApp with the payment pre-filled. The user always
// confirms in the UPI app (UPI Lite is used by that app automatically for small amounts).

package com.meta.wearable.dat.externalsampleapps.cameraaccess.upi

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Log
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
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

  /**
   * Speaks the payee, saves a clean copy of the QR to the gallery, and opens a UPI app.
   *
   * GPay / WhatsApp Pay often reject payments handed over by link (intent), especially to
   * merchants. Scanning the saved QR from the gallery inside the UPI app uses that app's own scan
   * flow, which they accept. Returns false if the QR is unusable.
   */
  fun pay(raw: String): Boolean {
    val uri = Uri.parse(raw)
    val vpa = uri.getQueryParameter("pa")
    if (vpa.isNullOrBlank()) {
      speak("This QR code has no UPI ID. Not paying.")
      return false
    }
    val name = uri.getQueryParameter("pn")?.takeIf { it.isNotBlank() } ?: vpa
    val amount = uri.getQueryParameter("am")?.takeIf { it.isNotBlank() }

    val saved = saveQrToGallery(raw, name)
    speak(
        buildString {
          append("Found $name")
          if (amount != null) append(", rupees $amount")
          append(". ")
          append(
              if (saved) "QR saved. In your UPI app, tap scan, then upload from gallery."
              else "Could not save the QR."
          )
        }
    )
    return openUpiApp(name)
  }

  /** Re-draws the exact scanned text as a crisp QR (same content, signed fields intact). */
  private fun saveQrToGallery(raw: String, name: String): Boolean =
      try {
        val size = 900
        val hints = mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8")
        val matrix = QRCodeWriter().encode(raw, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size) { i ->
          if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE
        }
        val bmp = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)

        val safeName = name.replace(Regex("[^A-Za-z0-9]+"), "_").take(30)
        val values =
            ContentValues().apply {
              put(MediaStore.Images.Media.DISPLAY_NAME, "UPI_${safeName}_${System.currentTimeMillis()}.png")
              put(MediaStore.Images.Media.MIME_TYPE, "image/png")
              put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GlassesPay")
            }
        val resolver = appContext.contentResolver
        val item = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        item != null &&
            resolver.openOutputStream(item)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } == true
      } catch (e: Exception) {
        Log.e(TAG, "Saving QR failed", e)
        false
      }

  /** Opens the user's UPI app (chooser if more than one is installed), on its home screen. */
  private fun openUpiApp(name: String): Boolean {
    val pm = appContext.packageManager
    val upiApps =
        pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay")), 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .mapNotNull { pm.getLaunchIntentForPackage(it) }
    if (upiApps.isEmpty()) {
      speak("No UPI app found on this phone.")
      return false
    }
    val intent =
        if (upiApps.size == 1) upiApps.first()
        else
            Intent.createChooser(upiApps.first(), "Open UPI app to pay $name")
                .putExtra(Intent.EXTRA_INITIAL_INTENTS, upiApps.drop(1).toTypedArray())
    return try {
      appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      true
    } catch (e: ActivityNotFoundException) {
      speak("Could not open the UPI app.")
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
