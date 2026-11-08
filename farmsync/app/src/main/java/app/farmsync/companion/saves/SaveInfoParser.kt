package app.farmsync.companion.saves

/**
 * Reads the values of chosen elements that are direct children of the root element (depth 2).
 * Deeper elements reuse the same names (every item has a <name>), so a depth-aware scan is
 * needed. Comments, CDATA sections and processing instructions are skipped as a whole, so a
 * value hidden inside a comment is never taken for a real one. Every occurrence is returned,
 * so callers can insist on exactly one. Pure Kotlin on purpose: runs in plain JVM unit tests.
 */
object XmlRoot {

    fun values(xml: String, wanted: Set<String>): Map<String, List<String>> {
        val found = HashMap<String, MutableList<String>>()
        var depth = 0
        var i = xml.indexOf('<')
        while (i >= 0 && i < xml.length) {
            when {
                xml.startsWith("<!--", i) -> { i = skipPast(xml, "-->", i); continue }
                xml.startsWith("<![CDATA[", i) -> { i = skipPast(xml, "]]>", i); continue }
                xml.startsWith("<?", i) -> { i = skipPast(xml, "?>", i); continue }
                xml.startsWith("<!", i) -> { i = skipPast(xml, ">", i); continue }
            }
            val end = xml.indexOf('>', i)
            if (end < 0) break
            val tag = xml.substring(i + 1, end)
            when {
                tag.startsWith("/") -> depth--
                tag.endsWith("/") -> Unit // self-closing, depth unchanged
                else -> {
                    depth++
                    val name = tag.substringBefore(' ').substringBefore('\t').substringBefore('\n').substringBefore('\r')
                    if (depth == 2 && name in wanted) {
                        val close = xml.indexOf("</$name>", end + 1)
                        val text = if (close >= 0) xml.substring(end + 1, close) else null
                        // A plain text value only: a nested element or comment means "not a value".
                        if (text != null && '<' !in text) {
                            found.getOrPut(name) { mutableListOf() } += unescape(text)
                            depth--
                            i = xml.indexOf('<', close + name.length + 3)
                            continue
                        }
                    }
                }
            }
            i = xml.indexOf('<', end + 1)
        }
        return found
    }

    private fun skipPast(xml: String, marker: String, from: Int): Int {
        val e = xml.indexOf(marker, from + 2)
        return if (e < 0) -1 else xml.indexOf('<', e + marker.length)
    }

    fun decode(bytes: ByteArray): String {
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }

    private fun unescape(s: String) = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}

/** Reads the summary fields from a SaveGameInfo file. */
object SaveInfoParser {

    private val WANTED = setOf(
        "name", "farmName", "gameVersion", "yearForSaveGame",
        "seasonForSaveGame", "dayOfMonthForSaveGame", "millisecondsPlayed",
    )

    fun parse(xml: String): SaveInfo {
        val found = XmlRoot.values(xml, WANTED).mapValues { it.value.first() }
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

    fun parse(bytes: ByteArray): SaveInfo = parse(XmlRoot.decode(bytes))
}
