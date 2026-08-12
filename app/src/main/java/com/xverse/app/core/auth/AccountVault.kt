package com.xverse.app.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 已登录账户的本地会话保险箱。
 *
 * Cookie 属于敏感凭据：仅以 Android Keystore 生成的 AES-GCM 密钥加密后保存，用户名列表
 * 不含任何认证信息。每次切换账户时才解密并恢复至 WebView CookieManager。
 */
internal class AccountVault(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun usernames(): List<String> =
        (prefs.getStringSet(KEY_USERS, emptySet()) ?: emptySet()).sortedBy { it.lowercase() }

    fun activeUsername(): String = prefs.getString(KEY_ACTIVE, "").orEmpty()

    fun save(username: String, cookieHeader: String): Boolean {
        val safeName = normalize(username) ?: return false
        if (cookieHeader.isBlank()) return false
        return runCatching {
            val users = usernames().toMutableSet().apply { add(safeName) }
            prefs.edit {
                putStringSet(KEY_USERS, users)
                putString(sessionKey(safeName), encrypt(cookieHeader))
                putString(KEY_ACTIVE, safeName)
            }
            true
        }.getOrDefault(false)
    }

    fun session(username: String): String? {
        val safeName = normalize(username) ?: return null
        val value = prefs.getString(sessionKey(safeName), null) ?: return null
        return runCatching { decrypt(value) }.getOrNull()
    }

    fun setActive(username: String) {
        normalize(username)?.let { active -> prefs.edit { putString(KEY_ACTIVE, active) } }
    }

    fun remove(username: String) {
        val safeName = normalize(username) ?: return
        val users = usernames().toMutableSet().apply { remove(safeName) }
        prefs.edit {
            putStringSet(KEY_USERS, users)
            remove(sessionKey(safeName))
            if (activeUsername() == safeName) remove(KEY_ACTIVE)
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val (ivText, encryptedText) = value.split(":", limit = 2).let {
            require(it.size == 2)
            it[0] to it[1]
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)),
        )
        return String(cipher.doFinal(Base64.decode(encryptedText, Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun normalize(username: String): String? = username.trim().removePrefix("@").takeIf {
        it.matches(Regex("[A-Za-z0-9_]{1,30}"))
    }

    private fun sessionKey(username: String) = "session_$username"

    private companion object {
        const val PREFS_NAME = "account_vault"
        const val KEY_USERS = "users"
        const val KEY_ACTIVE = "active"
        const val KEY_ALIAS = "xverse_account_vault"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
