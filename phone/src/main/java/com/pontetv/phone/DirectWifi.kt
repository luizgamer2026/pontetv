package com.pontetv.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import java.io.IOException

/**
 * Entra no grupo Wi-Fi Direct criado pela TV (SSID/senha vêm no QR) como rede local, sem internet,
 * e prende o processo a ela. O Android mostra uma confirmação ao usuário.
 */
object DirectWifi {
    private var cb: ConnectivityManager.NetworkCallback? = null

    fun connect(ctx: Context, ssid: String, pass: String, done: (Throwable?) -> Unit) {
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
        release(ctx)
        var finished = false
        try {
            val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(pass).build()
            val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(spec).build()
            val c = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    cm.bindProcessToNetwork(network)
                    if (!finished) { finished = true; done(null) }
                }
                override fun onUnavailable() {
                    if (!finished) { finished = true; done(IOException("Não conectou ao Wi-Fi Direct da TV (recusado ou fora de alcance)")) }
                }
                override fun onLost(network: Network) { cm.bindProcessToNetwork(null) }
            }
            cb = c
            cm.requestNetwork(req, c, 40_000)
        } catch (e: Exception) {
            done(e)
        }
    }

    fun release(ctx: Context) {
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
        cb?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        cb = null
        try { cm.bindProcessToNetwork(null) } catch (_: Exception) {}
    }
}
