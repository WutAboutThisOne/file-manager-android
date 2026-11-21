package local.files.explorer.core

import local.files.explorer.ui.browser.SearchUi
import local.files.explorer.ui.browser.searchStatusText
import local.files.explorer.ui.browser.contextActionsFor
import local.files.explorer.ui.browser.actionsFor
import local.files.explorer.ui.browser.ItemAction
import local.files.explorer.ui.BrowserOverflowItem
import local.files.explorer.ui.Tab
import local.files.explorer.ui.browserOverflowItems
import local.files.explorer.ui.home.percent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun file(path: String, size: Long = 1, dir: Boolean = false, backend: Backend = Backend.LOCAL, link: Boolean = false) =
    FileEntry(path.substringAfterLast('/'), path, backend, dir, size = size, isSymlink = link)

class SearchMatchTest {
    @Test fun singleCharacterMatches() = assertTrue(SearchMatch.matches("a.txt", "a"))
    @Test fun caseInsensitive() = assertTrue(SearchMatch.matches("Holiday.JPG", "holiday.jpg"))
    @Test fun allTokensMustMatchInAnyOrder() {
        assertTrue(SearchMatch.matches("invoice-2025-acme.pdf", "acme invoice"))
        assertFalse(SearchMatch.matches("invoice-2025.pdf", "acme invoice"))
    }
    @Test fun blankQueryMatchesNothing() = assertFalse(SearchMatch.matches("x", "   "))
}

class RecursiveSearcherTest {
    /** In-memory tree: path -> children. */
    private val tree = mapOf(
        "/r" to listOf(file("/r/a", dir = true), file("/r/notes.txt"), file("/r/.hidden", dir = true), file("/r/link", dir = true, link = true)),
        "/r/a" to listOf(file("/r/a/b", dir = true), file("/r/a/note-a.md")),
        "/r/a/b" to listOf(file("/r/a/b/deep-note.txt")),
        "/r/.hidden" to listOf(file("/r/.hidden/note-secret.txt")),
        "/r/link" to listOf(file("/r/link/note-loop.txt")),
    )
    private val lister: (FileEntry) -> List<FileEntry> = { tree[it.path] ?: emptyList() }
    private val root = file("/r", dir = true)

    private fun run(recursive: Boolean = true, hidden: Boolean = false, limit: Int = 1000, query: String = "note"): Pair<List<SearchHit>, SearchProgress> {
        val hits = mutableListOf<SearchHit>()
        var last = SearchProgress()
        RecursiveSearcher(lister).search(root, "Internal", query, recursive, hidden, limit, batchMs = 0) { h, p -> hits += h; last = p }
        return hits to last
    }

    @Test fun findsInSubfoldersBreadthFirstWithFolderLabels() {
        val (hits, p) = run()
        assertEquals(listOf("notes.txt", "note-a.md", "deep-note.txt"), hits.map { it.entry.name })
        assertEquals(listOf("Internal", "Internal/a", "Internal/a/b"), hits.map { it.folderLabel })
        assertTrue(p.done); assertFalse(p.limitHit); assertEquals(3, p.found)
    }

    @Test fun parentCrumbsLeadToTheFolder() {
        val deep = run().first.last()
        assertEquals(listOf("/r", "/r/a", "/r/a/b"), deep.parentCrumbs.map { it.path })
    }

    @Test fun thisFolderOnly() = assertEquals(listOf("notes.txt"), run(recursive = false).first.map { it.entry.name })

    @Test fun hiddenFoldersOnlyWhenAsked() {
        assertTrue(run(hidden = true).first.any { it.entry.name == "note-secret.txt" })
        assertFalse(run().first.any { it.entry.name == "note-secret.txt" })
    }

    @Test fun doesNotFollowSymlinks() = assertFalse(run().first.any { it.entry.name == "note-loop.txt" })

    @Test fun stopsAtLimitAndSaysSo() {
        val (hits, p) = run(limit = 2)
        assertEquals(2, hits.size); assertTrue(p.limitHit); assertTrue(p.done)
    }

    @Test fun streamsInBatches() {
        val batches = mutableListOf<Int>()
        RecursiveSearcher(lister).search(root, "I", "note", true, false, batchMs = 0) { h, _ -> batches += h.size }
        assertTrue("expected more than one delivery, got $batches", batches.size > 1)
    }

    @Test fun cancellationStopsTheWalk() {
        var calls = 0
        val p = RecursiveSearcher({ calls++; lister(it) }).search(root, "I", "note", true, false, isCancelled = { calls >= 1 }) { _, _ -> }
        assertTrue(p.cancelled); assertEquals(1, calls)
    }

    @Test fun unreadableFoldersAreSkipped() {
        val p = RecursiveSearcher({ if (it.path == "/r/a") error("EACCES") else lister(it) }).search(root, "I", "note", true, false) { _, _ -> }
        assertEquals(1, p.found)
    }
}

class CategoriesTest {
    private val dl = "/s/Download"
    private val idx = listOf(
        IndexedFile(file("/s/DCIM/a.jpg", 100)),
        IndexedFile(file("/s/Telegram/b.png", 200), inNomediaTree = true),
        IndexedFile(file("/s/.thumbs/c.jpg", 50), inNomediaTree = true, inHiddenPath = true),
        IndexedFile(file("/s/Movies/m.mp4", 1000)),
        IndexedFile(file("/s/Music/s.mp3", 10)),
        IndexedFile(file("/s/Documents/r.pdf", 5)), IndexedFile(file("/s/notes.md", 3)), IndexedFile(file("/s/x.xlsx", 4)),
        IndexedFile(file("$dl/app.apk", 70)), IndexedFile(file("$dl/sub/z.zip", 30)),
        IndexedFile(file("/s/DCIM", dir = true)),
    )

    @Test fun countsAndSizesRespectNomediaForMediaOnly() {
        val st = Categories.stats(idx, dl, respectNomedia = true, showHidden = false)
        assertEquals(CategoryStats(1, 100), st[Category.IMAGES])
        assertEquals(CategoryStats(1, 1000), st[Category.VIDEOS])
        assertEquals(CategoryStats(1, 10), st[Category.AUDIO])
        assertEquals(CategoryStats(3, 12), st[Category.DOCUMENTS])
        assertEquals(CategoryStats(1, 70), st[Category.APKS])
        assertEquals(CategoryStats(1, 30), st[Category.ARCHIVES])
        assertEquals(CategoryStats(2, 100), st[Category.DOWNLOADS])
    }

    @Test fun nomediaOffShowsEverythingButHidden() {
        val imgs = Categories.files(idx, Category.IMAGES, dl, respectNomedia = false, showHidden = false).map { it.name }
        assertEquals(listOf("a.jpg", "b.png"), imgs)
    }

    @Test fun showHiddenIncludesDotFolders() {
        val imgs = Categories.files(idx, Category.IMAGES, dl, respectNomedia = false, showHidden = true).map { it.name }
        assertEquals(listOf("a.jpg", "b.png", "c.jpg"), imgs)
    }

    @Test fun downloadsExcludesTheFolderItselfAndDirectories() {
        val list = Categories.files(idx + IndexedFile(file(dl, dir = true)), Category.DOWNLOADS, dl, true, false)
        assertEquals(listOf("app.apk", "z.zip"), list.map { it.name })
    }
}

class LargestAndFolderSizesTest {
    private val idx = listOf(
        IndexedFile(file("/s/Movies/big.mkv", 4_000_000_000)),
        IndexedFile(file("/s/Movies/mid.mp4", 400_000_000)),
        IndexedFile(file("/s/Download/a.iso", 1_500_000_000)),
        IndexedFile(file("/s/Download/s.pdf", 20_000_000)),
        IndexedFile(file("/s/.cache/blob", 9_000_000_000), inHiddenPath = true),
        IndexedFile(file("/s/tiny.txt", 5)),
        IndexedFile(file("/s/Movies", dir = true)),
    )

    @Test fun largestBiggestFirstWithThresholds() {
        assertEquals(listOf("big.mkv", "a.iso", "mid.mp4", "s.pdf"), LargestFiles.select(idx, SizeFilter.OVER_10MB, false).map { it.name })
        assertEquals(listOf("big.mkv", "a.iso", "mid.mp4"), LargestFiles.select(idx, SizeFilter.OVER_100MB, false).map { it.name })
        assertEquals(listOf("big.mkv", "a.iso"), LargestFiles.select(idx, SizeFilter.OVER_1GB, false).map { it.name })
        assertEquals("blob", LargestFiles.select(idx, SizeFilter.OVER_1GB, true).first().name)
    }

    @Test fun folderTreeAggregatesAndSortsLargestFirst() {
        val root = FolderSizes.build("/s", "Internal", idx.map { it.entry })
        assertEquals(idx.filter { !it.entry.isDirectory }.sumOf { it.entry.size }, root.bytes)
        assertEquals(listOf(".cache", "Movies", "Download"), root.children.map { it.name })
        assertEquals(1, root.directFiles); assertEquals(5L, root.directBytes)
        val movies = FolderSizes.find(root, "/s/Movies")
        assertNotNull(movies); assertEquals(4_400_000_000, movies!!.bytes); assertEquals(2, movies.files)
        assertNull(FolderSizes.find(root, "/s/Nope"))
        assertNull(FolderSizes.find(root, "/elsewhere"))
    }

    @Test fun nestedDrillDown() {
        val root = FolderSizes.build("/s", "I", listOf(file("/s/a/b/c/x.bin", 7), file("/s/a/y.bin", 3)))
        val a = FolderSizes.find(root, "/s/a")!!
        assertEquals(10, a.bytes); assertEquals(listOf("b"), a.children.map { it.name }); assertEquals(3, a.directBytes)
        assertEquals(7, FolderSizes.find(root, "/s/a/b/c")!!.bytes)
    }

    @Test fun directEntriesListsLooseFilesBiggestFirst() {
        val files = listOf(
            file("/s/tiny.txt", 5), file("/s/Movies/big.mkv", 4_000_000_000),
            file("/s/huge.bin", 9_000_000), file("/s/.dot", 100), file("/s/Movies/mid.mp4", 400),
        )
        assertEquals(listOf("huge.bin", "tiny.txt"), FolderSizes.directEntries(files, "/s", showHidden = false).map { it.name })
        assertEquals(listOf("huge.bin", ".dot", "tiny.txt"), FolderSizes.directEntries(files, "/s", showHidden = true).map { it.name })
        assertEquals(listOf("big.mkv", "mid.mp4"), FolderSizes.directEntries(files, "/s/Movies").map { it.name })
        assertTrue(FolderSizes.directEntries(files, "/s/Nope").isEmpty())
    }

    @Test fun largeOpThresholds() {
        assertTrue(LargeOp.isLarge(listOf(file("/d", dir = true))))
        assertTrue(LargeOp.isLarge(listOf(file("/a", 60_000_000))))
        assertTrue(LargeOp.isLarge((1..20).map { file("/f$it") }))
        assertFalse(LargeOp.isLarge(listOf(file("/a", 1_000_000), file("/b", 2_000_000))))
    }
}

class FolderLabelsAndUiTextTest {
    private val roots = listOf(PathRoot("/storage/emulated/0", "Internal storage"), PathRoot("/storage/1A2B-3C4D", "SD card"))

    @Test fun labelsRelativeToTheRightRoot() {
        assertEquals("Internal storage/DCIM/Camera", FolderLabels.parentLabel("/storage/emulated/0/DCIM/Camera/a.jpg", roots))
        assertEquals("Internal storage", FolderLabels.parentLabel("/storage/emulated/0/a.jpg", roots))
        assertEquals("SD card/Music", FolderLabels.parentLabel("/storage/1A2B-3C4D/Music/x.mp3", roots))
        assertEquals("/data/local", FolderLabels.parentLabel("/data/local/tmp", roots))
    }

    @Test fun searchStatus() {
        assertEquals("Searching\u2026 12 found", searchStatusText(SearchUi(true, running = true, found = 12, limitHit = false, limit = 1000)))
        assertEquals("1 result", searchStatusText(SearchUi(true, false, 1, false, 1000)))
        assertTrue(searchStatusText(SearchUi(true, false, 1000, true, 1000)).startsWith("Showing the first 1000"))
    }

    @Test fun percentages() {
        assertEquals("25%", percent(25, 100)); assertEquals("<1%", percent(1, 1000)); assertEquals("0%", percent(1, 0))
    }

    @Test fun contextMenuHasTheRequestedActions() {
        val a = contextActionsFor(file("/s/x.mp4"), readOnly = false)
        assertEquals(
            listOf(
                ItemAction.GO_TO_LOCATION, ItemAction.OPEN, ItemAction.SHARE,
                ItemAction.COPY, ItemAction.CUT, ItemAction.MOVE, ItemAction.RENAME,
                ItemAction.DETAILS, ItemAction.DELETE,
            ),
            a,
        )
        val apk = contextActionsFor(file("/s/x.apk"), readOnly = false)
        assertTrue(ItemAction.INSTALL in apk)
        val dir = contextActionsFor(file("/s/Photos", dir = true), readOnly = false)
        assertTrue(ItemAction.ADD_NOMEDIA in dir)
        val ro = contextActionsFor(file("/system/x", backend = Backend.ROOT), readOnly = true)
        assertFalse(ItemAction.DELETE in ro || ItemAction.MOVE in ro || ItemAction.SHARE in ro || ItemAction.CUT in ro)
        assertEquals(ItemAction.GO_TO_LOCATION, actionsFor(file("/s/x"), false, searchResult = true).first())
        assertFalse(ItemAction.GO_TO_LOCATION in actionsFor(file("/s/x"), false))
        assertTrue(ItemAction.ADD_NOMEDIA in actionsFor(file("/s/Photos", dir = true), false))
    }

    @Test fun toolbarOverflowAddsOrRemovesNomediaForCurrentFolder() {
        fun items(hasNomedia: Boolean = false, readOnly: Boolean = false, backend: Backend = Backend.LOCAL, browsing: Boolean = true, hasLocation: Boolean = true, tab: Tab = Tab.INTERNAL, hasEntries: Boolean = true, showHidden: Boolean = false, rootWrites: Boolean = false) =
            browserOverflowItems(browsing, hasLocation, backend, readOnly, showHidden, hasEntries, hasNomedia, tab, rootWrites)

        val add = items()
        assertEquals(
            listOf(
                BrowserOverflowItem.NEW_FOLDER, BrowserOverflowItem.SORT, BrowserOverflowItem.SHOW_HIDDEN,
                BrowserOverflowItem.SELECT_ALL, BrowserOverflowItem.ADD_NOMEDIA,
                BrowserOverflowItem.REFRESH, BrowserOverflowItem.SETTINGS,
            ),
            add,
        )
        val remove = items(hasNomedia = true, showHidden = true)
        assertTrue(BrowserOverflowItem.REMOVE_NOMEDIA in remove)
        assertFalse(BrowserOverflowItem.ADD_NOMEDIA in remove)
        assertTrue(BrowserOverflowItem.HIDE_HIDDEN in remove)
        assertFalse(BrowserOverflowItem.ADD_NOMEDIA in items(readOnly = true))
        assertFalse(BrowserOverflowItem.ADD_NOMEDIA in items(backend = Backend.ROOT, readOnly = true))
        assertFalse(BrowserOverflowItem.ADD_NOMEDIA in items(backend = Backend.ARCHIVE, readOnly = true))
        assertFalse(BrowserOverflowItem.ADD_NOMEDIA in items(backend = Backend.SAF))
        assertFalse(BrowserOverflowItem.ADD_NOMEDIA in items(browsing = false, hasLocation = false, tab = Tab.STORAGE, hasEntries = false))
        assertFalse(BrowserOverflowItem.SELECT_ALL in items(hasEntries = false))
        val root = items(backend = Backend.ROOT, readOnly = false, tab = Tab.ROOT, rootWrites = true)
        assertTrue(BrowserOverflowItem.REMOUNT_SYSTEM in root)
        assertFalse(BrowserOverflowItem.ADD_NOMEDIA in root)
        // Folder-row menus still offer .nomedia independently of the toolbar.
        assertTrue(ItemAction.REMOVE_NOMEDIA in actionsFor(file("/s/Photos", dir = true).copy(hasNomedia = true), false))
    }

    @Test fun rootFindUsesLongestToken() {
        val s = RootFindParser.script("/data", "a invoice")
        assertTrue(s.contains("'*invoice*'"))
    }
}

class PackageSnifferTest {
    @Test fun zipMagic() {
        assertTrue(PackageSniffer.looksLikeZip(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 9)))
        assertFalse(PackageSniffer.looksLikeZip("%PDF".toByteArray()))
        assertFalse(PackageSniffer.looksLikeZip(byteArrayOf(0x50)))
    }

    @Test fun classifiesByContents() {
        assertEquals(PackageKind.APK, PackageSniffer.classify(listOf("AndroidManifest.xml", "classes.dex", "resources.arsc")))
        assertEquals(PackageKind.BUNDLE, PackageSniffer.classify(listOf("base.apk", "split_config.arm64_v8a.apk", "toc.pb")))
        assertEquals(PackageKind.BUNDLE, PackageSniffer.classify(listOf("manifest.json", "com.app.apk", "config.en.apk", "Android/obb/x.obb")))
        assertEquals(PackageKind.NOT_PACKAGE, PackageSniffer.classify(listOf("photo.jpg", "notes.txt")))
        assertEquals(PackageKind.NOT_PACKAGE, PackageSniffer.classify(emptyList()))
    }

    @Test fun cacheNamesGetTheRightExtensionAndAreSafe() {
        assertEquals("Signal.apk", PackageSniffer.cacheName("Signal.apk", PackageKind.APK))
        assertEquals("download.apk", PackageSniffer.cacheName("download", PackageKind.APK))
        assertEquals("game.xapk", PackageSniffer.cacheName("game.xapk", PackageKind.BUNDLE))
        assertEquals("game.apks", PackageSniffer.cacheName("game.zip", PackageKind.BUNDLE))
        assertEquals("package.apk", PackageSniffer.cacheName(null, PackageKind.APK))
        assertEquals("passwd.apk", PackageSniffer.cacheName("../../etc/passwd", PackageKind.APK))
        assertEquals("a_b.apk", PackageSniffer.cacheName("a:b.apk", PackageKind.APK))
        assertEquals("hidden.apk", PackageSniffer.cacheName(".hidden.apk", PackageKind.APK))
    }
}
