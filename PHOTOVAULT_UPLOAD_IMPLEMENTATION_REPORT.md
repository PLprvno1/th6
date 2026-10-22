# PhotoVault Upload / Backup implementation report

Date: 2026-10-22

## Audit

Audited the current Android source and `PHOTOVAULT_SUPABASE_FINAL_RESET.sql` before editing. Confirmed Supabase Kotlin BOM 3.0.3 and inspected the cached 3.0.3 Storage Android source JAR. Its `BucketApi.upload(path, uri, options)` opens an input stream and sends a channel; the previous repository instead called `readBytes()` for URI uploads.

- The only manual upload function was in `LibraryFragment`; the associated image picker had no launch call, so the UI path was unreachable. A picker entry is now added to the existing library menu.
- `PhotoBackupManager` scanned images only. `PhotoBackupWorker` uploaded images only and skipped video rows even though `DevicePhotoScanner.scanPhotos()` already queried both MediaStore collections.
- The worker uploaded Storage first and wrote metadata second. Its local ledger could restore a metadata-pending URL, but new object names included time/index, so an upload timeout/retry could create another object. Metadata failure did not delete the object, correctly avoiding unsafe cleanup.
- Dedup used URI and `local_media_id` as if the latter were cross-device stable. It did not use the existing `content_hash` field in the backup manager. MediaStore's persisted `local_date_modified` value was actually chosen from capture/import timestamps before the true modified time.
- Auto backup uses the existing per-user WorkManager unique periodic and immediate work. Manual enqueue uses `KEEP`; worker has a process lock, owner check and preference checks. These mechanisms were retained and the worker now checks active account/session again while processing.
- Cloud photos without a local item were represented in the gallery. Remote videos were not recognized as videos; the player only read local `contentUri`, so cloud-only videos could not open.
- `PhotoUploadViewModel` is present but has no call site in current source; the active library path directly uses `StorageRepository`/`PhotoRepository`.
- SQL matches the inspected `Photo`, `Album`, and profile data models and their PostgREST field names, including `storage_path`, `media_type`, `content_hash`, and owner IDs. No SQL or Dashboard changes were made. Bucket remains public as specified, with a 50 MiB configured file limit and unrestricted MIME types. Upload/update/delete remain authenticated and owner-scoped by the Storage policies in the reset script; public bucket reads are intentionally public.

## Files changed

- `app/src/main/java/com/example/th6/data/StorageRepository.kt` — added SDK URI-stream upload, 50 MiB and readable-URI checks, MIME forwarding, media upload helper; kept image/avatar APIs.
- `app/src/main/java/com/example/th6/services/PhotoBackupManager.java` — queues both images and videos; dedup uses URI and, for unmatched items, available content hashes rather than assuming MediaStore IDs are global; retries a stale cloud row with its existing metadata ID.
- `app/src/main/java/com/example/th6/services/PhotoBackupWorker.java` — stable UID-scoped object path derived from URI + modification time + media type; stream upload and metadata retry; image/video MIME/type handling; current-session/account checks; per-file and uploading progress; rejects files beyond bucket limit through repository validation.
- `app/src/main/java/com/example/th6/services/DevicePhotoScanner.java` — persists actual MediaStore `DATE_MODIFIED` for revision detection.
- `app/src/main/java/com/example/th6/data/PhotoRepository.kt` — deterministic backup IDs include owner UID and preserve an existing matched cloud row ID.
- `app/src/main/java/com/example/th6/activities/BackupCenterActivity.java` and `app/src/main/res/layout/activity_backup_center.xml` — request image/video access for full backup and show scanned/backed-up/pending/uploading/failure detail from worker/scan state.
- `app/src/main/java/com/example/th6/fragments/LibraryFragment.java` — made manual picker reachable, stable owner-scoped image object/metadata IDs, active-session check, size/type validation via repository, ledger recovery when Storage succeeded but metadata failed, remote video recognition, and image-cloud error feedback.
- `app/src/main/res/menu/options_menu_library.xml` — added the manual image-upload menu action.
- `app/src/main/java/com/example/th6/activities/VideoPlayerActivity.java` — opens cloud video URLs and retries the cloud URL if the local copy of a matched item cannot be read.
- `app/src/main/java/com/example/th6/activities/PhotoDetailActivity.java` — shows a clear error if local and cloud image loading both fail.

## Image upload

The existing library menu now opens the picker. The URI goes to the 3.0.3 SDK's URI upload API (stream/channel, not a whole-file byte array), with MIME and a 50 MiB cap. Only after Storage returns a public URL does the app upsert photo metadata and refresh the library. Manual object and metadata IDs are deterministic for the signed-in user and picker URI, so selecting the same URI again retries the same object/row. If metadata fails, an owner-scoped local ledger entry records the URL for later recovery when that URI is visible to MediaStore backup.

## Batch backup, deduplication and auto backup

The existing WorkManager scheduler and worker remain the only batch system. The worker is sequential, retains WorkManager retry/backoff, verifies the active user before each item and before metadata writes, and uses stable paths so a timeout/retry does not invent a new object name. Metadata-pending items reuse the ledger URL/path. Object cleanup is intentionally not attempted. The manager uses same-device URI matches first; for unmatched local items it hashes only when cloud hashes exist, rather than matching solely on a potentially colliding MediaStore ID. True identical-file policy across devices remains conservative and is not enforced by a SQL uniqueness rule.

Auto backup remains per-account, every six hours, network constrained, and uses unique WorkManager jobs. Turning backup off prevents future periodic work; a running file is allowed to finish its current step and the worker observes the preference/account before continuing.

## Video

The scanner already exposed videos, but the previous manager/worker discarded them. Both are now in the same sequential queue; upload metadata uses `media_type = video`, stores `storage_path` and public URL, and derives MIME/extension from MediaStore with filename fallback. Cloud-only video rows become playable items, and local items can fall back to their cloud URL. The 50 MiB bucket cap is enforced. Files larger than this are reported as too large and are not uploaded. This does not claim support for videos above the bucket/project limit or resumable multipart transfer.

## Cloud sync, errors and recovery

Cloud metadata remains queried for the signed-in UID, then merged with local items by URI/hash. A cloud-only media item is retained and opens by public URL; a matched local image/video keeps local-first display/playback with cloud fallback. Metadata-only/unavailable public URLs result in an image error message or the video player's playback error, rather than a success indication.

A Storage success followed by metadata failure retains the object and retry details; it never attempts a speculative object delete. Backup ledger entries are UID-scoped. Logout/account switching stops further worker items; a completed Storage upload before the account change can remain in that account's ledger for later metadata recovery. An item whose local URI permission is revoked fails/retries without being marked backed up. A file whose content/modified timestamp changes receives a different stable path; the metadata row is updated, and the old object may remain orphaned because cross-service deletion is unsafe without stronger ownership/reference proof.

## UI/status

Backup Center uses scan results for total/backed-up/pending and WorkManager progress for currently uploading and failures. Library's existing backup overlay now labels image/video work as media and displays current media type/progress. The existing “Back up now” action rescans and re-enqueues failed/pending items; no second retry worker was added.

## Build and tests

- `app:compileDebugKotlin app:compileDebugJavaWithJavac`: **PASS**.
- `app:assembleDebug`: **PASS** (`BUILD SUCCESSFUL`, 37 tasks).
- SDK API check against cached 3.0.3 source JAR and compile: **PASS** for use of URI upload API.
- Android device/emulator availability: **BLOCKED**; `adb` is not installed/available in this environment.
- Live Supabase upload, database RLS, real account/session expiry, image/video open, network interruption, timeout/retry, file-size boundary, app process death/reboot and UI rotation: **BLOCKED / not run**. No actual project data or Storage objects were changed.
- SQL migration execution and Dashboard validation: **NOT RUN**, as requested.

## Remaining limits

- This is static/build validation, not end-to-end proof against the live bucket. The project-level effective upload limit may be lower than 50 MiB.
- Standard SDK URI upload streams but restarts a failed single-file request; resumable TUS was not added. At the 50 MiB boundary, real network timeout behavior needs device verification.
- Progress reports file counts/current item, not byte-level upload percentage. Failures are summarized per worker run; there is no durable per-file failed-state table.
- Manual picker URIs that are not visible to MediaStore can be retried by selecting the same URI again, but are not discoverable by the library-wide worker after the Activity loses that URI grant.
- Cloud-object existence is not proactively HEAD-checked during metadata sync. Load/play errors are surfaced, but automatic object/metadata reconciliation is not implemented.
- Updated backups of changed media use a new stable path; the previous Storage object is retained and can become orphaned.

## Manual device test checklist

1. Sign in, choose an image using Library → menu → “Tải ảnh lên cloud”; verify URL and metadata appear and the library refreshes.
2. Repeat with a file just under 50 MiB, then one over 50 MiB; confirm the first uploads (subject to project limit) and the second gets a size error.
3. Run Backup Center with image and video permissions; verify one image and one video upload, counts/progress, metadata `media_type`, `storage_path`, URL, and playback.
4. Disable Wi-Fi during upload, restore it and let WorkManager retry; confirm one object path and no duplicate metadata.
5. Revoke URI access or select a removed provider item; confirm it is not counted as backed up.
6. Force a metadata failure in staging after Storage upload, rerun backup and verify it writes metadata using the ledger path without uploading a differently named object.
7. Sign out/change account during a batch; verify worker stops before the next item and no metadata is written for the other account.
8. Sign into the same account on a second device; verify cloud-only images/videos open, and local matches do not show duplicate gallery entries.
9. Test inaccessible object URL/offline behavior and rotate/recreate Library and Backup Center while work continues.

## Requested runtime scenario matrix

None of the following runtime cases was executed; each is **BLOCKED** because this environment has no Android device/emulator (`adb` unavailable), and no live Supabase account/test inbox/network test environment was supplied. The implementation paths were only inspected/compiled:

| Scenario | Result |
|---|---|
| Valid account uploads image and metadata | BLOCKED |
| Invalid/expired session | BLOCKED |
| Offline during upload and WorkManager retry | BLOCKED |
| Rapid repeated manual upload / backup taps | BLOCKED |
| URI permission revoked or source removed | BLOCKED |
| At/below 50 MiB and over 50 MiB | BLOCKED |
| Storage succeeds, metadata fails, ledger recovery | BLOCKED |
| Same media retry uses same object path | BLOCKED |
| Auto backup across app close/reopen or reboot | BLOCKED |
| Disable/pause backup during an active transfer | BLOCKED |
| Logout/account switch during queue processing | BLOCKED |
| Video cloud upload and playback | BLOCKED |
| Cloud-only image/video on a second device | BLOCKED |
| Missing/inaccessible cloud object and retry UI | BLOCKED |
| Rotation/activity recreation while a transfer runs | BLOCKED |

The `assembleDebug` result is a build check only and does not convert these scenarios to PASS.

Additional automated test result:

- `app:testDebugUnitTest`: **PASS**, 5 existing unit tests, 0 failures. These cover the sample arithmetic and `PhotoTimelineUtils`; none exercises Supabase upload/backup. Runtime scenario matrix above remains BLOCKED.
