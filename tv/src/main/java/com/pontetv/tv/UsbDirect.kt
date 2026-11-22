package com.pontetv.tv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Modo "USB direto": o app lê o teclado USB da TV pela porta USB, sem passar pelo sistema Android da TV.
 *
 * Por quê: as teclas do sistema (F1…F12, Esc, Windows) são tratadas pela TV ANTES de chegarem a qualquer app. O jeito de elas
 * não agirem na TV é o sistema nunca ver a tecla: o app pega a interface HID do teclado (claimInterface com force: o Android solta o
 * driver dele), lê os relatórios de 8 bytes (modificadores + 6 teclas) e os manda como estão pelo Bluetooth.
 *
 * Só roda enquanto o teclado controla o celular (start/stop). Ao soltar (F8/VOLTAR), o app devolve a interface (releaseInterface) e o
 * Android religa o driver do teclado. Se algo falhar (sem permissão, sem teclado, erro), o teclado continua funcionando pelo caminho
 * normal do Android — e o HUD diz o motivo e o que o app enxerga de USB (campo [seen]).
 * O mouse continua pelo Android (captura do ponteiro): assim a roda de rolagem e os botões extras seguem funcionando.
 */
object UsbDirect {
    private const val ACTION_PERM = "com.pontetv.tv.USB_PERMISSION"

    /** Preferência do usuário (F11 ou botão do painel). */
    @Volatile var enabled = true
    /** true = o app está lendo o teclado direto neste instante. */
    @Volatile var active = false
    @Volatile var status = "parado"
    @Volatile var reports = 0
    @Volatile var lastReportAt = 0L
    @Volatile var errors = 0
    /** Diagnóstico: o que o Android da TV deixa o app ver de USB (host sim/não, quantos aparelhos, vid:pid e [classe/subclasse/protocolo] de cada interface). */
    @Volatile var seen = "?"
    @Volatile private var hostFeature = true

    private var app: Context? = null
    private var um: UsbManager? = null
    private val mainH = Handler(Looper.getMainLooper())
    // uma thread só: sessões nunca se sobrepõem (a nova espera a antiga devolver o teclado)
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "PonteTV-USB").apply { isDaemon = true } }
    @Volatile private var wantActive = false
    @Volatile private var gen = 0
    private var askedName: String? = null
    private var askedAt = 0L
    @Volatile private var deniedName: String? = null
    // interfaces que não são teclado padrão (só mexida pela thread PonteTV-USB)
    private val rejected = HashSet<String>()

    fun init(ctx: Context) {
        if (app != null) return
        val a = ctx.applicationContext
        app = a
        hostFeature = try { a.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST) } catch (_: Throwable) { true }
        um = a.getSystemService(Context.USB_SERVICE) as? UsbManager
        val f = IntentFilter().apply {
            addAction(ACTION_PERM)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(a, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(c: Context?, intent: Intent?) {
            val i = intent ?: return
            val d = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            when (i.action) {
                ACTION_PERM -> {
                    val ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (d != null) { if (ok) { askedName = null; deniedName = null } else deniedName = d.deviceName }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (d != null && (d.deviceName == askedName || d.deviceName == deniedName)) { askedName = null; deniedName = null }
                }
            }
        }
    }

    /** Começa a ler o teclado direto (chamado quando o teclado passa a controlar o celular). Não bloqueia. */
    fun start() {
        if (!enabled) { status = "desligado"; return }
        if (wantActive) return
        wantActive = true
        val g = ++gen
        status = "procurando teclado USB…"
        exec.execute { session(g) }
    }

    /** Para e devolve o teclado ao Android (soltar/F8/app saiu da frente). Não bloqueia: a thread devolve em até ~150 ms. */
    fun stop() {
        wantActive = false
        gen++
        if (!active) status = "parado"
    }

    /** Ao abrir o app: pede a permissão do USB logo (assim a caixa do Android não aparece no meio do jogo). */
    fun prepare() {
        if (!enabled) return
        exec.execute {
            try {
                val m = um ?: return@execute
                seen = describe(m)
                val f = pick(m) ?: return@execute
                if (!m.hasPermission(f.dev)) askPermission(m, f.dev)
            } catch (_: Throwable) {}
        }
    }

    private class Found(val dev: UsbDevice, val itf: UsbInterface, val ep: UsbEndpoint, val boot: Boolean) {
        val key: String get() = dev.deviceName + "#" + itf.id
    }

    /**
     * Interfaces HID de teclado com endpoint de interrupção de entrada. Primeiro as "boot" (subclasse 1, protocolo 1: relatório fixo de
     * 8 bytes); depois as sem subclasse (protocolo 0), que só valem se o descritor de relatório for de um teclado padrão (ver [isStdKeyboard]).
     */
    private fun candidates(m: UsbManager): List<Found> {
        val boot = ArrayList<Found>()
        val other = ArrayList<Found>()
        for (d in m.deviceList.values) {
            for (k in 0 until d.interfaceCount) {
                val itf = d.getInterface(k)
                if (itf.interfaceClass != UsbConstants.USB_CLASS_HID) continue
                if (itf.interfaceProtocol == 2) continue // mouse
                var ep: UsbEndpoint? = null
                for (j in 0 until itf.endpointCount) {
                    val e = itf.getEndpoint(j)
                    if (e.direction == UsbConstants.USB_DIR_IN && e.type == UsbConstants.USB_ENDPOINT_XFER_INT) { ep = e; break }
                }
                if (ep == null) continue
                if (itf.interfaceSubclass == 1 && itf.interfaceProtocol == 1) boot.add(Found(d, itf, ep, true))
                else if (itf.interfaceSubclass == 0 && ep.maxPacketSize >= 8) other.add(Found(d, itf, ep, false))
            }
        }
        return boot + other
    }

    private fun pick(m: UsbManager): Found? = candidates(m).firstOrNull { !rejected.contains(it.key) }

    private fun describe(m: UsbManager): String {
        val list = try { m.deviceList.values.toList() } catch (_: Throwable) { emptyList<UsbDevice>() }
        val sb = StringBuilder()
        sb.append("host USB do sistema: ").append(if (hostFeature) "sim" else "NÃO").append(" · aparelhos que o app vê: ").append(list.size)
        for (d in list.take(4)) {
            sb.append(" · ").append(String.format("%04x:%04x", d.vendorId, d.productId)).append(" [")
            for (k in 0 until d.interfaceCount) {
                val i = d.getInterface(k)
                if (k > 0) sb.append(' ')
                sb.append(i.interfaceClass).append('/').append(i.interfaceSubclass).append('/').append(i.interfaceProtocol)
            }
            sb.append(']')
        }
        return sb.toString()
    }

    private fun whyNone(m: UsbManager): String {
        val n = try { m.deviceList.size } catch (_: Throwable) { 0 }
        return when {
            n == 0 && !hostFeature -> "esta TV não oferece USB aos apps (sem host USB): o app não consegue ler o teclado direto"
            n == 0 -> "o Android da TV não mostra nenhum aparelho USB para o app"
            else -> "nenhum teclado USB padrão entre os $n aparelhos que o app vê"
        }
    }

    private fun askPermission(m: UsbManager, d: UsbDevice) {
        if (deniedName == d.deviceName) { status = "acesso USB negado (aceite na caixa do Android ou desligue o USB direto)"; return }
        status = "aguardando você aceitar o acesso USB na TV"
        val now = SystemClock.uptimeMillis()
        if (askedName == d.deviceName && now - askedAt < 60_000L) return
        askedName = d.deviceName; askedAt = now
        val a = app ?: return
        val pi = PendingIntent.getBroadcast(a, 0, Intent(ACTION_PERM).setPackage(a.packageName), PendingIntent.FLAG_MUTABLE)
        try { m.requestPermission(d, pi) } catch (_: Throwable) {}
    }

    private fun alive(g: Int) = wantActive && gen == g
    private fun nap(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    private fun session(g: Int) {
        while (alive(g)) {
            val m = um
            if (m == null) { status = "esta TV não tem USB host"; seen = "sem UsbManager"; nap(1000); continue }
            val f = try { pick(m) } catch (_: Throwable) { null }
            seen = try { describe(m) } catch (_: Throwable) { "?" }
            if (f == null) { status = try { whyNone(m) } catch (_: Throwable) { "procurando teclado USB…" }; nap(500); continue }
            if (!m.hasPermission(f.dev)) { askPermission(m, f.dev); nap(500); continue }
            runOn(g, m, f)
            nap(300)
        }
        active = false
    }

    /** O descritor de relatório é de um teclado padrão (mesmo relatório de 8 bytes do boot, sem Report ID)? Interface sem isso não é lida. */
    private fun isStdKeyboard(c: UsbDeviceConnection, f: Found): Boolean {
        val d = ByteArray(512)
        val n = try { c.controlTransfer(0x81, 0x06, 0x2200, f.itf.id, d, d.size, 500) } catch (_: Throwable) { -1 }
        if (n <= 0) return false
        var i = 0
        var page = -1
        var kbd = false
        var reportId = false
        while (i < n) {
            val b = d[i].toInt() and 0xFF
            if (b == 0xFE) { // item longo
                if (i + 1 >= n) break
                i += 3 + (d[i + 1].toInt() and 0xFF)
                continue
            }
            val size = when (b and 3) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 4 }
            val v = if (size >= 1 && i + 1 < n) d[i + 1].toInt() and 0xFF else 0
            when (b and 0xFC) {
                0x04 -> page = if (size == 1) v else -1        // Usage Page
                0x08 -> if (page == 0x01 && v == 0x06) kbd = true // Usage (Keyboard)
                0x84 -> reportId = true                         // Report ID
            }
            i += 1 + size
        }
        return kbd && !reportId
    }

    private fun runOn(g: Int, m: UsbManager, f: Found) {
        val c = try { m.openDevice(f.dev) } catch (_: Throwable) { null }
        if (c == null) { status = "não consegui abrir o teclado USB"; nap(1000); return }
        var claimed = false
        try {
            // pegar a interface faz o Android "remover" o teclado: avisa o InputWatcher para não contar como USB soltando
            InputWatcher.quietUntil = SystemClock.uptimeMillis() + 4000
            claimed = c.claimInterface(f.itf, true)
            if (!claimed) { status = "não consegui pegar o teclado (outro app usando?)"; nap(1000); return }
            if (!f.boot && !isStdKeyboard(c, f)) {
                rejected.add(f.key)
                status = "interface HID ${f.itf.id} não é teclado padrão (ignorada)"
                return
            }
            if (f.boot) c.controlTransfer(0x21, 0x0B, 0, f.itf.id, null, 0, 300) // SET_PROTOCOL: boot (relatório fixo de 8 bytes)
            c.controlTransfer(0x21, 0x0A, 0, f.itf.id, null, 0, 300) // SET_IDLE: só manda quando muda
            active = true
            status = "lendo ${f.dev.productName ?: "o teclado"} direto pelo USB"
            if (!readLoop(g, m, c, f)) { errors++; status = "o teclado USB parou de responder" }
        } catch (t: Throwable) {
            errors++
            status = "erro: ${t.javaClass.simpleName}"
        } finally {
            active = false
            // nenhuma tecla pode ficar presa no celular
            try { HidLink.send(HidDescriptor.ID_KEYBOARD, ByteArray(HidDescriptor.KEYBOARD_SIZE)) } catch (_: Throwable) {}
            InputWatcher.quietUntil = SystemClock.uptimeMillis() + 4000
            try { if (claimed) c.releaseInterface(f.itf) } catch (_: Throwable) {} // devolve o teclado ao Android
            try { c.close() } catch (_: Throwable) {}
        }
    }

    /** F8 solta, F9/Insert ritmo, F10/Pause keepalive, F11 modo USB (códigos de uso HID). */
    private fun cmdFor(u: Int): Int = when (u) {
        0x41 -> HubCommands.RELEASE_TOGGLE
        0x42, 0x49 -> HubCommands.RATE
        0x43, 0x48 -> HubCommands.KEEPALIVE
        0x44 -> HubCommands.USB
        else -> 0
    }

    /**
     * Lê com bulkTransfer (funciona em endpoint de interrupção e existe em qualquer Android; UsbDeviceConnection.requestWait(timeout)
     * só existe a partir do Android 13 e derrubava a leitura na TV, que é Android 11).
     * true = foi pedido para parar; false = o dispositivo caiu/parou de responder.
     */
    private fun readLoop(g: Int, m: UsbManager, c: UsbDeviceConnection, f: Found): Boolean {
        val ep = f.ep
        val size = if (ep.maxPacketSize >= 8) ep.maxPacketSize else 8
        val buf = ByteArray(size)
        var prev = ByteArray(8)
        var sent = ByteArray(8)
        var bad = 0
        var lastCheck = SystemClock.uptimeMillis()
        while (alive(g)) {
            val t0 = SystemClock.uptimeMillis()
            val n = c.bulkTransfer(ep, buf, size, 100)
            val now = SystemClock.uptimeMillis()
            if (n < 0) {
                // -1 em ~100 ms = nenhuma tecla mexeu (normal); -1 na hora = erro de verdade
                if (now - t0 < 30L) { if (++bad >= 5) return false; nap(20) } else bad = 0
                if (now - lastCheck > 1000L) {
                    lastCheck = now
                    val still = try { m.deviceList.containsKey(f.dev.deviceName) } catch (_: Throwable) { true }
                    if (!still) return false
                }
                continue
            }
            bad = 0
            if (n < 3) continue
            val cur = ByteArray(8)
            System.arraycopy(buf, 0, cur, 0, if (n < 8) n else 8)
            reports++
            lastReportAt = now
            // teclas de comando do hub: agem aqui e NÃO vão para o celular
            var cmd = 0
            val out = cur.copyOf()
            for (i in 2..7) {
                val u = cur[i].toInt() and 0xFF
                if (u == 0) continue
                val cm = cmdFor(u)
                if (cm != 0) {
                    out[i] = 0
                    var wasDown = false
                    for (k in 2..7) if ((prev[k].toInt() and 0xFF) == u) wasDown = true
                    if (!wasDown) cmd = cm
                }
            }
            prev = cur
            if (!out.contentEquals(sent)) {
                sent = out
                HidLink.send(HidDescriptor.ID_KEYBOARD, out.copyOf())
            }
            if (cmd != 0) { val cc = cmd; mainH.post { HubCommands.handler?.invoke(cc) } }
        }
        return true
    }
}
