package com.norfold.app.cloud

import android.app.Activity
import android.util.Base64
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.MessageDigest
import java.security.SecureRandom

data class GoogleIdentityResult(
    val idToken: String,
    val rawNonce: String,
    val subject: String,
    val displayName: String?,
    val email: String?,
)

class GoogleIdentityClient {
    suspend fun signIn(activity: Activity, authorizedAccountsOnly: Boolean = true): GoogleIdentityResult {
        ExternalServiceConfig.requireGoogleIdentity()
        val nonce = createNonce()
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(authorizedAccountsOnly)
            .setServerClientId(ExternalServiceConfig.googleServerClientId)
            .setAutoSelectEnabled(authorizedAccountsOnly)
            .setNonce(sha256(nonce))
            .build()
        val response = try {
            CredentialManager.create(activity).getCredential(
                context = activity,
                request = GetCredentialRequest.Builder().addCredentialOption(option).build(),
            )
        } catch (error: NoCredentialException) {
            if (!authorizedAccountsOnly) throw error
            return signIn(activity, authorizedAccountsOnly = false)
        }
        val custom = response.credential as? CustomCredential
            ?: error("Google returned an unsupported credential type.")
        check(custom.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Google returned an unexpected credential type: ${custom.type}"
        }
        val credential = GoogleIdTokenCredential.createFrom(custom.data)
        return GoogleIdentityResult(
            idToken = credential.idToken,
            rawNonce = nonce,
            subject = credential.id,
            displayName = credential.displayName,
            email = credential.id.takeIf { it.contains('@') },
        )
    }

    private fun createNonce(): String = ByteArray(32)
        .also(SecureRandom()::nextBytes)
        .let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING) }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
