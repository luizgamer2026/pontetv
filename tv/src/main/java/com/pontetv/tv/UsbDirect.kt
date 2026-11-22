package com.pontetv.tv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException

/**
 * Modo "USB direto": o app lê o teclado USB da TV pela porta USB, sem passar pelo sistema Android da TV.
 *
 * Por quê: o bloqueio de teclas por acessibilidade faz TODA tecla (e todo movimento do mouse) passar pelo system_server da TV e esperar até
 * 500 ms pela resposta do serviço; numa TV de ~900 MB isso acumulava até ~2 s de atraso e o teclado "morria". Aqui o app pega a interface HID
 * do teclado (claimInterface com force: o Android solta o driver dele), lê os relatórios de 8 bytes (modificadores + 6 teclas) e os manda
 * como estão pelo Bluetooth. Como o sistema nunca vê as teclas, F1…F12/Windows/Esc não agem na TV e não há nada para bloquear.
 *
 * Como acha o teclado: (1) o aparelho USB com o mesmo vid:pid do teclado que o Android já reconheceu (InputWatcher); (2) senão, qualquer um com
 * interface HID "boot" de teclado. Na interface HID escolhe a "boot" (relatório fixo de 8 bytes) ou, se não houver, lê o descritor de relatório e
 * só aceita o formato padrão de 64 bits (com ou sem "report ID").
 *
 * Só roda enquanto o teclado controla o celular (start/stop). Ao soltar (F8/VOLTAR), o app devolve a interface (releaseInterface) e o Android
 * religa o driver do teclado. Se algo falhar (sem permissão, sem teclado, formato diferente, erro), o teclado continua funcionando pelo caminho
 * normal do Android. O mouse continua pelo Android (captura do ponteiro): assim a roda de rolagem e os botões extras seguem funcionando.
 */
object UsbDirect {
    private const val ACTION_PERM = "com.pontetv.tv.USB_PERMISSION"

    /** Preferência do usuário (F11 ou botão do painel). */
    @Volatile var enabled = true
    /** true = o app está lendo o teclado direto neste instante. */
    @Volatile var active = false
    @Volatile var status = "parado"
    /** O que o app enxerga no USB (vid:pid nome [interface:classe/subclasse/protocolo]), para a HUD. */
    @Volatile var seen = ""
    @Volatile var reports = 0
    @Volatile var lastReportAt = 0L
    @Volatile var errors = 0

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
    private var seenAt = 0L

    fun init(ctx: Context) {
        if (app != null) return
        val a = ctx.applicationContext
        app = a
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

    /** Para e devolve o teclado ao Android (soltar/F8/app saiu da frente). Não bloqueia: a thread devolve em até ~200 ms. */
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
                val d = find(m) ?: return@execute
                if (!m.hasPermission(d)) askPermission(m, d)
            } catch (_: Throwable) {}
        }
    }

    private fun isBoot(i: UsbInterface) =
        i.interfaceClass == UsbConstants.USB_CLASS_HID && i.interfaceSubclass == 1 && i.interfaceProtocol == 1

    /** Interfaces HID do aparelho que têm endpoint de interrupção de entrada, as "boot" primeiro. */
    private fun hidInterfaces(d: UsbDevice): List<Pair<UsbInterface, UsbEndpoint>> {
        val out = ArrayList<Pair<UsbInterface, UsbEndpoint>>()
        for (k in 0 until d.interfaceCount) {
            val itf = d.getInterface(k)
            if (itf.interfaceClass != UsbConstants.USB_CLASS_HID) continue
            for (j in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(j)
                if (ep.direction == UsbConstants.USB_DIR_IN && ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) { out.add(itf to ep); break }
            }
        }
        return out.sortedBy { if (isBoot(it.first)) 0 else 1 }
    }

    /** Acha o teclado: mesmo vid:pid do que o Android reconheceu; senão qualquer aparelho com interface boot de teclado. */
    private fun find(m: UsbManager): UsbDevice? {
        val devs = m.deviceList.values
        val kv = InputWatcher.kbVid
        val kp = InputWatcher.kbPid
        if (kv >= 0) for (d in devs) if (d.vendorId == kv && d.productId == kp && hidInterfaces(d).isNotEmpty()) return d
        for (d in devs) for (k in 0 until d.interfaceCount) if (isBoot(d.getInterface(k))) return d
        return null
    }

    private fun describe(m: UsbManager): String {
        val devs = m.deviceList.values
        if (devs.isEmpty()) return "nenhum dispositivo USB visível ao app"
        val want = if (InputWatcher.kbVid >= 0) "teclado do Android = %04x:%04x · ".format(InputWatcher.kbVid, InputWatcher.kbPid) else ""
        return want + devs.joinToString(" | ") { d ->
            val nm = try { d.productName ?: "?" } catch (_: Throwable) { "?" }
            "%04x:%04x %s [".format(d.vendorId, d.productId, nm) +
                (0 until d.interfaceCount).joinToString(" ") { k ->
                    val i = d.getInterface(k)
                    "${i.id}:${i.interfaceClass}/${i.interfaceSubclass}/${i.interfaceProtocol}"
                } + "]"
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
            if (m == null) { status = "esta TV não tem USB host"; nap(1000); continue }
            val d = try { find(m) } catch (_: Throwable) { null }
            if (d == null) {
                status = "não achei o teclado entre os aparelhos USB"
                val now = SystemClock.uptimeMillis()
                if (now - seenAt > 2000L) { seenAt = now; seen = try { describe(m) } catch (_: Throwable) { "erro ao listar" } }
                nap(500); continue
            }
            if (!m.hasPermission(d)) { askPermission(m, d); nap(500); continue }
            runOn(g, m, d)
            nap(300)
        }
        active = false
    }

    private class Pick(val itf: UsbInterface, val ep: UsbEndpoint, val reportId: Int, val boot: Boolean)

    private fun readDescriptor(c: UsbDeviceConnection, itf: UsbInterface): ByteArray? {
        val buf = ByteArray(1024)
        val n = c.controlTransfer(0x81, 0x06, 0x2200, itf.id, buf, buf.size, 500) // GET_DESCRIPTOR (relatório HID) da interface
        return if (n > 0) buf.copyOf(n) else null
    }

    /**
     * Procura no descritor de relatório a coleção "Teclado" (página 1, uso 6) e confere se o relatório de entrada dela tem 64 bits
     * (modificadores + reservado + 6 teclas). Devolve o "report ID" dessa coleção (0 = sem ID) ou null se não for o formato padrão.
     */
    private fun parseKeyboard(d: ByteArray?): Int? {
        if (d == null) return null
        var i = 0
        var usagePage = 0; var rSize = 0; var rCount = 0; var rId = 0; var lastUsage = 0
        var depth = 0; var kbDepth = -1
        val bits = HashMap<Int, Int>()
        while (i < d.size) {
            val b = d[i].toInt() and 0xFF
            if (b == 0xFE) { val len = if (i + 1 < d.size) d[i + 1].toInt() and 0xFF else 0; i += 3 + len; continue }
            val sz = when (b and 3) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 4 }
            val type = (b shr 2) and 3
            val tag = (b shr 4) and 0xF
            var v = 0
            for (k in 0 until sz) if (i + 1 + k < d.size) v = v or ((d[i + 1 + k].toInt() and 0xFF) shl (8 * k))
            i += 1 + sz
            when (type) {
                1 -> when (tag) { 0 -> usagePage = v; 7 -> rSize = v; 8 -> rId = v; 9 -> rCount = v }
                2 -> if (tag == 0) lastUsage = v
                0 -> {
                    when (tag) {
                        0xA -> { depth++; if (kbDepth < 0 && v == 1 && usagePage == 1 && lastUsage == 6) kbDepth = depth }
                        0xC -> { if (kbDepth == depth) kbDepth = -1; depth-- }
                        0x8 -> if (kbDepth >= 0) bits[rId] = (bits[rId] ?: 0) + rSize * rCount
                    }
                    lastUsage = 0
                }
            }
        }
        for ((id, n) in bits) if (n == 64) return id
        return null
    }

    private fun runOn(g: Int, m: UsbManager, dev: UsbDevice) {
        val c = try { m.openDevice(dev) } catch (_: Throwable) { null }
        if (c == null) { status = "não consegui abrir o teclado USB"; nap(1000); return }
        var claimed: UsbInterface? = null
        try {
            // escolhe a interface do teclado: a "boot" (8 bytes fixos) ou a primeira com o formato padrão no descritor
            var pick: Pick? = null
            for ((itf, ep) in hidInterfaces(dev)) {
                if (isBoot(itf)) { pick = Pick(itf, ep, 0, true); break }
                val id = parseKeyboard(try { readDescriptor(c, itf) } catch (_: Throwable) { null })
                if (id != null) { pick = Pick(itf, ep, id, false); break }
            }
            if (pick == null) {
                status = "este teclado não usa o formato padrão de 8 bytes (usando o Android)"
                nap(5000); return
            }
            // pegar a interface faz o Android "remover" o teclado: avisa o InputWatcher para não contar como USB soltando
            InputWatcher.quietUntil = SystemClock.uptimeMillis() + 4000
            if (!c.claimInterface(pick.itf, true)) { status = "não consegui pegar o teclado (outro app usando?)"; nap(1000); return }
            claimed = pick.itf
            if (pick.boot) c.controlTransfer(0x21, 0x0B, 0, pick.itf.id, null, 0, 300) // SET_PROTOCOL: boot (relatório fixo de 8 bytes)
            c.controlTransfer(0x21, 0x0A, 0, pick.itf.id, null, 0, 300) // SET_IDLE: só manda quando muda
            active = true
            status = "lendo ${try { dev.productName } catch (_: Throwable) { null } ?: "o teclado"} direto pelo USB" +
                if (pick.reportId != 0) " (relatório #${pick.reportId})" else ""
            if (!readLoop(g, c, pick.ep, pick.reportId)) { errors++; status = "o teclado USB parou de responder" }
        } catch (t: Throwable) {
            errors++
            status = "erro: ${t.javaClass.simpleName}"
        } finally {
            active = false
            // nenhuma tecla pode ficar presa no celular
            try { HidLink.send(HidDescriptor.ID_KEYBOARD, ByteArray(HidDescriptor.KEYBOARD_SIZE)) } catch (_: Throwable) {}
            InputWatcher.quietUntil = SystemClock.uptimeMillis() + 4000
            val cl = claimed
            try { if (cl != null) c.releaseInterface(cl) } catch (_: Throwable) {} // devolve o teclado ao Android
            try { c.close() } catch (_: Throwable) {}
        }
    }

    /** F8 solta, F9/Insert ritmo, F10/Pause keepalive, F11 modo USB, Scroll Lock bloqueio (códigos de uso HID). */
    private fun cmdFor(u: Int): Int = when (u) {
        0x41 -> HubCommands.RELEASE_TOGGLE
        0x42, 0x49 -> HubCommands.RATE
        0x43, 0x48 -> HubCommands.KEEPALIVE
        0x44 -> HubCommands.USB
        0x47 -> HubCommands.FILTER
        else -> 0
    }

    /** true = foi pedido para parar; false = o dispositivo caiu/parou de responder. */
    private fun readLoop(g: Int, c: UsbDeviceConnection, ep: UsbEndpoint, reportId: Int): Boolean {
        val size = if (ep.maxPacketSize in 9..64) ep.maxPacketSize else 16
        val buf = ByteBuffer.allocate(size)
        val req = UsbRequest()
        if (!req.initialize(c, ep)) return false
        val off = if (reportId != 0) 1 else 0   // com "report ID", o primeiro byte é o ID e os 8 seguintes são o teclado
        var prev = ByteArray(8)
        var sent = ByteArray(8)
        var queued = false
        var bad = 0
        try {
            while (alive(g)) {
                if (!queued) {
                    buf.clear()
                    if (!req.queue(buf)) { if (++bad >= 3) return false; nap(20); continue }
                    queued = true
                }
                val done: UsbRequest? = try { c.requestWait(200L) } catch (_: TimeoutException) { continue } catch (_: Exception) { null }
                queued = false
                if (done == null) { if (++bad >= 3) return false; continue }
                bad = 0
                val n = buf.position()
                val raw = buf.array()
                if (reportId != 0 && (n < 1 || (raw[0].toInt() and 0xFF) != reportId)) continue // relatório de outra coleção (mídia etc.)
                if (n - off < 3) continue
                val cur = ByteArray(8)
                System.arraycopy(raw, off, cur, 0, if (n - off < 8) n - off else 8)
                reports++
                lastReportAt = SystemClock.uptimeMillis()
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
        } finally {
            try { req.cancel() } catch (_: Throwable) {}
            try { req.close() } catch (_: Throwable) {}
        }
    }
}
