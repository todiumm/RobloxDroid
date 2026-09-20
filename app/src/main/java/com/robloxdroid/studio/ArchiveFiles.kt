package com.robloxdroid.studio

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipInputStream

/** All archive paths (including link targets) must remain inside the destination. */
internal object ArchiveFiles {
    fun resolve(root: File, name: String): File {
        val normalized = name.replace('\\', '/')
        if (normalized.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
            throw IOException("Caminho absoluto no arquivo: $name")
        }
        val base = root.canonicalFile.toPath()
        val out = File(root, normalized).canonicalFile
        if (!out.toPath().startsWith(base)) throw IOException("Caminho fora do destino: $name")
        return out
    }

    fun extractZip(zip: ZipInputStream, root: File, onEntry: () -> Unit = {}) {
        root.mkdirs()
        while (true) {
            val entry = zip.nextEntry ?: break
            val out = resolve(root, entry.name)
            if (entry.isDirectory) out.mkdirs()
            else {
                out.parentFile?.mkdirs()
                out.outputStream().use { zip.copyTo(it) }
            }
            zip.closeEntry()
            onEntry()
        }
    }

    fun extractTar(tar: TarArchiveInputStream, root: File, stripFirst: Boolean = false) {
        root.mkdirs()
        val hardLinks = mutableListOf<Pair<String, String>>()
        fun relative(name: String): String {
            // Validate before stripping, so /evil/file cannot become file.
            resolve(root, name)
            return if (stripFirst) name.substringAfter('/', "") else name
        }
        while (true) {
            val entry = tar.nextEntry ?: break
            val rel = relative(entry.name)
            if (rel.isBlank()) continue
            val out = resolve(root, rel)
            when {
                entry.isDirectory -> out.mkdirs()
                entry.isSymbolicLink -> {
                    val target = entry.linkName.replace('\\', '/')
                    if (File(target).isAbsolute || Regex("^[A-Za-z]:").containsMatchIn(target)) {
                        throw IOException("Link absoluto no arquivo: ${entry.name}")
                    }
                    val resolved = File(out.parentFile, target)
                    resolve(root, resolved.relativeTo(root.canonicalFile).path)
                    out.parentFile?.mkdirs()
                    Files.createSymbolicLink(out.toPath(), java.nio.file.Paths.get(target))
                }
                entry.isLink -> hardLinks.add(rel to relative(entry.linkName))
                entry.isFile -> {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                    out.setReadable(true, false)
                    if (entry.mode and 0b001_001_001 != 0) out.setExecutable(true, false)
                }
                else -> throw IOException("Tipo de entrada não suportado: ${entry.name}")
            }
        }
        // A hard link can precede its target in the archive.
        while (hardLinks.isNotEmpty()) {
            val pending = hardLinks.size
            val iterator = hardLinks.iterator()
            while (iterator.hasNext()) {
                val (name, target) = iterator.next()
                val source = resolve(root, target)
                if (!Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
                val out = resolve(root, name)
                out.parentFile?.mkdirs()
                try {
                    Files.createLink(out.toPath(), source.toPath())
                } catch (_: java.nio.file.FileSystemException) {
                    // Android/SELinux can forbid link(2) even in the app's own directory.
                    source.copyTo(out)
                    out.setExecutable(source.canExecute(), false)
                }
                iterator.remove()
            }
            if (hardLinks.size == pending) throw IOException("Hard link sem destino no arquivo")
        }
    }

    fun replaceDirectory(staged: File, destination: File) {
        val backup = File(destination.parentFile, ".${destination.name}-backup-${System.nanoTime()}")
        val hadOld = destination.exists()
        if (hadOld && !destination.renameTo(backup)) throw IOException("Não foi possível preservar $destination")
        if (!staged.renameTo(destination)) {
            if (hadOld && !backup.renameTo(destination)) {
                throw IOException("Falha ao instalar; cópia anterior preservada em $backup")
            }
            throw IOException("Não foi possível instalar em $destination")
        }
        if (hadOld) backup.deleteRecursively()
    }
}
