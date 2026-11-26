package com.happy.consumption.capture

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.happy.consumption.MainActivity
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, private QA only. Inputs and outputs are never logged or packaged as assets. */
@RunWith(AndroidJUnit4::class)
class PrivateSampleOcrTest {
    @Test fun recognizeNinePrivateSamples() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("qaOcrSamples") == "true")
        val context = instrumentation.targetContext
        assertEquals("The private sample test requires the isolated QA app", "com.happy.consumption.qa", context.packageName)
        val directory = File(context.cacheDir, "ocr-sample-qa")
        val onlySample = InstrumentationRegistry.getArguments().getString("qaOcrOnlySample")?.toIntOrNull()
        require(onlySample == null || onlySample in 1..9)
        val samples = (onlySample?.let { listOf(it) } ?: (1..9).toList())
            .map { it to File(directory, "sample_%02d.jpg".format(it)) }
        val results = JSONArray()
        val report = JSONObject()
            .put("status", "running")
            .put("note", "Candidates are unedited production recognition results; this does not record the OCR engine's full text, manual corrections or end-to-end UI timing.")
            .put("samples", results)
        fun saveReport() {
            check(directory.isDirectory || directory.mkdirs())
            val temp = File(directory, "results.json.tmp")
            temp.writeText(report.toString(2), Charsets.UTF_8)
            check(temp.renameTo(File(directory, "results.json")))
        }
        try {
            check(samples.all { it.second.isFile && it.second.length() > 0 }) { "All requested private inputs are required" }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val activity = AtomicReference<MainActivity>()
                scenario.onActivity { activity.set(it) }
                val recognizerClass = Class.forName("com.happy.consumption.capture.OfflineNumberRecognizer")
                val constructor = recognizerClass.getDeclaredConstructor(android.app.Activity::class.java).apply { isAccessible = true }
                val method = recognizerClass.getDeclaredMethod("recognize", android.graphics.Bitmap::class.java).apply { isAccessible = true }
                val recognizer = constructor.newInstance(activity.get())
                for ((sampleNumber, input) in samples) {
                    val bitmap = checkNotNull(BitmapFactory.decodeFile(input.absolutePath)) { "Could not decode private sample" }
                    try {
                        val started = SystemClock.elapsedRealtime()
                        @Suppress("UNCHECKED_CAST")
                        val candidates = method.invoke(recognizer, bitmap) as List<NumberCandidate>
                        val candidateMs = SystemClock.elapsedRealtime() - started
                        val boxes = JSONArray()
                        candidates.forEach { candidate ->
                            val box = candidate.bounds
                            boxes.put(JSONObject().put("text", candidate.text)
                                .put("bounds", JSONArray().put(box.left).put(box.top).put(box.right).put(box.bottom)))
                        }
                        val sha = MessageDigest.getInstance("SHA-256").digest(input.readBytes())
                            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                        val sampleResult = JSONObject().put("sample", sampleNumber).put("sha256", sha)
                            .put("width", bitmap.width).put("height", bitmap.height)
                            .put("recognition_ms", candidateMs)
                            .put("candidates", boxes)
                        if (InstrumentationRegistry.getArguments().getString("qaOcrRawSample")?.toIntOrNull() == sampleNumber) {
                            val api = TessBaseAPI()
                            try {
                                check(api.init(File(context.filesDir, "offline-ocr").absolutePath, "eng"))
                                api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
                                api.setImage(bitmap)
                                api.getUTF8Text()
                                val symbols = JSONArray()
                                api.resultIterator?.let { iterator ->
                                    try {
                                        iterator.begin()
                                        do {
                                            val rect = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_SYMBOL)
                                            symbols.put(JSONObject()
                                                .put("text", iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_SYMBOL).orEmpty())
                                                .put("word_start", iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                                                .put("bounds", JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom)))
                                        } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_SYMBOL))
                                    } finally { iterator.delete() }
                                }
                                sampleResult.put("diagnostic_symbols", symbols)
                            } finally { api.recycle() }
                        }
                        results.put(sampleResult)
                        saveReport()
                    } finally { bitmap.recycle() }
                }
            }
            report.put("status", "complete")
            saveReport()
            assertEquals(samples.size, results.length())
        } catch (error: Throwable) {
            report.put("status", "failed").put("error_type", error.javaClass.name)
            runCatching { saveReport() }
            throw error
        } finally {
            // Inputs are temporary, including when recognition fails. The caller must
            // also remove the directory after pulling the private result, or if the
            // process/device is interrupted before this finally block can run.
            val notDeleted = samples.map { it.second }.filter { it.exists() && !it.delete() }
            assertTrue("Could not remove private QA input images", notDeleted.isEmpty())
        }
    }
}
