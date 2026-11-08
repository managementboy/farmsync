package app.farmsync.companion.sync

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

sealed interface AuthOutcome {
    data class Token(val accessToken: String) : AuthOutcome
    /** Google needs the user: account chooser and/or consent. Launch it from an activity. */
    data class NeedsUser(val intent: PendingIntent) : AuthOutcome
}

/**
 * Drive access through Google Identity Services. The app is recognised by its package name +
 * signing certificate (Android OAuth client in Cloud project farm-sync-514113); no secret.
 * Only the `drive.file` scope: the app sees just the files it created.
 */
object DriveAuth {
    const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

    /** [email] = the vault account chosen before; null on first link (Google shows the chooser). */
    suspend fun authorize(context: Context, email: String?): AuthOutcome {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE_DRIVE_FILE)))
            .apply { if (email != null) setAccount(Account(email, "com.google")) }
            .build()
        val result = Identity.getAuthorizationClient(context).authorize(request).await()
        val pending = result.pendingIntent
        return if (result.hasResolution() && pending != null) AuthOutcome.NeedsUser(pending)
        else AuthOutcome.Token(result.accessToken ?: error("No access token"))
    }

    /** Reads the token from the result of the chooser/consent activity. */
    fun tokenFromResult(context: Context, data: Intent?): String =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken
            ?: error("Drive access was not granted")
}
