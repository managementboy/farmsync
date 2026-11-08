package app.farmsync.companion.testing

import app.farmsync.companion.ops.WriteJournal
import app.farmsync.companion.saves.Farm
import app.farmsync.companion.saves.FarmScanner
import app.farmsync.companion.saves.SaveFs
import app.farmsync.companion.shizuku.SavePaths
import app.farmsync.companion.store.SnapshotStore
import app.farmsync.companion.sync.SyncState
import app.farmsync.companion.sync.VersionEntry
import app.farmsync.companion.sync.VersionVault
import app.farmsync.companion.sync.VersionProps
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Simulates the app process being killed: an Error, so no `catch (e: Exception)` runs. */
class Killed : Error("killed")

fun md5(f: File): String = MessageDigest.getInstance("MD5").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

/**
 * The Saves folder on disk, behaving like SaveFsService, with hooks to inject faults:
 * the game starting, the player saving, or the app being killed at a chosen moment.
 */
class FakeSaveFs(val root: File) : SaveFs {
    var gameRunning = false
    /** Throw [Killed] after this many renames inside one commit. */
    var killAfterRenames: Int? = null
    /** Throw a normal exception after this many renames inside one commit. */
    var failAfterRenames: Int? = null
    /** Runs at the start of every commit (e.g. the player saving just before). */
    var beforeCommit: ((String) -> Unit)? = null
    var failCreateFolder = false
    /** Throw [Killed] when the next file is staged. */
    var killOnStage = false
    /** Runs after every successful commit (e.g. the game starting and saving right then). */
    var afterCommit: ((String) -> Unit)? = null
    /** Throw [Killed] when the old BACKUP_SAVE is about to be removed (after the commit). */
    var killOnPointerRemoval = false
    var commits = 0

    init { root.mkdirs() }

    private fun dir(farm: String) = File(root, farm)

    override fun listFarmsJson(): String {
        val arr = JSONArray()
        root.listFiles().orEmpty().filter { it.isDirectory && SavePaths.isFarmFolder(it.name) }.sortedBy { it.name }.forEach { d ->
            val files = JSONArray()
            d.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".sstmp") }.sortedBy { it.name }.forEach {
                files.put(JSONObject().put("name", it.name).put("size", it.length()).put("mtime", it.lastModified()))
            }
            arr.put(JSONObject().put("name", d.name).put("files", files))
        }
        return arr.toString()
    }

    override fun md5(relPath: String): String {
        val f = SavePaths.resolve(root, relPath)
        return if (f.isFile) md5(f) else ""
    }

    override fun openRead(relPath: String): InputStream = SavePaths.resolve(root, relPath).inputStream()

    override fun isGameRunning() = gameRunning

    override fun stage(farm: String, fileName: String, source: File, md5: String) {
        require(fileName in SavePaths.stageable(farm))
        if (killOnStage) { killOnStage = false; throw Killed() }
        check(!gameRunning) { "Stardew is running" }
        val tmp = File(dir(farm), SavePaths.stagingName(fileName))
        source.copyTo(tmp, overwrite = true)
        check(md5(tmp) == md5) { "Checksum mismatch" }
    }

    override fun commit(farm: String, fileNames: List<String>) {
        beforeCommit?.invoke(farm)
        check(!gameRunning) { "Stardew is running" }
        commits++
        fileNames.forEachIndexed { i, name ->
            if (killAfterRenames == i) { killAfterRenames = null; throw Killed() }
            if (failAfterRenames == i) { failAfterRenames = null; error("disk error") }
            val tmp = File(dir(farm), SavePaths.stagingName(name))
            check(tmp.isFile) { "Not staged: $name" }
            check(tmp.renameTo(File(dir(farm), name)))
        }
        if (killAfterRenames == fileNames.size) { killAfterRenames = null; throw Killed() }
        afterCommit?.let { afterCommit = null; it(farm) }
        if (failAfterRenames == fileNames.size) { failAfterRenames = null; error("disk error") }
    }

    override fun removeIntroducedFile(farm: String, fileName: String, md5: String) {
        require(fileName == "${farm}_old" || fileName == "SaveGameInfo_old")
        check(!gameRunning)
        val f = File(dir(farm), fileName)
        check(md5(f) == md5)
        check(f.delete())
    }

    override fun discardStaged(farm: String) {
        SavePaths.stageable(farm).forEach { File(dir(farm), SavePaths.stagingName(it)).delete() }
    }

    override fun removeMiddayPointer(farm: String): Boolean {
        val p = File(dir(farm), SavePaths.POINTER)
        if (!p.exists()) return false
        if (killOnPointerRemoval) { killOnPointerRemoval = false; throw Killed() }
        check(!gameRunning)
        return p.delete()
    }

    override fun createFarmFolder(farm: String) {
        check(!gameRunning)
        check(!failCreateFolder) { "mkdir failed" }
        check(dir(farm).mkdir())
    }

    // ---------------------------------------------------------------- test helpers

    /** Writes a farm as the game would: main save, summary, optional pointer. */
    fun writeFarm(
        farm: String,
        day: Int,
        playedMs: Long,
        version: String = "1.6.15.3",
        midday: Boolean = false,
        savedAt: Long = 1_000_000L + playedMs,
    ) {
        val d = dir(farm).apply { mkdirs() }
        val id = farm.substringAfterLast('_')
        File(d, farm).writeText(mainXml(id, version, day))
        File(d, "SaveGameInfo").writeText(infoXml(version, day, playedMs))
        File(d, "SaveGameInfo").setLastModified(savedAt)
        if (midday) File(d, SavePaths.POINTER).writeBytes(ByteArray(52) { day.toByte() }) else File(d, SavePaths.POINTER).delete()
    }

    fun farm(name: String): Farm? = FarmScanner(this).scan().firstOrNull { it.folder == name }
    fun file(farm: String, name: String) = File(dir(farm), name)
    fun hasPointer(farm: String) = file(farm, SavePaths.POINTER).exists()

    companion object {
        fun mainXml(id: String, version: String, day: Int) =
            """<?xml version="1.0"?><SaveGame><player><name>Tester</name></player>""" +
                """<uniqueIDForThisGame>$id</uniqueIDForThisGame><gameVersion>$version</gameVersion><dayOfMonth>$day</dayOfMonth></SaveGame>"""

        fun infoXml(version: String, day: Int, playedMs: Long) =
            """<?xml version="1.0"?><Farmer><name>Tester</name><farmName>Test</farmName>""" +
                """<gameVersion>$version</gameVersion><yearForSaveGame>1</yearForSaveGame>""" +
                """<seasonForSaveGame>1</seasonForSaveGame><dayOfMonthForSaveGame>$day</dayOfMonthForSaveGame>""" +
                """<millisecondsPlayed>$playedMs</millisecondsPlayed></Farmer>"""
    }
}

/** One simulated device: its own Saves folder, snapshots, journal and sync memory. */
class Device(base: File, name: String, var clock: Long = 1_000_000L) {
    val fs = FakeSaveFs(File(base, "$name/Saves"))
    val store = SnapshotStore(File(base, "$name/snapshots"), name) { clock++ }
    val journal = WriteJournal(File(base, "$name/journal"))
    val temp = File(base, "$name/tmp").apply { mkdirs() }
    val state = FakeState(name)
}

class FakeState(override val deviceName: String) : SyncState {
    override val deviceId = "id-$deviceName"
    val bases = HashMap<String, String?>()
    val forced = HashSet<String>()
    val disabled = HashSet<String>()
    override fun base(farm: String) = bases[farm]
    override fun setBase(farm: String, version: String?) { bases[farm] = version }
    override fun isForced(farm: String) = farm in forced
    override fun setForced(farm: String, on: Boolean) { if (on) forced += farm else forced -= farm }
    override fun setEnabled(farm: String, on: Boolean) { if (on) disabled -= farm else disabled += farm }
}

/** The shared Drive vault, in memory. Uploads are immutable, like the real one. */
class FakeVault(private val dir: File) : VersionVault {
    val all = mutableListOf<VersionEntry>()
    private val farmOf = HashMap<String, String>()
    /** Runs during a download (e.g. the player saving meanwhile). */
    var duringDownload: (() -> Unit)? = null
    /** Uploads become visible only after this many further listings (Drive lag). */
    var hideUploadsFor = 0
    private val hidden = HashMap<String, Int>()
    private var n = 0

    init { dir.mkdirs() }

    override fun farms() = farmOf.values.distinct()

    /** Uploads fail (no connection). */
    var failUploads = false

    /** Returned (once) by the next listing instead of the real state: a device's stale view. */
    var staleOnce: List<VersionEntry>? = null

    override fun versions(farm: String): List<VersionEntry> {
        staleOnce?.let { staleOnce = null; return it.filter { v -> farmOf[v.id] == farm } }
        hidden.keys.toList().forEach { k -> hidden[k] = hidden.getValue(k) - 1; if (hidden.getValue(k) < 0) hidden.remove(k) }
        return all.filter { farmOf[it.id] == farm && it.id !in hidden }
    }

    override fun upload(farm: String, v: VersionEntry, zip: File): VersionEntry {
        if (failUploads) throw java.io.IOException("offline")
        val fileId = "file${n++}"
        zip.copyTo(File(dir, fileId))
        // Through the real Drive property encoding, so its limits apply here too.
        val stored = VersionProps.decode(fileId, VersionProps.encode(v))!!
        all += stored
        farmOf[stored.id] = farm
        if (hideUploadsFor > 0) hidden[stored.id] = hideUploadsFor
        return stored
    }

    fun zipOf(v: VersionEntry): File = File(dir, v.fileId)

    override fun markLost(v: VersionEntry) {
        val i = all.indexOfFirst { it.id == v.id }
        all[i] = VersionProps.decode(all[i].fileId, VersionProps.encode(all[i]) + ("lost" to "1"))!!
    }

    override fun download(v: VersionEntry, dest: File) {
        File(dir, v.fileId).copyTo(dest, overwrite = true)
        duringDownload?.invoke()
    }
}
