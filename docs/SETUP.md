# Setting up Farm Sync at home

This guide is for anyone who plays Stardew Valley on more than one Android device and wants their farms backed up and kept in sync through their own Google Drive.

You don't need a computer, root, or developer knowledge. Setup takes about 15 minutes per device, and you do it once.

> Farm Sync was written by an AI (Claude, by Anthropic) for a household without a single developer in it. It was tested on two Samsung devices, reviewed several times, and it makes a verified backup before it changes any save. It is still a hobby project with no warranty of any kind, so keep your own backups of farms you care about.

## What you need

- Android 13 or newer on each device, with Stardew Valley installed from Google Play (tested with 1.6.15).
- A Google account to keep your farms in. Every device that shares farms must use the same account; separate players can each use their own.
- Wi-Fi. Farm Sync relies on Shizuku, Shizuku starts over Wireless debugging, and Android only allows that on Wi-Fi.

## Why there are a few extra steps

Since Android 11, an app may not read another app's files, and Stardew's save folder counts. No permission an app can ask for gets around this.

[Shizuku](https://shizuku.rikka.app/) is a free, open-source helper that gives apps the same access a developer's USB cable has, and only to apps you approve. Farm Sync asks Shizuku for one thing: reading and writing the Stardew save folder. It touches nothing else on your device.

You start Shizuku through Android's **Wireless debugging**, which sits in the hidden **Developer options**. Steps 1 to 3 cover that.

## Step 1: Turn on Developer options

1. Open **Settings → About phone** (on a tablet, **About tablet**).
2. On a Samsung, tap **Software information**.
3. Tap **Build number** seven times and enter your PIN if asked. Android confirms that developer mode is on.

**Developer options** now appears at the bottom of Settings.

> **Samsung devices:** Samsung's **Auto Blocker** (Settings → Security and privacy → Auto Blocker) blocks Wireless debugging, so Shizuku can't start while it is on. You have to switch Auto Blocker off to use Farm Sync.
>
> Auto Blocker also stops apps from outside the Play Store and some USB attacks, so switching it off is a trade-off you should decide on for yourself.

## Step 2: Install Shizuku

Install **Shizuku** from Google Play (developer: Rikka Apps) or from its [GitHub releases](https://github.com/RikkaApps/Shizuku/releases). Farm Sync was tested with Shizuku 13.6.

## Step 3: Start Shizuku with Wireless debugging (once per device)

1. Connect to your Wi-Fi.
2. Open Shizuku and choose **Start via Wireless debugging → Pairing**. Shizuku shows a notification where you will type a code; allow notifications if it asks.
3. Open **Settings → Developer options → Wireless debugging**, switch it on, and tap **Pair device with pairing code**. A six-digit code appears.
4. Type that code into Shizuku's notification. It reports that pairing worked.
5. Back in Shizuku, tap **Start**. It should now say "Shizuku is running".

To keep it running after a restart, turn on **Start on boot (wireless debugging)** in Shizuku's settings. Some devices still need Wireless debugging switched on by hand after a restart. Farm Sync tells you when Shizuku isn't running and opens the right setting for you.

Wireless debugging only works on Wi-Fi networks you have allowed. On a new network, or when Wireless debugging is off, Shizuku stops. Your farms stay as they are; Farm Sync waits until Shizuku runs again.

## Step 4: Install Farm Sync

1. On the device, open the project's [Releases page](https://github.com/managementboy/farmsync/releases) and download `farm-sync-1.0.0.apk`.
2. Open the downloaded file. Android asks whether your browser or file manager may install apps; allow it for this install.
3. Open **Farm Sync**. When it asks for Shizuku permission, tap **Allow**.

Farm Sync lists your farms and makes a first verified backup of each one. It follows your device language (English or Spanish).

## Step 5: Link your Google Drive (once per device)

1. In Farm Sync, tap **Link Google Drive**.
2. Choose the Google account for your farms. Use the same account on every device that should share them.
3. On the first device, Farm Sync asks to create its vault, a folder called "Farm Sync" in that Drive. Tap **Create vault**. Later devices find the existing vault.

Farm Sync can only see the files it created in your Drive (Google's `drive.file` permission). It cannot see anything else there, and it sends nothing to anyone else.

From now on, every farm syncs automatically each time you open Farm Sync. You can switch sync off for a single farm on that farm's page.

## Everyday use

1. Finish playing and sleep in-game, so the day is saved.
2. Close Stardew completely by swiping it away in Recent apps. Farm Sync never writes while the game is still in memory, not even in the background.
3. Open Farm Sync on that device. It uploads the new day.
4. On the other device, open Farm Sync before you start Stardew. It downloads the new day.
5. Play.

A few situations Farm Sync handles by itself:

- If you played the same farm on two devices since the last sync, the version with more play time continues everywhere. The other version is kept, in Drive and as a backup on its device.
- If a device has unsaved progress from the middle of a day (the game's "continue where you left off"), Farm Sync leaves it alone. A newer day from another device waits until you sleep in-game. You can also tap **Download anyway**, and Farm Sync backs up your mid-day state first.
- A farm you deleted on one device is never put back there by itself. It stays in Drive, and you can download it again whenever you want.
- Farm Sync keeps the last 30 backups of every farm on each device, plus any backups you **Pin**.

To go back to an earlier day, open the farm, pick a backup and tap **Restore**. With sync on, the restored day becomes the shared version on all your devices at the next sync. Farm Sync backs up the current state first, so you can undo a restore.

To move a farm as a file, share a backup as a zip from the farm's backup menu, and bring a zip in with **Import** in the ⋮ menu. The zip has the same folder layout as a PC save.

## If something doesn't work

| What you see | What to do |
|---|---|
| "Shizuku is not running" | Connect to Wi-Fi, switch on Wireless debugging, open Shizuku and tap **Start**. |
| Shizuku can't start on a Samsung | Switch off Auto Blocker (step 1). |
| "Stardew is still open" | Swipe Stardew away in Recent apps, then sync again. |
| A day doesn't arrive on the other device | Open Farm Sync on the device you played on first, and wait for "Uploaded to Drive". Then open it on the other one. |
| "Newer day waits until you sleep in-game" | This device has unsaved mid-day progress. Sleep in-game, or tap **Download anyway**. |
| "Google Drive access was refused" | In Settings, unlink, then link the same account again. |
| Two copies of Farm Sync (Samsung Dual Messenger or Secure Folder) | Install it only in your normal profile; the other profile can't see the game's saves. |

The Settings screen lists the syncs of the last days, with what happened to each farm and why.

## Removing it

- Uninstalling Farm Sync deletes its backups on that device. Your game saves stay as they are.
- Deleting the "Farm Sync" folder in your Google Drive removes all synced copies.
- You can revoke Drive access at [myaccount.google.com/permissions](https://myaccount.google.com/permissions).
- If you like, switch Wireless debugging and Developer options off again, and Auto Blocker back on.

## Not supported (yet)

- Windows and PC saves. You can move a farm by hand: on a PC, saves are in `%AppData%\StardewValley\Saves\<Farm>_<ID>\`. Zip that folder to import it on Android; Farm Sync's exported zips unpack the same way.
- iPhone and iPad.
- Background sync. Syncing only when you open the app saves battery, and you always know when it happened.

Project page: [elkin.de/farm-sync](https://elkin.de/farm-sync/) · Source code: [github.com/managementboy/farmsync](https://github.com/managementboy/farmsync) · Privacy policy: [elkin.de/farm-sync-privacy](https://elkin.de/farm-sync-privacy/)

*Stardew Valley is a trademark of ConcernedApe LLC. Farm Sync is an independent fan project and is not affiliated with or endorsed by ConcernedApe, Chucklefish, Google or Rikka Apps.*
