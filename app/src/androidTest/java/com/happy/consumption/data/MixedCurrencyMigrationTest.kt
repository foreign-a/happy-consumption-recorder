package com.happy.consumption.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MixedCurrencyMigrationTest {
    private suspend fun oldDatabase(default: MoneyUnit?) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="migration-${System.nanoTime()}.db"
        val root=File(context.cacheDir,"migration-${System.nanoTime()}").apply { mkdirs() }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val store=PreferenceDataStoreFactory.create(scope=scope,produceFile={ File(root,"users.preferences_pb") })
        val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("schema-v1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val old=context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null)
        val entities=schema.getJSONArray("entities")
        for(i in 0 until entities.length()) {
            val entity=entities.getJSONObject(i);val table=entity.getString("tableName")
            old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
            val indices=entity.optJSONArray("indices") ?: JSONArray()
            for(j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
        }
        val queries=schema.getJSONArray("setupQueries")
        for(i in 0 until queries.length()) old.execSQL(queries.getString(i))
        old.execSQL("INSERT INTO user_identities(id) VALUES(7)")
        old.execSQL("INSERT INTO notes(id,userId,date,breakfast,lunch,dinner,other,isManual,updatedAt) VALUES(41,7,'2024-02-29',900,0,0,500,1,123456)")
        old.version=1;old.close()
        val salt=PasswordHasher.newSalt()
        val credential=Credential(7,"Legacy",PasswordHasher.hex(salt),PasswordHasher.hex(PasswordHasher.hashPassword("legacy-test-only",salt)),1)
        store.edit {
            it[stringPreferencesKey("users_json")]=JSONArray().put(credential.json()).toString()
            it[longPreferencesKey("current_user_id")]=7;it[longPreferencesKey("next_user_id")]=8
            if(default!=null) it[stringPreferencesKey("currency_7")]=default.name
        }
        val before=store.data.first()
        val db=Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(MIGRATION_1_2).build()
        try {
            val manager=UserManager(store,db,root);manager.initialize()
            val row=db.notes().queryByIdAndUser(41,7)!!
            assertEquals(900L,row.breakfast);assertEquals(500L,row.other);assertEquals(123456L,row.updatedAt);assertTrue(row.isManual)
            assertEquals(EntryCurrencies.uniform(default ?: MoneyUnit.YUAN),row.toItem().currencies)
            assertEquals(before,store.data.first())
            val owner=manager.session.value!!
            manager.selectCurrency(owner,MoneyUnit.POUND)
            assertEquals(row,db.notes().queryByIdAndUser(41,7))
            UserRepository(manager,db,owner).add("2024-03-01",Amounts(0,0,0,0))
            assertEquals(EntryCurrencies.uniform(MoneyUnit.POUND),db.notes().queryByUserAndDate(7,"2024-03-01")!!.toItem().currencies)
            manager.login("Legacy","legacy-test-only");assertEquals(7L,manager.session.value!!.id)
        } finally { scope.cancel();db.close();context.deleteDatabase(name);root.deleteRecursively() }
    }
    @Test fun version11CurrencyAndAllOldFieldsSurviveMigration() = runBlocking { oldDatabase(MoneyUnit.DOLLAR) }
    @Test fun version10WithoutCurrencyMigratesToYuan() = runBlocking { oldDatabase(null) }
    @Test fun oldBackupAndNewMixedBackupRemainReadable() {
        val salt=PasswordHasher.newSalt()
        val credential=Credential(1,"Fixture",PasswordHasher.hex(salt),PasswordHasher.hex(PasswordHasher.hashPassword("fixture-only",salt)),1)
        val note=NoteEntity(userId=1,date="2024-02-29",breakfast=900,lunch=0,dinner=0,other=500,isManual=true,updatedAt=99)
            .withCurrencies(EntryCurrencies(MoneyUnit.YUAN,MoneyUnit.YUAN,MoneyUnit.YUAN,MoneyUnit.DOLLAR))
        val encoded=BackupDocument.encode(listOf(BackupAccount(credential,MoneyUnit.POUND,listOf(note))))
        val restored=BackupDocument.decode(encoded).single()
        assertEquals(note,restored.notes.single());assertEquals("¥9.00 + $5.00",restored.notes.single().toItem().totals())
        val old=JSONObject(encoded).put("version",1)
        val legacyNote=old.getJSONArray("accounts").getJSONObject(0).getJSONArray("notes").getJSONObject(0)
        for(field in listOf("breakfastCurrency","lunchCurrency","dinnerCurrency","otherCurrency")) legacyNote.remove(field)
        assertEquals(EntryCurrencies.uniform(MoneyUnit.POUND),BackupDocument.decode(old.toString()).single().notes.single().toItem().currencies)
        val corrupted=JSONObject(encoded)
        corrupted.getJSONArray("accounts").getJSONObject(0).getJSONArray("notes").getJSONObject(0).put("otherCurrency","INVALID")
        try { BackupDocument.decode(corrupted.toString());fail() } catch(e:UserOperationException) { assertEquals(UserError.BACKUP_INVALID,e.reason) }
    }
}
