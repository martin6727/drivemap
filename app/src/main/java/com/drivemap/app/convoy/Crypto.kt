package com.drivemap.app.convoy

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Convoy codes and end-to-end encryption. The group code is the only secret: the AES-256 key is
 * derived from it with PBKDF2 and the broker topic uses a separate hash, so the relay never sees
 * the code, positions or names.
 */
object ConvoyCrypto {
    /** Crockford base32 without I, L, O, U (easy to read out loud). */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val rnd = SecureRandom()

    fun newCode(): String = (1..12).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString("")

    fun normalize(input: String): String = input.uppercase()
        .replace('O', '0').replace('I', '1').replace('L', '1')
        .filter { it in ALPHABET }

    fun isValid(code: String) = normalize(code).length == 12

    fun pretty(code: String): String = normalize(code).chunked(4).joinToString("-")

    fun deriveKey(code: String): SecretKey {
        val spec = PBEKeySpec(normalize(code).toCharArray(), "drivemap.convoy.v1".toByteArray(), 20_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    fun topicId(code: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(("drivemap.topic.v1:" + normalize(code)).toByteArray())
        return d.joinToString("") { "%02x".format(it) }.take(24)
    }

    /** Key for the shared (all users) radar report channel. Not secret – it just keeps out noise. */
    val publicKey: SecretKey by lazy {
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("drivemap.public.reports.v1".toByteArray()), "AES")
    }

    fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    fun decrypt(key: SecretKey, data: ByteArray): ByteArray? {
        if (data.size < 13 + 16) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12))
            c.doFinal(data, 12, data.size - 12)
        } catch (_: Exception) {
            null
        }
    }
}
