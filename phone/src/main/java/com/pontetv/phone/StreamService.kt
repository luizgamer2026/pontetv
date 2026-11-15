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

class StreamService : Service(), Link.Callback, ScreenCapture.Sink {
    companion object {
        const val ACT_STOP = "com.pontetv.phone.STOP"
        const val X_ID = "id"
        const val X_CODE = "code"
        const val X_DATA = "data"
        private const val MIN_KBPS = 1500
        @Volatile var running = false

        fun stop(c: Context) {
            c.startService(Intent(c, StreamService::class.java).setAction(ACT_STOP))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var capture: ScreenCapture? = null
    private var audio: AudioCapture? = null
    private var link: Link? = null
    private var done = false
    private var userMaxLong = 1280

    private val rcThread = HandlerThread("rate").also { it.start() }
    private val rc = Handler(rcThread.looper)
    private var bitrate = 0
    private var target = 0
    private var stable = 0
    private var lowCount = 0
    private var level = 0
    private val scales = floatArrayOf(1f, 0.75f, 0.5f)

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == ACT_STOP) { shutdown(true); return START_NOT_STICKY }
        if (running || i == null) return START_NOT_STICKY
        val prefs = Prefs(this)
        val tv = prefs.findPaired(i.getStringExtra(X_ID) ?: "")
        @Suppress("DEPRECATION") val data = i.getParcelableExtra<Intent>(X_DATA)
        if (tv == null || data == null) { stopSelf(); return START_NOT_STICKY }

        startFg(tv.name)
        running = true; done = false
        Session.reset()
        Session.state = Session.State.CONNECTING
        Session.tvName = tv.name
        Session.changed()
        target = prefs.bitrate; bitrate = target; level = 0
        userMaxLong = prefs.maxLong

        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(i.getIntExtra(X_CODE, 0), data)
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { main.post { shutdown(true) } }
            }, main)

            val cap = ScreenCapture(applicationContext, proj, this, prefs.maxLong, prefs.fps, bitrate)
            capture = cap

            val l = Link(tv.host, tv.port, prefs.deviceId, prefs.deviceName, tv.key, this)
            link = l
            cap.start()

            if (prefs.audio && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                val a = AudioCapture(proj) { pts, d -> link?.offerAudio(pts, d) }
                if (a.start()) { audio = a; l.setAudioConfig(AudioCapture.RATE, AudioCapture.CH) }
            }
            l.start()
            rc.postDelayed(tick, 1000)
        } catch (e: Exception) {
            fail("Falha ao iniciar a captura: ${e.message}")
        }
        return START_NOT_STICKY
    }

    // ---- Link.Callback ----
    override fun onConnected(tvName: String, tvMaxLong: Int) {
        // a TV informa a resolução do painel: nunca transmitir acima dela (poupa decoder e RAM da TV)
        if (tvMaxLong > 0) capture?.setMaxLong(min(userMaxLong, tvMaxLong))
        Session.state = Session.State.STREAMING
        Session.tvName = tvName
        capture?.requestKeyFrame()
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
        Session.e2eMs = latMs; Session.audMs = audMs; Session.tvFps = fps
    }

    override fun onTvDevices(kb: String?, mouse: String?) {
        Session.tvKb = kb; Session.tvMouse = mouse; Session.changed()
    }

    override fun onKeyframeRequest() { capture?.requestKeyFrame() }

    override fun onRemoteStop() { main.post { shutdown(false) } }

    override fun onGiveUp(reason: String) { main.post { fail(reason) } }

    // ---- ScreenCapture.Sink ----
    override fun onConfig(w: Int, h: Int, csd: ByteArray) {
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
            capture?.let { Session.width = it.outW; Session.height = it.outH }
            if (Session.state == Session.State.STREAMING) adapt(st)
            Session.targetKbps = bitrate
            Session.changed()
            rc.postDelayed(this, 1000)
        }
    }

    private fun adapt(st: LinkStats) {
        val cap = capture ?: return
        val congested = st.drops > 0 || st.maxQ >= 4 || st.rtt > 120 || Session.e2eMs > 300
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

    private fun startFg(name: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("stream", "Transmissão", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, StreamService::class.java).setAction(ACT_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val calPi = PendingIntent.getActivity(
            this, 2, Intent(this, CalibrateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, "stream")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("PonteTV")
            .setContentText("Transmitindo para $name")
            .setOngoing(true)
            .addAction(0, "Calibrar controles", calPi)
            .addAction(0, "Encerrar", stopPi)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }
}
