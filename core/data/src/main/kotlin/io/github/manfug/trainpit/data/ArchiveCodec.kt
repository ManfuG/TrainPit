package io.github.manfug.trainpit.data

import io.github.manfug.trainpit.model.ArchiveManifest
import io.github.manfug.trainpit.model.TrainPitJson
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/** TPARCH01 | iterations:u32be | salt:16 | nonce:12 | AES-GCM(JSON):n+16. Header is AAD. */
internal object ArchiveCodec {
    const val MAX_BYTES = 32 * 1024 * 1024
    const val MAX_ENTRIES = 50_000
    private const val ITERATIONS = 600_000
    private const val HEADER_SIZE = 40
    private val magic = "TPARCH01".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()

    fun encode(manifest: ArchiveManifest, password: String): ByteArray {
        require(password.length >= 12) { "Use an archive passphrase of at least 12 characters." }
        require(manifest.entries.size <= MAX_ENTRIES && manifest.devices.size <= MAX_ENTRIES) { "The archive exceeds 50,000 revisions or device keys. Export fewer sessions." }
        val clear = TrainPitJson.encodeToString(manifest).toByteArray(Charsets.UTF_8)
        require(clear.size <= MAX_BYTES - HEADER_SIZE - 16) { "The archive exceeds 32 MiB. Export fewer sessions." }
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_SIZE).put(magic).putInt(ITERATIONS).put(salt).put(iv).array()
        val key = derive(password, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            return header + cipher.doFinal(clear)
        } finally { key.fill(0); clear.fill(0) }
    }

    fun decode(bytes: ByteArray, password: String): ArchiveManifest {
        require(password.length >= 12) { "Enter the original archive passphrase (at least 12 characters)." }
        require(bytes.size in (HEADER_SIZE + 16)..MAX_BYTES) { "The archive is truncated or larger than 32 MiB." }
        val buffer = ByteBuffer.wrap(bytes)
        val foundMagic = ByteArray(8).also(buffer::get)
        require(foundMagic.contentEquals(magic)) { "Unsupported archive format. Choose a TrainPit version 1 archive." }
        require(buffer.int == ITERATIONS) { "Unsupported archive key derivation settings." }
        val salt = ByteArray(16).also(buffer::get)
        val iv = ByteArray(12).also(buffer::get)
        val key = derive(password, salt)
        val clear = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(bytes, 0, HEADER_SIZE)
            cipher.doFinal(bytes, HEADER_SIZE, bytes.size - HEADER_SIZE)
        } catch (e: Exception) {
            throw IllegalArgumentException("Archive authentication failed. Check the passphrase and obtain an intact file; nothing was imported.", e)
        } finally { key.fill(0) }
        try {
            // Reject excessive nesting before the JSON parser allocates a tree or recursive model.
            var depth = 0
            var quoted = false
            var escaped = false
            clear.forEach { byte ->
                val c = byte.toInt().toChar()
                if (quoted) {
                    if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
                } else when (c) {
                    '"' -> quoted = true
                    '{', '[' -> { depth++; require(depth <= 32) { "Archive nesting exceeds the supported limit." } }
                    '}', ']' -> depth--
                }
            }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(clear)).toString()
            val manifest = try { TrainPitJson.decodeFromString<ArchiveManifest>(text) }
                catch (e: Exception) { throw IllegalArgumentException("The authenticated archive contains invalid or unsupported records.", e) }
            require(manifest.format == 1) { "Unsupported archive manifest version." }
            require(manifest.entries.size <= MAX_ENTRIES && manifest.devices.size <= MAX_ENTRIES) { "The archive exceeds 50,000 revisions or device keys." }
            return manifest
        } finally { clear.fill(0) }
    }

    private fun derive(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
}
