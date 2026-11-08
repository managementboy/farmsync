package app.farmsync.companion.saves

/**
 * Compares Stardew version strings part by part; missing parts count as 0.
 * Mobile builds have a 4th part, so "1.6.15.3" > "1.6.15" (rule R6).
 */
object GameVersion {
    /** The leading "1.6.15.3" of a version string ("1.6.15.3 (build 42)" also works). */
    private val NUMERIC = Regex("""^\s*(\d+(?:\.\d+){1,3})""")

    /** True if [v] starts with a numeric version of two to four parts. Unknown = not valid. */
    fun isValid(v: String?) = v != null && NUMERIC.containsMatchIn(v)

    fun compare(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /**
     * True if a save tagged [saveVersion] can be loaded by a game at [installedVersion].
     * An unknown or malformed version on either side is never loadable (R6 fails closed).
     */
    fun canLoad(saveVersion: String?, installedVersion: String?) =
        isValid(saveVersion) && isValid(installedVersion) && compare(saveVersion!!, installedVersion!!) <= 0

    private fun parts(v: String) =
        NUMERIC.find(v)?.groupValues?.get(1)?.split('.')?.map { it.toInt() }.orEmpty()
}
