package com.happy.consumption.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

internal data class BackupAccount(val credential: Credential, val currency: MoneyUnit, val notes: List<NoteEntity>)
internal object BackupDocument {
    fun encode(accounts: List<BackupAccount>): String = JSONObject().put("format", "happy-consumption-backup")
        .put("version", 1).put("accounts", JSONArray().apply {
            accounts.forEach { account -> put(account.credential.json().put("currency", account.currency.name)
                .put("notes", JSONArray().apply { account.notes.forEach { note -> put(JSONObject()
                    .put("date", note.date).put("breakfast", note.breakfast).put("lunch", note.lunch)
                    .put("dinner", note.dinner).put("other", note.other).put("isManual", note.isManual)
                    .put("updatedAt", note.updatedAt)) } })) }
        }).toString()

    fun decode(text: String): List<BackupAccount> {
        try {
            val root = JSONObject(text)
            require(root.getString("format") == "happy-consumption-backup" && root.getInt("version") == 1)
            val values = root.getJSONArray("accounts")
            val names = mutableSetOf<String>(); val ids = mutableSetOf<Long>()
            fun integer(json: JSONObject, field: String): Long {
                require(json.get(field) is Int || json.get(field) is Long)
                return json.getLong(field)
            }
            return List(values.length()) { index ->
                val account = values.getJSONObject(index)
                val credential = Credential.read(account)
                require(credential.id > 0 && credential.createdAt >= 0 && credential.username.isNotBlank()
                    && credential.username == credential.username.trim() && names.add(credential.username) && ids.add(credential.id))
                require(PasswordHasher.fromHex(credential.salt).size == 16 && PasswordHasher.fromHex(credential.passwordHash).size == 32)
                val code = account.optString("currency", MoneyUnit.YUAN.name)
                require(MoneyUnit.entries.any { it.name == code })
                val rows = account.getJSONArray("notes"); val dates = mutableSetOf<String>()
                val notes = List(rows.length()) { row ->
                    val note = rows.getJSONObject(row); val date = note.getString("date")
                    require(LocalDate.parse(date).toString() == date && dates.add(date))
                    val amounts = Amounts(integer(note, "breakfast"), integer(note, "lunch"), integer(note, "dinner"), integer(note, "other"))
                    amounts.validate(); val updatedAt = integer(note, "updatedAt"); require(updatedAt >= 0)
                    require(note.get("isManual") is Boolean)
                    NoteEntity(userId = credential.id, date = date, breakfast = amounts.breakfast, lunch = amounts.lunch,
                        dinner = amounts.dinner, other = amounts.other, isManual = note.getBoolean("isManual"), updatedAt = updatedAt)
                }
                BackupAccount(credential, MoneyUnit.stored(code), notes)
            }
        } catch (_: Exception) { throw UserOperationException(UserError.BACKUP_INVALID) }
    }
}
