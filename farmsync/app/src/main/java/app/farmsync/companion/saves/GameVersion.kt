package app.farmsync.companion.saves

/**
 * Compares Stardew version strings part by part; missing parts count as 0.
 * Mobile builds have a 4th part, so "1.6.15.3" > "1.6.15" (rule R6).
 */
object GameVersion {
    fun compare(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** True if a save tagged [saveVersion] can be loaded by a game at [installedVersion]. */
    fun canLoad(saveVersion: String, installedVersion: String) = compare(saveVersion, installedVersion) <= 0

    private fun parts(v: String) = v.trim().split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
}
