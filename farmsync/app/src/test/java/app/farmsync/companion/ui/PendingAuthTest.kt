package app.farmsync.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The saved-state side of Google sign-in (U02). A [MapValues] stands in for SavedStateHandle:
 * the same map handed to a new [PendingAuth] is what Android restores after closing the app.
 */
class PendingAuthTest {
    private class MapValues : SavedValues {
        val m = HashMap<String, Any?>()
        override fun get(key: String) = m[key]
        override fun set(key: String, value: Any?) { if (value == null) m.remove(key) else m[key] = value }
    }

    @Test
    fun aCancelledMiddayAuthorizationIsNeverReplayed() {
        val saved = MapValues()
        PendingAuth(saved).requested("midday:Farm_1")
        // The user cancels Google's screen.
        assertNull(PendingAuth(saved).answered(ok = false, tokenValid = false))
        // Android closes and recreates the app; silent authorization would now be possible.
        assertNull(PendingAuth(saved).unstartedAfterRestart())
        assertTrue(saved.m.isEmpty()) // nothing left that could ever start it
    }

    @Test
    fun aFinishedScreenWithoutAValidTokenIsNotReplayedEither() {
        val saved = MapValues()
        PendingAuth(saved).requested("download:Farm_1")
        assertNull(PendingAuth(saved).answered(ok = true, tokenValid = false))
        assertNull(PendingAuth(saved).unstartedAfterRestart())
    }

    @Test
    fun anAuthorizedActionSurvivesRepeatedRestartsUntilItStarts() {
        val saved = MapValues()
        PendingAuth(saved).requested("download:Farm_1")
        assertEquals("download:Farm_1", PendingAuth(saved).answered(ok = true, tokenValid = true))
        // Queued behind start-up, then Android closes the app again — twice.
        assertEquals("download:Farm_1", PendingAuth(saved).unstartedAfterRestart())
        assertEquals("download:Farm_1", PendingAuth(saved).unstartedAfterRestart())
        // Its job starts: it is done and never runs a second time.
        PendingAuth(saved).started()
        assertNull(PendingAuth(saved).unstartedAfterRestart())
    }

    @Test
    fun anUnansweredRequestIsNotRunAfterARestart() {
        val saved = MapValues()
        PendingAuth(saved).requested("sync")
        // The app was closed while Google's screen was open; its answer is still to come.
        assertNull(PendingAuth(saved).unstartedAfterRestart())
        assertEquals("sync", PendingAuth(saved).answered(ok = true, tokenValid = true))
    }

    @Test
    fun aNewRequestResetsAnEarlierYes() {
        val saved = MapValues()
        PendingAuth(saved).apply { requested("sync"); answered(ok = true, tokenValid = true) }
        PendingAuth(saved).requested("midday:Farm_1") // Google asks again for something else
        assertNull(PendingAuth(saved).unstartedAfterRestart())
    }
}
