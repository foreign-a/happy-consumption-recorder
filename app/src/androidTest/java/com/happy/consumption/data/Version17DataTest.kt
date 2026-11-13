package com.happy.consumption.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class Version17DataTest {
    private class Fixture(context: Context) {
        val root = File(context.cacheDir, "v17-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope,
            produceFile = { File(root, "users.preferences_pb") })
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val manager = UserManager(store, db, root)
        fun close() { scope.cancel(); db.close(); root.deleteRecursively() }
    }

    private lateinit var source: Fixture
    private lateinit var target: Fixture
    private val clock = Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC)

    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        source = Fixture(context)
        target = Fixture(context)
        source.manager.initialize()
        target.manager.initialize()
        source.manager.register("Large amounts", "fixture-v17")
        source.manager.login("Large amounts", "fixture-v17")
    }

    @After fun cleanup() { source.close(); target.close() }

    private suspend fun assertInvalidAmountBackupPreservesTarget(rawAmount: String): JSONObject {
        val owner = source.manager.session.value!!
        UserRepository(source.manager, source.db, owner, clock).add("2026-11-12", Amounts(12345, 2, 3, 4))
        val valid = source.manager.exportBackup(owner).toString(Charsets.UTF_8)
        // Insert the numeric token verbatim: JSONObject.put(Double) would change its representation.
        val invalid = valid.replace("\"breakfast\":12345", "\"breakfast\":$rawAmount")
        assertNotEquals(valid, invalid)
        val parsedNote = JSONObject(invalid).getJSONArray("accounts").getJSONObject(0)
            .getJSONArray("notes").getJSONObject(0)

        target.manager.register("Existing", "target-v17")
        target.manager.login("Existing", "target-v17")
        val existing = target.manager.session.value!!
        UserRepository(target.manager, target.db, existing, clock)
            .add("2026-11-11", Amounts(Long.MAX_VALUE, 9_007_199_254_740_993L, 0, 1))
        val preferences = target.store.data.first()
        val rows = target.db.notes().queryByUserPaged(existing.id, 200, 0)

        try {
            target.manager.importBackup(invalid.toByteArray(Charsets.UTF_8))
            fail("Invalid cent amount $rawAmount was imported")
        } catch (error: UserOperationException) {
            assertEquals(UserError.BACKUP_INVALID, error.reason)
        }
        assertEquals(existing, target.manager.session.value)
        assertEquals(preferences, target.store.data.first())
        assertEquals(rows, target.db.notes().queryByUserPaged(existing.id, 200, 0))
        assertNull(target.manager.findUserId(owner.username))
        assertEquals(0, target.db.identities().countByUser(2))
        assertEquals(0, target.db.notes().countByUserOnce(2))
        assertFalse(File(target.root, "users/2").exists())
        return parsedNote
    }

    @Test fun backupRejectsLongMaximumPlusOneWithoutClampingOrChangingTarget() = runBlocking {
        val parsed = assertInvalidAmountBackupPreservesTarget("9223372036854775808")
        // Android JSON parses this token as Double and getLong clamps it to Long.MAX_VALUE.
        // Import must reject the original numeric type instead of accepting that coerced value.
        assertTrue(parsed.get("breakfast") is Double)
        assertEquals(Long.MAX_VALUE, parsed.getLong("breakfast"))
    }

    @Test fun backupRejectsNegativeCentsWithoutChangingTarget() = runBlocking {
        val parsed = assertInvalidAmountBackupPreservesTarget("-1")
        assertEquals(-1L, parsed.getLong("breakfast"))
    }

    @Test fun backupRejectsFractionalCentsWithoutTruncatingOrChangingTarget() = runBlocking {
        val parsed = assertInvalidAmountBackupPreservesTarget("1.5")
        assertTrue(parsed.get("breakfast") is Double)
        assertEquals(1L, parsed.getLong("breakfast"))
    }

    @Test fun encryptedBackupRoundTripPreservesLongMaximumAndLargeOddCentsExactly() = runBlocking {
        val owner = source.manager.session.value!!
        val repository = UserRepository(source.manager, source.db, owner, clock)
        val currencies = EntryCurrencies(MoneyUnit.YUAN, MoneyUnit.DOLLAR, MoneyUnit.POUND, MoneyUnit.YUAN)
        repository.add("2026-11-12", Amounts(Long.MAX_VALUE, Long.MAX_VALUE - 1,
            9_007_199_254_740_993L, 1_000_001L), currencies)
        val original = source.db.notes().queryByUserPaged(owner.id, 200, 0).single()
        assertEquals(Long.MAX_VALUE, original.breakfast)
        assertEquals(9_007_199_254_740_993L, original.dinner)

        val payload = source.manager.exportBackup(owner)
        val decoded = BackupDocument.decode(payload.toString(Charsets.UTF_8)).single().notes.single()
        assertEquals(original.copy(id = 0), decoded)
        val encrypted = BackupCipher.encrypt(payload, "backup-v17-only".toCharArray())
        target.manager.register("Existing", "target-v17")
        target.manager.login("Existing", "target-v17")
        val existingSession = target.manager.session.value
        target.manager.importBackup(BackupCipher.decrypt(encrypted, "backup-v17-only".toCharArray()))
        assertEquals(existingSession, target.manager.session.value)
        target.manager.login("Large amounts", "fixture-v17")
        val restored = target.manager.session.value!!
        assertNotEquals(owner.id, restored.id)
        assertEquals(original.copy(id = 0, userId = 0),
            target.db.notes().queryByUserPaged(restored.id, 200, 0).single().copy(id = 0, userId = 0))
        assertTrue(target.db.notes().queryByUserPaged(existingSession!!.id, 200, 0).isEmpty())
    }
}
