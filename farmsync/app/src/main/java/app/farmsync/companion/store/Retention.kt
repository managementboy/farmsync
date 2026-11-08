package app.farmsync.companion.store

/**
 * Which snapshots of one farm to delete: keep the newest [keep] unpinned ones (always at
 * least [alwaysKeepNewest]), plus every pinned one on top. Pinned snapshots do not use up
 * the [keep] budget, so pinning an old one never pushes out recent ones.
 */
object Retention {
    const val DEFAULT_KEEP = 30
    const val ALWAYS_KEEP_NEWEST = 3

    fun selectToDelete(
        snapshots: List<SnapshotMeta>,
        keep: Int = DEFAULT_KEEP,
        alwaysKeepNewest: Int = ALWAYS_KEEP_NEWEST,
    ): List<SnapshotMeta> {
        val newestFirst = snapshots.sortedByDescending { it.createdAt }
        val protected = newestFirst.take(maxOf(alwaysKeepNewest, 0)).map { it.id }.toSet()
        var kept = 0
        val delete = mutableListOf<SnapshotMeta>()
        for (s in newestFirst) {
            when {
                s.pinned -> Unit
                s.id in protected || kept < keep -> kept++
                else -> delete += s
            }
        }
        return delete
    }
}
