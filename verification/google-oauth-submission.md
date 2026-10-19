# Google OAuth verification submission — Gestore File

Status: preparation only. Do not claim Google approval or successful live-account testing until evidence is available.

## Application details

- App: Gestore File, Android 11+, package `it.liberofiles.app`.
- Project: `gestore-file-android`.
- Homepage: https://danielperrella.github.io/Gestore-File/
- Privacy: https://danielperrella.github.io/Gestore-File/privacy.html
- Source and downloads: https://github.com/danielperrella/Gestore-File
- Android client: 673519237675-6l86pgbevpjcgt9p4mer26ilj6jrrdj6.apps.googleusercontent.com
- Requested scope: https://www.googleapis.com/auth/drive

## Scope justification (submission text)

Gestore File is a user-operated Android file manager for local storage, removable storage and Google Drive. Users connect their own Google accounts to browse their existing My Drive hierarchy and explicitly select files and folders for download, upload, copy, move, rename, trash, restore, opening or export. The app must enumerate pre-existing files and folders, including items not created by Gestore File, and modify only items selected by the user.

The drive.file scope cannot implement this existing-Drive file-manager workflow because it restricts access to files created by the app or individually shared with the app through a picker; it does not allow browsing and managing the user's full pre-existing My Drive hierarchy. Read-only scopes cannot support user-requested uploads, rename, moves, trash and restore. Only the drive scope is requested. Shared Drives, automatic synchronization, permanent cloud deletion and background server processing are not implemented.

## Data flow (submission text)

Authorization is performed by Google Play services AuthorizationClient on Android. HTTPS requests go directly from the user's Android device to Google Drive API; no developer backend receives OAuth tokens, account data or file contents. Access tokens are held only in process memory. The application privately stores account email/permission ID and file references for selected locations, recent items and favorites. A local operation journal supports recovery notices. Temporary files for upload, opening, sharing and export are kept in the private app cache. Expired temporary files are cleaned at startup; users can disconnect the account to revoke access and remove account-specific cache and references.

File sharing is initiated by the user through Android read-only URI permissions. The app does not sell Google data, use it for advertising or train AI models with it. GitHub hosts source, the static site, APK and update metadata; Google Drive user content and OAuth tokens are not uploaded to GitHub. There is no analytics SDK or advertising SDK in this integration.

The app uses restricted data on the user's device and directly with Google APIs. We request review of this architecture when determining whether a server security assessment applies; we do not assert an exemption on Google's behalf.

## Authentic demonstration required before submission

Record the real Android app in English, using a dedicated test folder and no private files. Do not substitute mock API tests or a simulated login.

1. Show Gestore File version, connect button and account chooser.
2. Show the actual Google authorization screen, app name and requested Drive permission; include the Android OAuth client identity if the platform displays it. Do not fabricate a browser address bar for native authorization.
3. Grant access, browse a pre-existing My Drive folder, and open a harmless text file.
4. Create a folder, upload a small local text file and download it again.
5. Rename and move the test file, send it to Drive trash and restore it. Do not permanently delete anything.
6. Show explicit Google Docs export if available, account switch and disconnect/revocation.
7. Show the privacy link. Upload the finished recording as an unlisted YouTube video and enter its real URL in Google's verification form.

Pending: domain ownership verification, Google's branding result, authentic live demonstration/video URL and data-access submission. Production status alone is not approval.

## Ownership verification completed (2026-10-19 18:50 +02:00)

Google Search Console confirmed ownership of https://danielperrella.github.io/Gestore-File/ using docs/googlee25900306ac79f13.html. Keep this verification file deployed. OAuth audience is Production as explicitly confirmed by the owner; no Google approval is claimed.

Branding's previous automated check reported unverified homepage ownership and requested waiting 24 hours after ownership verification before retrying. The data-access submission remains gated on verified/published branding and an authentic demonstration video. No Android phone is currently connected via adb; no live Google login or demonstration recording has been performed. A simulated authorization must not be submitted as real evidence.
