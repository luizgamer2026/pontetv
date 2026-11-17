package com.pontetv.tv

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Transporte alternativo: a TV como teclado + mouse Bluetooth Low Energy (HID over GATT, "HOGP").
 * O celular é o central: ele faz o pareamento, descobre o HID e liga as notificações; a TV só anuncia e notifica.
 * Usa o mesmo descritor e os mesmos relatórios do modo clássico (ver HidDescriptor).
 */
@SuppressLint("MissingPermission")
object BleHid {
    private fun u(v: Int): UUID = UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", v))

    private val SVC_HID = u(0x1812)
    private val SVC_DIS = u(0x180A)
    private val SVC_BAS = u(0x180F)
    private val CH_HID_INFO = u(0x2A4A)
    private val CH_REPORT_MAP = u(0x2A4B)
    private val CH_CTRL = u(0x2A4C)
    private val CH_REPORT = u(0x2A4D)
    private val CH_PROTO = u(0x2A4E)
    private val CH_BATT = u(0x2A19)
    private val CH_MANUF = u(0x2A29)
    private val CH_PNP = u(0x2A50)
    private val DESC_CCC = u(0x2902)
    private val DESC_REF = u(0x2908)

    @Volatile var active = false
        private set
    /** Resultado do pedido de intervalo de conexão curto (mostrado no HUD). */
    @Volatile var boostInfo = "—"
        private set

    private val main = Handler(Looper.getMainLooper())
    private var ctx: Context? = null
    private var server: BluetoothGattServer? = null
    private var kbIn: BluetoothGattCharacteristic? = null
    private var mouseIn: BluetoothGattCharacteristic? = null
    private val pending = java.util.ArrayDeque<BluetoothGattService>()
    private val sent = Semaphore(0)
    @Volatile private var dev: BluetoothDevice? = null
    @Volatile private var ready = false
    private var advertising = false
    private var boostGatt: BluetoothGatt? = null

    private fun adapterOf(c: Context) =
        (c.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** A TV precisa poder anunciar BLE (modo periférico); muitas TVs não podem. */
    fun supported(c: Context): Boolean = try {
        val ad = adapterOf(c)
        ad != null && ad.isEnabled && ad.bluetoothLeAdvertiser != null
    } catch (e: Exception) { false }

    // ---------------- ciclo de vida ----------------
    fun start(c: Context): Boolean {
        if (active) return true
        val app = c.applicationContext
        val mgr = app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (mgr == null || mgr.adapter?.bluetoothLeAdvertiser == null) {
            HidLink.bleStatus("Esta TV não consegue anunciar BLE (sem modo periférico).")
            return false
        }
        val s = try { mgr.openGattServer(app, serverCb) } catch (e: Exception) { null }
        if (s == null) { HidLink.bleStatus("Não foi possível abrir o servidor BLE."); return false }
        ctx = app
        server = s
        active = true
        ready = false
        dev = null
        boostInfo = "—"
        buildServices()
        HidLink.bleStatus("BLE: registrando serviços…")
        addNext()
        return true
    }

    fun stop() {
        active = false
        ready = false
        try { adv()?.stopAdvertising(advCb) } catch (_: Exception) {}
        advertising = false
        closeBoost()
        try { server?.close() } catch (_: Exception) {}
        server = null
        dev = null
        kbIn = null
        mouseIn = null
        pending.clear()
        HidLink.bleConnection(null, false)
    }

    fun connect(d: BluetoothDevice) { try { server?.connect(d, true) } catch (_: Exception) {} }

    fun disconnect() { val d = dev ?: return; try { server?.cancelConnection(d) } catch (_: Exception) {} }

    private fun adv() = ctx?.let { adapterOf(it)?.bluetoothLeAdvertiser }

    // ---------------- serviços GATT ----------------
    private fun ch(uuid: UUID, props: Int, perms: Int, v: ByteArray? = null): BluetoothGattCharacteristic {
        val c = BluetoothGattCharacteristic(uuid, props, perms)
        if (v != null) c.setValue(v)
        return c
    }

    /** Característica "Report" (0x2A4D) com Report Reference (id, tipo) e, se notifica, o CCCD. */
    private fun report(id: Int, type: Int, props: Int, perms: Int, size: Int): BluetoothGattCharacteristic {
        val c = ch(CH_REPORT, props, perms, ByteArray(size))
        val ref = BluetoothGattDescriptor(DESC_REF, BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED)
        ref.setValue(byteArrayOf(id.toByte(), type.toByte()))
        c.addDescriptor(ref)
        if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            val ccc = BluetoothGattDescriptor(
                DESC_CCC,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
            )
            ccc.setValue(byteArrayOf(0, 0))
            c.addDescriptor(ccc)
        }
        return c
    }

    private fun buildServices() {
        val rd = BluetoothGattCharacteristic.PROPERTY_READ
        val wr = BluetoothGattCharacteristic.PROPERTY_WRITE
        val wnr = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        val nt = BluetoothGattCharacteristic.PROPERTY_NOTIFY
        val pRead = BluetoothGattCharacteristic.PERMISSION_READ
        val pReadEnc = BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        val pWriteEnc = BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
        pending.clear()

        val dis = BluetoothGattService(SVC_DIS, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        dis.addCharacteristic(ch(CH_MANUF, rd, pRead, "PonteTV".toByteArray()))
        dis.addCharacteristic(ch(CH_PNP, rd, pRead, byteArrayOf(0x02, 0x09, 0x12, 0x01, 0x00, 0x00, 0x01)))
        pending.add(dis)

        val bas = BluetoothGattService(SVC_BAS, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        bas.addCharacteristic(ch(CH_BATT, rd, pRead, byteArrayOf(100)))
        pending.add(bas)

        val hid = BluetoothGattService(SVC_HID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        hid.addCharacteristic(ch(CH_HID_INFO, rd, pReadEnc, byteArrayOf(0x11, 0x01, 0x00, 0x02)))
        hid.addCharacteristic(ch(CH_REPORT_MAP, rd, pReadEnc, HidDescriptor.bytes))
        hid.addCharacteristic(ch(CH_CTRL, wnr, pWriteEnc))
        hid.addCharacteristic(ch(CH_PROTO, rd or wnr, pReadEnc or pWriteEnc, byteArrayOf(1)))
        val k = report(HidDescriptor.ID_KEYBOARD, 1, rd or nt, pReadEnc, HidDescriptor.KEYBOARD_SIZE)
        val m = report(HidDescriptor.ID_MOUSE, 1, rd or nt, pReadEnc, HidDescriptor.MOUSE_SIZE)
        val led = report(HidDescriptor.ID_KEYBOARD, 2, rd or wr or wnr, pReadEnc or pWriteEnc, 1)
        kbIn = k
        mouseIn = m
        hid.addCharacteristic(k)
        hid.addCharacteristic(m)
        hid.addCharacteristic(led)
        pending.add(hid)
    }

    /** O Android só aceita o próximo serviço depois de onServiceAdded do anterior. */
    private fun addNext() {
        val s = server ?: return
        val svc = pending.pollFirst()
        if (svc == null) { startAdvertising(true); return }
        if (!s.addService(svc)) HidLink.bleStatus("Falha ao registrar um serviço BLE.")
    }

    // ---------------- anúncio ----------------
    private val advCb = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
            if (dev == null) HidLink.bleStatus("BLE: visível. Pareie a TV pelo celular (PonteTV → Procurar TV).")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE) { startAdvertising(false); return }
            if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) { advertising = true; return }
            HidLink.bleStatus("Falha ao anunciar BLE (código $errorCode).")
        }
    }

    private fun startAdvertising(withName: Boolean) {
        val a = adv() ?: return
        if (!active || dev != null) return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SVC_HID))
            .build()
        val scan = AdvertiseData.Builder().setIncludeDeviceName(withName).build()
        try { a.startAdvertising(settings, data, scan, advCb) }
        catch (e: Exception) { HidLink.bleStatus("Falha ao anunciar BLE: ${e.message}") }
    }

    // ---------------- servidor GATT ----------------
    private fun respond(d: BluetoothDevice, id: Int, offset: Int, v: ByteArray?) {
        val s = server ?: return
        val data = v ?: ByteArray(0)
        if (offset > data.size) { s.sendResponse(d, id, BluetoothGatt.GATT_INVALID_OFFSET, offset, null); return }
        s.sendResponse(d, id, BluetoothGatt.GATT_SUCCESS, offset, data.copyOfRange(offset, data.size))
    }

    private val serverCb = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status != BluetoothGatt.GATT_SUCCESS) { HidLink.bleStatus("Falha ao registrar serviço BLE (código $status)."); return }
            addNext()
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val d = device ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                dev = d
                ready = false
                HidLink.bleConnection(d, false)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED && dev?.address == d.address) {
                dev = null
                ready = false
                closeBoost()
                HidLink.bleConnection(null, false)
                // anúncio conectável para ao conectar: reinicia para o celular poder reconectar
                try { adv()?.stopAdvertising(advCb) } catch (_: Exception) {}
                advertising = false
                main.postDelayed({ if (active && dev == null) startAdvertising(true) }, 400)
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic?) {
            val d = device ?: return
            respond(d, requestId, offset, characteristic?.value)
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor?) {
            val d = device ?: return
            respond(d, requestId, offset, descriptor?.value)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            val d = device ?: return
            // modo de protocolo, ponto de controle e LEDs do teclado: aceita e ignora
            if (characteristic != null && characteristic.uuid == CH_PROTO && value != null && value.isNotEmpty()) characteristic.setValue(value)
            if (responseNeeded) server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?, requestId: Int, descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            val d = device ?: return
            if (descriptor != null && descriptor.uuid == DESC_CCC && value != null) {
                descriptor.setValue(value)
                val on = value.isNotEmpty() && (value[0].toInt() and 0x01) != 0
                if (on && descriptor.characteristic?.uuid == CH_REPORT) markReady(d)
            }
            if (responseNeeded) server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) { sent.release() }
    }

    /** O celular ligou as notificações do HID: já dá para mandar teclas e mouse. */
    private fun markReady(d: BluetoothDevice) {
        if (ready) return
        ready = true
        HidLink.bleConnection(d, true)
        boost(d)
    }

    // ---------------- envio ----------------
    /** Envia um relatório como notificação e espera ela sair (no máximo 25 ms). Chamado só pela thread de envio. */
    @Suppress("DEPRECATION")
    fun send(id: Int, data: ByteArray): Boolean {
        val s = server ?: return false
        val d = dev ?: return false
        if (!ready) return false
        val c = (if (id == HidDescriptor.ID_KEYBOARD) kbIn else mouseIn) ?: return false
        for (attempt in 0 until 6) {
            sent.drainPermits()
            val ok = try {
                if (Build.VERSION.SDK_INT >= 33) {
                    s.notifyCharacteristicChanged(d, c, false, data) == BluetoothStatusCodes.SUCCESS
                } else {
                    c.setValue(data)
                    s.notifyCharacteristicChanged(d, c, false)
                }
            } catch (e: Exception) { false }
            if (ok) {
                sent.tryAcquire(25, TimeUnit.MILLISECONDS)
                return true
            }
            try { Thread.sleep(2) } catch (_: InterruptedException) {}
        }
        return false
    }

    // ---------------- intervalo de conexão curto (melhor esforço) ----------------
    // Quem manda no intervalo é o celular (central). Como periférico, a TV só consegue pedir: abre uma conexão cliente
    // para o mesmo celular e pede prioridade alta (intervalo ~11-15 ms). Se o Android recusar, segue no padrão.
    private fun boost(d: BluetoothDevice) {
        val c = ctx ?: return
        closeBoost()
        boostInfo = "pedindo intervalo curto…"
        try { boostGatt = d.connectGatt(c, false, clientCb, BluetoothDevice.TRANSPORT_LE) }
        catch (e: Exception) { boostInfo = "indisponível" }
    }

    private fun closeBoost() {
        try { boostGatt?.close() } catch (_: Exception) {}
        boostGatt = null
    }

    private val clientCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && gatt != null) {
                val ok = try { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (e: Exception) { false }
                boostInfo = if (ok) "intervalo curto pedido" else "pedido recusado"
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                boostInfo = "sem conexão de cliente"
            }
        }
    }
}
