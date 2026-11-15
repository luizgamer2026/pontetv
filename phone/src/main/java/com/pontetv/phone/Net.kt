package com.pontetv.phone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import com.pontetv.common.Proto
import com.pontetv.common.Wire
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

data class Found(val id: String, val name: String, val kind: String, val host: String, val port: Int)

/** Descoberta de receptores na rede local via mDNS/NSD (sem internet). */
class NsdHelper(ctx: Context, private val onChange: (List<Found>) -> Unit) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val found = LinkedHashMap<String, Found>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val main = Handler(Looper.getMainLooper())
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.contains("pontetv")) main.post { queue.add(info); pump() }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post { found.remove(info.serviceName); onChange(found.values.toList()) }
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        listener = l
        try { nsd.discoverServices(Proto.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) } catch (_: Exception) {}
    }

    fun stop() {
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        listener = null
    }

    @Suppress("DEPRECATION")
    private fun pump() {
        if (resolving) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(s: NsdServiceInfo, errorCode: Int) {
                    main.post { resolving = false; pump() }
                }
                override fun onServiceResolved(s: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        val a = s.attributes
                        fun attr(k: String): String? = a[k]?.let { String(it) }
                        val id = attr("id")
                        val host = attr("ip") ?: s.host?.hostAddress
                        if (id != null && host != null) {
                            found[s.serviceName] = Found(id, attr("name") ?: s.serviceName, attr("kind") ?: "tv", host, s.port)
                            onChange(found.values.toList())
                        }
                        pump()
                    }
                }
            })
        } catch (e: Exception) { resolving = false }
    }
}

object Pairing {
    /** Pareia com um receptor usando o código numérico (PIN) ou o token do QR. Devolve a chave persistente. */
    fun pair(p: Prefs, host: String, port: Int, secret: String): Result<Paired> = runCatching {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 4000)
        s.soTimeout = 6000
        val w = Wire(s)
        try {
            w.send(Proto.T_HELLO, JSONObject().put("mode", "pair").put("id", p.deviceId)
                .put("name", p.deviceName).put("secret", secret).toString().toByteArray())
            val r = w.read()
            val j = JSONObject(String(r.data))
            if (r.type != Proto.T_HELLO_OK) throw IOException(j.optString("reason", "Recusado pela TV"))
            Paired(j.getString("id"), j.getString("name"), host, port, j.getString("key"), j.optString("kind", "tv"))
        } finally { w.close() }
    }
}
