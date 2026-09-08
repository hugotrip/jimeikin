package com.wanderwildwood.jimeikin.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The two things this app holds that are worth stealing: a music server's password, and a
 * YouTube account cookie.
 *
 * Both used to sit in the app's preferences file as plain text. On an unrooted phone nothing
 * else can read that file, so the everyday case was already safe — but the app allows backup,
 * and a backup is exactly the thing that carries an app's private files off the phone. A
 * password that leaves the phone in a backup has stopped being kept on the phone, which is
 * what the About screen promises.
 *
 * So they are sealed with a key generated inside the phone's hardware-backed keystore, which
 * is not exportable and is not itself backed up. The stored value is
 * `enc:v1:base64(iv + ciphertext)`. A value without that prefix is one written by an older
 * version and is read as it stands, then rewritten sealed the next time it is saved.
 *
 * If the key is gone — a restore onto a different phone, a reinstall — unsealing fails and
 * returns nothing, and the app behaves as though the server was never set up and asks again.
 * That is the correct outcome: the alternative is a login that silently cannot work.
 */
object Secrets {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "jimeikin.secrets.v1"
    private const val PREFIX = "enc:v1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun seal(plain: String?): String? {
        if (plain.isNullOrEmpty()) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
        } catch (e: Exception) {
            // Better to store nothing than to store it in the clear after promising not to.
            android.util.Log.w("Secrets", "could not seal a secret", e)
            null
        }
    }

    fun open(stored: String?): String? {
        if (stored.isNullOrEmpty()) return null
        if (!stored.startsWith(PREFIX)) return stored          // written before this existed
        return try {
            val blob = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES),
            )
            String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.w("Secrets", "could not open a secret; treating it as absent", e)
            null
        }
    }

    /** True for a value this has already sealed, so a migration can tell what still needs it. */
    fun isSealed(stored: String?): Boolean = stored?.startsWith(PREFIX) == true

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Deliberately not requiring the screen to be unlocked: playback carries on
                // with the phone in a pocket, and a sync that failed whenever the screen was
                // locked would be worse than useless.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }
}
