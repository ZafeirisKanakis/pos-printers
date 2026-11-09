// modules/pos-printer/android/src/main/java/zk/modules/posprinter/NetworkPrinters.kt
package zk.modules.posprinter

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume

object NetworkPrinters {
  // _pdl-datastream advertises the raw port (9100) that ESC/POS uses.
  // _printer / _ipp only tell us "there's a printer at this IP"; we still print raw on 9100.
  private val MDNS_TYPES = listOf("_pdl-datastream._tcp", "_printer._tcp", "_ipp._tcp")
  private const val RAW_PORT = 9100
  private const val TAG = "PosPrinter"
  /** How many hosts we probe at the same time. Too many floods the Wi-Fi and connects time out. */
  private const val SCAN_PARALLELISM = 32
  /** Per-host connect timeout. 400 ms was too tight on Wi-Fi with power saving. */
  private const val SCAN_TIMEOUT_MS = 1000

  suspend fun discover(context: Context, o: DiscoveryOptions): List<Map<String, Any?>> = coroutineScope {
    val mdns = async {
      if (o.mdns) runCatching { discoverMdns(context, o.mdnsTimeoutMs.toLong()) }
        .onFailure { Log.w(TAG, "mDNS discovery failed", it) }
        .getOrDefault(emptyList())
      else emptyList()
    }
    val subnet = async {
      if (o.subnetScan) runCatching { scanSubnet(context, o.subnetPort) }
        .onFailure { Log.w(TAG, "Subnet scan failed", it) }
        .getOrDefault(emptyList())
      else emptyList()
    }
    // Same IP found both ways: keep the mDNS entry, it has a real name.
    val byHost = LinkedHashMap<String, Map<String, Any?>>()
    mdns.await().forEach { byHost.putIfAbsent(it["address"] as String, it) }
    subnet.await().forEach { byHost.putIfAbsent(it["address"] as String, it) }
    byHost.values.toList()
  }

  @Suppress("DEPRECATION")
  private suspend fun discoverMdns(context: Context, timeoutMs: Long): List<Map<String, Any?>> {
    val nsd = context.getSystemService(NsdManager::class.java) ?: return emptyList()
    val seen = ConcurrentLinkedQueue<NsdServiceInfo>()

    val listeners = MDNS_TYPES.map { type ->
      object : NsdManager.DiscoveryListener {
        override fun onServiceFound(s: NsdServiceInfo) { seen.add(s) }
        override fun onServiceLost(s: NsdServiceInfo) {}
        override fun onDiscoveryStarted(t: String) {}
        override fun onDiscoveryStopped(t: String) {}
        override fun onStartDiscoveryFailed(t: String, code: Int) {}
        override fun onStopDiscoveryFailed(t: String, code: Int) {}
      }.also { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, it) }
    }
    try {
      delay(timeoutMs)
    } finally {
      listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
    }

    val result = LinkedHashMap<String, Map<String, Any?>>()
    // Resolve one at a time: before Android 14, parallel resolves fail with FAILURE_ALREADY_ACTIVE.
    for (s in seen.distinctBy { it.serviceName + it.serviceType }) {
      val r = withTimeoutOrNull(3000) { resolve(nsd, s) } ?: continue
      val addr = r.host as? Inet4Address ?: continue
      val host = addr.hostAddress ?: continue
      val isRaw = s.serviceType.contains("pdl-datastream")
      if (result.containsKey(host) && !isRaw) continue
      val port = if (isRaw) r.port else RAW_PORT
      result[host] = mapOf(
        "id" to "tcp:$host:$port",
        "name" to s.serviceName,
        "address" to host,
        "port" to port,
        "type" to "network",
        "source" to "mdns",
      )
    }
    return result.values.toList()
  }

  @Suppress("DEPRECATION")
  private suspend fun resolve(nsd: NsdManager, s: NsdServiceInfo): NsdServiceInfo? =
    suspendCancellableCoroutine { cont ->
      nsd.resolveService(s, object : NsdManager.ResolveListener {
        override fun onServiceResolved(info: NsdServiceInfo) { if (cont.isActive) cont.resume(info) }
        override fun onResolveFailed(info: NsdServiceInfo, code: Int) { if (cont.isActive) cont.resume(null) }
      })
    }

  /** Assumes a /24 network, which covers almost every shop/office Wi-Fi. */
  private suspend fun scanSubnet(context: Context, port: Int): List<Map<String, Any?>> {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
    // Use the Wi-Fi/Ethernet network, not the "active" one: with a VPN or mobile data
    // active we would otherwise scan the wrong address range.
    @Suppress("DEPRECATION")
    val lan = cm.allNetworks.firstOrNull { n ->
      val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
      (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
    if (lan == null) {
      Log.d(TAG, "Subnet scan skipped: no Wi-Fi/Ethernet network")
      return emptyList()
    }
    val lp = cm.getLinkProperties(lan) ?: return emptyList()
    val own = lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull()
      ?: return emptyList()
    val b = own.address
    val prefix = "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}"
    val self = b[3].toInt() and 0xFF
    Log.d(TAG, "Subnet scan: $prefix.1-254 port $port (phone is ${own.hostAddress})")

    val limit = Semaphore(SCAN_PARALLELISM)
    val found = coroutineScope {
      (1..254).filter { it != self }.map { i ->
        async(Dispatchers.IO) {
          limit.withPermit {
            val ip = "$prefix.$i"
            if (isPortOpen(ip, port, SCAN_TIMEOUT_MS)) mapOf(
              "id" to "tcp:$ip:$port",
              "name" to "Printer @ $ip",
              "address" to ip,
              "port" to port,
              "type" to "network",
              "source" to "subnet",
            ) else null
          }
        }
      }.awaitAll().filterNotNull()
    }
    Log.d(TAG, "Subnet scan found ${found.size}: ${found.map { it["address"] }}")
    return found
  }

  private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
    Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
    true
  } catch (_: Exception) {
    false
  }
}
