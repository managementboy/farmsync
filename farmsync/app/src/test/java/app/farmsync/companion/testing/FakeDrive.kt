package app.farmsync.companion.testing

import app.farmsync.companion.sync.DriveClient
import app.farmsync.companion.sync.DriveFile
import java.io.File

/**
 * Google Drive in memory, understanding exactly the query shapes the vault uses:
 * `'id' in parents` (any of), `mimeType='…'`, `trashed=false` and
 * `appProperties has {key='k' and value='v'}` (any of when joined by "or", else all of).
 * [failAfterWrites] simulates the connection dropping after that many metadata updates.
 */
class FakeDrive : DriveClient {
    class Node(val file: DriveFile, val parent: String?, val mime: String, var content: ByteArray, var props: MutableMap<String, String>)

    val nodes = LinkedHashMap<String, Node>()
    var failAfterWrites: Int? = null
    private var n = 0
    private var time = 0

    private fun newFile(name: String, parent: String?, mime: String, content: ByteArray, props: Map<String, String>): DriveFile {
        val id = "d${n++}"
        val f = DriveFile(id, name, mime, "2026-01-01T00:00:%02d".format(time++), props)
        nodes[id] = Node(f, parent, mime, content, props.toMutableMap())
        return view(id)
    }

    private fun view(id: String): DriveFile = nodes.getValue(id).let { it.file.copy(appProperties = it.props.toMap()) }

    override fun query(q: String): List<DriveFile> {
        val parents = Regex("""'([^']+)' in parents""").findAll(q).map { it.groupValues[1] }.toSet()
        val mime = Regex("""mimeType='([^']+)'""").find(q)?.groupValues?.get(1)
        val props = Regex("""appProperties has \{key='([^']+)' and value='((?:[^'\\]|\\.)*)'}""").findAll(q)
            .map { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("\\\\", "\\") }.toList()
        val anyProp = props.size > 1 && q.contains(" or appProperties")
        return nodes.values.filter { node ->
            (parents.isEmpty() || node.parent in parents) &&
                (mime == null || node.mime == mime) &&
                (props.isEmpty() || if (anyProp) props.any { (k, v) -> node.props[k] == v } else props.all { (k, v) -> node.props[k] == v })
        }.map { view(it.file.id) }.sortedBy { it.createdTime }
    }

    override fun createFolder(name: String, parent: String?, appProperties: Map<String, String>) =
        newFile(name, parent, "application/vnd.google-apps.folder", ByteArray(0), appProperties)

    override fun upload(name: String, parent: String, mime: String, content: ByteArray, appProperties: Map<String, String>) =
        newFile(name, parent, mime, content, appProperties)

    override fun upload(name: String, parent: String, mime: String, content: File, appProperties: Map<String, String>) =
        newFile(name, parent, mime, content.readBytes(), appProperties)

    override fun updateAppProperties(fileId: String, appProperties: Map<String, String?>) {
        failAfterWrites?.let { if (it <= 0) throw java.io.IOException("connection dropped") else failAfterWrites = it - 1 }
        val p = nodes.getValue(fileId).props
        appProperties.forEach { (k, v) -> if (v == null) p.remove(k) else p[k] = v }
    }

    override fun downloadText(fileId: String) = nodes.getValue(fileId).content.decodeToString()

    override fun downloadTo(fileId: String, dest: File, maxBytes: Long) = dest.writeBytes(nodes.getValue(fileId).content)
}
