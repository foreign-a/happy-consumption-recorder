package com.happy.consumption.capture

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.happy.consumption.R
import com.happy.consumption.HappyApplication
import com.happy.consumption.data.UserManager
import com.happy.consumption.data.UserSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

internal const val CAPTURE_REQUEST_ID = "capture_request_id"
internal const val CAPTURE_STARTED_AT = "capture_started_at"
internal val captureTriggers = LatestCaptureTrigger()
internal data class CaptureTiming(val id: String, val startedAt: Long) {
    fun mark(stage: String) {
        if (startedAt > 0) android.util.Log.i("ScreenshotTiming", "request=$id " + stage + "_ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt))
    }
}

/** Holds a single transient capture in process memory only, never on disk or in an Intent. */
internal object CaptureFrame {
    data class Frame(val bitmap: Bitmap, val session: UserSession, val worker: PreparedOcrWorker<Bitmap, List<NumberCandidate>>, val timing: CaptureTiming)
    private var frame: Frame? = null
    private var worker: PreparedOcrWorker<Bitmap, List<NumberCandidate>>? = null
    private val state = CaptureRequestState()
    private var timing: CaptureTiming? = null
    fun begin(context: Context, request: CaptureTiming) {
        clear()
        state.begin(request.id)
        timing = request
        worker = newOcrWorker(context.applicationContext, request)
    }
    fun isCurrent(id: String): Boolean = state.isCurrent(id)
    fun store(id: String, bitmap: Bitmap, session: UserSession): Boolean {
        if (!isCurrent(id)) return false
        frame?.bitmap?.recycle()
        val prepared = worker ?: return false
        frame = Frame(bitmap, session, prepared, checkNotNull(timing))
        return true
    }
    fun take(id: String?): Frame? {
        if (!state.isCurrent(id)) return null
        val result = frame ?: return null
        state.consume(id)
        frame = null; worker = null; timing = null
        return result
    }
    fun clear() { frame?.bitmap?.recycle(); frame = null; worker?.close(); worker = null; timing = null; state.clear() }
    fun clearIfCurrent(id: String): Boolean { if (!isCurrent(id)) return false; clear(); return true }
    fun clearIfPending(): Boolean { if (!state.hasCurrent()) return false; clear(); return true }
}

class CaptureAccessibilityService : AccessibilityService() {
    companion object {
        private val service = ServiceConnectionSlot<CaptureAccessibilityService>()
        fun current(): CaptureAccessibilityService? = service.current()
    }
    override fun onServiceConnected() { super.onServiceConnected(); service.connect(this) }
    private fun disconnect() {
        if (!service.disconnect(this)) return
        val hadPendingRequest = CaptureFrame.clearIfPending()
        CaptureRequestRunner.cancel()
        if (hadPendingRequest) toast(R.string.capture_service_unavailable)
    }
    override fun onUnbind(intent: Intent?): Boolean { disconnect(); return super.onUnbind(intent) }
    override fun onDestroy() { disconnect(); super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    internal fun capture(expected: UserSession, timing: CaptureTiming) {
        val requestId = timing.id
        if (Build.VERSION.SDK_INT < 30) { toast(R.string.capture_unsupported); return }
        val manager = (application as HappyApplication).userManager
        if (!CaptureFrame.isCurrent(requestId)) return
        if (current() !== this) { if (CaptureFrame.clearIfCurrent(requestId)) toast(R.string.capture_service_unavailable); return }
        if (manager.session.value != expected) { CaptureFrame.clear(); toast(R.string.capture_session_changed); return }
        timing.mark("screenshot_requested")
        try { takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                timing.mark("screenshot_received")
                val buffer = result.hardwareBuffer
                if (current() !== this@CaptureAccessibilityService) {
                    buffer.close()
                    if (CaptureFrame.clearIfCurrent(requestId)) toast(R.string.capture_service_unavailable)
                    return
                }
                if (!CaptureFrame.isCurrent(requestId) || manager.session.value != expected) {
                    buffer.close()
                    if (CaptureFrame.isCurrent(requestId)) { CaptureFrame.clear(); toast(R.string.capture_session_changed) }
                    return
                }
                val image = try {
                    runCatching { Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                } finally { buffer.close() }
                if (image == null) { if (CaptureFrame.clearIfCurrent(requestId)) toast(R.string.capture_retry); return }
                if (manager.session.value != expected) {
                    image.recycle(); CaptureFrame.clear(); toast(R.string.capture_session_changed); return
                }
                if (!CaptureFrame.store(requestId, image, expected)) { image.recycle(); return }
                try {
                    startActivity(previewIntent(this@CaptureAccessibilityService, requestId))
                    timing.mark("preview_requested")
                } catch (_: RuntimeException) { if (CaptureFrame.clearIfCurrent(requestId)) toast(R.string.capture_retry) }
            }
            override fun onFailure(errorCode: Int) {
                if (CaptureFrame.clearIfCurrent(requestId)) toast(
                    if (current() !== this@CaptureAccessibilityService || errorCode == ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS)
                        R.string.capture_service_unavailable else R.string.capture_retry)
            }
        }) } catch (_: RuntimeException) {
            if (CaptureFrame.clearIfCurrent(requestId)) toast(if (current() === this) R.string.capture_retry else R.string.capture_service_unavailable)
        }
    }
    private fun toast(id: Int) = Toast.makeText(this, id, Toast.LENGTH_LONG).show()
}

class CaptureTileService : TileService() {
    companion object { private val nextPendingIntent = AtomicInteger() }
    // PendingIntent overload exists only from API 34; older systems require the Intent overload.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        val requestId = UUID.randomUUID().toString()
        captureTriggers.register(requestId)
        CaptureRequestRunner.cancel()
        CaptureFrame.clear()
        val intent = gatewayIntent(this, requestId, android.os.SystemClock.elapsedRealtime())
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(this, nextPendingIntent.incrementAndGet(), intent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pending)
        } else startActivityAndCollapse(intent)
    }
}

/** Start both independent prerequisites immediately; capture requires both to finish. */
internal suspend fun <T> awaitCaptureReadiness(
    initialize: suspend () -> T,
    waitForDismissal: suspend () -> Unit
): T = coroutineScope {
    val initialization = async { initialize() }
    val dismissal = async { waitForDismissal() }
    val result = initialization.await()
    dismissal.await()
    result
}

/** Main-thread request lifetime is independent of the transient gateway Activity. */
private object CaptureRequestRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var requestId: String? = null

    fun cancel(id: String? = null) {
        if (id != null && requestId != id) return
        job?.cancel()
        job = null
        requestId = null
    }

    fun start(context: Context, manager: UserManager, timing: CaptureTiming) {
        cancel()
        val appContext = context.applicationContext
        val request = timing.id
        requestId = request
        Handler(Looper.getMainLooper()).postDelayed({
            if (CaptureFrame.clearIfCurrent(request)) {
                cancel(request)
                timing.mark("request_timeout")
                Toast.makeText(appContext, R.string.capture_retry, Toast.LENGTH_LONG).show()
            }
        }, 15000)
        job = scope.launch {
            try {
                val expected = awaitCaptureReadiness(
                    initialize = {
                        manager.initialize()
                        timing.mark("session_ready")
                        manager.session.value
                    },
                    // The gateway has been finished before start(); wait concurrently
                    // with initialization for it and the quick settings shade to leave.
                    waitForDismissal = { delay(500) }
                )
                if (!CaptureFrame.isCurrent(request)) return@launch
                if (expected == null) {
                    CaptureFrame.clearIfCurrent(request)
                    Toast.makeText(appContext, R.string.capture_login_required, Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (manager.session.value != expected) {
                    CaptureFrame.clearIfCurrent(request)
                    Toast.makeText(appContext, R.string.capture_session_changed, Toast.LENGTH_LONG).show()
                    return@launch
                }
                val service = CaptureAccessibilityService.current()
                if (service != null) service.capture(expected, timing)
                else if (CaptureFrame.clearIfCurrent(request)) {
                    Toast.makeText(appContext, R.string.capture_service_unavailable, Toast.LENGTH_LONG).show()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                CaptureFrame.clearIfCurrent(request)
                throw cancelled
            } catch (_: Exception) {
                if (CaptureFrame.clearIfCurrent(request)) {
                    Toast.makeText(appContext, R.string.capture_retry, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

class CaptureGatewayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!startCapture(intent)) finish()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (startCapture(intent)) setIntent(intent)
    }
    private fun startCapture(intent: Intent): Boolean {
        val requestId = intent.getStringExtra(CAPTURE_REQUEST_ID) ?: return false
        if (!captureTriggers.claim(requestId)) return false
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(this, R.string.capture_unsupported, Toast.LENGTH_LONG).show(); finish(); return true
        }
        if (CaptureAccessibilityService.current() == null) {
            Toast.makeText(this, R.string.capture_service_unavailable, Toast.LENGTH_LONG).show()
            runCatching { startActivity(accessibilitySettingsIntent()) }
            finish(); return true
        }
        val manager = (application as HappyApplication).userManager
        val timing = CaptureTiming(requestId,
            intent.getLongExtra(CAPTURE_STARTED_AT, android.os.SystemClock.elapsedRealtime()))
        CaptureFrame.begin(applicationContext, timing)
        val appContext = applicationContext
        finish()
        CaptureRequestRunner.start(appContext, manager, timing)
        return true
    }
}
