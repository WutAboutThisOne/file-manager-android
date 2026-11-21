# File Manager

An ad-free, tracker-free **file manager for Android** with no internet permission. It browses internal
storage, SD cards and USB drives, opens and creates archives, looks inside and installs APKs, and on a
rooted phone can browse the whole file system.

> Not affiliated with or endorsed by Google. Android is a trademark of Google LLC.

- **Package:** `local.files.explorer` · **Version:** 0.5.0 · **minSdk** 26 (Android 8) · **targetSdk** 35 (Android 15)
- **Stack:** Kotlin, Jetpack Compose, Material 3, DataStore, libsu (root), zip4j, Apache Commons Compress, XZ for Java
- **Licence:** Apache-2.0

## Screenshots

Screenshots are rendered from example data by the Robolectric tests into `dist/screens/`
(run `./gradlew :app:testDebugUnitTest`). They are attached to GitHub Releases rather than committed.

## Features

- **Home** – search across all storage, storage usage, category cards (Images, Videos, Music, Documents, APKs, Archives, Downloads, Recent), Largest files, Folder sizes and Find duplicates (same size and SHA-256).
- **Browsing** – internal storage, SD/USB volumes and any folder through Android's picker; breadcrumbs, list or grid, sorting, hidden files, thumbnails for images, videos and APKs; go to a typed path.
- **File operations** – copy, move, rename, delete, new folder, multi-select, share, open with, properties. Long copies run in a foreground service with progress and Cancel; name conflicts offer Replace, Skip or Keep both.
- **Archives** – browse zip, 7z, tar, tar.gz/xz/bz2, gz and xz like folders; extract all or a selection; create ZIP or 7z (optionally AES-encrypted). Entry names are sanitised against path traversal and zip bombs are guarded against.
- **APKs** – open .apk, .apks, .xapk and .apkm: app info (label, package, version, SDK levels), browse and extract entries with binary-XML decoding, and install through Android's package installer (or silently with root). Other apps can hand APKs to it with **Open with** or **Share**.
- **Root (optional)** – a Root tab that browses `/` through `su`, read-only by default; writes and `/system` remount need explicit, warned opt-in. Rooted volume mount/unmount.
- **.nomedia** – add or remove `.nomedia` on any folder; optional "Respect .nomedia" privacy mode.
- **Fold-aware layout** – on an unfolded foldable or tablet: navigation rail, folder tree beside contents, and dual pane with drag-to-copy; nothing is placed on the hinge.
- **About** – version, collapsible "What's new", and an Open-source licences page.

## Root vs. non-root

* On start, the app asks libsu whether root is **already** granted (no prompt). If not, it only opens a root
  shell (which shows the Magisk/KernelSU prompt) when an `su` binary exists. Unrooted phones never see a prompt.
* Any failure (no su, denied, timeout, exception) falls back to "not available"/"denied"; the Root tab and
  root actions are simply not shown. Settings shows a "Root not available" note with a "Check again" button.

## What "Respect .nomedia" means

A file is **media-hidden** if any folder between it and its storage root contains a `.nomedia`
file or is a dot-folder. These are the same rules Android's MediaProvider uses.

When **Respect .nomedia** is ON (default):
* media-hidden images/videos are never thumbnailed in the browser (no decoded previews of private folders);
* media-hidden files are left out of the **Recent** view;
* folders that contain a `.nomedia` show a small "nomedia" badge.

The folders themselves stay visible and browsable. A file manager has to let you reach your own files,
and hiding them would make it impossible to remove the `.nomedia` again.

When OFF: everything is thumbnailed and included in Recent, no badges.

Folder long-press / ⋮ menu, or the current folder's top-right ⋮ menu → **Add .nomedia**
or **Remove .nomedia**. File Manager then asks `MediaScannerConnection` to rescan the folder
(and its files) so gallery apps update.

## Permissions

Every runtime permission is requested in-app, just before it's needed, with an explanation first.

| Permission | Why |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` ("All files access") | Needed by any file manager to list and change files outside its own folder |
| `READ_EXTERNAL_STORAGE` (Android ≤ 12), `WRITE_EXTERNAL_STORAGE` (Android ≤ 10) | The same on older Android versions |
| `REQUEST_INSTALL_PACKAGES` | Install an APK you tap (Android asks you to allow this once) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Keep long copy/move/delete jobs running with a progress notification |
| `POST_NOTIFICATIONS` | Show that progress notification (optional) |

`INTERNET`, `ACCESS_NETWORK_STATE`, `QUERY_ALL_PACKAGES` and the advertising ID are explicitly
stripped from the manifest (`tools:node="remove"`), even if a library tries to add them.

## Privacy

No ads, no analytics, no crash reporting, no accounts and **no network access at all**. Your files never
leave the device. Archive passwords are kept in memory only.

## How it works

```
ui/        Compose screens (Home, browser, archives, APK sheet, settings, licences) + MainViewModel
fs/        Backends (local files, root via libsu, Storage Access Framework, archives) and the copy/move/delete engine
ops/       Operation model, progress and the foreground service that runs long jobs
archive/   Readers/writers for zip (zip4j), 7z/tar/gz/xz/bz2 (Commons Compress + XZ)
apk/       Self-written APK parsing: binary XML, resources table, split bundles
install/   Incoming "Open with"/"Share" handling and PackageInstaller sessions
storage/   Volumes, media index, direct file access behind content:// URIs
root/      Root detection and the root shell
core/      Pure helpers: sorting, search matching, .nomedia rules, root output parsing
settings/  DataStore-backed settings
```

## Building

Requirements: **JDK 21** (17+ works), Android SDK Platform 35 and Build-Tools, with `sdk.dir` in a
git-ignored `local.properties` (or `ANDROID_HOME`). libsu is downloaded from JitPack.

```bash
./gradlew :app:testDebugUnitTest   # unit, Robolectric and screenshot tests
./gradlew :app:assembleDebug       # debug APK (application id suffix .debug)
./gradlew :app:assembleRelease     # R8-minified, resource-shrunk release APK
```

**Signing your own release:** copy `keystore.properties.example` to `keystore.properties` (git-ignored),
create a keystore with `keytool`, and fill in the four values. Without it the release APK is unsigned.
Never commit keystores or passwords.

## Licence

Copyright 2026 The project authors. Licensed under the [Apache License 2.0](LICENSE).

## Third-party notices

See [NOTICE](NOTICE): libsu, zip4j, Apache Commons Compress/IO/Lang/Codec, AndroidX and Kotlin (Apache-2.0),
and XZ for Java (0BSD). The full texts and the Apache Commons NOTICE files are in
`app/src/main/assets/licenses/` and on the in-app Licences page.
