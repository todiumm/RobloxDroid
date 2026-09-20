package com.robloxdroid.studio

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ArchiveFilesTest {
    @get:Rule val temp = TemporaryFolder()

    private fun zip(name: String, content: String = "studio"): ZipInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use {
            it.putNextEntry(ZipEntry(name))
            it.write(content.toByteArray())
            it.closeEntry()
        }
        return ZipInputStream(ByteArrayInputStream(bytes.toByteArray()))
    }

    private fun tar(vararg entries: Pair<TarArchiveEntry, String>): TarArchiveInputStream {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(bytes).use { out ->
            entries.forEach { (entry, content) ->
                entry.size = content.toByteArray().size.toLong()
                out.putArchiveEntry(entry)
                out.write(content.toByteArray())
                out.closeArchiveEntry()
            }
        }
        return TarArchiveInputStream(ByteArrayInputStream(bytes.toByteArray()))
    }

    @Test fun nestedZipCanBeImportedAndLocated() {
        val root = temp.newFolder("version")
        zip("Studio/bin/ROBLOXSTUDIOBETA.EXE").use { ArchiveFiles.extractZip(it, root) }
        assertEquals("studio", StudioFiles.findExe(root)?.readText())
    }

    @Test fun zipCannotEscapeToSiblingWithSamePrefix() {
        val root = temp.newFolder("version")
        zip("../version-other/escaped").use {
            assertThrows(IOException::class.java) { ArchiveFiles.extractZip(it, root) }
        }
        assertFalse(File(temp.root, "version-other").exists())
    }

    @Test fun windowsTraversalAndAbsoluteNamesAreRejected() {
        val root = temp.newFolder()
        for (name in listOf("..\\escape", "/tmp/escape", "C:\\escape")) {
            zip(name).use { input ->
                assertThrows(IOException::class.java) { ArchiveFiles.extractZip(input, root) }
            }
        }
    }

    @Test fun existingSymlinkCannotRedirectZipWritesOutsideRoot() {
        val root = temp.newFolder()
        val outside = temp.newFolder()
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        zip("link/escape").use {
            assertThrows(IOException::class.java) { ArchiveFiles.extractZip(it, root) }
        }
        assertFalse(File(outside, "escape").exists())
    }

    @Test fun rootfsHardLinksPreserveContentsEvenBeforeTarget() {
        val root = temp.newFolder()
        val link = TarArchiveEntry("usr/share/rules/xorg", TarConstants.LF_LINK)
        link.linkName = "usr/share/rules/base"
        tar(link to "", TarArchiveEntry("usr/share/rules/base") to "rules").use {
            ArchiveFiles.extractTar(it, root)
        }
        assertEquals("rules", File(root, "usr/share/rules/xorg").readText())
        assertEquals("rules", File(root, "usr/share/rules/base").readText())
    }

    @Test fun missingHardLinkTargetFails() {
        val link = TarArchiveEntry("xorg", TarConstants.LF_LINK).apply { linkName = "missing" }
        tar(link to "").use { input ->
            assertThrows(IOException::class.java) { ArchiveFiles.extractTar(input, temp.newFolder()) }
        }
    }

    @Test fun tarKeepsSafeRelativeSymlinksAndExecutableMode() {
        val root = temp.newFolder()
        val binary = TarArchiveEntry("wine/bin/wine64").apply { mode = 493 }
        val link = TarArchiveEntry("wine/bin/wine", TarConstants.LF_SYMLINK).apply { linkName = "wine64" }
        tar(binary to "ELF", link to "").use { ArchiveFiles.extractTar(it, root, true) }
        assertEquals("ELF", File(root, "bin/wine").readText())
        assertTrue(File(root, "bin/wine64").canExecute())
    }

    @Test fun tarRejectsEscapingSymlink() {
        val link = TarArchiveEntry("wine/bin/link", TarConstants.LF_SYMLINK).apply { linkName = "../../outside" }
        tar(link to "").use { input ->
            assertThrows(IOException::class.java) { ArchiveFiles.extractTar(input, temp.newFolder(), true) }
        }
    }

    @Test fun tarRejectsTraversalAfterTopDirectory() {
        tar(TarArchiveEntry("wine/../../escape") to "bad").use { input ->
            assertThrows(IOException::class.java) { ArchiveFiles.extractTar(input, temp.newFolder(), true) }
        }
    }

    @Test fun failedPublishRestoresPreviousInstallation() {
        val destination = temp.newFolder("installed")
        File(destination, "data").writeText("previous")
        assertThrows(IOException::class.java) {
            ArchiveFiles.replaceDirectory(File(temp.root, "missing-stage"), destination)
        }
        assertEquals("previous", File(destination, "data").readText())
    }

    @Test fun publishReplacesInstallationOnlyAfterStaging() {
        val destination = temp.newFolder("installed")
        File(destination, "data").writeText("previous")
        val staged = temp.newFolder("staged")
        File(staged, "data").writeText("new")
        ArchiveFiles.replaceDirectory(staged, destination)
        assertEquals("new", File(destination, "data").readText())
        assertFalse(staged.exists())
    }

    @Test fun versionCannotEscapeAndIsNotPrefixedTwice() {
        assertEquals("version-abc", StudioFiles.versionDirectory(temp.root, "version-abc").name)
        assertThrows(IOException::class.java) { StudioFiles.versionDirectory(temp.root, "../outside") }
    }

    @Test fun quotedShellArgumentPreservesWindowsPathsAndMetacharacters() {
        val value = "C:\\some path\\RobloxStudioBeta.exe ' \$HOME `echo bad`"
        val process = ProcessBuilder("sh", "-c", "printf '%s' " + StudioFiles.shellQuote(value)).start()
        assertEquals(value, process.inputStream.bufferedReader().readText())
        assertEquals(0, process.waitFor())
    }
}
