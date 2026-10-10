package com.happy.consumption.data

import java.security.MessageDigest
import java.security.SecureRandom

object PasswordHasher {
    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
    fun hashPassword(password: String, salt: ByteArray): ByteArray {
        require(salt.size == 16) { "Invalid salt length" }
        return MessageDigest.getInstance("SHA-256").run {
            update(salt)
            digest(password.toByteArray(Charsets.UTF_8))
        }
    }
    fun verify(password: String, salt: ByteArray, expected: ByteArray): Boolean =
        MessageDigest.isEqual(hashPassword(password, salt), expected)
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun fromHex(text: String): ByteArray {
        require(text.length % 2 == 0 && text.all { it in "0123456789abcdef" }) { "Invalid credential encoding" }
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
