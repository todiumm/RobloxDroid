package com.robloxdroid.studio

import java.io.File
import java.io.IOException

internal object StudioFiles {
    fun findExe(directory: File): File? = directory.walkTopDown()
        .filter { it.isFile && it.name.equals("RobloxStudioBeta.exe", ignoreCase = true) }
        .firstOrNull()

    fun versionDirectory(versions: File, version: String): File {
        if (!version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) {
            throw IOException("Identificador de versão inválido: $version")
        }
        return File(versions, "version-" + version.removePrefix("version-"))
    }

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}
