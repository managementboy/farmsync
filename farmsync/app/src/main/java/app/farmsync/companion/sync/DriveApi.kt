package app.farmsync.companion.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A Drive error. Only the status code and Google's short machine reason (letters only) are
 * kept: the response body can contain account details and is never stored or shown.
 */
class DriveException(val code: Int, val reason: String) : Exception("drive_$code" + if (reason.isEmpty()) "" else "_$reason")

data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val createdTime: String,
    val appProperties: Map<String, String>,
)

/** The Drive calls the vault uses; an interface so the vault logic can be tested offline. */
interface DriveClient {
    fun query(q: String): List<DriveFile>
    fun createFolder(name: String, parent: String?, appProperties: Map<String, String>): DriveFile
    fun upload(name: String, parent: String, mime: String, content: ByteArray, appProperties: Map<String, String>): DriveFile
    fun upload(name: String, parent: String, mime: String, content: File, appProperties: Map<String, String>): DriveFile
    fun updateAppProperties(fileId: String, appProperties: Map<String, String?>)
    fun downloadText(fileId: String): String
    fun downloadTo(fileId: String, dest: File, maxBytes: Long)
}

/**
 * Minimal Google Drive v3 REST client (no extra libraries). With the drive.file scope every
 * query only ever sees files this app created.
 */
class DriveApi(private val token: String) : DriveClient {

    fun aboutEmail(): String =
        JSONObject(get("$API/about?fields=user(emailAddress)")).getJSONObject("user").getString("emailAddress")

    override fun query(q: String): List<DriveFile> {
        val out = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val url = "$API/files?spaces=drive&pageSize=200&orderBy=createdTime" +
                "&fields=nextPageToken,files(id,name,mimeType,createdTime,appProperties)" +
                "&q=" + enc(q) + (pageToken?.let { "&pageToken=" + enc(it) } ?: "")
            val json = JSONObject(get(url))
            val files = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) out += files.getJSONObject(i).toDriveFile()
            pageToken = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        return out
    }

    override fun createFolder(name: String, parent: String?, appProperties: Map<String, String>): DriveFile {
        val meta = JSONObject()
            .put("name", name)
            .put("mimeType", FOLDER)
            .put("appProperties", JSONObject(appProperties))
        if (parent != null) meta.put("parents", JSONArray().put(parent))
        return JSONObject(send("POST", "$API/files?fields=$FIELDS", "application/json", meta.toString().toByteArray()))
            .toDriveFile()
    }

    /** Creates a new file with content in one multipart request. */
    override fun upload(name: String, parent: String, mime: String, content: ByteArray, appProperties: Map<String, String>): DriveFile {
        val tmp = File.createTempFile("upload", null)
        return try {
            tmp.writeBytes(content)
            upload(name, parent, mime, tmp, appProperties)
        } finally {
            tmp.delete()
        }
    }

    /** Creates a new file, streaming [content] from disk (never held in memory as a whole). */
    override fun upload(name: String, parent: String, mime: String, content: File, appProperties: Map<String, String>): DriveFile {
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(parent))
            .put("appProperties", JSONObject(appProperties))
        val boundary = "farmsync" + System.nanoTime()
        val head = buildString {
            append("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n")
            append(meta.toString()).append("\r\n")
            append("--$boundary\r\nContent-Type: $mime\r\n\r\n")
        }.toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val c = connect("POST", "$UPLOAD/files?uploadType=multipart&fields=$FIELDS")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        c.setFixedLengthStreamingMode(head.size + content.length() + tail.size)
        c.outputStream.use { out ->
            out.write(head)
            content.inputStream().use { it.copyTo(out, 64 * 1024) }
            out.write(tail)
        }
        return JSONObject(response(c).use { it.readBytes().decodeToString() }).toDriveFile()
    }

    /** Sets (or with a null value removes) app properties of a file; other properties stay. */
    override fun updateAppProperties(fileId: String, appProperties: Map<String, String?>) {
        val props = JSONObject().apply { appProperties.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
        send("PATCH", "$API/files/$fileId?fields=id", "application/json",
            JSONObject().put("appProperties", props).toString().toByteArray())
    }

    fun updateContent(fileId: String, mime: String, content: ByteArray) {
        send("PATCH", "$UPLOAD/files/$fileId?uploadType=media&fields=id", mime, content)
    }

    override fun downloadText(fileId: String): String = get("$API/files/$fileId?alt=media")

    /** Downloads at most [maxBytes]; a larger file fails before it can fill the device. */
    override fun downloadTo(fileId: String, dest: File, maxBytes: Long) {
        open("GET", "$API/files/$fileId?alt=media", null, null).use { input ->
            var total = 0L
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw DriveException(413, "tooLarge")
                    out.write(buf, 0, n)
                }
            }
        }
    }

    private fun get(url: String): String = open("GET", url, null, null).use { it.readBytes().decodeToString() }

    private fun send(method: String, url: String, contentType: String, body: ByteArray): String =
        open(method, url, contentType, body).use { it.readBytes().decodeToString() }

    private fun open(method: String, url: String, contentType: String?, body: ByteArray?): InputStream {
        val c = connect(method, url)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", contentType)
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
        }
        return response(c)
    }

    private fun connect(method: String, url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        if (method == "PATCH") {
            c.requestMethod = "POST"
            c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        } else {
            c.requestMethod = method
        }
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun response(c: HttpURLConnection): InputStream {
        val code = c.responseCode
        if (code !in 200..299) {
            val reason = c.errorStream?.use { s ->
                runCatching {
                    JSONObject(s.readBytes().decodeToString()).getJSONObject("error")
                        .optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
                }.getOrDefault("")
            }.orEmpty().filter { it.isLetter() }.take(40)
            c.disconnect()
            throw DriveException(code, reason)
        }
        return c.inputStream
    }

    private fun JSONObject.toDriveFile() = DriveFile(
        id = getString("id"),
        name = optString("name"),
        mimeType = optString("mimeType"),
        createdTime = optString("createdTime"),
        appProperties = optJSONObject("appProperties")?.let { p ->
            p.keys().asSequence().associateWith { p.getString(it) }
        }.orEmpty(),
    )

    companion object {
        const val FOLDER = "application/vnd.google-apps.folder"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FIELDS = "id,name,mimeType,createdTime,appProperties"

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        /** Escapes a value for use inside single quotes in a Drive query. */
        fun q(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")
    }
}
