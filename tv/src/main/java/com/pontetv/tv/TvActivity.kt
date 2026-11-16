package com.pontetv.tv

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class TvActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var details: TextView
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var prevButtons = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
            setBackgroundColor(0xFF0A0F1F.toInt())
            isFocusableInTouchMode = true
        }
        val title = TextView(this).apply {
            text = "PonteTV · HID sem fio"
            textSize = 30f; setTextColor(0xFFEAF0FF.toInt())
        }
        status = TextView(this).apply {
            text = "Iniciando Bluetooth HID…"
            textSize = 19f; setTextColor(0xFF22D3A6.toInt())
        }
        details = TextView(this).apply {
            text = "Conecte o teclado e o mouse USB à TV.\nNo celular, abra Configurações > Bluetooth e procure por PonteTV HID."
            textSize = 16f; setTextColor(0xFFEAF0FF.toInt())
        }
        val help = TextView(this).apply {
            text = "Mantenha este aplicativo aberto enquanto usa os periféricos. Os comandos são enviados como relatórios Bluetooth HID nativos; o celular deve reconhecê-los como teclado e mouse, sem instalar um serviço de acessibilidade."
            textSize = 14f; setTextColor(0xFF8FA0C8.toInt())
        }
        root.addView(title)
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 22 })
        root.addView(details, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 18 })
        root.addView(help, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 18 })
        setContentView(root)
        root.requestFocus()
        requestBluetoothPermissions()
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            val needed = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isNotEmpty()) {
                requestPermissions(needed.toTypedArray(), 10)
                return
            }
        }
        startHid()
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(r, p, g)
        if (r == 10 && g.isNotEmpty() && g.all { it == PackageManager.PERMISSION_GRANTED }) startHid()
        else status.text = "Permissões Bluetooth não concedidas."
    }

    private fun startHid() {
        val manager = getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            status.text = "Ative o Bluetooth da TV para continuar."
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        if (Build.VERSION.SDK_INT >= 26) {
            val discoverable = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            startActivityForResult(discoverable, 11)
        } else startHidBridge()
    }

    @Deprecated("Deprecated by Android; retained for API 26+ discoverability result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 11) {
            if (resultCode > 0) startHidBridge()
            else status.text = "A TV precisa ficar detectável por Bluetooth para o celular encontrá-la."
        }
    }

    private fun startHidBridge() {
        HidBridge.start(this) { message, connected ->
            runOnUiThread {
                status.text = message
                status.setTextColor(if (connected) 0xFF22D3A6.toInt() else 0xFFFFB020.toInt())
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (HidBridge.sendKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_MOVE) {
                sendButtonChanges(event.buttonState)
                return true
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
            event.actionMasked == MotionEvent.ACTION_MOVE ||
            event.actionMasked == MotionEvent.ACTION_SCROLL ||
            event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
            event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) {
            handleMouse(event)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun handleMouse(e: MotionEvent) {
        if ((e.source and InputDevice.SOURCE_MOUSE) != InputDevice.SOURCE_MOUSE &&
            (e.source and InputDevice.SOURCE_MOUSE_RELATIVE) != InputDevice.SOURCE_MOUSE_RELATIVE) return

        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> {
                val x = e.getAxisValue(MotionEvent.AXIS_RELATIVE_X).let { if (it != 0f) it else if (lastX.isNaN()) 0f else e.x - lastX }
                val y = e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y).let { if (it != 0f) it else if (lastY.isNaN()) 0f else e.y - lastY }
                lastX = e.x; lastY = e.y
                HidBridge.sendMouseMove(x.toInt(), y.toInt(), 0)
                sendButtonChanges(e.buttonState)
            }
            MotionEvent.ACTION_SCROLL -> HidBridge.sendMouseMove(0, 0, (e.getAxisValue(MotionEvent.AXIS_VSCROLL)).toInt())
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> sendButtonChanges(e.buttonState)
        }
    }

    private fun sendButtonChanges(buttons: Int) {
        val changed = buttons xor prevButtons
        if (changed != 0) {
            val mapped = ((buttons and MotionEvent.BUTTON_PRIMARY) != 0).let { if (it) 1 else 0 } or
                (((buttons and MotionEvent.BUTTON_SECONDARY) != 0).let { if (it) 2 else 0 }) or
                (((buttons and MotionEvent.BUTTON_TERTIARY) != 0).let { if (it) 4 else 0 })
            HidBridge.sendMouseButtons(mapped)
        }
        prevButtons = buttons
    }

    override fun onDestroy() {
        HidBridge.stop()
        super.onDestroy()
    }
}
