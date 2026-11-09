// modules/pos-printer/android/src/main/java/zk/modules/posprinter/EscPosEncoder.kt
package zk.modules.posprinter

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

class EscPosEncoder(private val charset: Charset) {
  private val out = ByteArrayOutputStream()

  private fun cmd(vararg b: Int) = apply { b.forEach { out.write(it) } }

  fun reset() = cmd(0x1B, 0x40)
  fun codePage(n: Int) = cmd(0x1B, 0x74, n)
  fun align(a: String?) = cmd(0x1B, 0x61, when (a) { "center" -> 1; "right" -> 2; else -> 0 })
  fun bold(on: Boolean) = cmd(0x1B, 0x45, if (on) 1 else 0)
  fun underline(on: Boolean) = cmd(0x1B, 0x2D, if (on) 1 else 0)
  fun size(w: Int, h: Int) = cmd(0x1D, 0x21, ((w.coerceIn(1, 8) - 1) shl 4) or (h.coerceIn(1, 8) - 1))
  fun text(s: String) = apply { out.write(s.toByteArray(charset)) } // unmappable chars become '?'
  fun feed(lines: Int) = cmd(0x1B, 0x64, lines.coerceIn(0, 255))
  /** GS V 65/66 n: feed n then cut. Printers without a cutter ignore it. */
  fun cut(partial: Boolean) = cmd(0x1D, 0x56, if (partial) 66 else 65, 3)
  fun raw(bytes: ByteArray) = apply { out.write(bytes) }

  fun qr(value: String, size: Int) = apply {
    val data = value.toByteArray(Charsets.UTF_8)
    val len = data.size + 3
    cmd(0x1D, 0x28, 0x6B, 4, 0, 0x31, 0x41, 0x32, 0x00)              // model 2
    cmd(0x1D, 0x28, 0x6B, 3, 0, 0x31, 0x43, size.coerceIn(1, 16))     // module size
    cmd(0x1D, 0x28, 0x6B, 3, 0, 0x31, 0x45, 0x31)                     // error correction M
    cmd(0x1D, 0x28, 0x6B, len and 0xFF, (len shr 8) and 0xFF, 0x31, 0x50, 0x30) // store
    out.write(data)
    cmd(0x1D, 0x28, 0x6B, 3, 0, 0x31, 0x51, 0x30)                     // print
  }

  /**
   * GS v 0 raster image, sent in horizontal strips so printers with small
   * buffers don't drop data. Recycles the bitmap.
   */
  fun raster(bmp: Bitmap?) = apply {
    bmp ?: return@apply
    val w = bmp.width
    val h = bmp.height
    val bytesPerRow = (w + 7) / 8
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    bmp.recycle()

    align("left")
    var row = 0
    while (row < h) {
      val strip = minOf(RASTER_STRIP_ROWS, h - row)
      cmd(
        0x1D, 0x76, 0x30, 0x00,
        bytesPerRow and 0xFF, (bytesPerRow shr 8) and 0xFF,
        strip and 0xFF, (strip shr 8) and 0xFF,
      )
      for (y in row until row + strip) {
        val base = y * w
        for (bx in 0 until bytesPerRow) {
          var b = 0
          for (bit in 0 until 8) {
            val x = bx * 8 + bit
            if (x < w && (px[base + x] and 0xFF) < 128) b = b or (0x80 shr bit)
          }
          out.write(b)
        }
      }
      row += strip
    }
  }

  fun bytes(): ByteArray = out.toByteArray()

  companion object {
    /** Lower this (e.g. 24 or 48) if a printer stops halfway through a raster receipt. */
    private const val RASTER_STRIP_ROWS = 128

    fun encode(commands: List<Map<String, Any?>>, options: PrintOptions): ByteArray =
      when (options.mode) {
        "text" -> encodeText(commands, options)
        "raster" -> encodeRaster(commands, options)
        else -> throw PrinterException("Unknown print mode: ${options.mode}")
      }

    /**
     * Renders the receipt as the printer would show it in raster mode.
     * Image QR codes are drawn for real; native ones appear as a placeholder box.
     */
    fun preview(commands: List<Map<String, Any?>>, options: PrintOptions): Map<String, Any?> {
      val r = RasterRenderer(options.widthDots, options.columns)
      for (c in commands) {
        when (c["type"]) {
          "text" -> addText(r, c)
          "feed" -> r.addFeed(int(c["lines"], 1))
          "qr" -> if (options.qrMode == "native") r.addQrPlaceholder(int(c["size"], 6)) else addQr(r, c)
          "cut" -> r.addCutMarker()
        }
      }
      val bmp = r.flush() ?: return mapOf("uri" to null, "width" to 0, "height" to 0)
      val w = bmp.width
      val h = bmp.height
      val png = ByteArrayOutputStream().use {
        bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        it.toByteArray()
      }
      bmp.recycle()
      return mapOf(
        "uri" to "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP),
        "width" to w,
        "height" to h,
      )
    }

    private fun encodeText(commands: List<Map<String, Any?>>, options: PrintOptions): ByteArray {
      val charset = try {
        Charset.forName(options.charset)
      } catch (e: Exception) {
        throw PrinterException("Unsupported charset: ${options.charset}", e)
      }
      val e = EscPosEncoder(charset).reset().codePage(options.codePage)

      for (c in commands) {
        when (c["type"]) {
          "text" -> e
            .align(c["align"] as? String)
            .bold(c["bold"] == true)
            .underline(c["underline"] == true)
            .size(int(c["width"], 1), int(c["height"], 1))
            .text(c["value"] as? String ?: "")
            .size(1, 1).bold(false).underline(false).align("left")
          "feed" -> e.feed(int(c["lines"], 1))
          "cut" -> e.cut(c["partial"] == true)
          "qr" -> if (options.qrMode == "native") {
            e.align(c["align"] as? String ?: "center")
              .qr(c["value"] as? String ?: "", int(c["size"], 6))
              .align("left")
          } else {
            // Text mode, but the QR still goes as a small image (needs GS v 0 support).
            val r = RasterRenderer(options.widthDots, options.columns)
            addQr(r, c)
            e.raster(r.flush())
          }
          "raw" -> e.raw(decodeRaw(c))
          else -> throw PrinterException("Unknown command type: ${c["type"]}")
        }
      }
      return e.bytes()
    }

    /**
     * Text, feeds and (by default) QR codes are drawn into bitmaps. Cut, raw and
     * native QR stay ESC/POS commands, so the bitmap is flushed before each of them
     * to keep the receipt order.
     */
    private fun encodeRaster(commands: List<Map<String, Any?>>, options: PrintOptions): ByteArray {
      val e = EscPosEncoder(Charsets.US_ASCII).reset()
      val r = RasterRenderer(options.widthDots, options.columns)

      for (c in commands) {
        when (c["type"]) {
          "text" -> addText(r, c)
          "feed" -> r.addFeed(int(c["lines"], 1))
          "qr" -> if (options.qrMode == "native") {
            e.raster(r.flush())
              .align(c["align"] as? String ?: "center")
              .qr(c["value"] as? String ?: "", int(c["size"], 6))
              .align("left")
          } else {
            addQr(r, c)
          }
          "cut" -> e.raster(r.flush()).cut(c["partial"] == true)
          "raw" -> e.raster(r.flush()).raw(decodeRaw(c))
          else -> throw PrinterException("Unknown command type: ${c["type"]}")
        }
      }
      e.raster(r.flush())
      return e.bytes()
    }

    private fun addText(r: RasterRenderer, c: Map<String, Any?>) = r.addText(
      value = c["value"] as? String ?: "",
      align = c["align"] as? String,
      bold = c["bold"] == true,
      underline = c["underline"] == true,
      width = int(c["width"], 1),
      height = int(c["height"], 1),
    )

    private fun addQr(r: RasterRenderer, c: Map<String, Any?>) = r.addQr(
      value = c["value"] as? String ?: "",
      size = int(c["size"], 6),
      align = c["align"] as? String ?: "center",
    )

    private fun decodeRaw(c: Map<String, Any?>): ByteArray =
      Base64.decode(c["base64"] as? String ?: "", Base64.DEFAULT)

    private fun int(v: Any?, default: Int) = (v as? Number)?.toInt() ?: default
  }
}
