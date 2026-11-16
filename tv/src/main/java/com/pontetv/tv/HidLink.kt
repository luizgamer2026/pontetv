package com.pontetv.tv

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.Executors
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

/**
 * A TV se apresenta ao celular como um teclado + mouse Bluetooth de verdade (perfil HID Device).
 * O celular é o "computador": recebe as teclas e o mouse como se estivessem ligados nele.
 */
@SuppressLint("MissingPermission")
object HidLink {
    interface Listener { fun onHidState() }

    @Volatile var listener: Listener? = null
    @Volatile var supported = true
    @Volatile var registered = false
    @Volatile var host: BluetoothDevice? = null
    @Volatile var status = ""
    val connected: Boolean get() = host != null

    private var app: Context? = null
    private var prefs: TvPrefs? = null
    private var adapter: BluetoothAdapter? = null
    @Volatile private var hid: BluetoothHidDevice? = null
    private var started = false
    private val main = Handler(Looper.getMainLooper())
    private val cbExec = Executors.newSingleThreadExecutor()

    // ---- envio de baixa latência ----
    // Intervalo mínimo entre dois relatórios de MOVIMENTO (~125 Hz, como um mouse Bluetooth comum). Se o Bluetooth
    // não der conta, o movimento que chega nesse intervalo é SOMADO ao relatório que ainda não saiu (nada se perde,
    // nada fica na fila acumulando atraso).
    private const val MOVE_GAP_NS = 8_000_000L
    // Com o controle ativo e nada para enviar, manda um relatório vazio de mouse a cada 80 ms só para o link
    // Bluetooth não voltar ao modo de economia (sniff), que atrasa o primeiro movimento/tecla depois de uma pausa.
    private const val KEEPALIVE_NS = 80_000_000L
    private const val MAX_QUEUE = 512

    private class Report(val id: Int, val data: ByteArray)

    private val qLock = ReentrantLock()
    private val qCond: Condition = qLock.newCondition()
    private val queue = java.util.ArrayDeque<Report>()
    @Volatile private var workerRun = false
    @Volatile private var lastButtons = 0   // estado de botões que o mouse deve manter (usado no keepalive)
    private var nextMoveAt = 0L             // só a thread de envio mexe

    /** Diagnóstico (mostrado no HUD): relatórios enviados por segundo e o envio mais demorado da última janela. */
    @Volatile var statRate = 0
    @Volatile var statWorstMs = 0

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        prefs = TvPrefs(ctx)
        adapter = (ctx.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        startWorker()
    }

    fun adapterName(): String = try { adapter?.name ?: "—" } catch (e: SecurityException) { "—" }
    fun btEnabled(): Boolean = try { adapter?.isEnabled == true } catch (e: SecurityException) { false }
    fun hasAdapter(): Boolean = adapter != null

    fun bonded(): List<BluetoothDevice> =
        try { adapter?.bondedDevices?.toList() ?: emptyList() } catch (e: SecurityException) { emptyList() }

    fun nameOf(d: BluetoothDevice): String = try { d.name ?: d.address } catch (e: SecurityException) { d.address }

    private fun notifyState() { main.post { listener?.onHidState() } }

    private fun fail(msg: String) {
        supported = false
        status = msg
        notifyState()
    }

    /** Liga o perfil HID Device e registra o teclado+mouse. Chamar depois das permissões de Bluetooth. */
    fun start() {
        val ctx = app ?: return
        if (started) return
        val ad = adapter
        if (ad == null) { fail("Esta TV não tem Bluetooth."); return }
        started = true
        try {
            val f = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(bondRx, f, Context.RECEIVER_EXPORTED)
            else ctx.registerReceiver(bondRx, f)
        } catch (_: Exception) {}
        val ok = try { ad.getProfileProxy(ctx, svc, BluetoothProfile.HID_DEVICE) } catch (e: Exception) { false }
        if (!ok) { fail("Esta TV não deixa o app usar o Bluetooth como teclado/mouse (perfil HID indisponível)."); return }
        status = "Preparando o Bluetooth da TV…"
        notifyState()
        main.postDelayed({
            if (hid == null && btEnabled() && supported) {
                fail("O Bluetooth desta TV não oferece o perfil de teclado/mouse (HID Device).")
            }
        }, 6000)
    }

    private val svc = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val h = proxy as BluetoothHidDevice
            hid = h
            registerApp(h)
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = null; registered = false; host = null
            status = "Bluetooth da TV desconectou o serviço de teclado/mouse."
            notifyState()
        }
    }

    private var usingQos = false
    private var qosRetried = false

    private fun registerApp(h: BluetoothHidDevice, withQos: Boolean = true) {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "PonteTV", "Teclado e mouse da TV", "PonteTV",
            BluetoothHidDevice.SUBCLASS1_COMBO, HidDescriptor.bytes
        )
        // QoS "melhor esforço" com latência alvo de 11,25 ms (a mesma que mouses Bluetooth comuns pedem).
        val qos: BluetoothHidDeviceAppQosSettings? = if (withQos) {
            try {
                BluetoothHidDeviceAppQosSettings(
                    BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11250,
                    BluetoothHidDeviceAppQosSettings.MAX
                )
            } catch (e: Exception) { null }
        } else null
        usingQos = qos != null
        val ok = try { h.registerApp(sdp, qos, qos, cbExec, callback) } catch (e: Exception) { false }
        if (!ok) {
            if (qos != null && !qosRetried) { qosRetried = true; registerApp(h, false); return }
            fail("Não foi possível registrar o teclado/mouse Bluetooth (outro app já usa o perfil?).")
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (!registered && usingQos && !qosRetried) {
                // o Bluetooth desta TV não aceitou o QoS: registra de novo sem ele
                qosRetried = true
                hid?.let { registerApp(it, false); return }
            }
            HidLink.registered = registered
            status = if (registered) "Pronto: aguardando o celular." else "Teclado/mouse Bluetooth não registrado."
            notifyState()
            if (registered) main.postDelayed({ autoConnect() }, 600)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    device?.let { prefs?.lastHost = it.address }
                    status = "Conectado."
                }
                BluetoothProfile.STATE_CONNECTING -> status = "Conectando…"
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host?.address == device?.address) { host = null; clearQueue() }
                    status = "Desconectado. Aguardando o celular."
                }
            }
            notifyState()
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            val h = hid ?: return
            val d = device ?: return
            when (id.toInt()) {
                HidDescriptor.ID_KEYBOARD -> h.replyReport(d, type, id, ByteArray(HidDescriptor.KEYBOARD_SIZE))
                HidDescriptor.ID_MOUSE -> h.replyReport(d, type, id, ByteArray(HidDescriptor.MOUSE_SIZE))
                else -> h.reportError(d, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
            }
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            val d = device ?: return
            hid?.reportError(d, BluetoothHidDevice.ERROR_RSP_SUCCESS) // LEDs do teclado: aceita e ignora
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice?) {
            if (host?.address == device?.address) { host = null; clearQueue() }
            notifyState()
        }
    }

    private val bondRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            @Suppress("DEPRECATION")
            val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            // celular acabou de parear com a TV: a TV conecta nele
            if (st == BluetoothDevice.BOND_BONDED && d != null && host == null) {
                main.postDelayed({ if (host == null) connect(d) }, 1500)
            }
            notifyState()
        }
    }

    private fun autoConnect() {
        val h = hid ?: return
        if (host != null) return
        try {
            val cur = h.connectedDevices.firstOrNull()
            if (cur != null) { host = cur; notifyState(); return }
        } catch (_: Exception) {}
        val last = prefs?.lastHost ?: return
        bonded().firstOrNull { it.address == last }?.let { connect(it) }
    }

    fun connect(d: BluetoothDevice) {
        val h = hid
        if (h == null || !registered) { status = "Bluetooth ainda não está pronto."; notifyState(); return }
        status = "Conectando a ${nameOf(d)}…"
        notifyState()
        try { h.connect(d) } catch (e: Exception) { status = "Falha ao conectar: ${e.message}"; notifyState() }
    }

    fun disconnect() {
        val d = host ?: return
        try { hid?.disconnect(d) } catch (_: Exception) {}
    }

    /**
     * Envia um relatório HID (id 1 = teclado, id 2 = mouse) ao celular conectado.
     * Não bloqueia: o relatório entra numa fila curta e uma thread dedicada (prioridade alta) faz o envio.
     * Relatórios de mouse seguidos com os mesmos botões são somados em um só enquanto esperam a vez.
     */
    fun send(id: Int, data: ByteArray) {
        if (id == HidDescriptor.ID_MOUSE && data.size == HidDescriptor.MOUSE_SIZE) lastButtons = data[0].toInt() and 0x1F
        if (host == null) return
        qLock.lock()
        try {
            val last = queue.peekLast()
            if (last != null && id == HidDescriptor.ID_MOUSE && last.id == id && merge(last.data, data)) {
                // somado ao relatório de mouse que ainda não saiu
            } else {
                if (queue.size >= MAX_QUEUE) queue.clear() // link morto: não deixa crescer sem limite
                queue.addLast(Report(id, data))
            }
            qCond.signal()
        } finally { qLock.unlock() }
    }

    private fun merge(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != HidDescriptor.MOUSE_SIZE || b.size != HidDescriptor.MOUSE_SIZE || a[0] != b[0]) return false
        val x = a[1] + b[1]; val y = a[2] + b[2]; val v = a[3] + b[3]; val h = a[4] + b[4]
        if (x !in -127..127 || y !in -127..127 || v !in -127..127 || h !in -127..127) return false
        a[1] = x.toByte(); a[2] = y.toByte(); a[3] = v.toByte(); a[4] = h.toByte()
        return true
    }

    private fun isMove(r: Report): Boolean {
        if (r.id != HidDescriptor.ID_MOUSE || r.data.size != HidDescriptor.MOUSE_SIZE) return false
        val d = r.data
        return d[1].toInt() != 0 || d[2].toInt() != 0 || d[3].toInt() != 0 || d[4].toInt() != 0
    }

    private fun clearQueue() {
        qLock.lock()
        try { queue.clear() } finally { qLock.unlock() }
    }

    private fun startWorker() {
        if (workerRun) return
        workerRun = true
        Thread({ pump() }, "PonteTV-HID").apply { isDaemon = true; start() }
    }

    /** Próximo relatório a enviar (espera a fila, respeita o ritmo do movimento e gera o keepalive). */
    private fun nextReport(): Report? {
        qLock.lock()
        try {
            while (workerRun) {
                val head = queue.peekFirst()
                if (head == null) {
                    if (InputBridge.capturing && host != null) {
                        if (qCond.awaitNanos(KEEPALIVE_NS) <= 0L && queue.isEmpty()) {
                            return Report(HidDescriptor.ID_MOUSE, byteArrayOf(lastButtons.toByte(), 0, 0, 0, 0))
                        }
                    } else qCond.await()
                    continue
                }
                if (isMove(head)) {
                    val gap = nextMoveAt - System.nanoTime()
                    // só espera se não há nada atrás (tecla/botão nunca esperam); enquanto espera, o movimento novo é somado
                    if (gap > 0 && queue.size == 1) { qCond.awaitNanos(gap); continue }
                }
                return queue.pollFirst()
            }
            return null
        } finally { qLock.unlock() }
    }

    private fun pump() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Exception) {}
        var winStart = System.nanoTime()
        var count = 0
        var worst = 0L
        while (workerRun) {
            val r = nextReport() ?: break
            val h = hid
            val d = host
            if (h == null || d == null) { clearQueue(); continue }
            val t0 = System.nanoTime()
            if (isMove(r)) nextMoveAt = t0 + MOVE_GAP_NS
            try { h.sendReport(d, r.id, r.data) } catch (_: Exception) {}
            val dt = System.nanoTime() - t0
            count++
            if (dt > worst) worst = dt
            if (t0 - winStart >= 1_000_000_000L) {
                statRate = count
                statWorstMs = (worst / 1_000_000L).toInt()
                count = 0; worst = 0L; winStart = t0
            }
        }
    }
}
