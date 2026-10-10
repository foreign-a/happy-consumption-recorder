package com.happy.consumption.data

// 移植自 QA AutoGenerationIdempotencyTest.java；实际 Room 并发兜底另由设备测试验证。
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.io.IOException

class AutoGenerationIdempotencyTest {
    @Test fun datasetUniqueUserDates() {
        val keys=DatasetFixture.rows.map { it[0] to it[1] }
        assertEquals(31104,keys.size);assertEquals(keys.size,keys.toSet().size)
    }
    private val clock = Clock.fixed(Instant.parse("2024-02-29T12:00:00Z"), ZoneOffset.UTC)
    private class Gateway : AutoRecordGateway {
        val notes = mutableMapOf<Pair<Long,String>, NoteEntity>()
        var readsFail=false; var writes=0
        override suspend fun exists(userId: Long, date: String): Boolean { if(readsFail) throw IOException("read failed"); return notes.containsKey(userId to date) }
        override suspend fun insertZeroIfAbsent(note: NoteEntity): Long {
            val key=note.userId to note.date
            if(notes.containsKey(key)) return -1
            notes[key]=note; writes++; return writes.toLong()
        }
    }
    @Test fun repeatedGenerateAndInjectedTimestamp() = runBlocking {
        val g=Gateway();val generator=AutoRecordGenerator(g,clock)
        assertTrue(generator.shouldGenerate(1,generator.today())); assertTrue(generator.generate(1,generator.today()))
        repeat(10){ assertFalse(generator.generate(1,generator.today())) }
        val note=g.notes.values.single();assertFalse(note.isManual);assertEquals(0L,note.breakfast+note.lunch+note.dinner+note.other)
        assertEquals(clock.millis(),note.updatedAt)
    }
    @Test fun manualNotOverwrittenAndUsersIndependent() = runBlocking {
        val g=Gateway(); val original=NoteEntity(9,1,"2024-02-29",1,2,3,4,true,0);g.notes[1L to original.date]=original
        val generator=AutoRecordGenerator(g,clock)
        assertFalse(generator.generate(1,original.date));assertEquals(original,g.notes[1L to original.date])
        assertTrue(generator.generate(2,original.date));assertEquals(2,g.notes.size)
    }
    @Test fun readExceptionDoesNotWrite() = runBlocking {
        val g=Gateway();g.readsFail=true
        try { AutoRecordGenerator(g,clock).generate(1,"2024-02-29");fail() } catch (_: IOException) { }
        assertEquals(0,g.writes);assertTrue(g.notes.isEmpty())
    }
}
