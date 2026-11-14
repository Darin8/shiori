package app.shiori.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.core.content.IntentCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var held: Image? = null
    private var callback: MediaProjection.Callback? = null
    private var width = 0
    private var height = 0
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var imageThread: HandlerThread? = null

    @Volatile private var state: CaptureState = CaptureState.NoConsent

    val hasProjection: Boolean get() = state !== CaptureState.NoConsent

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "stop requested")
            stopSelf()
            return START_NOT_STICKY
        }
        // startForeground must come before getMediaProjection.
        startForeground()
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        setUp(code, data)
        return START_NOT_STICKY
    }

    private fun startForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Session", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Shiori")
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null, "Stop",
                    PendingIntent.getService(
                        this, 0,
                        Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).build()
            )
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun setUp(code: Int, data: Intent) {
        release()
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data)
        if (mp == null) { Log.w(TAG, "no projection"); stopSelf(); return }
        val dm = resources.displayMetrics
        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        width = bounds.width()
        height = bounds.height()
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "projection stopped")
                main.post {
                    state = state.next(CaptureEvent.ProjectionStopped)
                    release()
                    stopSelf()
                }
            }
        }
        callback = cb
        mp.registerCallback(cb, main)
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val thread = HandlerThread("shiori-capture").also { it.start() }
        imageThread = thread
        r.setOnImageAvailableListener({ onImage(it) }, Handler(thread.looper))
        // No surface between presses: the display costs nothing until captureFrame attaches the reader.
        display = mp.createVirtualDisplay(
            "shiori", width, height, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, null
        )
        projection = mp
        reader = r
        instance = this
        state = state.next(CaptureEvent.ConsentGranted)
        Log.i(TAG, "capture ready ${width}x$height")
        listener?.onSessionStarted()
    }

    private fun release() {
        val wasRunning = projection != null
        display?.release(); display = null
        synchronized(lock) { held?.close(); held = null }
        reader?.close(); reader = null
        imageThread?.quitSafely(); imageThread = null
        callback?.let { projection?.unregisterCallback(it) }; callback = null
        projection?.stop(); projection = null
        state = CaptureState.NoConsent
        if (instance === this) instance = null
        if (wasRunning) listener?.onSessionStopped()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    // Capture thread: keeps only the newest Image open, without copying pixels.
    private fun onImage(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        synchronized(lock) {
            held?.close()
            held = img
        }
    }

    private fun hasHeld(): Boolean = synchronized(lock) { held != null }

    /** Closes any queued images so the next one is fresh. */
    private fun drain() {
        synchronized(lock) { held?.close(); held = null }
        val r = reader ?: return
        try {
            while (true) { r.acquireLatestImage()?.close() ?: break }
        } catch (e: Exception) { /* nothing queued */ }
    }

    private fun copyHeld(): Bitmap? = synchronized(lock) {
        val img = held ?: return null
        val plane = img.planes[0]
        val padded = plane.rowStride / plane.pixelStride
        val full = Bitmap.createBitmap(padded, height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(plane.buffer.duplicate().apply { rewind() })
        if (padded == width) full
        else Bitmap.createBitmap(full, 0, 0, width, height).also { full.recycle() }
    }

    /** A fresh frame at full display resolution, or null on no consent, a capture already running, or timeout. */
    suspend fun captureFrame(): Bitmap? {
        synchronized(lock) {
            if (state !== CaptureState.Ready) return null
            state = state.next(CaptureEvent.CaptureRequested)
        }
        val t0 = System.nanoTime()
        try {
            val d = display
            val r = reader
            if (d == null || r == null) return null
            drain()
            d.surface = r.surface
            val got = try {
                withTimeoutOrNull(FRAME_TIMEOUT_MS) {
                    while (!hasHeld()) delay(10)
                    true
                } ?: false
            } finally {
                try { d.surface = null } catch (e: Exception) { Log.w(TAG, "detach failed", e) }
            }
            val bmp = if (got) copyHeld() else null
            synchronized(lock) { held?.close(); held = null }
            val ms = (System.nanoTime() - t0) / 1_000_000
            Log.i(TAG, "frame ${bmp?.let { "${it.width}x${it.height}" } ?: "timeout"} in $ms ms")
            return bmp
        } finally {
            synchronized(lock) {
                if (state === CaptureState.Capturing) state = state.next(CaptureEvent.FrameDelivered)
            }
        }
    }

    companion object {
        const val TAG = "Shiori"
        private const val CHANNEL = "session"
        private const val FRAME_TIMEOUT_MS = 500L
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        const val ACTION_STOP = "app.shiori.STOP"

        @Volatile var instance: CaptureService? = null

        /** Told when the capture session becomes ready and when it ends (set by the accessibility service). */
        @Volatile var listener: Listener? = null

        interface Listener {
            fun onSessionStarted()
            fun onSessionStopped()
        }

        /** Ends the session: projection, foreground service and notification. */
        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureService::class.java))
        }

        fun start(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, CaptureService::class.java)
                    .putExtra(EXTRA_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data)
            )
        }
    }
}
