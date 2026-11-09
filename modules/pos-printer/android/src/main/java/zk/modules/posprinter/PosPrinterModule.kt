// modules/pos-printer/android/src/main/java/zk/modules/posprinter/PosPrinterModule.kt
package zk.modules.posprinter

import android.content.Context
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class PrinterException(message: String, cause: Throwable? = null) :
  CodedException("ERR_POS_PRINTER", message, cause)

class DiscoveryOptions : Record {
  @Field val bluetooth: Boolean = true
  /** Active scan for unpaired devices. 0 = only return paired devices. */
  @Field val bluetoothScanMs: Int = 8000
  @Field val mdns: Boolean = true
  @Field val mdnsTimeoutMs: Int = 4000
  /** Probe every IP in the local /24 for an open raw-print port. Catches printers that don't advertise via mDNS. */
  @Field val subnetScan: Boolean = true
  @Field val subnetPort: Int = 9100
}

class PrintOptions : Record {
  /** "raster" = phone draws the text (Greek works everywhere). "text" = printer's built-in font + code page. */
  @Field val mode: String = "raster"
  /** Raster: printable width in dots. 58mm = 384, 80mm = 576 (some Epson models 512). */
  @Field val widthDots: Int = 576
  /** Raster: characters per line at normal size. Must match the Receipt's columns. */
  @Field val columns: Int = 48
  /** "image" = phone draws the QR (works on any printer with GS v 0). "native" = printer's own QR command. */
  @Field val qrMode: String = "image"
  /** Text: ESC t n — printer-specific code page number. Epson: 15 = ISO-8859-7, 47 = WPC1253. */
  @Field val codePage: Int = 15
  /** Text: Java charset used to turn text into bytes. Must match codePage. */
  @Field val charset: String = "ISO-8859-7"
  @Field val timeoutMs: Int = 8000
}

class PosPrinterModule : Module() {
  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  override fun definition() = ModuleDefinition {
    Name("PosPrinter")

    AsyncFunction("discoverPrinters") Coroutine { options: DiscoveryOptions ->
      val ctx = context
      coroutineScope {
        val bt = async {
          if (options.bluetooth) BluetoothPrinters.discover(ctx, options.bluetoothScanMs.toLong())
          else emptyList()
        }
        val net = async { NetworkPrinters.discover(ctx, options) }
        bt.await() + net.await()
      }
    }

    AsyncFunction("print") Coroutine { printerId: String, commands: List<Map<String, Any?>>, options: PrintOptions ->
      val data = withContext(Dispatchers.Default) { EscPosEncoder.encode(commands, options) }
      PrinterTransport.send(context, printerId, data, options.timeoutMs)
      data.size
    }

    AsyncFunction("previewReceipt") Coroutine { commands: List<Map<String, Any?>>, options: PrintOptions ->
      withContext(Dispatchers.Default) { EscPosEncoder.preview(commands, options) }
    }

    Function("isBluetoothEnabled") {
      BluetoothPrinters.isEnabled(context)
    }
  }
}
