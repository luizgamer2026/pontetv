package com.pontetv.tv

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
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
import java.util.concurrent.Executors

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
    private var hid: BluetoothHidDevice? = null
    private var started = false
    private val main = Handler(Looper.getMainLooper())
    private val cbExec = Executors.newSingleThreadExecutor()
    private val sendExec = Executors.newSingleThreadExecutor()

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        prefs = TvPrefs(ctx)
        adapter = (ctx.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
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

    private fun registerApp(h: BluetoothHidDevice) {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "PonteTV", "Teclado e mouse da TV", "PonteTV",
            BluetoothHidDevice.SUBCLASS1_COMBO, HidDescriptor.bytes
        )
        val ok = try { h.registerApp(sdp, null, null, cbExec, callback) } catch (e: Exception) { false }
        if (!ok) fail("Não foi possível registrar o teclado/mouse Bluetooth (outro app já usa o perfil?).")
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
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
                    if (host?.address == device?.address) host = null
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
            if (host?.address == device?.address) host = null
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

    /** Envia um relatório HID (id 1 = teclado, id 2 = mouse) ao celular conectado. */
    fun send(id: Int, data: ByteArray) {
        val h = hid ?: return
        val d = host ?: return
        sendExec.execute { try { h.sendReport(d, id, data) } catch (_: Exception) {} }
    }
}
