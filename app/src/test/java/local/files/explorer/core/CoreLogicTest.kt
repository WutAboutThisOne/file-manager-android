package local.files.explorer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun f(name: String, size: Long = 0, date: Long = 0, dir: Boolean = false, children: Int? = null) =
    FileEntry(name, "/s/$name", isDirectory = dir, size = size, lastModified = date, childCount = children)

class SorterTest {
    private val items = listOf(
        f("b.txt", 300, 3), f("A.jpg", 100, 5), f("file10.txt", 50, 1), f("file2.txt", 900, 2),
        f("Zeta", dir = true, date = 9, children = 1), f("alpha", dir = true, date = 1, children = 7),
    )

    private fun names(o: SortOrder) = Sorter.sort(items, o).map { it.name }

    @Test fun nameAscendingFoldersFirstNatural() =
        assertEquals(listOf("alpha", "Zeta", "A.jpg", "b.txt", "file2.txt", "file10.txt"), names(SortOrder()))

    @Test fun nameDescendingKeepsFoldersFirst() =
        assertEquals(listOf("Zeta", "alpha", "file10.txt", "file2.txt", "b.txt", "A.jpg"), names(SortOrder(ascending = false)))

    @Test fun sizeAscending() =
        assertEquals(listOf("Zeta", "alpha", "file10.txt", "A.jpg", "b.txt", "file2.txt"), names(SortOrder(SortField.SIZE)))

    @Test fun dateDescending() =
        assertEquals(listOf("Zeta", "alpha", "A.jpg", "b.txt", "file2.txt", "file10.txt"), names(SortOrder(SortField.DATE, ascending = false)))

    @Test fun typeGroupsByExtensionThenName() =
        assertEquals(listOf("alpha", "Zeta", "A.jpg", "b.txt", "file2.txt", "file10.txt"), names(SortOrder(SortField.TYPE)))

    @Test fun foldersCanMixWithFiles() =
        assertEquals("A.jpg", Sorter.sort(items, SortOrder(foldersFirst = false)).first().name)

    @Test fun naturalOrderHandlesLeadingZerosAndCase() {
        assertTrue(NaturalOrder.compare("img9", "img10") < 0)
        assertTrue(NaturalOrder.compare("IMG_002", "img_10") < 0)
        assertTrue(NaturalOrder.compare("a", "B") < 0)
        assertEquals(0, NaturalOrder.compare("same", "same"))
    }
}

class PathsTest {
    @Test fun normalize() {
        assertEquals("/", Paths.normalize(""))
        assertEquals("/", Paths.normalize("///"))
        assertEquals("/a/c", Paths.normalize("/a/./b/../c/"))
        assertEquals("/", Paths.normalize("/.."))
        assertEquals("/storage/emulated/0", Paths.normalize("/storage//emulated/0/"))
    }

    @Test fun parentNameChild() {
        assertNull(Paths.parent("/"))
        assertEquals("/", Paths.parent("/system"))
        assertEquals("/a", Paths.parent("/a/b/"))
        assertEquals("b", Paths.name("/a/b"))
        assertEquals("/", Paths.name("/"))
        assertEquals("/x", Paths.child("/", "x"))
        assertEquals("/a/x", Paths.child("/a/", "x"))
    }

    @Test fun ancestors() {
        assertTrue(Paths.isAncestorOrSelf("/a", "/a/b/c"))
        assertTrue(Paths.isAncestorOrSelf("/a", "/a"))
        assertTrue(Paths.isAncestorOrSelf("/", "/anything"))
        assertFalse(Paths.isAncestorOrSelf("/a", "/ab"))
    }

    @Test fun breadcrumbsUseLongestMatchingRoot() {
        val roots = listOf(PathRoot("/storage/emulated/0", "Internal storage"), PathRoot("/storage/emulated/0/Download", "Downloads"))
        val c = Paths.breadcrumbs("/storage/emulated/0/Download/apks", roots)
        assertEquals(listOf("Downloads", "apks"), c.map { it.label })
        assertEquals("/storage/emulated/0/Download/apks", c.last().path)
        val d = Paths.breadcrumbs("/storage/emulated/0/DCIM/Camera/", roots)
        assertEquals(listOf("Internal storage", "DCIM", "Camera"), d.map { it.label })
        assertEquals("/storage/emulated/0/DCIM", d[1].path)
    }

    @Test fun breadcrumbsWithoutRootStartAtSlash() {
        val c = Paths.breadcrumbs("/system/bin")
        assertEquals(listOf("/", "system", "bin"), c.map { it.label })
        assertEquals(listOf("/", "/system", "/system/bin"), c.map { it.path })
        assertEquals(listOf(Crumb("/", "/")), Paths.breadcrumbs("/"))
    }

    @Test fun shellQuoteEscapesSingleQuotes() =
        assertEquals("'it'\\''s here'", Paths.shellQuote("it's here"))
}

class ConflictResolverTest {
    private val existing = setOf("photo.jpg", "photo (1).jpg", "notes", ".bashrc", "archive.tar.gz")
    private val exists: (String) -> Boolean = { it in existing }

    @Test fun noConflictWritesAsIs() =
        assertEquals(Resolution.Write("new.txt", false), ConflictResolver.resolve("new.txt", exists, ConflictPolicy.SKIP))

    @Test fun replaceOverwrites() =
        assertEquals(Resolution.Write("photo.jpg", true), ConflictResolver.resolve("photo.jpg", exists, ConflictPolicy.REPLACE))

    @Test fun skipSkips() =
        assertEquals(Resolution.Skip, ConflictResolver.resolve("photo.jpg", exists, ConflictPolicy.SKIP))

    @Test fun keepBothFindsNextFreeNumber() =
        assertEquals(Resolution.Write("photo (2).jpg", false), ConflictResolver.resolve("photo.jpg", exists, ConflictPolicy.KEEP_BOTH))

    @Test fun keepBothOnAlreadyNumberedName() =
        assertEquals("photo (2).jpg", ConflictResolver.uniqueName("photo (1).jpg", exists))

    @Test fun keepBothForFoldersAndDotFiles() {
        assertEquals("notes (1)", ConflictResolver.uniqueName("notes", exists))
        assertEquals(".bashrc (1)", ConflictResolver.uniqueName(".bashrc", exists))
        assertEquals("archive.tar (1).gz", ConflictResolver.uniqueName("archive.tar.gz", exists))
    }

    @Test fun sameLocationNeverReplacesItself() =
        assertEquals(Resolution.Write("photo (2).jpg", false), ConflictResolver.resolve("photo.jpg", exists, ConflictPolicy.REPLACE, sameLocation = true))

    @Test fun conflictsListsOnlyExisting() =
        assertEquals(listOf("notes"), ConflictResolver.conflicts(listOf("a", "notes"), exists))

    @Test fun cannotPasteFolderIntoItself() {
        val dirs = setOf("/s/A")
        assertNotNull(ConflictResolver.validatePaste(listOf("/s/A"), "/s/A/sub") { it in dirs })
        assertNotNull(ConflictResolver.validatePaste(listOf("/s/A"), "/s/A") { it in dirs })
        assertNull(ConflictResolver.validatePaste(listOf("/s/A"), "/s/AB") { it in dirs })
        assertNull(ConflictResolver.validatePaste(listOf("/s/file"), "/s/file2") { false })
    }
}

class NomediaPolicyTest {
    private val nomediaDirs = setOf("/sdcard/WhatsApp/Media/.Statuses", "/sdcard/Secret")
    private val probe = NomediaProbe { it in nomediaDirs }

    @Test fun fileInsideNomediaFolderIsHiddenFromMedia() {
        val p = NomediaPolicy(probe, respect = true, stopAt = listOf("/sdcard"))
        assertTrue(p.hiddenFromMedia("/sdcard/Secret/a.jpg"))
        assertTrue(p.hiddenFromMedia("/sdcard/Secret/deep/er/b.jpg"))
        assertFalse(p.hiddenFromMedia("/sdcard/DCIM/c.jpg"))
    }

    @Test fun dotDirectoriesCountAsNomedia() {
        val p = NomediaPolicy(probe, respect = true, stopAt = listOf("/sdcard"))
        assertTrue(p.hiddenFromMedia("/sdcard/.thumbnails/x.jpg"))
        assertTrue(p.hiddenFromMedia("/sdcard/WhatsApp/Media/.Statuses", isDirectory = true))
    }

    @Test fun respectOffHidesNothing() {
        val p = NomediaPolicy(probe, respect = false)
        assertFalse(p.hiddenFromMedia("/sdcard/Secret/a.jpg"))
        assertTrue(p.isInNomediaTree("/sdcard/Secret/a.jpg"))
        val e = FileEntry("Secret", "/sdcard/Secret", isDirectory = true, hasNomedia = true)
        assertFalse(p.showBadge(e))
    }

    @Test fun badgeOnlyForFoldersCarryingNomedia() {
        val p = NomediaPolicy(probe, respect = true)
        assertTrue(p.showBadge(FileEntry("Secret", "/sdcard/Secret", isDirectory = true, hasNomedia = true)))
        assertFalse(p.showBadge(FileEntry("DCIM", "/sdcard/DCIM", isDirectory = true)))
        assertFalse(p.showBadge(FileEntry("a.jpg", "/sdcard/Secret/a.jpg", isDirectory = false, hasNomedia = true)))
    }

    @Test fun thumbnailsAndMediaFiltering() {
        val p = NomediaPolicy(probe, respect = true, stopAt = listOf("/sdcard"))
        val hidden = FileEntry("a.jpg", "/sdcard/Secret/a.jpg", isDirectory = false)
        val shown = FileEntry("b.jpg", "/sdcard/DCIM/b.jpg", isDirectory = false)
        assertFalse(p.allowThumbnail(hidden))
        assertTrue(p.allowThumbnail(shown))
        assertEquals(listOf(shown), p.filterMedia(listOf(hidden, shown)))
        // root/SAF entries aren't subject to the rule
        assertTrue(p.allowThumbnail(hidden.copy(backend = Backend.ROOT)))
    }

    @Test fun stopsAtVolumeRoot() {
        // a .nomedia above the volume root must not hide everything
        val p = NomediaPolicy({ it == "/storage" }, respect = true, stopAt = listOf("/storage/emulated/0"))
        assertFalse(p.hiddenFromMedia("/storage/emulated/0/DCIM/x.jpg"))
    }

    @Test fun listingDetectsNomediaEvenAmongOtherFiles() {
        val nomedia = FileEntry(".nomedia", "/sdcard/Telegram/.nomedia", isDirectory = false)
        val pic = FileEntry("a.jpg", "/sdcard/Telegram/a.jpg", isDirectory = false)
        val sub = FileEntry("Video", "/sdcard/Telegram/Video", isDirectory = true)
        assertTrue(listingHasNomedia(listOf(pic, nomedia, sub)))
        assertFalse(listingHasNomedia(listOf(pic, sub)))
        assertFalse(listingHasNomedia(listOf(FileEntry(".nomedia", "/sdcard/x/.nomedia", isDirectory = true))))
    }
}

class RootParsingTest {
    private val out = listOf(
        "drwxr-xr-x\troot\troot\t4096\t1700000000\tsystem",
        "lrwxrwxrwx\troot\troot\t11\t1230768000\tbin",
        "lrwxrwxrwx\troot\troot\t21\t1230768000\tsdcard",
        "-rw-r--r--\troot\troot\t1234\t1700000100\tdefault.prop",
        "-rw-------\tsystem\tsystem\t42\t1700000200\tname\twith tab",
        "garbage line",
        "drwx------\troot\troot\t3452\t1700000000\t.hidden",
        RootListingParser.MARKER,
        "sdcard",
    )

    @Test fun parsesStatOutput() {
        val e = RootListingParser.parse("/", out).associateBy { it.name }
        assertEquals(6, e.size)
        assertTrue(e.getValue("system").isDirectory)
        assertEquals("/system", e.getValue("system").path)
        assertEquals(1_700_000_000_000L, e.getValue("system").lastModified)
        assertEquals("drwxr-xr-x", e.getValue("system").permissions)
        assertTrue(e.getValue("bin").isSymlink)
        assertFalse(e.getValue("bin").isDirectory) // not in link-dirs section
        assertTrue(e.getValue("sdcard").isDirectory)
        assertEquals(1234L, e.getValue("default.prop").size)
        assertEquals("system", e.getValue("name\twith tab").owner)
        assertTrue(e.getValue(".hidden").isHidden)
        assertTrue(e.values.all { it.backend == Backend.ROOT })
    }

    @Test fun scriptQuotesPath() {
        val s = RootListingParser.script("/data/it's")
        assertTrue(s.startsWith("cd '/data/it'\\''s' && "))
        assertTrue(s.contains(RootListingParser.MARKER))
    }

    @Test fun parsesSmVolumes() {
        val v = SmParser.parseVolumes(listOf("private mounted null", "emulated;0 mounted null", "public:179,65 mounted 1234-ABCD", "public:8,1 unmounted null", "", "junk"))
        assertEquals(4, v.size)
        val sd = v.first { it.id == "public:179,65" }
        assertEquals("1234-ABCD", sd.uuid)
        assertTrue(sd.isPublic && sd.isMounted)
        val usb = v.first { it.id == "public:8,1" }
        assertNull(usb.uuid)
        assertFalse(usb.isMounted)
        assertEquals("sm unmount public:179,65", SmParser.mountCommand(sd, false))
        assertEquals("sm mount public:8,1", SmParser.mountCommand(usb, true))
        assertNull(SmParser.mountCommand(v.first { it.id == "private" }, false))
        assertNull(SmParser.mountCommand(SmVolume("public:1,1; reboot", "mounted", null), false))
    }

    @Test fun parsesFindOutput() {
        val r = RootFindParser.parse(listOf("-rw-r--r--\troot\troot\t10\t1\t/system/etc/hosts", "drwxr-xr-x\troot\troot\t0\t1\t/system/etc/init"))
        assertEquals(listOf("hosts", "init"), r.map { it.name })
        assertTrue(r[1].isDirectory)
        assertTrue(RootFindParser.script("/system", "ho'st*").contains("'*ho'\\''st*'"))
    }

    @Test fun volumeStateMapping() {
        assertEquals(VolumeState.MOUNTED, VolumeState.fromEnvironment("mounted"))
        assertEquals(VolumeState.EJECTING, VolumeState.fromEnvironment("ejecting"))
        assertEquals(VolumeState.UNMOUNTED, VolumeState.fromEnvironment("unmounted"))
        assertEquals(VolumeState.UNKNOWN, VolumeState.fromEnvironment(null))
        assertTrue(VolumeState.MOUNTED_READ_ONLY.isReadable)
        assertFalse(VolumeState.BAD_REMOVAL.isReadable)
    }
}

class MiscTest {
    @Test fun sizes() {
        assertEquals("999 B", Format.size(999))
        assertEquals("1 kB", Format.size(1000))
        assertEquals("1.5 MB", Format.size(1_500_000))
        assertEquals("128 GB", Format.size(128_000_000_000))
    }

    @Test fun names() {
        assertEquals("jpg", Names.extension("a.b.jpg"))
        assertEquals("", Names.extension(".nomedia"))
        assertEquals("", Names.extension("noext"))
        assertNotNull(Names.validate("a/b"))
        assertNotNull(Names.validate(" "))
        assertNotNull(Names.validate(".."))
        assertNull(Names.validate("ok name.txt"))
    }

    @Test fun fileTypes() {
        assertEquals(FileType.APK, FileType.of("x.APK"))
        assertEquals(FileType.APK_BUNDLE, FileType.of("x.xapk"))
        assertEquals(FileType.IMAGE, FileType.of("noext", "image/png"))
        assertEquals(FileType.OTHER, FileType.of("noext"))
    }

    @Test fun splitBundles() {
        val b = SplitApk.analyze(listOf("manifest.json", "icon.png", "split_config.arm64_v8a.apk", "base.apk", "split_config.en.apk", "Android/obb/x/main.obb"))
        assertEquals("base.apk", b.baseEntry)
        assertEquals(3, b.splitCount)
        assertEquals("base.apk", b.apkEntries.first())
        assertEquals(1, b.obbEntries.size)
        val apkm = SplitApk.analyze(listOf("com.app.apk", "config.xxhdpi.apk"))
        assertEquals("com.app.apk", apkm.baseEntry)
        assertFalse(SplitApk.analyze(listOf("readme.txt")).isValid)
        assertTrue(SplitApk.isBundleName("App.APKS"))
    }
}
