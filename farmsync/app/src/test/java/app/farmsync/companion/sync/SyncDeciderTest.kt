package app.farmsync.companion.sync

import app.farmsync.companion.sync.SyncAction.CONFLICT
import app.farmsync.companion.sync.SyncAction.CREATE
import app.farmsync.companion.sync.SyncAction.DELETED_HERE
import app.farmsync.companion.sync.SyncAction.NONE
import app.farmsync.companion.sync.SyncAction.PULL
import app.farmsync.companion.sync.SyncAction.PUSH
import app.farmsync.companion.sync.SyncAction.REMOTE_ONLY
import app.farmsync.companion.sync.SyncAction.UP_TO_DATE
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncDeciderTest {

    private fun v(id: String, parent: String? = null, conflict: Boolean = false) =
        VersionEntry(id, "file-$id", parent, "dev", "Dev", 0, null, conflict)

    private fun m(head: String?, vararg versions: VersionEntry) =
        Manifest("F_1", head, versions.associateBy { it.id }, emptyMap())

    private val d = SyncDecider::decide

    @Test fun emptyVault() {
        assertEquals(CREATE, d("a", null, null))
        assertEquals(NONE, d(null, null, null))
        assertEquals(CREATE, d("a", null, m(null)))
    }

    @Test fun upToDate() = assertEquals(UP_TO_DATE, d("b", "b", m("b", v("a"), v("b", "a"))))

    @Test fun playedHereNobodyElse_push() = assertEquals(PUSH, d("c", "b", m("b", v("a"), v("b", "a"))))

    @Test fun notPlayedHereVaultNewer_pull() {
        assertEquals(PULL, d("a", "a", m("b", v("a"), v("b", "a"))))
        // Device never synced but has a copy the vault knows (e.g. copied by hand earlier).
        assertEquals(PULL, d("a", null, m("b", v("a"), v("b", "a"))))
    }

    @Test fun playedHereAndElsewhere_conflict() =
        assertEquals(CONFLICT, d("x", "a", m("b", v("a"), v("b", "a"))))

    @Test fun unknownLineage_conflict() =
        // First sync of a device whose copy differs from everything in the vault.
        assertEquals(CONFLICT, d("x", null, m("b", v("b"))))

    @Test fun unresolvedConflictStaysConflict() =
        assertEquals(CONFLICT, d("x", "a", m("b", v("a"), v("b", "a"), v("x", "a", conflict = true))))

    @Test fun notOnDevice() {
        assertEquals(REMOTE_ONLY, d(null, null, m("b", v("b"))))
        assertEquals(DELETED_HERE, d(null, "b", m("b", v("b"))))
    }

    @Test fun manifestRoundTripAndMerge() {
        val a = Manifest("F_1", "b", mapOf("a" to v("a"), "b" to v("b", "a")),
            mapOf("phone" to DeviceEntry("Phone", "b", 10)))
        val back = Manifest.fromJson(JSONObject(a.toJson().toString()))
        assertEquals(a, back)

        val remote = Manifest("F_1", "c", mapOf("a" to v("a"), "c" to v("c", "a", conflict = true)),
            mapOf("phone" to DeviceEntry("Phone", "a", 5), "tablet" to DeviceEntry("Tablet", "c", 20)))
        val merged = a.mergedOnto(remote)
        assertEquals("b", merged.head) // our head wins
        assertEquals(setOf("a", "b", "c"), merged.versions.keys) // nothing lost
        assertEquals("b", merged.devices.getValue("phone").has) // newer entry wins
        assertEquals("c", merged.devices.getValue("tablet").has)
    }
}
