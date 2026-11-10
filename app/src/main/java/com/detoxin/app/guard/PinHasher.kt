package com.detoxin.app.guard

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Хэш мастер-PIN: PBKDF2-HMAC-SHA256 со случайной солью. Сам PIN нигде не хранится.
 * Чистый JVM-код без Android-зависимостей. Вычисление занимает десятки миллисекунд —
 * вызывать вне главного потока.
 */
object PinHasher {

    const val ITERATIONS = 120_000
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 12

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    fun isValidPin(pin: String): Boolean = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    fun hash(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Сравнение за постоянное время. */
    fun verify(pin: String, salt: ByteArray, iterations: Int, expected: ByteArray): Boolean =
        MessageDigest.isEqual(hash(pin, salt, iterations), expected)

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
