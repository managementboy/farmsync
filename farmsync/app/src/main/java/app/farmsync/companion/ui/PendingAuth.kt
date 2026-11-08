package app.farmsync.companion.ui

/** A tiny key-value view of the ViewModel's saved state (SavedStateHandle in the app, a map in tests). */
interface SavedValues {
    operator fun get(key: String): Any?
    operator fun set(key: String, value: Any?)
}

/**
 * Bookkeeping for an action that waits on Google's sign-in screen, kept in saved state so it
 * survives Android closing the app. Never holds a token: only the action's name and whether
 * the user actually authorized it.
 *
 *  - [requested]: Google needs the user for [action].
 *  - [answered]: Google's screen came back. Only a successful answer with a valid token makes the
 *    action eligible to continue; a canceled or failed one clears it for good (U02).
 *  - [started]: the action's job accepted it; nothing is pending any more.
 *  - [unstartedAfterRestart]: in a new process, an authorized action whose job never started.
 *    It stays recorded until it starts, so another restart still finds it.
 */
class PendingAuth(private val s: SavedValues) {

    fun requested(action: String) {
        s[ACTION] = action
        s[AUTHORIZED] = null
    }

    /** Returns the action to continue, or null if there is none or the answer was not a yes. */
    fun answered(ok: Boolean, tokenValid: Boolean): String? {
        val action = s[ACTION] as? String
        if (!ok || !tokenValid || action == null) {
            clear()
            return null
        }
        s[AUTHORIZED] = true
        return action
    }

    fun started() = clear()

    fun unstartedAfterRestart(): String? = if (s[AUTHORIZED] == true) s[ACTION] as? String else null

    fun clear() {
        s[ACTION] = null
        s[AUTHORIZED] = null
    }

    companion object {
        private const val ACTION = "pendingAuth"
        private const val AUTHORIZED = "pendingAuthAuthorized"
    }
}
