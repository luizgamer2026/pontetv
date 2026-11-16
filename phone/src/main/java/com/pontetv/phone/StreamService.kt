package com.pontetv.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import com.pontetv.common.Proto
import kotlin.math.max
import kotlin.math.min

/**
 * Serviço de transmissão. A permissão de captura de tela (aviso do Android) é pedida UMA vez:
 * quando a TV encerra ou a conexão cai, o serviço fica em ESPERA mantendo a MediaProjection;
 * reconectar não pede o aviso de novo. "Encerrar" (notificação/app) finaliza tudo.
 */
class StreamService : Service(), Link.Callback, ScreenCapture.Sink {
    companion object {
        const val ACT_STOP = "com.pontetv.phone.STOP"
        const val ACT_CONNECT = "com.pontetv.phone.CONNECT"
        const val X_ID = "id"
        const val X_CODE = "code"
        const val X_DATA = "data"
        private const val MIN_KBPS = 1500
        @Volatile var running = false
        @Volatile var current: StreamService? = null

        fun stop(c: Context) {
            c.startService(Intent(c, StreamService::class.java).setAction(ACT_STOP))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var capture: ScreenCapture? = null
    private var audio: AudioCapture? = null
    @Volatile private var link: Link? = null
    private var done = false
    private var userMaxLong = 1280
    @Volatile private var lastCfg: Triple<Int, Int, ByteArray>? = null

    private val rcThread = HandlerThread("rate").also { it.start() }
    private val rc = Handler(rcThread.looper)
    private var bitrate = 0
    private var target = 0
    private var stable = 0
    private var lowCount = 0
    private var level = 0
    private val scales = floatArrayOf(1f, 0.75f, 0.5f)

    override fun onBind(i: Intent?): IBinder? = null

    fun sendControl(type: Int, data: ByteArray) { link?.sendControl(type, data) }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            ACT_STOP -> { shutdown(true); return START_NOT_STICKY }
            ACT_CONNECT -> {
                if (running && !done) Prefs(this).findPaired(i.getStringExtra(X_ID) ?: "")?.let { connect(it) }
                return START_NOT_STICKY
            }
        }
        if (running || i == null) return START_NOT_STICKY
        val prefs = Prefs(this)
        val tv = prefs.findPaired(i.getStringExtra(X_ID) ?: "")
        @Suppress("DEPRECATION") val data = i.getParcelableExtra<Intent>(X_DATA)
        if (tv == null || data == null) { stopSelf(); return START_NOT_STICKY }

        startForeground(1, notification("Conectando a ${tv.name}"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        running = true; done = false; current = this
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(i.getIntExtra(X_CODE, 0), data)
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { main.post { shutdown(true) } }
            }, main)
            val cap = ScreenCapture(applicationContext, proj, this, prefs.maxLong, prefs.fps, prefs.bitrate)
            capture = cap
            cap.start()
            connect(tv)
        } catch (e: Exception) {
            fail("Falha ao iniciar a captura: ${e.message}")
        }
        return START_NOT_STICKY
    }

    private fun connect(tv: Paired) {
        val prefs = Prefs(this)
        rc.removeCallbacksAndMessages(null)
        link?.stop(false)
        audio?.stop(); audio = null
        Session.reset()
        Session.state = Session.State.CONNECTING
        Session.tvName = tv.name
        Session.changed()
        target = prefs.bitrate; bitrate = target; level = 0; stable = 0; lowCount = 0
        userMaxLong = prefs.maxLong
        postNotif("Conectando a ${tv.name}")
        val cap = capture ?: return
        val proj = projection ?: return
        cap.setBitrate(bitrate)
        if (cap.paused) cap.resume()
        cap.setScale(1f)

        val l = Link(tv.host, tv.port, prefs.deviceId, prefs.deviceName, tv.key, this)
        link = l
        lastCfg?.let { l.setVideoConfig(it.first, it.second, it.third) }
        if (prefs.audio && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            val a = AudioCapture(proj) { pts, d -> link?.offerAudio(pts, d) }
            if (a.start()) { audio = a; l.setAudioConfig(AudioCapture.RATE, AudioCapture.CH) }
        }
        l.start()
        rc.postDelayed(tick, 1000)
    }

    /** ESPERA: libera encoder/áudio/conexão, mantém a permissão de captura. */
    private fun standby(msg: String) {
        if (done) return
        rc.removeCallbacksAndMessages(null)
        link?.stop(false); link = null
        audio?.stop(); audio = null
        capture?.pause()
        lastCfg = null
        InputAccessibilityService.instance?.reset()
        Session.state = Session.State.STANDBY
        Session.message = msg
        Session.game = false; Session.rtt = -1; Session.kbps = 0; Session.fps = 0
        Session.e2eMs = -1; Session.audMs = -1; Session.tvKb = null; Session.tvMouse = null
        Session.changed()
        postNotif("Em espera — abra o PonteTV e escolha a TV (sem novo aviso de captura)")
    }

    // ---- Link.Callback ----
    override fun onConnected(tvName: String, tvMaxLong: Int) {
        // a TV informa a resolução do painel: nunca transmitir acima dela
        if (tvMaxLong > 0) capture?.setMaxLong(min(userMaxLong, tvMaxLong))
        Session.state = Session.State.STREAMING
        Session.tvName = tvName
        capture?.requestKeyFrame()
        InputAccessibilityService.instance?.resendGame()
        postNotif("Transmitindo para $tvName")
        Session.changed()
    }

    override fun onLost(reason: String) {
        Session.state = Session.State.RECONNECTING
        Session.message = reason
        Session.changed()
    }

    override fun onInput(data: ByteArray) {
        if (data.isNotEmpty() && data[0].toInt() == Proto.I_MODE && data.size > 1) {
            Session.game = data[1].toInt() == 1
            Session.changed()
        }
        InputAccessibilityService.instance?.post(data)
    }

    override fun onTvStats(latMs: Int, audMs: Int, fps: Int, drops: Int) {
        Session.e2eMs = latMs; Session.audMs = audMs; Session.tvFps = fps; Session.tvDrops = drops
    }

    override fun onTvDevices(kb: String?, mouse: String?) {
        Session.tvKb = kb; Session.tvMouse = mouse; Session.changed()
    }

    override fun onCommand(json: String) { InputAccessibilityService.instance?.postCommand(json) }

    override fun onAppsRequest() {
        Thread {
            try { link?.sendControl(Proto.T_APPS, AppsList.build(applicationContext).toByteArray()) } catch (_: Exception) {}
        }.start()
    }

    override fun onKeyframeRequest() { capture?.requestKeyFrame() }

    override fun onRemoteStop() { main.post { standby("A TV encerrou a transmissão") } }

    override fun onGiveUp(reason: String) { main.post { standby(reason) } }

    // ---- ScreenCapture.Sink ----
    override fun onConfig(w: Int, h: Int, csd: ByteArray) {
        lastCfg = Triple(w, h, csd)
        Session.width = w; Session.height = h
        link?.setVideoConfig(w, h, csd)
    }

    override fun onFrame(pts: Long, key: Boolean, data: ByteArray) { link?.offerVideo(pts, key, data) }

    // ---- Controle adaptativo de bitrate / resolução ----
    private val tick = object : Runnable {
        override fun run() {
            if (done) return
            val l = link ?: return
            val st = l.takeStats(1f)
            Session.kbps = st.kbps; Session.fps = st.fps; Session.rtt = st.rtt; Session.drops = st.drops
            Session.transport = l.transport
            capture?.let { Session.width = it.outW; Session.height = it.outH }
            if (Session.state == Session.State.STREAMING) adapt(st)
            Session.targetKbps = bitrate
            Session.changed()
            rc.postDelayed(this, 1000)
        }
    }

    private fun adapt(st: LinkStats) {
        val cap = capture ?: return
        val congested = st.drops > 0 || st.maxQ >= 4 || st.rtt > 120 || Session.e2eMs > 300 || Session.tvDrops >= 2
        if (congested) {
            stable = 0
            if (bitrate > MIN_KBPS) {
                bitrate = max(MIN_KBPS, (bitrate * 0.7f).toInt()); cap.setBitrate(bitrate); lowCount = 0
            } else if (++lowCount >= 3 && level < scales.size - 1) {
                level++; cap.setScale(scales[level]); lowCount = 0
            }
        } else {
            lowCount = 0; stable++
            if (stable >= 4 && bitrate < target) {
                bitrate = min(target, (bitrate * 1.15f).toInt() + 1); cap.setBitrate(bitrate); stable = 2
            } else if (stable >= 20 && bitrate >= target && level > 0) {
                level--; cap.setScale(scales[level]); stable = 0
            }
        }
    }

    private fun fail(msg: String) {
        Session.message = msg
        Session.state = Session.State.ERROR
        shutdown(false)
    }

    private fun shutdown(sendStop: Boolean) {
        if (done) return
        done = true
        current = null
        rc.removeCallbacksAndMessages(null)
        rcThread.quitSafely()
        link?.stop(sendStop)
        audio?.stop()
        capture?.stop()
        try { projection?.stop() } catch (_: Exception) {}
        InputAccessibilityService.instance?.reset()
        DirectWifi.release(this)
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
        nm.createNotificationChannel(NotificationChannel("stream", "Transmissão", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, StreamService::class.java).setAction(ACT_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val calPi = PendingIntent.getActivity(
            this, 2, Intent(this, CalibrateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        return Notification.Builder(this, "stream")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("PonteTV")
            .setContentText(text)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(0, "Calibrar controles", calPi)
            .addAction(0, "Encerrar", stopPi)
            .build()
    }

    private fun postNotif(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, notification(text))
    }
}
