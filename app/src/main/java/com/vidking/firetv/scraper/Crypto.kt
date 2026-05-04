package com.vidking.firetv.scraper

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Tiny AES-CBC helper used by VidZeeScraper. Backed by `javax.crypto` —
 * no third-party crypto dependency.
 */
internal object Crypto {

    /**
     * AES-256-CBC with PKCS5/PKCS7 padding. [key] must already be exactly
     * 32 bytes (callers are responsible for padding/truncation).
     */
    fun aesCbcDecrypt(cipherText: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key must be 32 bytes (got ${key.size})" }
        require(iv.size == 16) { "AES IV must be 16 bytes (got ${iv.size})" }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(cipherText)
    }

    /**
     * Pads or truncates a UTF-8 string to exactly 32 bytes by appending NUL
     * bytes. Matches Node's `Buffer.from(key, 'utf-8')` extended to 32 bytes,
     * which is the convention several scraper backends use.
     */
    fun pad32(s: String): ByteArray {
        val raw = s.toByteArray(Charsets.UTF_8)
        return if (raw.size >= 32) raw.copyOf(32)
        else raw + ByteArray(32 - raw.size)
    }
}
