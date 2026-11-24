package com.restia.mobile.ui.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/** Lo que devolvió Google al tocar «Continuar con Google». */
sealed interface GoogleSignInResult {
    data class Token(val idToken: String) : GoogleSignInResult
    data object Cancelled : GoogleSignInResult
    data object NoAccount : GoogleSignInResult
    data object Failed : GoogleSignInResult
}

/**
 * Pide a Credential Manager el ID token de Google (Decisiones_Google_Login en restia-api). `clientId` es el
 * ID de cliente Web que da la API: así el token llega con la audiencia que la API verifica. Necesita el
 * contexto de la Activity para mostrar la hoja de cuentas.
 */
suspend fun requestGoogleIdToken(activity: Context, clientId: String): GoogleSignInResult {
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
        .build()
    return try {
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            GoogleSignInResult.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
        } else {
            GoogleSignInResult.Failed
        }
    } catch (_: GetCredentialCancellationException) {
        GoogleSignInResult.Cancelled
    } catch (_: NoCredentialException) {
        GoogleSignInResult.NoAccount
    } catch (_: GoogleIdTokenParsingException) {
        GoogleSignInResult.Failed
    } catch (_: GetCredentialException) {
        GoogleSignInResult.Failed
    }
}
