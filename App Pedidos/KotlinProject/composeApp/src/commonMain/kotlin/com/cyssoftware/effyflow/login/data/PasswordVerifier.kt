package com.cyssoftware.effyflow.login.data

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Standard JVM KDF; generated only on this device. */
object PasswordVerifier {
    const val ITERATIONS = 600_000
    data class Encoded(val salt: String, val verifier: String, val iterations: Int)
    fun create(password: String): Encoded {
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Encoded(hex(salt), hex(derive(password, salt, ITERATIONS)), ITERATIONS)
    }
    fun matches(password: String, salt: String, verifier: String, iterations: Int): Boolean = runCatching {
        require(iterations == ITERATIONS)
        val saltBytes = decode(salt); val expected = decode(verifier)
        require(saltBytes.size == 32 && expected.size == 32)
        MessageDigest.isEqual(expected, derive(password, saltBytes, iterations))
    }.getOrDefault(false)
    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val chars = password.toCharArray(); val spec = PBEKeySpec(chars, salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword(); chars.fill('\u0000') }
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun decode(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { value.substring(it*2, it*2+2).toInt(16).toByte() }
    }
}
