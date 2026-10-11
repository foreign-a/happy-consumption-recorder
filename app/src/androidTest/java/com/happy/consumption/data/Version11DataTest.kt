package com.happy.consumption.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.*

@RunWith(AndroidJUnit4::class)
class Version11DataTest {
    private class Fixture(context: Context) {
        val root = File(context.cacheDir, "v11-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "users.preferences_pb") })
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val manager = UserManager(store, db, root)
        fun close() { scope.cancel(); db.close(); root.deleteRecursively() }
    }
    private lateinit var source: Fixture
    private lateinit var target: Fixture
    private val clock = Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC)
    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        source = Fixture(context); target = Fixture(context)
        source.manager.initialize(); target.manager.initialize()
        source.manager.register("A", "fixture-A"); source.manager.register("B", "fixture-B")
        source.manager.login("A", "fixture-A")
    }
    @After fun cleanup() { source.close(); target.close() }
    private fun repository() = UserRepository(source.manager, source.db, source.manager.session.value!!, clock)
    @Test fun batchCreatesThirtyPrecedingZerosAndPreservesExistingAndOtherUser() = runBlocking {
        val repo = repository(); repo.ensureToday(); repo.add("2024-03-01", Amounts(123,456,789,1000))
        source.db.notes().insertIfAbsent(NoteEntity(userId=2, date="2023-01-01", breakfast=222, lunch=0, dinner=0, other=0, isManual=true, updatedAt=4))
        val before = source.db.notes().queryByUserPaged(1,200,0)
        val foreign = source.db.notes().queryByUserPaged(2,200,0)
        assertEquals(30, repo.generatePastThirtyDays())
        val after = source.db.notes().queryByUserPaged(1,200,0)
        assertEquals(32,after.size); assertTrue(after.containsAll(before)); assertEquals(foreign,source.db.notes().queryByUserPaged(2,200,0))
        val generated = after.filterNot { it in before }
        assertEquals("2024-02-29",generated.first().date); assertEquals("2024-01-31",generated.last().date)
        assertTrue(generated.all { !it.isManual && it.breakfast+it.lunch+it.dinner+it.other==0L })
        assertEquals(30,repo.generatePastThirtyDays()); assertEquals(62,source.db.notes().countByUserOnce(1))
        source.manager.logout()
        try { repo.generatePastThirtyDays(); fail() } catch (_:AccessDeniedException) { }
    }
    @Test fun emptyAccountBatchUsesTodayAndCurrencyIsIsolatedAndPersistent() = runBlocking {
        val owner = source.manager.session.value!!
        source.manager.selectCurrency(owner, MoneyUnit.POUND)
        assertEquals(MoneyUnit.POUND,source.manager.currencyFor(owner).first())
        assertEquals(30,repository().generatePastThirtyDays())
        assertEquals("2026-10-10",source.db.notes().queryByUserPaged(1,200,0).first().date)
        source.manager.initialize()
        assertEquals(MoneyUnit.POUND,source.manager.currencyFor(source.manager.session.value!!).first())
        source.manager.login("B","fixture-B")
        assertEquals(MoneyUnit.YUAN,source.manager.currencyFor(source.manager.session.value!!).first())
        try { source.manager.selectCurrency(owner,MoneyUnit.DOLLAR); fail() } catch (_:AccessDeniedException) { }
    }
    @Test fun encryptedBackupRestoresCredentialsAmountsCurrencyAndRemapsIds() = runBlocking {
        val owner = source.manager.session.value!!
        repository().add("2024-02-29",Amounts(1,999999,0,567))
        source.manager.selectCurrency(owner,MoneyUnit.DOLLAR)
        val original = source.db.notes().queryByUserPaged(owner.id,200,0)
        val payload = source.manager.exportBackup(owner)
        assertEquals(1,BackupDocument.decode(payload.toString(Charsets.UTF_8)).size)
        val encrypted = BackupCipher.encrypt(payload,"backup-test-only".toCharArray())
        target.manager.register("Existing","target-password"); target.manager.login("Existing","target-password")
        val existing = target.manager.session.value
        target.manager.importBackup(BackupCipher.decrypt(encrypted,"backup-test-only".toCharArray()))
        assertEquals(existing,target.manager.session.value)
        target.manager.login("A","fixture-A"); val restored=target.manager.session.value!!
        assertEquals(2L,restored.id); assertEquals(MoneyUnit.DOLLAR,target.manager.currencyFor(restored).first())
        assertEquals(original.map { it.copy(id=0,userId=0) },target.db.notes().queryByUserPaged(restored.id,200,0).map { it.copy(id=0,userId=0) })
        val prefsBefore=target.store.data.first()
        try { target.manager.importBackup(payload); fail() } catch(e:UserOperationException) { assertEquals(UserError.BACKUP_CONFLICT,e.reason) }
        assertEquals(prefsBefore,target.store.data.first())
        try { target.manager.importBackup("invalid".toByteArray()); fail() } catch(e:UserOperationException) { assertEquals(UserError.BACKUP_INVALID,e.reason) }
        assertEquals(prefsBefore,target.store.data.first())
        assertNull(target.manager.findUserId("B"))
        source.manager.logout()
        try { source.manager.exportBackup(owner); fail() } catch (_:AccessDeniedException) { }
    }
    @Test fun interruptedImportRecoversWithoutDuplicatingRowsOrAccounts() = runBlocking {
        repository().add("2024-01-01",Amounts(1,2,3,4))
        val plan=BackupDocument.decode(source.manager.exportBackup(source.manager.session.value!!).toString(Charsets.UTF_8))
            .map { it.copy(credential=it.credential.copy(id=8), notes=it.notes.map { note -> note.copy(userId=8) }) }
        target.store.edit { it[stringPreferencesKey("pending_import_json")]=BackupDocument.encode(plan);it[longPreferencesKey("next_user_id")]=9 }
        target.db.identities().insert(UserIdentity(8))
        plan.first().notes.forEach { target.db.notes().insertIfAbsent(it) }
        target.manager.initialize(); target.manager.initialize(); target.manager.login("A","fixture-A")
        assertEquals(8L,target.manager.session.value!!.id);assertEquals(1,target.db.notes().countByUserOnce(8))
        assertNull(target.store.data.first()[stringPreferencesKey("pending_import_json")])
        target.manager.register("Next","next-password"); assertEquals(9L,target.manager.findUserId("Next"))
    }
}
