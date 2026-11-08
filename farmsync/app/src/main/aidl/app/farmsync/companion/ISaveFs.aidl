package app.farmsync.companion;

// File access to the Stardew Saves folder, served by SaveFsService running as
// the shell user via Shizuku. All paths are RELATIVE to the Saves folder and
// are rejected if they escape it (rule R4).
// Writes are deliberately narrow: only the four standard save files of one farm
// (writeStandardFile) and removing that farm's BACKUP_SAVE pointer. No general delete.
interface ISaveFs {
    // Called by the Shizuku server to stop the service. Code fixed by Shizuku.
    void destroy() = 16777114;

    // JSON: [{"name":"Farm_123","files":[{"name":..,"size":..,"mtime":..}]}]
    String listFarmsJson() = 1;

    // Lowercase hex md5 of a file, or "" if it does not exist.
    String md5(String relPath) = 2;

    // Read-only descriptor for a file inside Saves.
    ParcelFileDescriptor openRead(String relPath) = 3;

    // True while any Stardew process exists (rule R2: not just foreground).
    boolean isGameRunning() = 4;

    // uid the service runs as (2000 = shell), for the setup screen.
    int serviceUid() = 5;

    // Atomically replaces one standard file of an EXISTING farm folder (R3, R4):
    // fileName must be <farm>, <farm>_old, SaveGameInfo or SaveGameInfo_old.
    // Writes <file>.sstmp, checks its md5 against expectedMd5, then renames over the target.
    // Refuses while the game is running (R2). Throws on any problem; target is untouched then.
    void writeStandardFile(String farm, String fileName, in ParcelFileDescriptor source, String expectedMd5) = 6;

    // Removes <farm>/BACKUP_SAVE (the "restore where you left off" pointer, R7).
    // The caller must have snapshotted the farm first (R1). Returns false if absent.
    boolean removeMiddayPointer(String farm) = 7;

    // Creates a NEW, empty farm folder (for importing a farm that is not on this device).
    // Fails if it already exists or the name is not like Name_12345. Never deletes anything.
    void createFarmFolder(String farm) = 8;
}
