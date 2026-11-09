// modules/pos-printer/android/src/main/java/zk/modules/posprinter/PrinterTransport.kt
package zk.modules.posprinter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

object PrinterTransport {
  private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
  private const val BT_CHUNK = 1024

  suspend fun send(context: Context, printerId: String, data: ByteArray, timeoutMs: Int) =
    withContext(Dispatchers.IO) {
      when {
        printerId.startsWith("bt:") -> sendBluetooth(context, printerId.removePrefix("bt:"), data)
        printerId.startsWith("tcp:") -> {
          val target = printerId.removePrefix("tcp:")
          val host = target.substringBeforeLast(":")
          val port = target.substringAfterLast(":").toIntOrNull() ?: 9100
          sendTcp(host, port, data, timeoutMs)
        }
        else -> throw PrinterException("Unknown printer id: $printerId")
      }
    }

  private fun sendTcp(host: String, port: Int, data: ByteArray, timeoutMs: Int) {
    try {
      Socket().use { s ->
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        s.getOutputStream().apply { write(data); flush() }
      }
    } catch (e: IOException) {
      throw PrinterException("Could not print to $host:$port: ${e.message}", e)
    }
  }

  @SuppressLint("MissingPermission")
  private suspend fun sendBluetooth(context: Context, address: String, data: ByteArray) {
    val adapter = BluetoothPrinters.adapter(context) ?: throw PrinterException("Bluetooth not available")
    if (!adapter.isEnabled) throw PrinterException("Bluetooth is turned off")
    adapter.cancelDiscovery() // an active scan makes RFCOMM connects slow or fail

    val device = adapter.getRemoteDevice(address)
    val socket = connect { device.createRfcommSocketToServiceRecord(SPP_UUID) }
      ?: connect { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) } // some cheap printers need this
      ?: throw PrinterException("Could not connect to $address. Is the printer on and paired?")

    try {
      val out = socket.outputStream
      var off = 0
      while (off < data.size) {
        val n = minOf(BT_CHUNK, data.size - off)
        out.write(data, off, n)
        out.flush()
        off += n
        delay(10) // small printers have small buffers
      }
      // Closing right after the last write cuts off the end of the receipt on many printers.
      delay(300L + data.size / 10)
    } catch (e: IOException) {
      throw PrinterException("Bluetooth write failed: ${e.message}", e)
    } finally {
      runCatching { socket.close() }
    }
  }

  private fun connect(factory: () -> BluetoothSocket): BluetoothSocket? {
    val s = try { factory() } catch (_: IOException) { return null }
    return try {
      s.connect()
      s
    } catch (_: IOException) {
      runCatching { s.close() }
      null
    }
  }
}
