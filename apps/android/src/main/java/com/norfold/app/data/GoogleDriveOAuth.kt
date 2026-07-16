package com.norfold.app.data

import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope

object GoogleDriveOAuth {
    const val DriveAppDataScope = "https://www.googleapis.com/auth/drive.appdata"

    fun authorizationRequest(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DriveAppDataScope)))
        .build()

    fun revocationRequest(): RevokeAccessRequest = RevokeAccessRequest.builder()
        .setScopes(listOf(Scope(DriveAppDataScope)))
        .build()
}
