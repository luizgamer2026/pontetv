package com.pontetv.tv

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.security.SecureRandom

/**
 * A TV cria um grupo Wi-Fi Direct (ela é o "roteador"); o celular entra pelo SSID/senha do QR.
 * Precisa de Android 10+ na TV e da permissão de localização (exigida pelo Android para Wi-Fi P2P).
 * Se a TV usa Wi-Fi com um só rádio, ele pode cair enquanto o grupo existir; no cabo não há problema.
 */
object DirectGroup {
    @Volatile var active = false
    @Volatile var ssid = ""
    @Volatile var pass = ""
    @Volatile var ownerIp = "192.168.49.1"
    @Volatile var error = ""
    private var mgr: WifiP2pManager? = null
    private var ch: WifiP2pManager.Channel? = null
    private val main = Handler(Looper.getMainLooper())

    private fun rnd(n: Int): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        return (1..n).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    @SuppressLint("MissingPermission")
    fun start(ctx: Context, done: () -> Unit) {
        val m = ctx.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (m == null) { error = "Esta TV não tem Wi-Fi Direct"; done(); return }
        if (Build.VERSION.SDK_INT < 29) { error = "Wi-Fi Direct exige Android 10+ na TV"; done(); return }
        val c = m.initialize(ctx.applicationContext, Looper.getMainLooper(), null)
        mgr = m; ch = c
        try { m.removeGroup(c, null) } catch (_: Exception) {}
        main.postDelayed({
            try {
                ssid = "DIRECT-PT-" + rnd(4)
                pass = rnd(10)
                val cfg = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pass).build()
                m.createGroup(c, cfg, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        active = true; error = ""
                        m.requestConnectionInfo(c) { info ->
                            info?.groupOwnerAddress?.hostAddress?.let { ownerIp = it }
                            done()
                        }
                    }
                    override fun onFailure(reason: Int) {
                        active = false; error = "Wi-Fi Direct falhou (código $reason)"; done()
                    }
                })
            } catch (e: Exception) {
                active = false; error = "Wi-Fi Direct indisponível: ${e.message}"; done()
            }
        }, 700)
    }

    fun stop() {
        val m = mgr; val c = ch
        active = false
        if (m != null && c != null) try { m.removeGroup(c, null) } catch (_: Exception) {}
    }
}
