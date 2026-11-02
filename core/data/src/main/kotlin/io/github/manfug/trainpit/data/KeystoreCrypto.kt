package io.github.manfug.trainpit.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.manfug.trainpit.model.DeviceKey
import io.github.manfug.trainpit.model.Entry
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun fingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

/** Keys never leave Android Keystore; losing them is an error, never an implicit reset. */
internal class KeystoreCrypto(uid: String, create: Boolean) {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val namespace = "trainpit.${fingerprint(uid.toByteArray(Charsets.UTF_8))}"
    private val aesAlias = "$namespace.data.v1"
    private val signingAlias = "$namespace.sign.v1"
    private val aes: SecretKey
    private val signing: PrivateKey
    val device: DeviceKey

    init {
        if (!store.containsAlias(aesAlias)) {
            check(create) { "The local encryption key is missing. Recover records from the cloud or an encrypted archive on a new installation; existing local data cannot be reset automatically." }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(aesAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                generateKey()
            }
        }
        if (!store.containsAlias(signingAlias)) {
            check(create) { "The local signing key is missing. Register a new device before recovering history; keys are never silently replaced." }
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(signingAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
                generateKeyPair()
            }
        }
        aes = store.getKey(aesAlias, null) as SecretKey
        signing = store.getKey(signingAlias, null) as PrivateKey
        val publicBytes = store.getCertificate(signingAlias).publicKey.encoded
        device = DeviceKey(uid, fingerprint(publicBytes), Base64.getEncoder().encodeToString(publicBytes))
    }

    fun encrypt(label: String, bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aes)
        cipher.updateAAD(label.toByteArray(Charsets.UTF_8))
        return cipher.iv + cipher.doFinal(bytes)
    }

    fun decrypt(label: String, bytes: ByteArray): ByteArray {
        require(bytes.size >= 28) { "Encrypted local data is truncated. Do not erase the database; recover a verified copy." }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, aes, GCMParameterSpec(128, bytes, 0, 12))
            cipher.updateAAD(label.toByteArray(Charsets.UTF_8))
            cipher.doFinal(bytes, 12, bytes.size - 12)
        } catch (e: Exception) {
            throw IllegalStateException("Local data could not be authenticated. Keep the database and recover a verified copy; it has not been reset.", e)
        }
    }

    fun sign(entry: Entry): Entry {
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(signing)
            update(entry.signingBytes())
        }.sign()
        return entry.copy(signature = Base64.getEncoder().encodeToString(signature))
    }

    /** Only explicit device-data erasure may destroy these otherwise stable keys. */
    fun destroyKeys() {
        var failure: Exception? = null
        for (alias in listOf(aesAlias, signingAlias)) {
            try {
                if (store.containsAlias(alias)) store.deleteEntry(alias)
            } catch (error: Exception) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw IllegalStateException("Android could not remove this account's device keys. This account remains blocked; uninstall TrainPit to finish removing this device's data.", it) }
    }
}
