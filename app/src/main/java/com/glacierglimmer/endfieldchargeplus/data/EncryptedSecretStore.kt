// `EncryptedSharedPreferences` / `MasterKey` are deprecated in androidx.security:security-crypto 1.1.0
// but remain the API this project standardises on across API 26+; see the KDoc on openPreferences()
// for the rationale and the migration note. The suppression is file-wide because both the import and
// the call sites raise the same deprecation.
@file:Suppress("DEPRECATION")

package com.glacierglimmer.endfieldchargeplus.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog

/**
 * [SecretStore] backed by `EncryptedSharedPreferences` with an AES-256-GCM Android Keystore key.
 *
 * Hard rules:
 *  * the clear value is **never** written anywhere else — if the keystore cannot be used,
 *    [isAvailable] returns `false` and every operation degrades to a no-op (a plaintext fallback is
 *    deliberately not implemented);
 *  * every value that is written or read is registered with [AppLog.registerSecret], so a later log
 *    line cannot leak it.
 */
class EncryptedSecretStore(private val context: Context) : SecretStore {

    /** `null` when the keystore/EncryptedSharedPreferences is unusable on this device. */
    private val preferences: SharedPreferences? by lazy { openPreferences() }

    override fun isAvailable(): Boolean = preferences != null

    override fun put(key: String, value: String?) {
        AppLog.registerSecret(value)
        val prefs = preferences
        if (prefs == null) {
            degraded("store '$key'")
            return
        }
        runCatching {
            prefs.edit().apply {
                if (value.isNullOrEmpty()) remove(key) else putString(key, value)
            }.commit()
        }.onFailure { AppLog.e(TAG, "Could not store secret '$key'", it) }
    }

    override fun get(key: String): String? {
        val prefs = preferences
        if (prefs == null) {
            degraded("read '$key'")
            return null
        }
        val value = runCatching { prefs.getString(key, null) }
            .onFailure { AppLog.e(TAG, "Could not read secret '$key'", it) }
            .getOrNull()
        AppLog.registerSecret(value)
        return value
    }

    override fun remove(key: String) {
        val prefs = preferences
        if (prefs == null) {
            degraded("remove '$key'")
            return
        }
        runCatching { prefs.edit().remove(key).commit() }
            .onFailure { AppLog.e(TAG, "Could not remove secret '$key'", it) }
    }

    private fun degraded(operation: String) {
        AppLog.w(
            TAG,
            "Encrypted secret storage is unavailable; $operation was skipped " +
                "(no plaintext fallback is written)",
        )
    }

    /**
     * Opens (or creates) the Keystore-backed encrypted preference file.
     *
     * `EncryptedSharedPreferences` is kept on purpose: it is the API this project can rely on across
     * the whole supported range (API 26+) with a stable on-disk format, it derives every key from an
     * AES-256-GCM Android Keystore master key, and this class never falls back to plaintext storage
     * (see [degraded]). A future replacement with a hand-written Keystore envelope would need a
     * migration path for existing installations, which is tracked in docs/ARCHITECTURE.md.
     */
    @Suppress("DEPRECATION")
    private fun openPreferences(): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (t: Throwable) {
        AppLog.e(TAG, "Encrypted secret storage could not be initialised", t)
        null
    }

    companion object {
        private const val TAG = "SecretStore"

        /** Encrypted preferences file; it only ever holds secret material. */
        const val FILE_NAME = "ecp_secrets"
    }
}
