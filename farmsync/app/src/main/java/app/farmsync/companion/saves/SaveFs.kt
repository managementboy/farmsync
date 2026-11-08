package app.farmsync.companion.saves

import android.os.ParcelFileDescriptor
import app.farmsync.companion.ISaveFs
import java.io.File
import java.io.InputStream

/**
 * Access to the Saves folder as the app's code sees it. Production: [AidlSaveFs] over the
 * Shizuku service. Tests: an in-memory fake with fault injection. Keeping this a plain Kotlin
 * interface lets the save-safety logic (SaveOps, recovery, sync) run in JVM unit tests.
 */
interface SaveFs {
    fun listFarmsJson(): String
    fun md5(relPath: String): String
    fun openRead(relPath: String): InputStream
    fun isGameRunning(): Boolean
    /** Writes a staging copy of one allowed file; the live file is untouched. */
    fun stage(farm: String, fileName: String, source: File, md5: String)
    /** Renames staged files over the live ones in [fileNames] order. */
    fun commit(farm: String, fileNames: List<String>)
    fun discardStaged(farm: String)
    /** Removes an `_old` file a failed write added, only if it still has [md5]. */
    fun removeIntroducedFile(farm: String, fileName: String, md5: String)
    fun removeMiddayPointer(farm: String): Boolean
    fun createFarmFolder(farm: String)
}

class AidlSaveFs(private val s: ISaveFs) : SaveFs {
    override fun listFarmsJson(): String = s.listFarmsJson()
    override fun md5(relPath: String): String = s.md5(relPath)
    override fun openRead(relPath: String): InputStream = ParcelFileDescriptor.AutoCloseInputStream(s.openRead(relPath))
    override fun isGameRunning(): Boolean = s.isGameRunning()
    override fun stage(farm: String, fileName: String, source: File, md5: String) =
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { s.stageFile(farm, fileName, it, md5) }
    override fun commit(farm: String, fileNames: List<String>) = s.commitStaged(farm, fileNames.toTypedArray())
    override fun discardStaged(farm: String) = s.discardStaged(farm)
    override fun removeIntroducedFile(farm: String, fileName: String, md5: String) = s.removeIntroducedFile(farm, fileName, md5)
    override fun removeMiddayPointer(farm: String): Boolean = s.removeMiddayPointer(farm)
    override fun createFarmFolder(farm: String) = s.createFarmFolder(farm)
}
