package com.example.xr.companion

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

data class DiscoveredPcHost(
    val serviceName: String,
    val hostAddress: String,
    val controlPort: Int,
    val udpPort: Int = CompanionProtocol.DEFAULT_UDP_PORT
)

/**
 * Discovers PC Companion servers on the local Wi-Fi network via Android NsdManager (mDNS).
 * Falls back cleanly if NSD is unavailable or when user enters IP / uses USB adb reverse manually.
 */
class CompanionNsdDiscovery(private val context: Context) {

    private val nsdManager: NsdManager? =
        runCatching { context.getSystemService(Context.NSD_SERVICE) as? NsdManager }.getOrNull()

    private val resolveExecutor = Executors.newSingleThreadExecutor()
    private val _hosts = MutableStateFlow<List<DiscoveredPcHost>>(emptyList())
    val hosts: StateFlow<List<DiscoveredPcHost>> = _hosts.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var discovering = false

    fun startDiscovery() {
        val mgr = nsdManager ?: return
        if (discovering) return
        _hosts.value = emptyList()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                discovering = true
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolveService(mgr, serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _hosts.value = _hosts.value.filterNot { it.serviceName == serviceInfo.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                discovering = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discovering = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                discovering = false
            }
        }
        discoveryListener = listener
        runCatching {
            mgr.discoverServices(CompanionProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveService(mgr: NsdManager, info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                mgr.registerServiceInfoCallback(
                    info,
                    resolveExecutor,
                    object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                        override fun onServiceUpdated(updated: NsdServiceInfo) {
                            val addr = updated.hostAddresses.firstOrNull {
                                CompanionNetworkSecurity.isPrivateInetAddress(it)
                            }?.hostAddress ?: return
                            val udpPort = updated.attributes["udpPort"]
                                ?.toString(Charsets.UTF_8)
                                ?.toIntOrNull() ?: CompanionProtocol.DEFAULT_UDP_PORT
                            upsertHost(
                                DiscoveredPcHost(
                                    serviceName = updated.serviceName,
                                    hostAddress = addr,
                                    controlPort = updated.port,
                                    udpPort = udpPort
                                )
                            )
                            runCatching { mgr.unregisterServiceInfoCallback(this) }
                        }

                        override fun onServiceLost() {}
                        override fun onServiceInfoCallbackUnregistered() {}
                    }
                )
            }
        } else {
            runCatching {
                mgr.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val addr = resolved.host ?: return
                        if (!CompanionNetworkSecurity.isPrivateInetAddress(addr)) return
                        val hostAddr = addr.hostAddress ?: return
                        upsertHost(
                            DiscoveredPcHost(
                                serviceName = resolved.serviceName,
                                hostAddress = hostAddr,
                                controlPort = resolved.port,
                                udpPort = CompanionProtocol.DEFAULT_UDP_PORT
                            )
                        )
                    }
                })
            }
        }
    }

    private fun upsertHost(host: DiscoveredPcHost) {
        val current = _hosts.value.toMutableList()
        val idx = current.indexOfFirst { it.hostAddress == host.hostAddress && it.controlPort == host.controlPort }
        if (idx >= 0) current[idx] = host else current += host
        _hosts.value = current
    }

    fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        discovering = false
        runCatching { nsdManager?.stopServiceDiscovery(listener) }
    }
}
