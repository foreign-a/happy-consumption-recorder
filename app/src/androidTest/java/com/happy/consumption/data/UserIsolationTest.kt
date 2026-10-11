package com.happy.consumption.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal

@RunWith(AndroidJUnit4::class)
class UserIsolationTest {
    private lateinit var db: AppDatabase
    private lateinit var manager: UserManager
    private lateinit var root: File
    private lateinit var scope: CoroutineScope
    private lateinit var store: androidx.datastore.core.DataStore<Preferences>
    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.cacheDir, "isolation-${System.nanoTime()}").apply { mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "users.preferences_pb") })
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        manager = UserManager(store, db, root)
        manager.initialize(); manager.register("A", "fixture-A"); manager.register("B", "fixture-B")
    }
    @After fun cleanup() { scope.cancel(); db.close(); root.deleteRecursively() }
    private suspend fun denied(action: suspend () -> Unit) {
        try { action(); fail("cross-user operation accepted") } catch (_: AccessDeniedException) { }
    }
    @Test fun datasetQueriesAndAuthorization() = runBlocking {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context
        val fixture = JSONArray(context.assets.open("isolation-fixture.json").bufferedReader().use { it.readText() })
        for (i in 0 until fixture.length()) {
            val row = fixture.getJSONObject(i)
            fun cents(key: String) = BigDecimal(row.getString(key)).movePointRight(2).longValueExact()
            db.notes().upsert(NoteEntity(userId = row.getLong("userId"), date = row.getString("date"),
                breakfast = cents("breakfast"), lunch = cents("lunch"), dinner = cents("dinner"), other = cents("other"),
                isManual = row.getBoolean("isManual"), updatedAt = 0))
        }
        assertEquals(80, fixture.length())
        for (id in 1L..2L) {
            val rows = db.notes().queryByUserPaged(id, 200, 0)
            assertEquals(40, rows.size); assertTrue(rows.all { it.userId == id })
            rows.forEach { assertEquals(it, db.notes().queryByUserAndDate(id, it.date)) }
        }
        manager.login("A", "fixture-A")
        val a = UserRepository(manager, db, manager.session.value!!)
        assertEquals(40, a.queryRecords(1).size)
        denied { a.queryRecords(2) }; denied { a.queryByDate(2, "2024-01-01") }
        val bRow = db.notes().queryByUserPaged(2, 1, 0).first()
        assertEquals(0, db.notes().deleteByIdAndUser(bRow.id, 1))
        assertEquals(0, db.notes().updateByIdAndUser(bRow.id, 1, 0, 0, 0, 0, true, 1))
        assertEquals(bRow, db.notes().queryByUserAndDate(2, bRow.date))
        // 即使传入另一用户的主键，upsert 也只能更新自己的日期记录。
        db.notes().upsert(bRow.copy(userId = 1, breakfast = 123))
        assertEquals(bRow, db.notes().queryByUserAndDate(2, bRow.date))
        manager.logout(); denied { a.queryRecords(1) }
        manager.login("B", "fixture-B")
        val b = UserRepository(manager, db, manager.session.value!!)
        denied { b.queryRecords(1) }; denied { a.queryRecords(1) }
        manager.logout(); manager.login("A", "fixture-A"); denied { a.queryRecords(1) }
        assertEquals(40, UserRepository(manager, db, manager.session.value!!).queryRecords(1).size)
    }
    @Test fun credentialsDeletionAndRestore() = runBlocking {
        try { manager.login("A", "wrong"); fail() } catch (e: UserOperationException) { assertEquals(UserError.INVALID_LOGIN, e.reason) }
        try { manager.register(" A ", "other"); fail() } catch (e: UserOperationException) { assertEquals(UserError.DUPLICATE_NAME, e.reason) }
        try { manager.register("C", "123"); fail() } catch (e: UserOperationException) { assertEquals(UserError.SHORT_PASSWORD, e.reason) }
        assertTrue(File(root, "users/1").isDirectory); assertTrue(File(root, "users/2").isDirectory)
        val prefs = store.data.first()[stringPreferencesKey("users_json")]!!
        assertFalse(prefs.contains("fixture-A")); assertFalse(prefs.contains("fixture-B"))
        val persisted = File(root, "users.preferences_pb").readBytes().toString(Charsets.UTF_8)
        assertFalse(persisted.contains("fixture-A")); assertFalse(persisted.contains("fixture-B"))
        val users = JSONArray(prefs)
        for (i in 0 until users.length()) {
            assertEquals(32, users.getJSONObject(i).getString("salt").length)
            assertEquals(64, users.getJSONObject(i).getString("passwordHash").length)
            assertFalse(users.getJSONObject(i).has("password"))
        }
        manager.login("A", "fixture-A")
        val a = UserRepository(manager, db, manager.session.value!!)
        a.add("2024-01-01", 0, 1, 999999, 10)
        manager.logout(); manager.login("B", "fixture-B")
        val b = UserRepository(manager, db, manager.session.value!!)
        assertTrue(b.queryRecords(2).isEmpty())
        b.add("2024-01-01", 10, 20, 30, 40)
        File(root, "users/2/test-export.txt").writeText("sample")
        val expected = manager.session.value!!
        for (password in listOf("", "wrong", "fixture-A")) {
            try { manager.deleteCurrentUser(expected, password); fail("unverified deletion accepted") }
            catch (e: UserOperationException) { assertEquals(UserError.INVALID_PASSWORD, e.reason) }
            assertEquals(expected, manager.session.value)
            assertEquals(1, db.notes().countByUserOnce(2))
            assertTrue(File(root, "users/2/test-export.txt").exists())
        }
        manager.logout(); manager.login("B", "fixture-B")
        denied { manager.deleteCurrentUser(expected, "fixture-B") }
        manager.deleteCurrentUser(manager.session.value!!, "fixture-B")
        assertNull(manager.session.value); assertEquals(0, db.notes().countByUserOnce(2))
        assertEquals(0, db.identities().countByUser(2)); assertFalse(File(root, "users/2").exists())
        assertFalse(store.data.first().contains(longPreferencesKey("current_user_id")))
        assertEquals(1, JSONArray(store.data.first()[stringPreferencesKey("users_json")]).length())
        try { manager.login("B", "fixture-B"); fail() } catch (_: UserOperationException) { }
        manager.login("A", "fixture-A")
        val restored = UserManager(store, db, root); restored.initialize()
        assertEquals(1L, restored.session.value!!.id)
        assertEquals(999999L, UserRepository(restored, db, restored.session.value!!).queryRecords(1).single().dinner)
        manager.register("B", "new-fixture"); assertTrue(File(root, "users/3").isDirectory)
    }
    @Test fun foreignKeyAndRegistrationRecovery() = runBlocking {
        try {
            db.notes().insertIfAbsent(NoteEntity(userId = 999, date = "2024-01-01", breakfast = 0, lunch = 0, dinner = 0, other = 0, isManual = false, updatedAt = 0))
            fail("orphan note accepted")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        val salt = PasswordHasher.newSalt()
        val pending = Credential(3, "C", PasswordHasher.hex(salt), PasswordHasher.hex(PasswordHasher.hashPassword("recovery-fixture", salt)), 0)
        store.edit { it[stringPreferencesKey("pending_registration_json")] = pending.json().toString(); it[longPreferencesKey("next_user_id")] = 4 }
        val recovered = UserManager(store, db, root); recovered.initialize()
        assertFalse(store.data.first().contains(stringPreferencesKey("pending_registration_json")))
        assertTrue(File(root, "users/3").isDirectory); assertEquals(1, db.identities().countByUser(3))
        recovered.login("C", "recovery-fixture"); assertEquals(3L, recovered.session.value!!.id)
        db.notes().insertIfAbsent(NoteEntity(userId = 3, date = "2024-01-01", breakfast = 0, lunch = 0, dinner = 0, other = 0, isManual = false, updatedAt = 0))
        db.identities().deleteByUser(3); assertEquals(0, db.notes().countByUserOnce(3))
    }
    @Test fun pendingDeletionRecoversOnStartup() = runBlocking {
        manager.login("B", "fixture-B")
        UserRepository(manager, db, manager.session.value!!).add("2024-01-01", 1, 2, 3, 4)
        store.edit { it[longPreferencesKey("pending_delete_user_id")] = 2; it.remove(longPreferencesKey("current_user_id")) }
        val recovered = UserManager(store, db, root); recovered.initialize()
        assertNull(recovered.session.value); assertEquals(0, db.notes().countByUserOnce(2))
        assertFalse(File(root, "users/2").exists())
        assertFalse(store.data.first().contains(longPreferencesKey("pending_delete_user_id")))
        assertEquals(1, JSONArray(store.data.first()[stringPreferencesKey("users_json")]).length())
    }
    @Test fun autoConflictsMutationsAndStalePermits() = runBlocking {
        val clock=java.time.Clock.fixed(java.time.Instant.parse("2024-02-29T08:00:00Z"),java.time.ZoneOffset.UTC)
        manager.login("A","fixture-A");val session=manager.session.value!!
        val a=UserRepository(manager,db,session,clock)
        val generated=(1..20).map { async(Dispatchers.IO) { a.ensureToday() } }.awaitAll()
        assertEquals(1,generated.count{it});assertEquals(1,db.notes().countByUserOnce(1))
        val auto=db.notes().queryByUserAndDate(1,"2024-02-29")!!
        assertFalse(auto.isManual);assertEquals(clock.millis(),auto.updatedAt)
        a.add("2024-02-28",0,1,999999,1)
        val original=db.notes().queryByUserAndDate(1,"2024-02-28")!!
        try { a.add("2024-02-28",1,2,3,4);fail() } catch(e:UserOperationException){assertEquals(UserError.DATE_EXISTS,e.reason)}
        try { a.add("2024-03-01",1,2,3,4);fail() } catch(e:UserOperationException){assertEquals(UserError.INVALID_DATE,e.reason)}
        assertEquals(original,db.notes().queryByUserAndDate(1,original.date))
        assertEquals(-1L,db.notes().insertIfAbsent(original.copy(id=0)))
        val c=RecordConfirmation(session);c.requestModify(auto.toItem());c.review(Amounts(999999,1,0,2))
        val newer=UserRepository(manager,db,session,java.time.Clock.offset(clock,java.time.Duration.ofSeconds(5)))
        newer.modify(c.confirmModify())
        val changed=db.notes().queryByIdAndUser(auto.id,1)!!
        assertEquals(auto.id,changed.id);assertEquals(auto.date,changed.date);assertEquals(auto.userId,changed.userId)
        assertTrue(changed.isManual);assertEquals(clock.millis()+5000,changed.updatedAt)
        assertFalse(a.ensureToday());assertEquals(changed,db.notes().queryByIdAndUser(auto.id,1))
        c.requestModify(changed.toItem());c.review(Amounts(1,2,3,4));val oldPermit=c.confirmModify()
        c.requestDelete(changed.toItem());val oldDelete=c.confirmDelete()
        manager.logout();manager.login("B","fixture-B")
        val b=UserRepository(manager,db,manager.session.value!!,clock);assertTrue(b.ensureToday())
        denied { a.ensureToday() };denied { a.modify(oldPermit) };denied { a.delete(oldDelete) }
        val foreign=RecordConfirmation(manager.session.value!!);foreign.requestModify(changed.toItem());foreign.review(Amounts(0,0,0,0))
        denied { b.modify(foreign.confirmModify()) }
        foreign.requestDelete(changed.toItem());denied { b.delete(foreign.confirmDelete()) }
        manager.logout();manager.login("A","fixture-A")
        val fresh=UserRepository(manager,db,manager.session.value!!,clock)
        denied { fresh.modify(oldPermit) };denied { fresh.delete(oldDelete) }
        val current=RecordConfirmation(manager.session.value!!);current.requestDelete(original.toItem());fresh.delete(current.confirmDelete())
        assertNull(db.notes().queryByIdAndUser(original.id,1));assertEquals(1,db.notes().countByUserOnce(1));assertEquals(1,db.notes().countByUserOnce(2))
    }
    @Test fun datastoreReadFailureDoesNotGenerateOrOverwrite() = runBlocking {
        manager.login("A","fixture-A")
        val repo=UserRepository(manager,db,manager.session.value!!)
        repo.add("2024-01-01",10,20,30,40)
        val original=db.notes().queryByUserAndDate(1,"2024-01-01")!!
        val encoded=store.data.first()[stringPreferencesKey("users_json")]!!
        store.edit { it[stringPreferencesKey("users_json")]="{" }
        try { repo.ensureToday();fail() } catch (_:org.json.JSONException) { }
        try { repo.add("2024-01-02",1,2,3,4);fail() } catch (_:org.json.JSONException) { }
        assertEquals(1,db.notes().countByUserOnce(1));assertEquals(original,db.notes().queryByUserAndDate(1,original.date))
        assertEquals("{",store.data.first()[stringPreferencesKey("users_json")])
        val auth=com.happy.consumption.ui.AppViewModel(manager)
        withTimeout(10000) { while(!auth.startupFailed.value) delay(10) }
        assertFalse(auth.ready.value)
        store.edit { it[stringPreferencesKey("users_json")]=encoded }
        auth.initialize();withTimeout(10000){while(!auth.ready.value)delay(10)}
        assertEquals(1,UserRepository(manager,db,manager.session.value!!).snapshot(1).count)
    }
}
