package com.pontetv.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Modo Controle: sem transmissão de tela e sem aviso de captura. Mantém a conexão com a TV
 * (que lê o teclado e o mouse USB) e entrega as entradas ao celular via acessibilidade/teclado PonteTV.
 */
class ControlService : Service(), Link.Callback {
    companion object {
        const val ACT_STOP = "com.pontetv.phone.CTL_STOP"
        const val X_ID = "id"
        @Volatile var running = false

        fun stop(c: Context) {
            c.startService(Intent(c, ControlService::class.java).setAction(ACT_STOP))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var link: Link? = null
    private var done = false

    private val tick = object : Runnable {
        override fun run() {
            if (done) return
            Session.rtt = link?.rttMs ?: -1
            Session.changed()
            main.postDelayed(this, 1000)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == ACT_STOP) { shutdown(true); return START_NOT_STICKY }
        if (running || i == null) return START_NOT_STICKY
        val prefs = Prefs(this)
        val tv = prefs.findPaired(i.getStringExtra(X_ID) ?: "")
        if (tv == null) { stopSelf(); return START_NOT_STICKY }
        startForeground(1, notification("Conectando a ${tv.name}"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        running = true; done = false
        Session.reset()
        Session.state = Session.State.CONNECTING
        Session.tvName = tv.name
        Session.changed()
        val l = Link(tv.host, tv.port, prefs.deviceId, prefs.deviceName, tv.key, this, "control")
        link = l
        l.start()
        main.postDelayed(tick, 1000)
        return START_NOT_STICKY
    }

    // ---- Link.Callback ----
    override fun onConnected(tvName: String, tvMaxLong: Int) {
        Session.state = Session.State.CONTROL
        Session.tvName = tvName
        InputAccessibilityService.instance?.controlOn(true)
        getSystemService(NotificationManager::class.java).notify(1, notification("Controle ativo · $tvName"))
        Session.changed()
    }

    override fun onLost(reason: String) {
        Session.state = Session.State.RECONNECTING
        Session.message = reason
        InputAccessibilityService.instance?.reset()
        Session.changed()
    }

    override fun onInput(data: ByteArray) { InputAccessibilityService.instance?.post(data) }
    override fun onCommand(json: String) { InputAccessibilityService.instance?.postCommand(json) }
    override fun onTvDevices(kb: String?, mouse: String?) { Session.tvKb = kb; Session.tvMouse = mouse; Session.changed() }
    override fun onTvStats(latMs: Int, audMs: Int, fps: Int, drops: Int) {}
    override fun onKeyframeRequest() {}
    override fun onAppsRequest() {}
    override fun onRemoteStop() { main.post { shutdown(false) } }
    override fun onGiveUp(reason: String) {
        main.post {
            Session.message = reason
            Session.state = Session.State.ERROR
            shutdown(false)
        }
    }

    private fun shutdown(sendStop: Boolean) {
        if (done) return
        done = true
        main.removeCallbacksAndMessages(null)
        link?.stop(sendStop)
        InputAccessibilityService.instance?.reset()
        if (Session.state != Session.State.ERROR) Session.state = Session.State.IDLE
        Session.game = false
        Session.changed()
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!done) shutdown(false)
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("stream", "Conexão com a TV", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 11, Intent(this, ControlService::class.java).setAction(ACT_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val calPi = PendingIntent.getActivity(
            this, 12, Intent(this, CalibrateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 13, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        return Notification.Builder(this, "stream")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("PonteTV")
            .setContentText(text)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(0, "Calibrar controles", calPi)
            .addAction(0, "Desconectar", stopPi)
            .build()
    }
}
