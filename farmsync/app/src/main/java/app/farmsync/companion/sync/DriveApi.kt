package app.farmsync.companion.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class DriveException(val code: Int, message: String) : Exception("Drive $code: $message")

data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val createdTime: String,
    val appProperties: Map<String, String>,
)

/**
 * Minimal Google Drive v3 REST client (no extra libraries). With the drive.file scope every
 * query only ever sees files this app created.
 */
class DriveApi(private val token: String) {

    fun aboutEmail(): String =
        JSONObject(get("$API/about?fields=user(emailAddress)")).getJSONObject("user").getString("emailAddress")

    fun query(q: String): List<DriveFile> {
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

    fun createFolder(name: String, parent: String?, appProperties: Map<String, String>): DriveFile {
        val meta = JSONObject()
            .put("name", name)
            .put("mimeType", FOLDER)
            .put("appProperties", JSONObject(appProperties))
        if (parent != null) meta.put("parents", JSONArray().put(parent))
        return JSONObject(send("POST", "$API/files?fields=$FIELDS", "application/json", meta.toString().toByteArray()))
            .toDriveFile()
    }

    /** Creates a new file with content in one multipart request. */
    fun upload(name: String, parent: String, mime: String, content: ByteArray, appProperties: Map<String, String>): DriveFile {
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(parent))
            .put("appProperties", JSONObject(appProperties))
        val boundary = "farmsync" + System.nanoTime()
        val body = buildString {
            append("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n")
            append(meta.toString()).append("\r\n")
            append("--$boundary\r\nContent-Type: $mime\r\n\r\n")
        }.toByteArray() + content + "\r\n--$boundary--\r\n".toByteArray()
        return JSONObject(
            send("POST", "$UPLOAD/files?uploadType=multipart&fields=$FIELDS", "multipart/related; boundary=$boundary", body)
        ).toDriveFile()
    }

    fun updateContent(fileId: String, mime: String, content: ByteArray) {
        send("PATCH", "$UPLOAD/files/$fileId?uploadType=media&fields=id", mime, content)
    }

    fun downloadText(fileId: String): String = get("$API/files/$fileId?alt=media")

    fun downloadTo(fileId: String, dest: File) {
        open("GET", "$API/files/$fileId?alt=media", null, null).use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
    }

    private fun get(url: String): String = open("GET", url, null, null).use { it.readBytes().decodeToString() }

    private fun send(method: String, url: String, contentType: String, body: ByteArray): String =
        open(method, url, contentType, body).use { it.readBytes().decodeToString() }

    private fun open(method: String, url: String, contentType: String?, body: ByteArray?): InputStream {
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
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", contentType)
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        if (code !in 200..299) {
            val err = c.errorStream?.use { it.readBytes().decodeToString() }.orEmpty()
            c.disconnect()
            throw DriveException(code, err.take(300))
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
