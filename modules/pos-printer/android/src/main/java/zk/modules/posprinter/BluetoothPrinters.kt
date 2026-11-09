// modules/pos-printer/android/src/main/java/zk/modules/posprinter/BluetoothPrinters.kt
package zk.modules.posprinter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.delay

/** Permissions are requested from JS before any of this runs. */
@SuppressLint("MissingPermission")
object BluetoothPrinters {

  fun adapter(context: Context): BluetoothAdapter? =
    context.getSystemService(BluetoothManager::class.java)?.adapter

  fun isEnabled(context: Context) = adapter(context)?.isEnabled == true

  suspend fun discover(context: Context, scanMs: Long): List<Map<String, Any?>> {
    val adapter = adapter(context) ?: return emptyList()
    if (!adapter.isEnabled) return emptyList()

    val found = LinkedHashMap<String, Map<String, Any?>>()
    adapter.bondedDevices?.forEach { d ->
      if (d.type != BluetoothDevice.DEVICE_TYPE_LE) found[d.address] = info(d, "bonded")
    }

    if (scanMs > 0) {
      val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
          if (intent.action != BluetoothDevice.ACTION_FOUND) return
          val d: BluetoothDevice = (
            if (Build.VERSION.SDK_INT >= 33)
              intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else
              @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
          ) ?: return
          // ESC/POS over Bluetooth = Classic SPP. Skip BLE-only devices and nameless noise.
          if (d.type == BluetoothDevice.DEVICE_TYPE_LE || d.name.isNullOrBlank()) return
          synchronized(found) { found.putIfAbsent(d.address, info(d, "scan")) }
        }
      }
      val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
      if (Build.VERSION.SDK_INT >= 33) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
      } else {
        context.registerReceiver(receiver, filter)
      }
      try {
        adapter.startDiscovery()
        delay(scanMs)
      } finally {
        adapter.cancelDiscovery()
        runCatching { context.unregisterReceiver(receiver) }
      }
    }

    return synchronized(found) {
      found.values.sortedByDescending { it["likelyPrinter"] == true }
    }
  }

  private fun info(d: BluetoothDevice, source: String): Map<String, Any?> = mapOf(
    "id" to "bt:${d.address}",
    "name" to (d.name ?: d.address),
    "address" to d.address,
    "type" to "bluetooth",
    "paired" to (d.bondState == BluetoothDevice.BOND_BONDED),
    // Most receipt printers report major class IMAGING; cheap ones sometimes report nothing useful.
    "likelyPrinter" to (d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.IMAGING),
    "source" to source,
  )
}
