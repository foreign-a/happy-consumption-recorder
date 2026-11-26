package com.happy.consumption.capture

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.googlecode.tesseract.android.TessBaseAPI
import com.happy.consumption.HappyApplication
import com.happy.consumption.R
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.min

data class NumberCandidate(val text: String, val bounds: Rect)

class CapturePreviewActivity : Activity() {
    private var worker: PreparedOcrWorker<Bitmap, List<NumberCandidate>>? = null
    private var view: CandidateView? = null
    private lateinit var message: TextView
    private var image: Bitmap? = null
    private val frameGate = PreviewFrameGate()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (!showCurrentFrame(intent.getStringExtra(CAPTURE_REQUEST_ID))) finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (showCurrentFrame(intent.getStringExtra(CAPTURE_REQUEST_ID))) setIntent(intent)
    }

    private fun showCurrentFrame(requestId: String?): Boolean {
        val frame = CaptureFrame.take(requestId) ?: return false
        frame.timing.mark("preview_delivered")
        // Detach the previous image before releasing our references. Android's
        // renderer may still use its bitmap, so it must not be recycled here.
        setContentView(FrameLayout(this))
        releaseCurrentFrame()
        val generation = frameGate.next()
        val bitmap = frame.bitmap
        val expectedSession = frame.session
        val manager = (application as HappyApplication).userManager
        if (manager.session.value != expectedSession) {
            frame.worker.close()
            android.widget.Toast.makeText(this, R.string.capture_session_changed, android.widget.Toast.LENGTH_LONG).show()
            finish(); return true
        }
        worker = frame.worker
        image = bitmap
        val root = FrameLayout(this)
        view = CandidateView(this, bitmap, frame.timing) { candidate ->
            if (!frameGate.trySelect(generation)) return@CandidateView
            if (manager.session.value != expectedSession) {
                android.widget.Toast.makeText(this, R.string.capture_session_changed, android.widget.Toast.LENGTH_LONG).show()
                finish(); return@CandidateView
            }
            ScreenshotSelection.enqueue(candidate.text, expectedSession)
            startActivity(screenshotMainIntent(this))
            finish()
        }.also { root.addView(it) }
        message = TextView(this).apply {
            setBackgroundColor(0xdd000000.toInt()); setTextColor(Color.WHITE)
            textSize = 18f; setPadding(24, 32, 24, 32); setText(R.string.capture_processing)
        }
        root.addView(message, FrameLayout.LayoutParams(-1, -2))
        setContentView(root)
        frame.timing.mark("preview_ready")
        frame.worker.submit(bitmap) { result ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed && frameGate.isCurrent(generation)) {
                    result.onSuccess { candidates ->
                        view?.setCandidates(candidates)
                        if (candidates.isEmpty()) message.setText(R.string.capture_none)
                        else { message.visibility = View.GONE; android.widget.Toast.makeText(this, R.string.capture_hint, android.widget.Toast.LENGTH_SHORT).show() }
                    }.onFailure { message.setText(R.string.capture_ocr_error) }
                }
            }
        }
        return true
    }
    private fun releaseCurrentFrame() {
        image = null
        // The worker retains the bitmap only while native OCR is using it.
        // Rendering may outlive view detachment, so GC owns bitmap disposal.
        worker?.close()
        worker = null
        view = null
    }
    override fun onDestroy() {
        frameGate.invalidate()
        releaseCurrentFrame()
        super.onDestroy()
    }
}

internal fun newOcrWorker(context: Context, timing: CaptureTiming): PreparedOcrWorker<Bitmap, List<NumberCandidate>> =
    PreparedOcrWorker { cancelled -> NativeOcrEngine.open(context.applicationContext, timing, cancelled) }

// Retained for the opt-in private instrumentation test; production uses the prewarmed worker.
private class OfflineNumberRecognizer(private val activity: Activity) {
    fun recognize(bitmap: Bitmap): List<NumberCandidate> {
        val engine = NativeOcrEngine.open(activity.applicationContext, CaptureTiming("qa", 0)) { false }
        return try { engine.recognize(bitmap) } finally { engine.close() }
    }
}

private class NativeOcrEngine private constructor(context: Context, private val timing: CaptureTiming) : PreparedOcrEngine<Bitmap, List<NumberCandidate>> {
    companion object {
        private val modelLock = Any()
        private val slot = Semaphore(1)
        fun open(context: Context, timing: CaptureTiming, cancelled: () -> Boolean): NativeOcrEngine {
            while (true) {
                if (cancelled()) throw CancellationException("Capture request cancelled")
                if (slot.tryAcquire(100, TimeUnit.MILLISECONDS)) break
            }
            try {
                if (cancelled()) throw CancellationException("Capture request cancelled")
                return NativeOcrEngine(context, timing)
            } catch (error: Throwable) {
                slot.release()
                throw error
            }
        }
    }
    private val api = TessBaseAPI()
    init {
        try {
            val dir = File(context.filesDir, "offline-ocr/tessdata")
            synchronized(modelLock) {
                if (!dir.exists() && !dir.mkdirs()) error("Cannot prepare OCR model")
                val model = File(dir, "eng.traineddata")
                if (!model.isFile || model.length() == 0L) {
                    val tmp = File(dir, "eng.traineddata.tmp")
                    context.assets.open("tessdata/eng.traineddata").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    if (!tmp.renameTo(model)) error("Cannot install OCR model")
                }
            }
            if (!api.init(dir.parentFile!!.absolutePath, "eng")) error("Cannot initialize OCR")
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
            timing.mark("ocr_ready")
        } catch (error: Throwable) {
            api.recycle()
            throw error
        }
    }
    override fun recognize(bitmap: Bitmap): List<NumberCandidate> {
        timing.mark("ocr_started")
        api.setImage(bitmap)
        api.getUTF8Text() // Run recognition before asking for the iterator.
        val iterator = api.resultIterator ?: return emptyList()
        try {
            val symbols = mutableListOf<OcrSymbol>()
            iterator.begin()
            do {
                val symbol = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_SYMBOL).orEmpty()
                if (symbol.isNotEmpty()) {
                    val rect = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_SYMBOL)
                    symbols += OcrSymbol(symbol, rect.left, rect.top, rect.right, rect.bottom,
                        iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                }
            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_SYMBOL))
            return extractOcrCandidates(symbols).map { candidate ->
                NumberCandidate(candidate.text, Rect(candidate.left, candidate.top, candidate.right, candidate.bottom))
            }
        } finally { iterator.delete() }
    }
    override fun close() { try { api.recycle() } finally { slot.release() } }
}

private class CandidateView(context: Activity, private val bitmap: Bitmap, private val timing: CaptureTiming, private val onPick: (NumberCandidate) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var candidates: List<NumberCandidate> = emptyList()
    private val target = RectF()
    private var measured = false
    fun setCandidates(value: List<NumberCandidate>) { candidates = value; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val left = (width - bitmap.width * scale) / 2f
        val top = (height - bitmap.height * scale) / 2f
        target.set(left, top, left + bitmap.width * scale, top + bitmap.height * scale)
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
        canvas.drawBitmap(bitmap, null, target, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = Color.rgb(0, 160, 100)
        candidates.forEach { c -> canvas.drawRect(left + c.bounds.left * scale, top + c.bounds.top * scale, left + c.bounds.right * scale, top + c.bounds.bottom * scale, paint) }
        if (candidates.isNotEmpty() && !measured && timing.startedAt > 0) {
            measured = true
            timing.mark("candidate_draw")
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP || target.width() <= 0 || target.height() <= 0) return true
        val x = (event.x - target.left) * bitmap.width / target.width()
        val y = (event.y - target.top) * bitmap.height / target.height()
        val padding = 15f * resources.displayMetrics.density * bitmap.width / target.width()
        val boxes = candidates.map { CandidateHitBox(it.bounds.left.toFloat(), it.bounds.top.toFloat(),
            it.bounds.right.toFloat(), it.bounds.bottom.toFloat()) }
        selectCandidateIndex(boxes, x, y, padding)?.let { onPick(candidates[it]) }
        return true
    }
}
