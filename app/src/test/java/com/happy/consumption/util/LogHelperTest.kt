package com.happy.consumption.util

import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files
import java.io.File
import java.time.*

class LogHelperTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-10T02:03:04.005Z"), ZoneId.of("Asia/Shanghai"))
    @Test fun appendConcurrentSafeStackAndRetention() = runBlocking {
        val root=Files.createTempDirectory("logger").toFile()
        try {
            File(root,"2026-10-02.log").writeText("old")
            File(root,"2026-10-03.log").writeText("boundary\n")
            File(root,"2026-10-10.log").writeText("today\n")
            File(root,"keep.txt").writeText("unrelated")
            val writer=FileLogWriter(root,clock)
            coroutineScope { (1..100).map { launch(Dispatchers.Default) { writer.write("Info","Test","event=$it") } }.joinAll() }
            val cause=IllegalArgumentException("credential-secret-cause")
            val error=NullPointerException("credential-secret-input").apply { initCause(cause);addSuppressed(java.io.IOException("credential-secret-suppressed")) }
            writer.write("Error","Test","Injected failure",error);writer.close()
            assertFalse(File(root,"2026-10-02.log").exists())
            assertEquals("boundary\n",File(root,"2026-10-03.log").readText())
            assertTrue(File(root,"keep.txt").exists())
            val text=File(root,"2026-10-10.log").readText()
            assertTrue(text.startsWith("today\n"));assertEquals(100,Regex("event=\\d+").findAll(text).count())
            assertTrue(text.contains("10:03:04.005 [Error]"));assertTrue(text.contains("java.lang.NullPointerException"))
            assertTrue(text.contains("\tat "));assertTrue(text.contains("Caused by:"));assertTrue(text.contains("Suppressed:"))
            assertFalse(text.contains("credential-secret"));assertEquals(0,writer.failureCount)
        } finally { root.deleteRecursively() }
    }
    @Test fun directoryWriteAndCleanupFailuresDoNotEscape() = runBlocking {
        val root=Files.createTempDirectory("logger-failure").toFile()
        try {
            val blocked=File(root,"blocked").apply{writeText("file")}
            val unavailable=FileLogWriter(blocked,clock)
            unavailable.write("Info","Test","safe");unavailable.close();assertTrue(unavailable.failureCount>0)
            val logs=File(root,"logs").apply{mkdirs()};File(logs,"2026-10-02.log").writeText("old")
            val denied=FileLogWriter(logs,clock,append={_,_->throw java.io.IOException("write denied")},delete={false})
            denied.write("Info","Test","safe");denied.close();assertEquals(2,denied.failureCount)
            assertTrue(File(logs,"2026-10-02.log").exists())
        } finally { root.deleteRecursively() }
    }
}
