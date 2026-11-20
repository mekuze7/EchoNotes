package com.example.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages user-configured preferences including custom Gemini API keys
 * and audio settings with persistent on-device SharedPreferences storage.
 */
class UserPreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "echonotes_user_prefs"
        private const val KEY_CUSTOM_GEMINI_API_KEY = "custom_gemini_api_key"
        private const val KEY_CUSTOM_DEEPGRAM_API_KEY = "custom_deepgram_api_key"
        private const val KEY_LIVE_CAPTION_PROVIDER = "live_caption_provider"
        private const val DEEPGRAM_KEY_ALIAS = "echonotes_deepgram_key"
        private const val KEY_ACTIVE_GEMINI_MODEL = "active_gemini_model"
        private const val KEY_SUBTITLE_LANGUAGE = "subtitle_language"
        private const val KEY_TRANSLATION_ENABLED = "translation_enabled"
        private const val KEY_TRANSLATION_LANGUAGE = "translation_language"
        private const val KEY_SUBTITLE_TEXT_SIZE = "subtitle_text_size"
        private const val KEY_SUBTITLE_POSITION = "subtitle_position"

        @Volatile
        private var instance: UserPreferencesManager? = null

        fun getInstance(context: Context): UserPreferencesManager {
            return instance ?: synchronized(this) {
                instance ?: UserPreferencesManager(context).also { instance = it }
            }
        }
    }

    /**
     * Retrieves the custom Gemini API key configured directly by the user in-app, if any.
     */
    fun getCustomGeminiApiKey(): String {
        return prefs.getString(KEY_CUSTOM_GEMINI_API_KEY, "")?.trim()?.trim('"', '\'') ?: ""
    }

    /**
     * Saves a custom Gemini API key entered by the user.
     */
    fun setCustomGeminiApiKey(apiKey: String) {
        val sanitized = apiKey.trim().trim('"', '\'')
        val previous = getCustomGeminiApiKey()
        prefs.edit().apply {
            putString(KEY_CUSTOM_GEMINI_API_KEY, sanitized)
            if (previous != sanitized) remove(KEY_ACTIVE_GEMINI_MODEL)
        }.apply()
    }

    /**
     * Clears any custom Gemini API key so the app falls back to the .env / BuildConfig key.
     */
    fun clearCustomGeminiApiKey() {
        prefs.edit()
            .remove(KEY_CUSTOM_GEMINI_API_KEY)
            .remove(KEY_ACTIVE_GEMINI_MODEL)
            .apply()
    }

    fun getCustomDeepgramApiKey(): String {
        val encrypted = prefs.getString(KEY_CUSTOM_DEEPGRAM_API_KEY, null) ?: return ""
        return try {
            val payload = Base64.decode(encrypted, Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(payload)
            val ivSize = buffer.int
            require(ivSize in 12..buffer.remaining()) { "Invalid encrypted Deepgram key." }
            val iv = ByteArray(ivSize).also(buffer::get)
            val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateDeepgramKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalStateException("Could not decrypt the saved Deepgram API key.", e)
        }
    }

    fun setCustomDeepgramApiKey(apiKey: String) {
        val sanitized = apiKey.trim().trim('"', '\'')
        if (sanitized.isBlank()) {
            clearCustomDeepgramApiKey()
            return
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateDeepgramKey())
            val ciphertext = cipher.doFinal(sanitized.toByteArray(Charsets.UTF_8))
            val payload = ByteBuffer.allocate(Int.SIZE_BYTES + cipher.iv.size + ciphertext.size)
                .putInt(cipher.iv.size)
                .put(cipher.iv)
                .put(ciphertext)
                .array()
            prefs.edit()
                .putString(KEY_CUSTOM_DEEPGRAM_API_KEY, Base64.encodeToString(payload, Base64.NO_WRAP))
                .apply()
        } catch (e: Exception) {
            throw IllegalStateException("Could not securely save the Deepgram API key.", e)
        }
    }

    fun clearCustomDeepgramApiKey() {
        prefs.edit().remove(KEY_CUSTOM_DEEPGRAM_API_KEY).apply()
    }

    fun hasCustomDeepgramApiKey(): Boolean = getCustomDeepgramApiKey().isNotEmpty()

    fun getLiveCaptionProvider(): String =
        prefs.getString(KEY_LIVE_CAPTION_PROVIDER, "gemini") ?: "gemini"

    fun setLiveCaptionProvider(provider: String) {
        require(provider in setOf("gemini", "deepgram")) { "Unsupported live caption provider: $provider" }
        prefs.edit().putString(KEY_LIVE_CAPTION_PROVIDER, provider).apply()
    }

    private fun getOrCreateDeepgramKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(DEEPGRAM_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                DEEPGRAM_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return keyGenerator.generateKey()
    }

    /**
     * Checks if a user has entered a custom key.
     */
    fun hasCustomGeminiApiKey(): Boolean {
        return getCustomGeminiApiKey().isNotEmpty()
    }

    /**
     * Retrieves the active or cached Gemini model name.
     */
    fun getActiveGeminiModel(): String {
        return prefs.getString(KEY_ACTIVE_GEMINI_MODEL, "") ?: ""
    }

    /**
     * Saves the currently active or auto-detected Gemini model name.
     */
    fun setActiveGeminiModel(model: String) {
        prefs.edit().putString(KEY_ACTIVE_GEMINI_MODEL, model.trim()).apply()
    }

    /**
     * Resets the active Gemini model cache.
     */
    fun clearActiveGeminiModel() {
        prefs.edit().remove(KEY_ACTIVE_GEMINI_MODEL).apply()
    }

    fun getSubtitleLanguage(): String = prefs.getString(KEY_SUBTITLE_LANGUAGE, "auto") ?: "auto"

    fun setSubtitleLanguage(language: String) {
        require(language in setOf("auto", "en", "am")) { "Unsupported subtitle language: $language" }
        prefs.edit().putString(KEY_SUBTITLE_LANGUAGE, language).apply()
    }

    fun isLiveTranslationEnabled(): Boolean = prefs.getBoolean(KEY_TRANSLATION_ENABLED, false)

    fun setLiveTranslationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TRANSLATION_ENABLED, enabled).apply()
    }

    fun getTranslationLanguage(): String = prefs.getString(KEY_TRANSLATION_LANGUAGE, "en") ?: "en"

    fun setTranslationLanguage(language: String) {
        require(language in setOf("en", "am")) { "Unsupported translation language: $language" }
        prefs.edit().putString(KEY_TRANSLATION_LANGUAGE, language).apply()
    }

    fun getSubtitleTextSizeSp(): Float = prefs.getFloat(KEY_SUBTITLE_TEXT_SIZE, 22f).coerceIn(16f, 36f)

    fun setSubtitleTextSizeSp(sizeSp: Float) {
        require(sizeSp in 16f..36f) { "Subtitle text size must be between 16 and 36 sp." }
        prefs.edit().putFloat(KEY_SUBTITLE_TEXT_SIZE, sizeSp).apply()
    }

    fun getSubtitlePosition(): String = prefs.getString(KEY_SUBTITLE_POSITION, "bottom") ?: "bottom"

    fun setSubtitlePosition(position: String) {
        require(position in setOf("top", "bottom")) { "Unsupported subtitle position: $position" }
        prefs.edit().putString(KEY_SUBTITLE_POSITION, position).apply()
    }
}
