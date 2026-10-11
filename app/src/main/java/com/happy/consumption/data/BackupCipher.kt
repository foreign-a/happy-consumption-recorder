package com.happy.consumption.data

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// 文件格式固定版本、salt 与 IV；认证覆盖头部，篡改和错误密码均拒绝。
object BackupCipher {
    private val magic = "HCRB0001".toByteArray(Charsets.US_ASCII)
    const val MAX_FILE_BYTES = 64 * 1024 * 1024
    private const val HEADER_BYTES = 36
    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        require(password.size >= 8)
        val spec = PBEKeySpec(password, salt, 210_000, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }
    fun encrypt(payload: ByteArray, password: CharArray): ByteArray {
        require(payload.size <= MAX_FILE_BYTES - HEADER_BYTES - 16)
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val header = magic + salt + iv
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        return header + cipher.doFinal(payload)
    }
    fun decrypt(file: ByteArray, password: CharArray): ByteArray {
        require(file.size in (HEADER_BYTES + 16)..MAX_FILE_BYTES && file.take(8).toByteArray().contentEquals(magic))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, file.copyOfRange(8, 24)), GCMParameterSpec(128, file.copyOfRange(24, 36)))
        cipher.updateAAD(file.copyOfRange(0, HEADER_BYTES))
        return cipher.doFinal(file, HEADER_BYTES, file.size - HEADER_BYTES)
    }
}
