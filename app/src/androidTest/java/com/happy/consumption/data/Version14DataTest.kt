package com.happy.consumption.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.*

@RunWith(AndroidJUnit4::class)
class Version14DataTest {
    private lateinit var db: AppDatabase
    private lateinit var manager: UserManager
    private lateinit var scope: CoroutineScope
    private lateinit var root: File
    private lateinit var owner: UserSession
    private lateinit var repo: UserRepository
    private val clock=Clock.fixed(Instant.parse("2026-10-26T23:30:00Z"),ZoneId.of("Asia/Shanghai"))
    @Before fun setup() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        root=File(context.cacheDir,"v14-${System.nanoTime()}").apply { mkdirs() }
        scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val store=PreferenceDataStoreFactory.create(scope=scope,produceFile={ File(root,"users.preferences_pb") })
        db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        manager=UserManager(store,db,root);manager.initialize()
        manager.register("A","fixture-A");manager.register("B","fixture-B");manager.login("A","fixture-A")
        owner=manager.session.value!!;repo=UserRepository(manager,db,owner,clock)
    }
    @After fun cleanup() { scope.cancel();db.close();root.deleteRecursively() }
    @Test fun concurrentPastCreationIsIdempotentAndPreservesAmountsCurrenciesAndOtherUser() = runBlocking {
        manager.selectCurrency(owner,MoneyUnit.POUND)
        val results=coroutineScope { (1..8).map { async(Dispatchers.Default) { repo.ensurePastDate("2026-10-26") } }.awaitAll() }
        assertEquals(1,results.count { it })
        val row=db.notes().queryByUserAndDate(owner.id,"2026-10-26")!!
        assertEquals(0L,row.breakfast+row.lunch+row.dinner+row.other)
        assertEquals(MoneyUnit.POUND, row.toItem().currencies[Meal.OTHER])
        db.notes().updateByIdAndUser(row.id,owner.id,123,456,789,42,true,999,otherCurrency=MoneyUnit.DOLLAR.name)
        val changed=db.notes().queryByUserAndDate(owner.id,row.date)!!
        assertFalse(repo.ensurePastDate(row.date));assertEquals(changed,db.notes().queryByUserAndDate(owner.id,row.date))
        val b=manager.findUserId("B")!!;assertNull(db.notes().queryByUserAndDate(b,row.date))
        for (date in listOf("2026-10-27","2026-10-28","invalid")) {
            try { repo.ensurePastDate(date);fail() } catch (_:UserOperationException) { }
        }
        manager.logout()
        try { repo.ensurePastDate("2026-10-25");fail() } catch (_:AccessDeniedException) { }
    }
    @Test fun batchDeletesOnlyChosenPastDatesAndBlocksTodayForeignUserAndStaleSession() = runBlocking {
        repo.ensureToday();repo.add("2026-09-30",Amounts(1,2,3,4));repo.add("2026-10-26",Amounts(5,6,7,8))
        repo.add("2026-10-25",Amounts(9,10,11,12))
        val foreign=NoteEntity(userId=manager.findUserId("B")!!,date="2026-10-26",breakfast=999,lunch=0,dinner=0,other=0,isManual=true,updatedAt=0)
        db.notes().insertIfAbsent(foreign)
        val all=repo.range("2026-09-01","2026-10-31")
        val past=all.filter { it.date in listOf("2026-09-30","2026-10-26") }
        try { repo.deleteBatch(BatchDeletionPermit(owner,all));fail() } catch (_:UserOperationException) { }
        val other=db.notes().queryByUserAndDate(foreign.userId,foreign.date)!!.toItem()
        try { repo.deleteBatch(BatchDeletionPermit(owner,past+other));fail() } catch (_:AccessDeniedException) { }
        assertEquals(all,repo.range("2026-09-01","2026-10-31"))
        assertEquals(2,repo.deleteBatch(BatchDeletionPermit(owner,past)))
        assertEquals(setOf("2026-10-25","2026-10-27"),repo.range("2026-09-01","2026-10-31").map { it.date }.toSet())
        assertEquals(999L,db.notes().queryByUserAndDate(foreign.userId,foreign.date)!!.breakfast)
        val permit=BatchDeletionPermit(owner,listOf(repo.range("2026-10-25","2026-10-25").single()))
        manager.login("B","fixture-B")
        try { repo.deleteBatch(permit);fail() } catch (_:AccessDeniedException) { }
        assertNotNull(db.notes().queryByUserAndDate(owner.id,"2026-10-25"))
    }
    @Test fun replacementIdentityRejectsWholeBatchWithoutDeletingValidRows() = runBlocking {
        repo.ensurePastDate("2026-10-25");repo.ensurePastDate("2026-10-26")
        val chosen=repo.range("2026-10-25","2026-10-26")
        val old=chosen.last();db.notes().deleteByIdAndUser(old.id,owner.id);repo.ensurePastDate(old.date)
        val replacement=db.notes().queryByUserAndDate(owner.id,old.date)!!
        assertNotEquals(old.id,replacement.id)
        try { repo.deleteBatch(BatchDeletionPermit(owner,chosen));fail() } catch (e:UserOperationException) { assertEquals(UserError.RECORD_MISSING,e.reason) }
        assertEquals(2,repo.range("2026-10-25","2026-10-26").size)
        assertEquals(replacement,db.notes().queryByUserAndDate(owner.id,old.date))
    }
    @Test fun databaseFailureRollsBackEntireBatchAndNewPermitCanRetry() = runBlocking {
        repo.ensurePastDate("2026-10-25");repo.ensurePastDate("2026-10-26")
        val chosen=repo.range("2026-10-25","2026-10-26")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_batch BEFORE DELETE ON notes WHEN OLD.date='2026-10-26' BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
        val permit=BatchDeletionPermit(owner,chosen)
        try { repo.deleteBatch(permit);fail() } catch (_:android.database.sqlite.SQLiteException) { }
        assertEquals(chosen,repo.range("2026-10-25","2026-10-26"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_batch")
        try { repo.deleteBatch(permit);fail() } catch (e:UserOperationException) { assertEquals(UserError.UNCONFIRMED,e.reason) }
        assertEquals(2,repo.deleteBatch(BatchDeletionPermit(owner,chosen)))
    }
}
