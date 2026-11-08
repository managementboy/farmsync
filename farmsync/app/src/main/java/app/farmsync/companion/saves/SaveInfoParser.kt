package app.farmsync.companion.saves

/**
 * Reads the summary fields from a SaveGameInfo file.
 *
 * The wanted values are direct children of the root element (depth 2). Deeper
 * elements reuse the same names (every item has a <name>), so a depth-aware scan
 * is needed. Pure Kotlin on purpose: runs in plain JVM unit tests.
 */
object SaveInfoParser {

    private val WANTED = setOf(
        "name", "farmName", "gameVersion", "yearForSaveGame",
        "seasonForSaveGame", "dayOfMonthForSaveGame", "millisecondsPlayed",
    )

    fun parse(xml: String): SaveInfo {
        val found = HashMap<String, String>()
        var depth = 0
        var i = xml.indexOf('<')
        while (i >= 0 && i < xml.length && found.size < WANTED.size) {
            val end = xml.indexOf('>', i)
            if (end < 0) break
            val tag = xml.substring(i + 1, end)
            when {
                tag.startsWith("?") || tag.startsWith("!") -> Unit
                tag.startsWith("/") -> depth--
                tag.endsWith("/") -> Unit // self-closing, depth unchanged
                else -> {
                    depth++
                    val name = tag.substringBefore(' ').substringBefore('\t')
                    if (depth == 2 && name in WANTED && name !in found) {
                        val close = xml.indexOf("</$name>", end + 1)
                        if (close >= 0) {
                            found[name] = unescape(xml.substring(end + 1, close))
                            depth--
                            i = xml.indexOf('<', close + name.length + 3)
                            continue
                        }
                    }
                }
            }
            i = xml.indexOf('<', end + 1)
        }
        return SaveInfo(
            farmer = found["name"].orEmpty(),
            farmName = found["farmName"].orEmpty(),
            gameVersion = found["gameVersion"].orEmpty(),
            year = found["yearForSaveGame"]?.toIntOrNull() ?: 0,
            season = found["seasonForSaveGame"]?.toIntOrNull() ?: -1,
            day = found["dayOfMonthForSaveGame"]?.toIntOrNull() ?: 0,
            playedMs = found["millisecondsPlayed"]?.toLongOrNull() ?: 0L,
        )
    }

    fun parse(bytes: ByteArray): SaveInfo {
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        return parse(String(bytes, start, bytes.size - start, Charsets.UTF_8))
    }

    private fun unescape(s: String) = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
