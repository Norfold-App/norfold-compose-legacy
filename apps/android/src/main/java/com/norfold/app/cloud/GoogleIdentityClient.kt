package com.norfold.app.cloud

import android.app.Activity
import android.util.Base64
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.MessageDigest
import java.security.SecureRandom

data class GoogleIdentityResult(
    val idToken: String,
    val rawNonce: String,
    val subject: String,
    val displayName: String?,
    val givenName: String?,
    val familyName: String?,
    val email: String?,
    val profilePictureUri: String?,
)

sealed class GoogleSignInException(message: String) : Exception(message) {
    class NoAccountAvailable : GoogleSignInException("No Google account is available on this device. Add a Google account in Android Settings, then try again.")
}

class GoogleIdentityClient {
    suspend fun signIn(activity: Activity, authorizedAccountsOnly: Boolean = true): GoogleIdentityResult {
        ExternalServiceConfig.requireGoogleIdentity()
        val nonce = createNonce()
        // Returning users: silent auto-select against already-authorized accounts (no UI).
        // Everyone else: the explicit Sign in with Google sheet, which Play services renders
        // itself. The passive GetGoogleIdOption chooser goes through the AOSP credential
        // selector, which MIUI/HyperOS kills on arrival and the framework then reports as a
        // spurious "user cancelled" — so it is never used for the account-picking step.
        val option = if (authorizedAccountsOnly) {
            GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true)
                .setServerClientId(ExternalServiceConfig.googleServerClientId)
                .setAutoSelectEnabled(true)
                .setNonce(sha256(nonce))
                .build()
        } else {
            GetSignInWithGoogleOption.Builder(ExternalServiceConfig.googleServerClientId)
                .setNonce(sha256(nonce))
                .build()
        }
        val response = try {
            CredentialManager.create(activity).getCredential(
                context = activity,
                request = GetCredentialRequest.Builder().addCredentialOption(option).build(),
            )
        } catch (error: NoCredentialException) {
            if (!authorizedAccountsOnly) throw GoogleSignInException.NoAccountAvailable()
            return signIn(activity, authorizedAccountsOnly = false)
        } catch (error: GetCredentialCancellationException) {
            // A cancel during the silent step is the OS aborting, not the user: retry visibly.
            if (authorizedAccountsOnly) return signIn(activity, authorizedAccountsOnly = false)
            throw error
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
            givenName = credential.givenName,
            familyName = credential.familyName,
            email = credential.id.takeIf { it.contains('@') },
            profilePictureUri = credential.profilePictureUri?.toString(),
        )
    }

    private fun createNonce(): String = ByteArray(32)
        .also(SecureRandom()::nextBytes)
        .let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING) }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
