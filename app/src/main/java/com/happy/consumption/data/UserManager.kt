package com.happy.consumption.data

import com.happy.consumption.util.LogHelper

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class UserSession(val id: Long, val username: String, val generation: Long)
enum class UserError { EMPTY_NAME, SHORT_PASSWORD, DUPLICATE_NAME, INVALID_LOGIN, INVALID_PASSWORD, NO_SESSION, STORAGE, DATE_EXISTS, INVALID_AMOUNT, INVALID_DATE, RECORD_MISSING, UNCONFIRMED, BACKUP_INVALID, BACKUP_CONFLICT, RECORD_CHANGED, INVALID_ADJUSTMENT }
class UserOperationException(val reason: UserError) : Exception(reason.name)
class AccessDeniedException : Exception("Access denied")

internal data class Credential(val id: Long, val username: String, val salt: String, val passwordHash: String, val createdAt: Long) {
    fun json() = JSONObject().put("id", id).put("username", username).put("salt", salt)
        .put("passwordHash", passwordHash).put("createdAt", createdAt)
    companion object {
        fun read(json: JSONObject) = Credential(json.getLong("id"), json.getString("username"),
            json.getString("salt"), json.getString("passwordHash"), json.getLong("createdAt"))
    }
}

class UserManager(private val store: DataStore<Preferences>, private val database: AppDatabase, private val filesRoot: File) {
    private val mutex = Mutex()
    private var generation = 0L
    private val sessionState = MutableStateFlow<UserSession?>(null)
    val session = sessionState.asStateFlow()
    private val usersKey = stringPreferencesKey("users_json")
    private val currentKey = longPreferencesKey("current_user_id")
    private val nextKey = longPreferencesKey("next_user_id")
    private val pendingDeleteKey = longPreferencesKey("pending_delete_user_id")
    private val pendingRegisterKey = stringPreferencesKey("pending_registration_json")
    private val pendingImportKey = stringPreferencesKey("pending_import_json")
    private fun currencyKey(id: Long) = stringPreferencesKey("currency_$id")
    internal suspend fun defaultCurrency(id: Long) = MoneyUnit.stored(store.data.first()[currencyKey(id)])
    fun currencyFor(owner: UserSession): Flow<MoneyUnit> = store.data.map { MoneyUnit.stored(it[currencyKey(owner.id)]) }.distinctUntilChanged()
    suspend fun selectCurrency(owner: UserSession, currency: MoneyUnit) = withCurrentUser(owner) { id ->
        store.edit { it[currencyKey(id)] = currency.name }; Unit
    }

    private fun rejectAccess(): Nothing { LogHelper.info("Security", "Rejected stale or unauthorized session"); throw AccessDeniedException() }
    private fun users(prefs: Preferences): List<Credential> {
        val array = JSONArray(prefs[usersKey] ?: "[]")
        return List(array.length()) { Credential.read(array.getJSONObject(it)) }
    }
    private fun serialize(users: List<Credential>) = JSONArray().apply { users.forEach { put(it.json()) } }.toString()
    private fun directory(id: Long): File {
        check(id > 0)
        return File(filesRoot, "users/$id")
    }
    private suspend fun recoverLocked() {
        var prefs = store.data.first()
        prefs[pendingImportKey]?.let { finishImportLocked(it) }
        prefs = store.data.first()
        prefs[pendingDeleteKey]?.let { finishDeletionLocked(it) }
        prefs = store.data.first()
        prefs[pendingRegisterKey]?.let { encoded ->
            val user = Credential.read(JSONObject(encoded))
            database.identities().insert(UserIdentity(user.id))
            val dir = directory(user.id)
            if (!dir.isDirectory && !dir.mkdirs()) throw UserOperationException(UserError.STORAGE)
            store.edit { updated ->
                val existing = users(updated)
                updated[usersKey] = serialize(if (existing.any { it.id == user.id }) existing else existing + user)
                updated.remove(pendingRegisterKey)
            }
        }
    }
    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            recoverLocked()
            val prefs = store.data.first()
            val accounts = users(prefs)
            accounts.forEach {
                database.identities().insert(UserIdentity(it.id))
                database.notes().fillLegacyCurrencies(it.id, MoneyUnit.stored(prefs[currencyKey(it.id)]).name)
                val dir = directory(it.id)
                if (!dir.isDirectory && !dir.mkdirs()) throw UserOperationException(UserError.STORAGE)
            }
            val active = accounts.find { it.id == prefs[currentKey] }
            if (active == null) store.edit { it.remove(currentKey) }
            sessionState.value = active?.let { UserSession(it.id, it.username, ++generation) }
        }
    }
    suspend fun exportBackup(owner: UserSession): ByteArray = withCurrentUser(owner) { id ->
            val prefs = store.data.first()
            val accounts = database.withTransaction {
                users(prefs).filter { it.id == id }.map { credential ->
                    val rows = buildList {
                        var offset = 0
                        do {
                            val page = database.notes().queryByUserPaged(credential.id, 200, offset)
                            addAll(page); offset += page.size
                        } while (page.size == 200)
                    }
                    BackupAccount(credential, MoneyUnit.stored(prefs[currencyKey(credential.id)]), rows)
                }
            }
            BackupDocument.encode(accounts).toByteArray(Charsets.UTF_8)
    }
    suspend fun importBackup(payload: ByteArray) = withContext(Dispatchers.IO) {
        val accounts = BackupDocument.decode(payload.toString(Charsets.UTF_8))
        mutex.withLock {
            recoverLocked()
            val prefs = store.data.first(); val existing = users(prefs)
            if (accounts.any { imported -> existing.any { it.username == imported.credential.username } })
                throw UserOperationException(UserError.BACKUP_CONFLICT)
            var next = prefs[nextKey] ?: 1L
            val remapped = accounts.map { account ->
                val id = next; next = Math.addExact(next, 1)
                account.copy(credential = account.credential.copy(id = id), notes = account.notes.map { it.copy(userId = id) })
            }
            // 已验证的导入计划先持久化，跨 Room / DataStore 中断可重复恢复。
            store.edit { it[nextKey] = next; it[pendingImportKey] = BackupDocument.encode(remapped) }
            recoverLocked()
            LogHelper.info("Backup", "Import result=success")
        }
    }
    private suspend fun finishImportLocked(encoded: String) {
        val accounts = BackupDocument.decode(encoded)
        database.withTransaction {
            accounts.forEach { account ->
                database.identities().insert(UserIdentity(account.credential.id))
                account.notes.forEach { database.notes().insertIfAbsent(it) }
            }
        }
        accounts.forEach { account ->
            val dir = directory(account.credential.id)
            if (!dir.isDirectory && !dir.mkdirs()) throw UserOperationException(UserError.STORAGE)
        }
        store.edit { prefs ->
            val existing = users(prefs)
            prefs[usersKey] = serialize(existing + accounts.map { it.credential }.filter { candidate -> existing.none { it.id == candidate.id } })
            accounts.forEach { prefs[currencyKey(it.credential.id)] = it.currency.name }
            prefs.remove(pendingImportKey)
        }
    }
    suspend fun register(username: String, password: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            recoverLocked()
            val name = username.trim()
            if (name.isEmpty()) throw UserOperationException(UserError.EMPTY_NAME)
            if (password.length < 4) throw UserOperationException(UserError.SHORT_PASSWORD)
            val prefs = store.data.first()
            if (users(prefs).any { it.username == name }) throw UserOperationException(UserError.DUPLICATE_NAME)
            val id = prefs[nextKey] ?: 1L
            val salt = PasswordHasher.newSalt()
            val account = Credential(id, name, PasswordHasher.hex(salt), PasswordHasher.hex(PasswordHasher.hashPassword(password, salt)), System.currentTimeMillis())
            // 先持久化可恢复操作，仅包含哈希；进程中断后继续完成身份表与目录。
            store.edit { it[nextKey] = Math.addExact(id, 1); it[pendingRegisterKey] = account.json().toString() }
            recoverLocked()
        }
    }
    suspend fun login(username: String, password: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            recoverLocked()
            val user = users(store.data.first()).find { it.username == username.trim() }
                ?: throw UserOperationException(UserError.INVALID_LOGIN)
            if (!PasswordHasher.verify(password, PasswordHasher.fromHex(user.salt), PasswordHasher.fromHex(user.passwordHash)))
                throw UserOperationException(UserError.INVALID_LOGIN)
            store.edit { it[currentKey] = user.id }
            sessionState.value = UserSession(user.id, user.username, ++generation)
            LogHelper.info("Account", "Login userId=${user.id} result=success")
        }
    }
    fun getCurrentUserId(): Flow<Long?> = session.map { it?.id }.distinctUntilChanged()
    internal suspend fun findUserId(username: String): Long? = withContext(Dispatchers.IO) {
        mutex.withLock { users(store.data.first()).find { it.username == username }?.id }
    }
    suspend fun logout() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val oldId = sessionState.value?.id
            store.edit { it.remove(currentKey) }
            sessionState.value = null
            LogHelper.info("Account", "Logout userId=$oldId result=success")
        }
    }
    suspend fun <T> withCurrentUser(expected: UserSession, action: suspend (Long) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = sessionState.value
            if (current == null || current != expected) rejectAccess()
            val persisted = store.data.first()
            if (persisted[currentKey] != current.id || users(persisted).none { it.id == current.id }) rejectAccess()
            action(current.id)
        }
    }
    suspend fun deleteCurrentUser(expected: UserSession, password: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (sessionState.value != expected) rejectAccess()
            val prefs = store.data.first()
            if (prefs[currentKey] != expected.id) rejectAccess()
            val account = users(prefs).find { it.id == expected.id } ?: rejectAccess()
            if (password.isEmpty() || !PasswordHasher.verify(password, PasswordHasher.fromHex(account.salt), PasswordHasher.fromHex(account.passwordHash)))
                { LogHelper.info("Security", "Delete verification rejected userId=${expected.id}"); throw UserOperationException(UserError.INVALID_PASSWORD) }
            val id = expected.id
            store.edit { it[pendingDeleteKey] = id; it.remove(currentKey) }
            sessionState.value = null
            finishDeletionLocked(id)
            LogHelper.info("Account", "Delete userId=$id result=success")
        }
    }
    private suspend fun finishDeletionLocked(id: Long) {
        database.withTransaction {
            database.notes().deleteByUser(id)
            database.identities().deleteByUser(id)
        }
        val dir = directory(id)
        if (dir.exists() && !dir.deleteRecursively()) throw UserOperationException(UserError.STORAGE)
        store.edit { prefs ->
            prefs[usersKey] = serialize(users(prefs).filterNot { it.id == id })
            if (prefs[currentKey] == id) prefs.remove(currentKey)
            prefs.remove(pendingDeleteKey)
            prefs.remove(currencyKey(id))
        }
    }
}
