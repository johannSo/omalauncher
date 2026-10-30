package dev.omalauncher.pp.linux

import dev.omalauncher.pp.linux.RestoreStep.ArchToOld
import dev.omalauncher.pp.linux.RestoreStep.DeleteMarker
import dev.omalauncher.pp.linux.RestoreStep.DeleteOld
import dev.omalauncher.pp.linux.RestoreStep.DeleteStaging
import dev.omalauncher.pp.linux.RestoreStep.MoveHome
import dev.omalauncher.pp.linux.RestoreStep.OldToArch
import dev.omalauncher.pp.linux.RestoreStep.RestoreRecords
import dev.omalauncher.pp.linux.RestoreStep.StagingToArch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotPlanTest {
    private fun meta(
        id: String,
        at: Long,
    ) = SnapshotMeta(id, at, SnapshotReason.Update, home = true, files = 1, bytes = 1, homeBytes = 0)

    @Test fun pruningKeepsTheNewest() {
        val all = listOf(meta("b", 2), meta("a", 1), meta("d", 4), meta("c", 3))
        assertEquals(listOf("a", "b"), snapshotsToPrune(all, 2).map { it.id })
        assertEquals(listOf("a", "b", "c"), snapshotsToPrune(all, 1).map { it.id })
        assertEquals(emptyList<SnapshotMeta>(), snapshotsToPrune(all, 4))
        assertEquals(emptyList<SnapshotMeta>(), snapshotsToPrune(all, 10))
        assertEquals(emptyList<SnapshotMeta>(), snapshotsToPrune(emptyList(), 2))
    }

    @Test fun pruningAlwaysKeepsOne() {
        val all = listOf(meta("a", 1), meta("b", 2))
        assertEquals(listOf("a"), snapshotsToPrune(all, 0).map { it.id })
    }

    @Test fun pruningBreaksTiesById() {
        // Two taken in the same millisecond: the later id ("-2") is the newer one.
        val all = listOf(meta("20261030-120000-2", 5), meta("20261030-120000", 5))
        assertEquals(listOf("20261030-120000"), snapshotsToPrune(all, 1).map { it.id })
    }

    @Test fun metaRoundTrips() {
        val m = SnapshotMeta("20261030-120000", 1_790_000_000_000, SnapshotReason.Manual, home = false, files = 123_456, bytes = 3_000_000_000, homeBytes = 0)
        assertEquals(m, SnapshotMeta.fromJson(org.json.JSONObject(m.toJson().toString())))
        // A reason from a newer launcher reads as taken by hand.
        assertEquals(SnapshotReason.Manual, SnapshotMeta.fromJson(m.toJson().put("reason", "scheduled")).reason)
        assertEquals(SnapshotReason.Update, SnapshotReason.of("update"))
    }

    @Test fun idsAreUniqueAndSortByTime() {
        val t = 1_790_000_000_000
        val first = SnapshotMeta.idAt(t, emptyList())
        val second = SnapshotMeta.idAt(t, listOf(first))
        val third = SnapshotMeta.idAt(t, listOf(first, second))
        assertNotEquals(first, second)
        assertEquals("$first-2", second)
        assertEquals("$first-3", third)
        assertTrue(SnapshotMeta.idAt(t + 60_000, emptyList()) > first)
        assertTrue(Regex("\\d{8}-\\d{6}").matches(first))
    }

    @Test fun manifestLinesRoundTrip() {
        val entries =
            listOf(
                ManifestEntry(ManifestEntry.Type.Dir, "usr/bin", mode = 0x1ed, mtime = 1_700_000_000_123),
                ManifestEntry(ManifestEntry.Type.File, "usr/bin/sudo", mode = 0xfed, mtime = 5, size = 300_000, hash = "ab".repeat(32), stamp = "42:1.5:1.5"),
                ManifestEntry(ManifestEntry.Type.File, "etc/empty", mode = 0x1a4),
                ManifestEntry(ManifestEntry.Type.Link, "usr/lib/libz.so", target = "libz.so.1.3"),
                ManifestEntry(ManifestEntry.Type.Link, "usr/lib/.l2s.x", target = "/data/user/0/dev.omalauncher.pp/files/arch/usr/lib/.l2s.x0001"),
                // Linux names may hold tabs, newlines and percent signs.
                ManifestEntry(ManifestEntry.Type.File, "root/odd\tname\n100%.txt", mode = 0x1a4, size = 1, hash = "cd".repeat(32)),
                ManifestEntry(ManifestEntry.Type.Link, "root/l", target = "a\tb%0A"),
            )
        for (e in entries) {
            val line = e.encode()
            assertFalse(line, '\n' in line)
            assertEquals(8, line.split('\t').size)
            assertEquals(e, ManifestEntry.decode(line))
        }
    }

    @Test fun badManifestLinesAreSkipped() {
        assertNull(ManifestEntry.decode(""))
        assertNull(ManifestEntry.decode("x\t644\t0\t0\t\t\tpath\t"))
        assertNull(ManifestEntry.decode("f\tnotoctal\t0\t0\t\t\tpath\t"))
        assertNull(ManifestEntry.decode("f\t644"))
    }

    @Test fun escapingIsReversible() {
        for (s in listOf("", "plain", "%", "%%25", "a\tb", "\r\n", "%0A literally", "ünï/cødé")) {
            assertEquals(s, ManifestEntry.unescape(ManifestEntry.escape(s)))
        }
    }

    @Test fun runtimeDirsAndCachesAreKeptEmpty() {
        for (p in listOf("tmp", "run", "proc", "sys", "dev", "var/tmp", "var/cache/pacman/pkg", "root/.cache", "home/alice/.cache")) {
            assertTrue(p, SnapshotPaths.skipsContents(p))
        }
        // Bound in from the launcher's own data by proot: not the guest's to roll back.
        for (p in listOf("root/.local/share/omarchy", "root/.config/omalauncher/widgets", "root/.config/omalauncher/style", "root/.config/omarchy/themes")) {
            assertTrue(p, SnapshotPaths.skipsContents(p))
        }
        for (p in listOf("usr", "etc", "var", "var/lib/pacman", "var/cache", "root/.config/omalauncher", "home/alice", "home/alice/.cache/x", "usr/tmp")) {
            assertFalse(p, SnapshotPaths.skipsContents(p))
        }
        assertTrue(SnapshotPaths.skips("var/lib/pacman/db.lck"))
        assertFalse(SnapshotPaths.skips("var/lib/pacman/local/bash-5.2-1/desc"))
    }

    @Test fun homeIsRootAndHome() {
        for (p in listOf("root", "root/.bashrc", "home", "home/alice/notes.md")) assertTrue(p, SnapshotPaths.isHome(p))
        for (p in listOf("rootfs", "homework", "usr/home", "etc/skel/.bashrc", "var/lib/root")) assertFalse(p, SnapshotPaths.isHome(p))
    }

    @Test fun ownBytesCountsWhatOnlyOneHolds() {
        val objects =
            mapOf(
                "old" to setOf("shared", "libc-old", "gone"),
                "new" to setOf("shared", "libc-new"),
                "empty" to emptySet(),
            )
        val sizes = mapOf("shared" to 1000L, "libc-old" to 30L, "libc-new" to 40L, "gone" to 5L)
        assertEquals(mapOf("old" to 35L, "new" to 40L, "empty" to 0L), ownBytes(objects, sizes))
        // With one snapshot, all of it is its own.
        assertEquals(mapOf("new" to 1040L), ownBytes(mapOf("new" to setOf("shared", "libc-new")), sizes))
    }

    @Test fun roomLeavesTheReserve() {
        assertTrue(snapshotFits(newBytes = 1_000, free = 3_000, reserve = 2_000))
        assertFalse(snapshotFits(newBytes = 1_001, free = 3_000, reserve = 2_000))
        assertTrue(snapshotFits(newBytes = 0, free = 2_000, reserve = 2_000))
    }

    @Test fun guestManifestKeepsHomeRecordsWithHome() {
        val snapshot = mapOf("arch/usr/local/bin/bwrap" to "old-bwrap", "arch/root/.config/foot/foot.ini" to "old-foot", "arch/etc/x" to "old-x")
        val current = mapOf("arch/usr/local/bin/bwrap" to "new-bwrap", "arch/root/.config/foot/foot.ini" to "new-foot", "arch/root/.local/share/applications/a.desktop" to "new-a")
        assertEquals(
            mapOf(
                "arch/usr/local/bin/bwrap" to "old-bwrap",
                "arch/etc/x" to "old-x",
                "arch/root/.config/foot/foot.ini" to "new-foot",
                "arch/root/.local/share/applications/a.desktop" to "new-a",
            ),
            mergedGuestManifest(snapshot, current, keepHome = true),
        )
        assertEquals(snapshot, mergedGuestManifest(snapshot, current, keepHome = false))
    }

    @Test fun restoreSwapsInOrder() {
        assertEquals(
            listOf(ArchToOld, MoveHome, StagingToArch, RestoreRecords, DeleteMarker, DeleteOld),
            restoreSteps(RestorePhase.Swapping, arch = true, staging = true, old = false, keepHome = true),
        )
        assertEquals(
            listOf(ArchToOld, StagingToArch, RestoreRecords, DeleteMarker, DeleteOld),
            restoreSteps(RestorePhase.Swapping, arch = true, staging = true, old = false, keepHome = false),
        )
    }

    @Test fun killedBetweenTheRenamesFinishes() {
        assertEquals(
            listOf(MoveHome, StagingToArch, RestoreRecords, DeleteMarker, DeleteOld),
            restoreSteps(RestorePhase.Swapping, arch = false, staging = true, old = true, keepHome = true),
        )
    }

    @Test fun killedAfterTheSwapCleansUp() {
        assertEquals(listOf(RestoreRecords, DeleteMarker, DeleteOld), restoreSteps(RestorePhase.Swapping, arch = true, staging = false, old = true, keepHome = true))
        // arch.old was being deleted: records again (they're the same), and what's left of it goes.
        assertEquals(listOf(RestoreRecords, DeleteMarker), restoreSteps(RestorePhase.Swapping, arch = true, staging = false, old = false, keepHome = false))
    }

    @Test fun killedWhileUnpackingLeavesTheRootfs() {
        assertEquals(listOf(DeleteStaging, DeleteMarker), restoreSteps(RestorePhase.Unpacking, arch = true, staging = true, old = false, keepHome = true))
        assertEquals(listOf(DeleteMarker), restoreSteps(RestorePhase.Unpacking, arch = true, staging = false, old = false, keepHome = true))
        // An unreadable marker counts as unpacking: nothing was swapped that it could finish.
        assertEquals(listOf(DeleteStaging, DeleteMarker), restoreSteps(null, arch = true, staging = true, old = false, keepHome = true))
    }

    @Test fun theRootfsNeverStaysMissing() {
        assertEquals(listOf(OldToArch, DeleteMarker), restoreSteps(RestorePhase.Swapping, arch = false, staging = false, old = true, keepHome = true))
        assertEquals(listOf(DeleteStaging, OldToArch, DeleteMarker), restoreSteps(RestorePhase.Unpacking, arch = false, staging = true, old = true, keepHome = true))
        for (phase in listOf(null, RestorePhase.Unpacking, RestorePhase.Swapping)) {
            for (arch in listOf(true, false)) for (staging in listOf(true, false)) for (old in listOf(true, false)) {
                val steps = restoreSteps(phase, arch, staging, old, keepHome = true)
                // Simulate: which of the three exist after the steps.
                var a = arch
                var s = staging
                var o = old
                for (step in steps) {
                    when (step) {
                        DeleteStaging -> s = false
                        ArchToOld -> {
                            assertTrue(a && !o)
                            o = true
                            a = false
                        }
                        StagingToArch -> {
                            assertTrue(s && !a)
                            a = true
                            s = false
                        }
                        OldToArch -> {
                            assertTrue(o && !a)
                            a = true
                            o = false
                        }
                        DeleteOld -> {
                            assertTrue("deletes arch.old only with arch in place", a)
                            o = false
                        }
                        MoveHome -> assertTrue(s && o && !a)
                        RestoreRecords -> assertTrue(a)
                        DeleteMarker -> {}
                    }
                }
                assertEquals(DeleteMarker, steps.filter { it != DeleteOld }.last())
                assertFalse("$phase $arch $staging $old leaves arch.restore", s)
                // A complete arch.restore counts once swapping began; an unfinished one is no rootfs.
                if (arch || old || (staging && phase == RestorePhase.Swapping)) assertTrue("$phase $arch $staging $old leaves no rootfs", a)
            }
        }
    }

    @Test fun compressedFilesAreStoredAsIs() {
        for (n in listOf("x.tar.gz", "font.woff2", "icon.PNG", "a.zst", "pkg.tar.xz")) assertTrue(n, storedAsIs(n))
        for (n in listOf("libc.so.6", "bash", "README", "desc", "config.json", ".gz-not")) assertFalse(n, storedAsIs(n))
    }
}
