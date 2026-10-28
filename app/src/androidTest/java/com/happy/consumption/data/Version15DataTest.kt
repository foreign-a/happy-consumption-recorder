package com.happy.consumption.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.*

class Version15DataTest {
    private lateinit var root: File
    private lateinit var scope: CoroutineScope
    private lateinit var db: AppDatabase
    private lateinit var repo: UserRepository
    private lateinit var owner: UserSession
    @Before fun setup() {
        runBlocking {
            val context=ApplicationProvider.getApplicationContext<Context>()
            root=File(context.cacheDir,"v15-${System.nanoTime()}").apply { mkdirs() }
            scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val store=PreferenceDataStoreFactory.create(scope=scope,produceFile={ File(root,"users.preferences_pb") })
            db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
            val manager=UserManager(store,db,root);manager.initialize();manager.register("A","fixture-v15");manager.login("A","fixture-v15")
            owner=manager.session.value!!
            repo=UserRepository(manager,db,owner,Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"),ZoneOffset.UTC))
            repo.add("2026-10-27",Amounts(1000,200,300,400))
        }
    }
    @After fun cleanup() { scope.cancel();db.close();root.deleteRecursively() }
    @Test fun staleRelativeWriteIsRejectedAndOneTimePermitCannotBeReused() = runBlocking {
        val base=repo.range("2026-10-27","2026-10-27").single()
        val first=ModificationPermit(owner,base,Amounts(1200,200,300,400),base.currencies,true)
        repo.modify(first)
        try { repo.modify(first);fail() } catch(e:UserOperationException) { assertEquals(UserError.UNCONFIRMED,e.reason) }
        try { repo.modify(ModificationPermit(owner,base,Amounts(1300,200,300,400),base.currencies,true));fail() }
        catch(e:UserOperationException) { assertEquals(UserError.RECORD_CHANGED,e.reason) }
        assertEquals(1200L,repo.range(base.date,base.date).single().breakfast)
    }
    @Test fun concurrentRelativeSnapshotsNeverOverwriteEachOtherSilently() = runBlocking {
        val base=repo.range("2026-10-27","2026-10-27").single()
        val results=coroutineScope { listOf(1200L,1300L).map { amount -> async(Dispatchers.Default) {
            try { repo.modify(ModificationPermit(owner,base,Amounts(amount,200,300,400),base.currencies,true));true }
            catch(e:UserOperationException) { assertEquals(UserError.RECORD_CHANGED,e.reason);false }
        } }.awaitAll() }
        assertEquals(1,results.count { it })
        assertTrue(repo.range(base.date,base.date).single().breakfast in listOf(1200L,1300L))
    }
}
