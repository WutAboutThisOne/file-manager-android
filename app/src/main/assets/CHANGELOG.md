# File Manager changelog
# Newest first. Add a "## <version> — <date>" header and "- " bullets for each release;
# the About screen reads this file as-is.

## 0.5.0 — 2026-11-21
- Renamed from Folio Files to File Manager. It's the same app: it updates in place and keeps your settings
- Now open source under the Apache License 2.0
- The Licences page now includes the NOTICE texts for the bundled Apache Commons libraries (Compress, IO, Lang and Codec)
- Licences is now a short list of components with their versions and licence types; tap one to read its full licence (and any NOTICE) on its own page
- Removed unused libraries

## 0.4.5 — 2026-11-19
- Open Fold layout: navigation rail, folder tree beside the folder's contents, and it keeps your place when you fold or unfold
- Dual pane: two folders side by side; copy or move the selection to the other pane with a tap, or drag it across
- Half-folded, nothing sits on the hinge: panes split at the fold
- Targets Android 15 (API 35); long copies stop cleanly at Android 15's background limit
- Open-source licences moved to their own Licences page, linked from About
- What's new shows the latest version; tap an older version to expand it

## 0.4.4 — 2026-11-17
- About page now shows the version and this running changelog

## 0.4.3 — 2026-11-17
- Add / Remove .nomedia from the folder toolbar ⋮ menu, with a confirmation toast

## 0.4.2 — 2026-11-05
- Go to file location from Largest files, category lists and search
- Folder sizes lists loose files in the current folder, with Open and a jump to that folder in the browser
- Tapping the Home Internal storage card opens the Internal tab

## 0.4.1 — 2026-11-05
- Multi-select and ⋮ actions (move, copy, cut, rename, share, delete) in Folder sizes and Largest files
- Add / Remove .nomedia on local folders from their ⋮ menu
- Find duplicates on Home, grouped by size then SHA-256; Select extras keeps one copy

## 0.4.0 — 2026-10-27
- Browse and unpack APKs and split bundles: decoded manifest and resources, permissions, extract
- App info screen (package, version, SDK, permissions, components) read from the file itself
- Size limits removed; free-space checks before extracting or saving incoming files

## 0.3.0 — 2026-10-21
- Browse, extract and compress zip, 7z and tar archives (AES passwords, zip-slip and zip-bomb guards)
- Open with routing for archives

## 0.2.1 — 2026-10-21
- Appears in Open with and Share for APKs and split bundles, and can install them

## 0.2.0 — 2026-10-21
- Just-in-time permissions, Home categories, Largest files, Folder sizes, context menu and search fixes
- Dropped the QUERY_ALL_PACKAGES permission

## 0.1.0 — 2026-10-21
- First release: ad-free file explorer with rooted and non-rooted modes
