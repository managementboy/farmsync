# Google Cloud setup for Farm Sync (done 2026-11-08)

| Item | Value |
|---|---|
| Google account | managementboy@gmail.com |
| Project | **Farm Sync**, ID `farm-sync-514113`, no organisation |
| API | Google Drive API, enabled |
| Consent screen | App name "Farm Sync", audience **External**, status **In production** (no verification needed: no logo, non-sensitive scope only, 1 domain) |
| Homepage | https://elkin.de/farm-sync/ (WordPress page 49068) |
| Privacy policy | https://elkin.de/farm-sync-privacy/ (WordPress page 49069; source: `docs/privacy-policy.md`) |
| Authorized domain | `elkin.de` |
| Scope | `https://www.googleapis.com/auth/drive.file` only |
| OAuth client | Type **Android**, name "Farm Sync Android (household key)", package `app.farmsync.companion`, SHA-1 `CD:9F:5D:E4:1C:32:7A:68:F1:FF:0E:73:DA:E6:04:35:E1:2B:0B:84` |
| Client ID | `986878340508-…apps.googleusercontent.com` (not needed in the app: Android clients are matched by package name + signing certificate) |

## Notes
- Google says new OAuth settings can take **5 minutes to a few hours** to take effect.
- The SHA-1 belongs to the household signing key `~/.farmsync/farmsync-household.keystore`. If the key ever changes, add a second Android client with the new SHA-1. Never replace the key.
- No client secret exists for Android clients, so nothing secret lives in the repo.
