package com.vpnbox.util

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ConfigEncryptor {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH = 128
    private const val KEY_SIZE = 32

    fun encrypt(data: String, key: String): String {
        val secretKey = SecretKeySpec(key.toByteArray().copyOf(KEY_SIZE), "AES")
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        
        val iv = cipher.iv
        val encrypted = cipher.doFinal(data.toByteArray())
        
        val combined = iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(encryptedData: String, key: String): String {
        val combined = Base64.decode(encryptedData, Base64.NO_WRAP)
        val secretKey = SecretKeySpec(key.toByteArray().copyOf(KEY_SIZE), "AES")
        
        val iv = combined.copyOfRange(0, 12)
        val ciphertext = combined.copyOfRange(12, combined.size)
        
        val cipher = Cipher.getInstance(ALGORITHM)
        val spec = GCMParameterSpec(TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        
        val decrypted = cipher.doFinal(ciphertext)
        return String(decrypted)
    }

    fun generateKey(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..KEY_SIZE).map { chars.random() }.joinToString("")
    }
}
