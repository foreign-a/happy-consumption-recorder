package com.happy.consumption.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

internal data class BackupAccount(val credential: Credential, val currency: MoneyUnit, val notes: List<NoteEntity>,
    val entries: List<LedgerEntity> = emptyList(), val remarks: List<CustomRemarkEntity> = emptyList())
internal object BackupDocument {
    fun encode(accounts: List<BackupAccount>): String = JSONObject().put("format", "happy-consumption-backup")
        .put("version", 3).put("accounts", JSONArray().apply {
            accounts.forEach { account -> put(account.credential.json().put("currency", account.currency.name)
                .put("notes", JSONArray().apply { account.notes.forEach { note -> put(JSONObject()
                    .put("date", note.date).put("breakfast", note.breakfast).put("lunch", note.lunch)
                    .put("dinner", note.dinner).put("other", note.other).put("isManual", note.isManual)
                    .put("updatedAt", note.updatedAt)
                    .put("breakfastCurrency", note.breakfastCurrency ?: account.currency.name).put("lunchCurrency", note.lunchCurrency ?: account.currency.name)
                    .put("dinnerCurrency", note.dinnerCurrency ?: account.currency.name).put("otherCurrency", note.otherCurrency ?: account.currency.name)) } })
                .put("entries", JSONArray().apply { account.entries.forEach { entry -> put(JSONObject()
                    .put("requestId", entry.requestId).put("date", entry.date).put("kind", entry.kind)
                    .put("amount", entry.amount).put("currency", entry.currency).put("remark", entry.remark).put("isOtherIncome", entry.isOtherIncome)) } })
                .put("remarks", JSONArray().apply { account.remarks.forEach { remark -> put(JSONObject()
                    .put("kind", remark.kind).put("name", remark.name)) } })) }
        }).toString()

    fun decode(text: String): List<BackupAccount> {
        try {
            val root = JSONObject(text)
            require(root.getString("format") == "happy-consumption-backup" && root.getInt("version") in 1..3)
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
                    val note = rows.getJSONObject(row)
                    fun currency(field: String): String {
                        val value = if (root.getInt("version") == 1) code else note.getString(field)
                        require(MoneyUnit.entries.any { it.name == value }); return value
                    }
                    val date = note.getString("date")
                    require(LocalDate.parse(date).toString() == date && dates.add(date))
                    val amounts = Amounts(integer(note, "breakfast"), integer(note, "lunch"), integer(note, "dinner"), integer(note, "other"))
                    amounts.validate(); val updatedAt = integer(note, "updatedAt"); require(updatedAt >= 0)
                    require(note.get("isManual") is Boolean)
                    NoteEntity(userId = credential.id, date = date, breakfast = amounts.breakfast, lunch = amounts.lunch,
                        dinner = amounts.dinner, other = amounts.other, isManual = note.getBoolean("isManual"), updatedAt = updatedAt, breakfastCurrency = currency("breakfastCurrency"),
                        lunchCurrency = currency("lunchCurrency"), dinnerCurrency = currency("dinnerCurrency"), otherCurrency = currency("otherCurrency"))
                }
                val entries = if (root.getInt("version") < 3) emptyList() else {
                    val values = account.getJSONArray("entries"); val requests = mutableSetOf<String>()
                    List(values.length()) { row ->
                        val entry = values.getJSONObject(row)
                        val request = entry.getString("requestId"); val date = entry.getString("date")
                        val kind = entry.getString("kind"); val currency = entry.getString("currency")
                        val remark = entry.getString("remark"); val amount = integer(entry, "amount")
                        require(entry.get("isOtherIncome") is Boolean)
                        val isOther = entry.getBoolean("isOtherIncome")
                        require(!isOther || kind == EntryKind.INCOME.name)
                        require(request.isNotBlank() && request.length <= 100 && requests.add(request))
                        require(LocalDate.parse(date).toString() == date && EntryKind.entries.any { it.name == kind })
                        require(MoneyUnit.entries.any { it.name == currency } && amount >= 0 && remark.isNotBlank() && remark.length <= 100)
                        LedgerEntity(userId = credential.id, requestId = request, date = date, kind = kind, amount = amount, currency = currency, remark = remark, isOtherIncome = isOther)
                    }
                }
                val remarks = if (root.getInt("version") < 3) emptyList() else {
                    val values = account.getJSONArray("remarks"); val unique = mutableSetOf<Pair<String, String>>()
                    List(values.length()) { row ->
                        val remark = values.getJSONObject(row); val kind = remark.getString("kind"); val name = remark.getString("name")
                        require(EntryKind.entries.any { it.name == kind } && name.isNotBlank() && name == name.trim() && name.length <= 100 && unique.add(kind to name))
                        CustomRemarkEntity(userId = credential.id, kind = kind, name = name)
                    }
                }
                BackupAccount(credential, MoneyUnit.stored(code), notes, entries, remarks)
            }
        } catch (_: Exception) { throw UserOperationException(UserError.BACKUP_INVALID) }
    }
}
