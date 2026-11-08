package app.farmsync.companion.saves

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.InputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/** [message] is a short reason code: id, root, malformed, incomplete, doctype, version_*. */
class SaveXmlException(message: String) : RuntimeException(message)

/** What a pair of save files really says about itself. */
data class CheckedSave(val id: String, val gameVersion: String, val info: SaveInfo)

/**
 * Strict check of a farm's two required files before they may replace anything (R6, R8):
 * both must be complete, well-formed XML with the expected root element (`SaveGame` for the
 * main file, `Farmer` for SaveGameInfo). The ID must be exactly one root-level
 * `uniqueIDForThisGame` equal to the folder's ID. The version tags must not contradict each
 * other. A streaming SAX parser is used, so a big save is never held in memory as a whole.
 * DTDs are refused, so no entity tricks or outside lookups are possible.
 * Runs on Android and in plain JVM tests.
 */
object SaveXml {

    fun check(farm: String, main: File, summary: File): CheckedSave {
        val m = scan(main, "SaveGame", setOf("uniqueIDForThisGame", "gameVersion"))
        val s = scan(summary, "Farmer", SUMMARY_FIELDS)
        val id = farm.substringAfterLast('_')
        val ids = m["uniqueIDForThisGame"].orEmpty().map { it.trim() }
        if (ids.size != 1 || ids.single() != id) throw SaveXmlException("id")
        val mainVersions = m["gameVersion"].orEmpty().map { it.trim() }
        val summaryVersions = s["gameVersion"].orEmpty().map { it.trim() }
        if (mainVersions.size > 1 || summaryVersions.size > 1) throw SaveXmlException("version_twice")
        val mv = mainVersions.singleOrNull()
        val sv = summaryVersions.singleOrNull()
        if (mv != null && sv != null && mv != sv) throw SaveXmlException("version_mismatch")
        // The main file's own tag wins; older saves without it fall back to the summary's.
        val version = mv ?: sv ?: ""
        val one = { k: String -> s[k]?.firstOrNull() }
        val info = SaveInfo(
            farmer = one("name").orEmpty(),
            farmName = one("farmName").orEmpty(),
            gameVersion = version,
            year = one("yearForSaveGame")?.trim()?.toIntOrNull() ?: 0,
            season = one("seasonForSaveGame")?.trim()?.toIntOrNull() ?: -1,
            day = one("dayOfMonthForSaveGame")?.trim()?.toIntOrNull() ?: 0,
            playedMs = one("millisecondsPlayed")?.trim()?.toLongOrNull() ?: 0L,
        )
        return CheckedSave(id, version, info)
    }

    /** Folder-level helper for a farm extracted to [dir] (standard file names). */
    fun check(farm: String, dir: File) = check(farm, File(dir, farm), File(dir, "SaveGameInfo"))

    private val SUMMARY_FIELDS = setOf(
        "name", "farmName", "gameVersion", "yearForSaveGame",
        "seasonForSaveGame", "dayOfMonthForSaveGame", "millisecondsPlayed",
    )

    /** Values of root-level elements in [wanted]; throws unless the document is complete and well-formed. */
    fun scan(file: File, root: String, wanted: Set<String>): Map<String, List<String>> =
        file.inputStream().buffered().use { scan(it, root, wanted) }

    fun scan(input: InputStream, root: String, wanted: Set<String>): Map<String, List<String>> {
        val found = HashMap<String, MutableList<String>>()
        val handler = object : DefaultHandler() {
            var depth = 0
            var rootSeen = false
            var capturing: String? = null
            val text = StringBuilder()

            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
                depth++
                val name = qName.substringAfter(':')
                if (depth == 1) {
                    if (name != root) throw SaveXmlException("root")
                    rootSeen = true
                }
                if (capturing != null) capturing = "" // a nested element: not a plain value
                if (depth == 2 && name in wanted) {
                    capturing = name
                    text.setLength(0)
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (!capturing.isNullOrEmpty() && text.length < 4096) text.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                if (depth == 2) {
                    val c = capturing
                    if (!c.isNullOrEmpty()) found.getOrPut(c) { mutableListOf() } += text.toString()
                    capturing = null
                }
                depth--
            }

            override fun resolveEntity(publicId: String?, systemId: String?) = InputSource(StringReader(""))

            override fun endDocument() {
                if (!rootSeen || depth != 0) throw SaveXmlException("incomplete")
            }
        }
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = false; isValidating = false }
        for ((feature, on) in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        )) runCatching { factory.setFeature(feature, on) } // not every platform parser knows every feature
        val guarded = DoctypeGuard(input)
        try {
            factory.newSAXParser().parse(InputSource(guarded), handler)
        } catch (e: SaveXmlException) {
            throw e
        } catch (e: Exception) {
            throw SaveXmlException("malformed")
        }
        return found
    }

    /** Refuses any document type declaration, whatever the platform parser supports. */
    private class DoctypeGuard(private val inner: InputStream) : InputStream() {
        private val window = StringBuilder()
        private var checked = 0L

        override fun read(): Int {
            val b = inner.read()
            if (b >= 0) look(byteArrayOf(b.toByte()), 0, 1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(b, off, len)
            if (n > 0) look(b, off, n)
            return n
        }

        private fun look(b: ByteArray, off: Int, n: Int) {
            if (checked > 64 * 1024) return // a DOCTYPE must come before the root element
            for (i in off until off + n) {
                window.append(b[i].toInt().toChar())
                if (window.length > 16) window.deleteCharAt(0)
                if (window.endsWith("<!DOCTYPE") || window.endsWith("<!ENTITY")) throw SaveXmlException("doctype")
            }
            checked += n
        }

        override fun close() = inner.close()
    }
}
