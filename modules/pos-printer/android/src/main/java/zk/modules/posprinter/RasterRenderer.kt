// modules/pos-printer/android/src/main/java/zk/modules/posprinter/RasterRenderer.kt
package zk.modules.posprinter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.ceil
import kotlin.math.max

/**
 * Draws receipt text onto a monochrome bitmap with Android's own fonts,
 * so Greek, accents and € render the same on every printer.
 *
 * Text is monospace and sized so that `columns` characters fill `widthDots`,
 * which keeps Receipt.row() padding aligned exactly like in text mode.
 */
class RasterRenderer(private val widthDots: Int, private val columns: Int) {

  private sealed interface Item { val height: Int }
  private class TextLine(
    val text: String,
    val paint: Paint,
    val align: String?,
    override val height: Int,
    val baseline: Float,
  ) : Item
  private class Gap(override val height: Int) : Item
  private class QrPlaceholder(override val height: Int) : Item
  private class QrImage(val matrix: BitMatrix, val module: Int, val align: String?, override val height: Int) : Item
  private class CutMarker(override val height: Int) : Item

  private val items = mutableListOf<Item>()
  private val baseTextSize: Float
  private val baseLineHeight: Int

  init {
    val probe = Paint().apply { typeface = Typeface.MONOSPACE; textSize = 100f }
    val charWidth = probe.measureText("0")
    baseTextSize = 100f * (widthDots.toFloat() / columns) / charWidth
    probe.textSize = baseTextSize
    baseLineHeight = lineHeight(probe)
  }

  private fun lineHeight(p: Paint): Int {
    val fm = p.fontMetrics
    return ceil(fm.descent - fm.ascent).toInt() + 2
  }

  fun addText(value: String, align: String?, bold: Boolean, underline: Boolean, width: Int, height: Int) {
    val w = width.coerceIn(1, 8)
    val h = height.coerceIn(1, 8)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.BLACK
      typeface = Typeface.MONOSPACE
      isFakeBoldText = bold
      isUnderlineText = underline
      textSize = baseTextSize * h
      textScaleX = w.toFloat() / h
    }
    val lh = lineHeight(paint)
    val baseline = -paint.fontMetrics.ascent + 1
    val maxChars = max(1, columns / w)

    value.trimEnd('\n').split('\n').forEach { line ->
      line.chunked(maxChars).ifEmpty { listOf("") }.forEach { part ->
        items += TextLine(part, paint, align, lh, baseline)
      }
    }
  }

  fun addFeed(lines: Int) {
    if (lines > 0) items += Gap(baseLineHeight * lines)
  }

  /**
   * Draws the QR code into the bitmap, so it prints on printers that don't support
   * the native QR command. `size` = dots per QR module, same meaning as in GS ( k.
   */
  fun addQr(value: String, size: Int, align: String?) {
    val hints = mapOf(
      EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
      EncodeHintType.CHARACTER_SET to "UTF-8",
      EncodeHintType.MARGIN to 0,
    )
    // Width/height 0 → one pixel per module; we scale it ourselves for sharp edges.
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 0, 0, hints)
    val module = size.coerceIn(1, 16).coerceAtMost(max(1, widthDots / matrix.width))
    items += QrImage(matrix, module, align, matrix.height * module + QR_PADDING * 2)
  }

  /** Preview of a native QR (printed by the printer itself, so we can only show a box). */
  fun addQrPlaceholder(size: Int) {
    items += QrPlaceholder(size.coerceIn(1, 16) * 29 + 16)
  }

  /** Preview only. */
  fun addCutMarker() {
    items += CutMarker(baseLineHeight)
  }

  /** Renders everything added so far into one bitmap and clears the queue. Null if nothing to draw. */
  fun flush(): Bitmap? {
    if (items.isEmpty()) return null
    val height = max(1, items.sumOf { it.height })
    val bmp = Bitmap.createBitmap(widthDots, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(Color.WHITE)

    var y = 0
    for (item in items) {
      when (item) {
        is TextLine -> {
          val tw = item.paint.measureText(item.text)
          val x = when (item.align) {
            "center" -> (widthDots - tw) / 2f
            "right" -> widthDots - tw
            else -> 0f
          }.coerceAtLeast(0f)
          canvas.drawText(item.text, x, y + item.baseline, item.paint)
        }
        is QrImage -> {
          val side = item.matrix.width * item.module
          val left = when (item.align) {
            "left" -> 0
            "right" -> widthDots - side
            else -> (widthDots - side) / 2
          }.coerceAtLeast(0)
          val top = y + QR_PADDING
          val fill = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
          for (my in 0 until item.matrix.height) {
            for (mx in 0 until item.matrix.width) {
              if (item.matrix[mx, my]) {
                val px = (left + mx * item.module).toFloat()
                val py = (top + my * item.module).toFloat()
                canvas.drawRect(px, py, px + item.module, py + item.module, fill)
              }
            }
          }
        }
        is QrPlaceholder -> {
          val side = (item.height - 16).toFloat()
          val left = (widthDots - side) / 2f
          val top = y + 8f
          val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.BLACK }
          canvas.drawRect(left, top, left + side, top + side, stroke)
          val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = baseTextSize
            textAlign = Paint.Align.CENTER
          }
          canvas.drawText("QR", widthDots / 2f, top + side / 2f, label)
        }
        is CutMarker -> {
          val dash = Paint().apply {
            color = Color.BLACK
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
          }
          val cy = y + item.height / 2f
          canvas.drawLine(0f, cy, widthDots.toFloat(), cy, dash)
        }
        is Gap -> Unit
      }
      y += item.height
    }
    items.clear()
    toMonochrome(bmp)
    return bmp
  }

  /** Thermal printers have no grey: every pixel becomes pure black or white. */
  private fun toMonochrome(bmp: Bitmap) {
    val w = bmp.width
    val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    for (i in px.indices) {
      val c = px[i]
      val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
      px[i] = if (lum < THRESHOLD) Color.BLACK else Color.WHITE
    }
    bmp.setPixels(px, 0, w, 0, 0, w, h)
  }

  companion object {
    /** Higher = bolder text. 128–170 is a sensible range. */
    private const val THRESHOLD = 150
    /** White space above and below a QR code, in dots. */
    private const val QR_PADDING = 16
  }
}
