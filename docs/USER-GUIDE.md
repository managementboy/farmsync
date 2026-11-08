# Using Farm Sync

This guide explains every screen of Farm Sync 1.0 and what to do in everyday situations. If the app isn't set up yet, start with the [setup guide](SETUP.md).

Farm Sync shows its texts in your device language, English or Spanish. This guide gives the English label first and the Spanish one in brackets, for example **Restore** (*Restaurar*).

## The idea

Farm Sync does two things for your Stardew Valley farms:

1. It keeps verified backups of every farm on each device.
2. When you link a Google account, it keeps each farm the same on all your devices through a "Farm Sync" folder in that account's Drive.

All of this happens when you open the app. Nothing runs in the background, and Farm Sync never touches a save while Stardew is still open, not even in the background.

## The main screen

### The banner at the top

The banner shows the most important thing right now:

| Banner | What it means | What to do |
|---|---|---|
| **All synced** (*Todo sincronizado*), with the time of the last sync | Everything is in order. | Nothing. |
| **Syncing…** (*Sincronizando…*) | Farm Sync is backing up or syncing. | Wait a few seconds. |
| **Farm Sync can't reach your saves** (*Farm Sync no puede acceder a tus partidas*) | Shizuku isn't running. | Connect to your Wi-Fi, switch on Wireless debugging and start Shizuku. The button **Open Developer options** (*Abrir Opciones de desarrollador*) takes you there. |
| **Farm Sync needs permission in Shizuku** (*Farm Sync necesita permiso en Shizuku*) | Shizuku runs, but Farm Sync isn't allowed yet. | Tap **Grant permission** (*Conceder permiso*). |
| **Close Stardew to download** (*Cierra Stardew para descargar*) | Stardew is still in memory, so Farm Sync won't write. | Swipe Stardew away in Recent apps, then open Farm Sync again. |
| **1 farm needs a look** (*1 granja necesita atención*) | A sync failed or a farm needs repair. | Open the farm to see why. |
| **Google Drive: not linked** (*Google Drive: sin vincular*) | Backups work, sync doesn't. | Tap **Link Google Drive** (*Vincular Google Drive*) if you want sync. |

### Your farms

Each farm on this device has a card:

- A calendar tile with the in-game day, season and year. Its colour follows the season.
- The farm's name, the farmer and the play time.
- A sync status line (see [What the sync status means](#what-the-sync-status-means)).
- **☀ mid-day** (*☀ a mitad del día*) if the game has unsaved progress from the middle of a day, the "continue where you left off" state.

Tap a card to open the farm's page.

### Not on this device (kept in snapshots)

Under **Not on this device** (*No están en este dispositivo*) you find farms that used to be here and are now gone, for example because you deleted them in the game. Their backups are kept on this device. Farm Sync never puts these farms back by itself. To bring one back, see [Bringing back a deleted farm](#bringing-back-a-deleted-farm).

### In Google Drive, not on this device

Under **In Google Drive, not on this device** (*En Google Drive, no en este dispositivo*) you find farms that another device synced and that never were on this one. Each row says which device it came from and when. Tap **Download to this device** (*Descargar a este dispositivo*) to add it. Farm Sync never downloads a new farm without you tapping that.

### The buttons at the top

- The sync button (two arrows) syncs now. You rarely need it, because opening the app syncs anyway.
- The ⋮ menu has:
  - **Import** (*Importar*): add or replace a farm from a zip file.
  - **Snapshot all** (*Copiar todas*): make a backup of every farm right now.
  - **Settings** (*Ajustes*).
  - **About Farm Sync** (*Acerca de Farm Sync*).

## What the sync status means

| Status | Meaning |
|---|---|
| **Synced** (*Sincronizada*) | This device has the newest version. |
| **Uploaded to Drive** (*Subida a Drive*) | You played here; the new day is now in Drive for your other devices. |
| **Newer day downloaded** (*Día nuevo descargado*) | Another device played further; this device now has that day. A backup of the previous state was made first. |
| **Played on two devices: kept this one** (*Jugada en dos dispositivos: se quedó esta*) | The farm was played here and elsewhere since the last sync. This device played longer, so its version continues everywhere. The other version stays in Drive. |
| **Played on two devices: took the other one** (*Jugada en dos dispositivos: se tomó la otra*) | Same situation, but the other device played longer. This device now has that version; its own is kept in a backup and in Drive. |
| **Newer day waits until you sleep in-game** (*El día nuevo espera a que duermas en el juego*) | Drive has a newer day, but this device has unsaved mid-day progress. Farm Sync won't overwrite it. See [Mid-day progress](#mid-day-progress). |
| **Close Stardew to download** (*Cierra Stardew para descargar*) | A newer day is waiting, but Stardew is still open. |
| **Played meanwhile, syncs next time** (*Se jugó mientras tanto, se sincroniza la próxima vez*) | You saved a day while the sync was running. Nothing was replaced; the next sync uploads your new day. |
| **Not synced yet** (*Aún sin sincronizar*) | The farm hasn't been synced on this device yet. |
| **Sync off** (*Sin sincronizar*) | You switched sync off for this farm. |
| **Deleted on this device** (*Borrada en este dispositivo*) | The farm was deleted here after it had synced, so Farm Sync won't bring it back. |
| **Interrupted write, repair pending** (*Escritura interrumpida, reparación pendiente*) | A write to this farm was cut off (app closed, phone switched off). Close Stardew and open Farm Sync again; it repairs this before anything else. |
| **Couldn't sync** (*No se pudo sincronizar*) | Something went wrong, for example no internet. The farm's page says why. The next sync tries again. |

## A farm's page

Tap a farm on the main screen to open its page.

- At the top are the day tile, the farm's name, the farmer and the play time.
- **Sync with Google Drive** (*Sincronizar con Google Drive*) switches sync on or off for this farm on this device. Every farm syncs unless you switch it off here. Below the switch is the sync status, and a reason if a sync failed.
- If a newer day waits because of mid-day progress, this card explains it and offers **Download anyway** (*Descargar igualmente*).
- **Backups** (*Copias de seguridad*) lists every backup of this farm on this device, newest first, with when it was made, the in-game day and how it was made. The backup that matches the farm right now is marked **Same as on this device** (*Igual que en este dispositivo*).
- **Technical details** (*Detalles técnicos*) shows the folder name, the game version and the files, which is useful if you ever need to find a farm by hand.
- The **Create backup** (*Crear copia*) button at the bottom makes a backup right now.
- The ⋮ menu at the top has **Share farm** (*Compartir granja*), which sends the current farm as a zip through Android's share sheet.

Each backup row has its own ⋮ menu:

- **Restore…** (*Restaurar…*) puts this backup back. It is only offered when the backup differs from the current state.
- **Pin** (*Fijar*) keeps this backup forever, outside the automatic clean-up. **Unpin** (*Soltar*) undoes that.
- **Share** (*Compartir*) sends this backup as a zip.

## Backups

**When backups are made.** Farm Sync makes a backup:

| Label | When |
|---|---|
| automatic (*automática*) | When you open the app and the farm changed since its last backup. |
| manual (*manual*) | When you tap **Create backup** or **Snapshot all**. |
| before restore (*antes de restaurar*) | Right before a restore replaces the farm. |
| before import (*antes de importar*) | Right before an import replaces the farm. |
| before sync download (*antes de descargar*) | Right before a newer day from Drive replaces the farm. |
| for sync (*para sincronizar*) | When a farm is uploaded and no matching backup exists yet. |

**What a backup contains.** A backup holds the whole farm folder, including the mid-day files and the game's "continue where you left off" marker. Farm Sync checks each backup three times: while copying, against the device afterwards, and by reading the stored zip back.

**How many are kept.** Farm Sync keeps the newest 30 backups of each farm on each device, plus every pinned one. The newest three are never removed. Backups live inside the app, so uninstalling Farm Sync deletes them.

## Restoring an earlier day

1. Open the farm and find the backup you want.
2. Tap its ⋮ menu, then **Restore…** (*Restaurar…*).
3. The dialog shows the day on the device now and the day in the backup. It warns you if you are going back in time, and if this device has mid-day progress.
4. Tap **Restore** (*Restaurar*).

Before writing, Farm Sync:

- makes a backup of the current state;
- checks that Stardew is closed;
- checks that the backup is a complete, valid save of this farm, and from a game version you have installed.

It then writes all files and verifies them. If anything goes wrong, it puts the previous state back and verifies that too.

With sync on, a restored day becomes the shared version on all your devices at the next sync, even if it has less play time. That is on purpose: you chose that day.

## Mid-day progress

When you leave Stardew in the middle of a day, the game keeps that progress and offers "continue where you left off" next time. Farm Sync shows this as **☀ mid-day**.

Farm Sync doesn't overwrite mid-day progress without asking. If another device has a newer day, the farm shows **Newer day waits until you sleep in-game**. You have two choices:

- Play on and sleep in-game. Your new day is then uploaded, and if both devices played, the one with more play time continues.
- Tap **Download anyway** (*Descargar igualmente*) on the farm's page. Farm Sync backs up your mid-day state first, then takes the newer day. The game won't offer to continue the mid-day state afterwards.

## How sync decides

Each time you open Farm Sync, it looks at every farm:

- **Played here since the last sync, nowhere else:** your day is uploaded.
- **Played elsewhere, not here:** the newer day is downloaded, after a backup.
- **Played on both devices:** your day is uploaded first, so nothing is lost. Then the version with more play time continues everywhere, and the other one stays in Drive.
- **Restored or imported here on purpose:** your version becomes the shared one.
- **Unsaved mid-day progress here:** a newer day waits (see above).
- **Stardew still open:** nothing is written; you see **Close Stardew to download**.
- **Deleted here:** it stays deleted here.

Two devices that open Farm Sync at the same moment can't overwrite each other: every upload is a separate file in Drive, and both devices work out the same newest version.

## Everyday situations

### Playing on the phone, then on the tablet

1. On the phone: play, sleep in-game, swipe Stardew away, open Farm Sync. It shows **Uploaded to Drive**.
2. On the tablet: open Farm Sync before Stardew. It shows **Newer day downloaded**.
3. Play on the tablet.

### Played on both devices by mistake

Open Farm Sync on both. The version with more play time continues on both, and you'll see **Played on two devices: …**. The other version is not lost: it is a backup on the device that had it, and it stays in Drive. If you prefer that one, restore it from the backup list on that device; it then becomes the shared version.

### Going back a day

Restore the backup from the day you want (see [Restoring an earlier day](#restoring-an-earlier-day)). With sync on, your other devices get that day at their next sync.

### A new phone or tablet

Set up Shizuku and Farm Sync on the new device and link the same Google account. Farm Sync finds your existing vault by itself. Your farms appear under **In Google Drive, not on this device**. Tap **Download to this device** for each farm you want.

### A lost or broken device

Nothing to do on the lost device. Your farms are in Drive as of the last time that device synced. Set up a new device as above.

### Stop syncing one farm on one device

Open the farm and switch **Sync with Google Drive** off. Backups continue; the farm just isn't synced from or to this device. Switch it back on to sync again.

### Removing a farm from one device

Delete it in Stardew. Farm Sync sees it is gone, keeps its backups under **Not on this device**, and won't download it again on this device. The farm stays in Drive and on your other devices.

### Bringing back a deleted farm

Open the farm under **Not on this device**, open a backup's ⋮ menu and tap **Share** (*Compartir*). Save the zip, for example to Files. Then use **Import** in the main ⋮ menu and pick that zip. With sync on, the imported farm becomes the shared version.

### Moving a farm to or from a PC

From Android to PC: open the farm, tap **Share farm**, and send the zip to your PC. Unpack it into `%AppData%\StardewValley\Saves\`. The zip holds the farm's folder plus a small `meta.json` file, which you can delete.

From PC to Android: zip the farm's folder from `%AppData%\StardewValley\Saves\` (the folder named like `Farm_123456789`), copy the zip to the device, and use **Import**. Close Stardew on the device first.

Farm Sync checks an imported zip before writing anything:

- it must hold exactly one farm with its main file and SaveGameInfo;
- the save must belong to that farm;
- the files must be complete and valid;
- the game version must be one installed on this device.

### Giving a farm to a friend

Share the farm or one of its backups as a zip. Your friend can import it with Farm Sync, or copy it into the game's save folder on a PC.

## Settings

The **Settings** (*Ajustes*) screen has:

- **Google Drive** with the linked account and **Unlink** (*Desvincular*). Unlinking stops sync on this device and keeps everything else: your farms, backups and the data in Drive. Linking the same account again carries on where it stopped.
- **Diagnostics** (*Diagnóstico*): whether Shizuku runs and Farm Sync may use it, the installed Stardew version, and the Farm Sync version.
- **Sync log** (*Registro de sincronización*): the last 30 sync results, per farm, with what happened and, for failures, why.
- **About Farm Sync**: version, links, licences, and the note that the app was made with AI.

## After a crash or power cut

If Farm Sync was closed in the middle of writing a farm, it repairs that the next time it opens, before it does anything else:

| Message | Meaning |
|---|---|
| "an interrupted write was finished and checked" (*se terminó y comprobó una escritura interrumpida*) | The write had completed; only the final check was left. |
| "an interrupted write was undone. The farm is as it was before." (*se deshizo una escritura interrumpida*) | Farm Sync put the farm back to its state before that write. |
| "an interrupted write was found, but the farm was played since. It was kept exactly as it is, and a backup was made." (*se encontró una escritura interrumpida, pero la granja se jugó después*) | You played after the interruption. Farm Sync kept your play. |
| "a download did not finish. Download it again to complete it." (*una descarga no terminó*) | Tap **Download to this device** again. |
| "an interrupted write needs repair. Close Stardew completely and reopen Farm Sync." (*una escritura interrumpida necesita reparación*) | Stardew was open, so Farm Sync waited. |

## What Farm Sync will never do

- Write while Stardew is in memory, or close Stardew for you.
- Change a save without a verified backup first.
- Install a save from a newer game version than yours.
- Put back a farm you deleted.
- See anything in your Drive except its own folder, or send your data anywhere else.
- Sync in the background.

## Getting help

The project lives at [github.com/managementboy/farmsync](https://github.com/managementboy/farmsync), with the source code, the setup guide and this guide. Farm Sync was written by an AI (Claude) for a household without developers, and it is a hobby project with no warranty of any kind. The [project page](https://elkin.de/farm-sync/) has more background.
