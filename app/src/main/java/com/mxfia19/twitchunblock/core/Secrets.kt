package com.mxfia19.twitchunblock.core

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Jetons du compte (OAuth, renouvellement, cookie « auth-token » de la
 * session web) chiffrés dans les préférences par une clé AES du Keystore
 * d'Android (AES/GCM) : la clé ne quitte pas l'appareil, une copie des
 * préférences ne livre pas les jetons. Valeur gardée : « v1: » + base64 de
 * (taille de l'IV, IV, chiffré). Une valeur encore en clair (versions
 * d'avant) est chiffrée à sa place au lancement ; une valeur qui ne se
 * déchiffre plus (clé perdue) compte comme absente.
 */
object Secrets {
    private const val ALIAS = "tu_tokens"
    private const val PREFIX = "v1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private var cached: SecretKey? = null

    @Synchronized
    private fun key(): SecretKey {
        cached?.let { return it }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(ALIAS, null) as? SecretKey ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generator.generateKey()
        }
        cached = key
        return key
    }

    /** Chiffré ; null si le Keystore refuse (certains appareils). */
    private fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val sealed = byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.encodeToString(sealed, Base64.NO_WRAP)
    } catch (e: Exception) {
        Logger.warn("SECRETS", "Chiffrement impossible", e.javaClass.simpleName)
        null
    }

    private fun decrypt(stored: String): String? = try {
        val sealed = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
        val ivSize = sealed[0].toInt()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 1, ivSize))
        String(cipher.doFinal(sealed, 1 + ivSize, sealed.size - 1 - ivSize), Charsets.UTF_8)
    } catch (e: Exception) {
        Logger.warn("SECRETS", "Jeton illisible, ignoré", e.javaClass.simpleName)
        null
    }

    /** Jeton gardé sous `name`, en clair pour l'app ; null s'il manque ou
     *  ne se déchiffre plus. */
    fun read(sp: SharedPreferences, name: String): String? {
        val stored = sp.getString(name, null) ?: return null
        return if (stored.startsWith(PREFIX)) decrypt(stored) else stored
    }

    /** Garde un jeton chiffré (en clair si le Keystore refuse : la session
     *  n'est jamais perdue). */
    fun put(editor: SharedPreferences.Editor, name: String, value: String): SharedPreferences.Editor =
        editor.putString(name, encrypt(value) ?: value)

    /** Valeurs encore en clair (versions d'avant) : lues, chiffrées et
     *  réécrites à leur place, le texte en clair disparaît. Laissées telles
     *  quelles si le Keystore refuse (jamais de déconnexion). */
    fun migrate(sp: SharedPreferences, vararg names: String) {
        val editor = sp.edit()
        val done = mutableListOf<String>()
        for (name in names) {
            val stored = sp.getString(name, null) ?: continue
            if (stored.startsWith(PREFIX)) continue
            val sealed = encrypt(stored) ?: continue
            editor.putString(name, sealed)
            done += name
        }
        if (done.isNotEmpty()) {
            editor.apply()
            Logger.info("SECRETS", "Jetons chiffrés", done.joinToString())
        }
    }
}
