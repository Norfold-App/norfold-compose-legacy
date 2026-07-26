package com.norfold.app.data

import android.content.Context
import android.os.Build
import java.util.UUID

/** Stable identity for this app installation. It is never serialized into a shared snapshot. */
class InstallationIdentityStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(Preferences, Context.MODE_PRIVATE)

    val installationId: String
        get() = prefs.getString(InstallationId, null)
            ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
            ?: UUID.randomUUID().toString().also { prefs.edit().putString(InstallationId, it).commit() }

    val suggestedDeviceName: String
        get() = listOf(Build.MANUFACTURER, Build.MODEL)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
            .joinToString(" ")
            .ifBlank { "Android device" }
            .take(120)

    fun bindAccount(accountId: String?) {
        prefs.edit().apply {
            if (accountId.isNullOrBlank()) remove(AccountId) else putString(AccountId, accountId)
        }.apply()
    }

    fun boundAccountId(): String? = prefs.getString(AccountId, null)?.takeIf(String::isNotBlank)

    private companion object {
        const val Preferences = "norfold_installation_identity"
        const val InstallationId = "installation_id"
        const val AccountId = "account_id"
    }
}
