package app.farmsync.companion;

// File access to the Stardew Saves folder, served by SaveFsService running as
// the shell user via Shizuku. All paths are RELATIVE to the Saves folder and
// are rejected if they escape it or pass through a symbolic link (rule R4).
// Writes are deliberately narrow: stage + commit of the standard save files of one
// farm (and its BACKUP_SAVE pointer, only to roll back), removing that pointer, and
// creating a new empty farm folder. There is no general delete.
interface ISaveFs {
    // Called by the Shizuku server to stop the service. Code fixed by Shizuku.
    void destroy() = 16777114;

    // JSON: [{"name":"Farm_123","files":[{"name":..,"size":..,"mtime":..}]}]
    // Only real farm folders and regular files; links are skipped.
    String listFarmsJson() = 1;

    // Lowercase hex md5 of a file, or "" if it does not exist.
    String md5(String relPath) = 2;

    // Read-only descriptor for a file inside Saves.
    ParcelFileDescriptor openRead(String relPath) = 3;

    // True while any Stardew process exists (rule R2: not just foreground).
    boolean isGameRunning() = 4;

    // uid the service runs as (2000 = shell), for the setup screen.
    int serviceUid() = 5;

    // (6 was writeStandardFile, replaced by stageFile + commitStaged.)

    // Removes <farm>/BACKUP_SAVE (the "restore where you left off" pointer, R7).
    // The caller must have snapshotted the farm first (R1). Returns false if absent.
    boolean removeMiddayPointer(String farm) = 7;

    // Creates a NEW, empty farm folder (for importing a farm that is not on this device).
    // Fails if it already exists or the name is not like Name_12345. Never deletes anything.
    void createFarmFolder(String farm) = 8;

    // Writes <farm>/<fileName>.sstmp (new file, links never followed) and checks its md5.
    // fileName: <farm>, <farm>_old, SaveGameInfo, SaveGameInfo_old or BACKUP_SAVE.
    // The live file is not touched. Refuses while the game is running (R2).
    void stageFile(String farm, String fileName, in ParcelFileDescriptor source, String expectedMd5) = 9;

    // Renames the staged files over the live ones, in the given order (R3).
    // Checks first that the game is not running and every named file is staged.
    void commitStaged(String farm, in String[] fileNames) = 10;

    // Removes this farm's *.sstmp staging files (never live files).
    void discardStaged(String farm) = 11;
}
