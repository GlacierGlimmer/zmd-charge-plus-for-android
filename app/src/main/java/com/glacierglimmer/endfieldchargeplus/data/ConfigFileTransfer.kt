package com.glacierglimmer.endfieldchargeplus.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.glacierglimmer.endfieldchargeplus.core.json.ConfigCodec
import com.glacierglimmer.endfieldchargeplus.core.json.ConfigFormatException
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Import/export of the configuration file through the Storage Access Framework.
 *
 * The class is deliberately `ActivityResultContracts.CreateDocument` / `OpenDocument` friendly: the
 * UI owns the picker and only hands over the resulting [Uri].
 *
 * Rules:
 *  * the file is a plain desktop-compatible `settings.json`, so it stays meaningful on the other
 *    editions;
 *  * files larger than [MAX_FILE_BYTES] are rejected with an explicit message instead of being read
 *    into memory;
 *  * a clear-text API key is **never** written: [sanitizeSecrets] blanks a value that is not in a
 *    protected form, and the key itself lives in [EncryptedSecretStore], not in this file;
 *  * [importFrom] parses and normalizes but does not persist — the caller decides when to call
 *    `ConfigRepository.replace`.
 */
class ConfigFileTransfer(
    private val context: Context,
    private val repository: ConfigRepository,
) {

    /** File name suggested to the system document picker. */
    fun suggestedFileName(): String = FILE_NAME

    /** Serializes the current configuration and writes it to [uri]. Never throws. */
    suspend fun exportTo(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val config = sanitizeSecrets(repository.load())
            val json = ConfigCodec.encode(config)
            val bytes = json.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_FILE_BYTES) {
                throw IOException("The configuration is larger than ${MAX_FILE_BYTES / (1024 * 1024)} MB.")
            }
            val stream = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("The selected file could not be opened for writing.")
            stream.use { it.write(bytes) }
            AppLog.i(TAG, "Configuration exported (${bytes.size} bytes)")
        }.onFailure { AppLog.e(TAG, "Configuration export failed", it) }
    }

    /** Reads [uri], parses and normalizes it. Does not persist. Never throws. */
    suspend fun importFrom(uri: Uri): Result<AppConfig> = withContext(Dispatchers.IO) {
        val text = try {
            readCapped(uri)
        } catch (t: Throwable) {
            AppLog.e(TAG, "Could not read the configuration file", t)
            return@withContext Result.failure(
                ConfigFormatException("The selected file could not be read: ${t.message ?: t.javaClass.simpleName}", t),
            )
        }
        repository.importJson(text).map { sanitizeSecrets(it) }
    }

    /**
     * Blanks a value that is obviously a clear-text secret.
     *
     * The configuration only ever carries the *protected* form. A platform-protected blob is
     * either tag-prefixed (`android-keystore-v1:`, `linux-aesgcm-v1:`, `macos-keychain-v1:`) or
     * base64 with `+`/`/`/`=` padding; anything that looks like a raw token is removed instead of
     * being exported.
     */
    internal fun sanitizeSecrets(config: AppConfig): AppConfig {
        val value = config.customHud.deepSeekApiKeyProtected
        if (value.isBlank() || !looksLikeClearSecret(value)) return config
        AppLog.w(TAG, "Refusing to store/export a clear-text API key; the protected field was blanked")
        return config.copy(customHud = config.customHud.copy(deepSeekApiKeyProtected = ""))
    }

    /** True when [value] is a raw secret rather than a platform-protected blob. */
    internal fun looksLikeClearSecret(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("sk-", ignoreCase = true)) return true
        if (PROTECTED_TAGS.any { trimmed.startsWith(it, ignoreCase = true) }) return false
        if (trimmed.contains(':')) return false
        // A raw token has no base64 padding/plus/slash; a DPAPI blob almost always has one of them.
        val looksLikeBase64 = trimmed.any { it == '+' || it == '/' || it == '=' }
        return !looksLikeBase64 && trimmed.length >= 24 && trimmed.none { it.isWhitespace() }
    }

    private fun readCapped(uri: Uri): String {
        val resolver = context.contentResolver
        val declaredSize = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }.getOrNull()
        if (declaredSize != null && declaredSize > MAX_FILE_BYTES) {
            throw IOException("The selected file is larger than ${MAX_FILE_BYTES / (1024 * 1024)} MB.")
        }

        val input = resolver.openInputStream(uri)
            ?: throw IOException("The selected file could not be opened.")
        val buffer = ByteArrayOutputStream()
        input.use { stream ->
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                buffer.write(chunk, 0, read)
                if (buffer.size().toLong() > MAX_FILE_BYTES) {
                    throw IOException("The selected file is larger than ${MAX_FILE_BYTES / (1024 * 1024)} MB.")
                }
            }
        }
        return buffer.toString(Charsets.UTF_8.name())
    }

    companion object {
        private const val TAG = "ConfigTransfer"

        /** 4 MB is far above any realistic configuration and keeps the read bounded. */
        const val MAX_FILE_BYTES: Long = 4L * 1024L * 1024L

        const val FILE_NAME = "endfield-charge-plus-android-config.json"

        private val PROTECTED_TAGS = listOf(
            "android-keystore-v1:",
            "linux-aesgcm-v1:",
            "macos-keychain-v1:",
            "dpapi-v1:",
        )
    }
}
