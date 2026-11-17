package com.pontetv.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/** Procura a TV por Bluetooth e faz o pareamento. Depois de pareada, é a TV que conecta no celular. */
@SuppressLint("MissingPermission")
class BtFinder(private val ctx: Context, private val onChange: () -> Unit) {
    class Entry(val device: BluetoothDevice, val name: String, val bonded: Boolean, val likelyTv: Boolean)

    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val found = LinkedHashMap<String, BluetoothDevice>()
    private var registered = false
    @Volatile var scanning = false
        private set

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    @Suppress("DEPRECATION")
                    val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (d != null) found[d.address] = d
                    onChange()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> { scanning = false; onChange() }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                BluetoothDevice.ACTION_NAME_CHANGED -> onChange()
            }
        }
    }

    fun supported(): Boolean = adapter != null
    fun enabled(): Boolean = try { adapter?.isEnabled == true } catch (e: SecurityException) { false }

    fun register() {
        if (registered) return
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(rx, f, Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(rx, f)
        registered = true
    }

    fun unregister() {
        stop()
        if (registered) { try { ctx.unregisterReceiver(rx) } catch (_: Exception) {}; registered = false }
    }

    fun start() {
        val ad = adapter ?: return
        try {
            if (ad.isDiscovering) ad.cancelDiscovery()
            found.clear()
            scanning = ad.startDiscovery()
        } catch (e: SecurityException) { scanning = false }
        onChange()
    }

    fun stop() {
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
        scanning = false
    }

    fun bond(d: BluetoothDevice): Boolean = try { d.createBond() } catch (e: Exception) { false }

    private fun entry(d: BluetoothDevice, bonded: Boolean): Entry? {
        val n: String? = try { d.name } catch (e: SecurityException) { null }
        if (n == null && !bonded) return null // aparelhos sem nome só atrapalham a lista
        val name = n ?: d.address
        val major: Int? = try { d.bluetoothClass?.majorDeviceClass } catch (e: SecurityException) { null }
        val tv = name.contains("tv", ignoreCase = true) ||
            major == BluetoothClass.Device.Major.AUDIO_VIDEO || major == BluetoothClass.Device.Major.PERIPHERAL
        return Entry(d, name, bonded, tv)
    }

    /** Pareados + encontrados; prováveis TVs primeiro. */
    fun list(): List<Entry> {
        val ad = adapter ?: return emptyList()
        val out = LinkedHashMap<String, Entry>()
        try { for (d in ad.bondedDevices) entry(d, true)?.let { out[d.address] = it } } catch (_: SecurityException) {}
        for ((a, d) in found) if (a !in out) entry(d, d.bondState == BluetoothDevice.BOND_BONDED)?.let { out[a] = it }
        return out.values.sortedWith(
            compareByDescending<Entry> { it.likelyTv }.thenByDescending { it.bonded }.thenBy { it.name.lowercase() }
        )
    }
}
