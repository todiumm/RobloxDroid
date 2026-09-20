package com.robloxdroid.studio

import android.content.Context
import android.util.Log
import java.io.File

/**
 * RobloxDroid — launcher do Roblox Studio.
 *
 * Diferença central em relação ao PolyDroid2 (que rodava o cliente Linux do
 * Polytoria direto no Box64): o Roblox Studio é um programa Windows, então a
 * cadeia de execução ganha uma camada extra:
 *
 *   Android → libbox64.so (x86_64→ARM64) → Wine (x86_64) → RobloxStudioBeta.exe
 *                                                          └─ DXVK (D3D11→Vulkan)
 *
 * O Vulkan final é o Turnip/Mesa ARM64 nativo (mesma ponte de Surface do
 * PolyDroid2, via libxvk_droid.so + vulkan_surface_shim).
 *
 * Mantém a API pública original (launch/stop/sessionLog*) para não tocar na
 * GameActivity nem no LogReporter.
 */
object Box64Launcher {
    private const val TAG = "RobloxDroid"
    private const val SESSION_LOG_MAX = 4L shl 20

    private var process: Process? = null
    private var sessionLogDir: File? = null
    private var sessionLogWriter: java.io.Writer? = null
    private var sessionLogBytes = 0L

    fun sessionLogFiles(ctx: Context): List<File> =
        listOf(File(ctx.filesDir, "session.log.1"), File(ctx.filesDir, "session.log")).filter { it.exists() }

    @Synchronized
    private fun startSessionLog(ctx: Context) {
        try {
            sessionLogWriter?.close()
        } catch (_: Exception) {}
        File(ctx.filesDir, "session.log.1").delete()
        sessionLogDir = ctx.filesDir
        sessionLogBytes = 0
        sessionLogWriter = try {
            java.io.FileWriter(File(ctx.filesDir, "session.log"), false)
        } catch (e: Exception) {
            Log.w(TAG, "session log open failed: ${e.message}")
            null
        }
    }

    @Synchronized
    fun sessionLog(line: String) {
        val w = sessionLogWriter ?: return
        try {
            w.write(line)
            w.write("\n")
            w.flush()
            sessionLogBytes += line.length + 1
            if (sessionLogBytes > SESSION_LOG_MAX) {
                w.close()
                val dir = sessionLogDir ?: return
                val f = File(dir, "session.log")
                f.renameTo(File(dir, "session.log.1"))
                sessionLogWriter = java.io.FileWriter(f, false)
                sessionLogBytes = 0
            }
        } catch (_: Exception) {
            sessionLogWriter = null
        }
    }

    /** Caminho do Wine dentro do rootfs (instalado por StudioDownloader). */
    fun wineDir(ctx: Context): File = File(RootFs.rootDir(ctx), "opt/wine")
    fun wineBinDir(ctx: Context): File = File(wineDir(ctx), "bin")
    fun winePrefixDir(ctx: Context): File = File(RootFs.rootDir(ctx), "wineprefix")

    /**
     * Procura o executável do Studio nas duas localizações suportadas:
     *  1. Instalação oficial via zip do CDN:
     *     $WINEPREFIX/drive_c/users/user/AppData/Local/Roblox/Versions/version-* /RobloxStudioBeta.exe
     *  2. Instalação importada:  /opt/roblox/RobloxStudioBeta.exe
     */
    fun findStudioExe(ctx: Context): File? {
        val root = RootFs.rootDir(ctx)
        val versionsDir = File(winePrefixDir(ctx), "drive_c/users/user/AppData/Local/Roblox/Versions")
        val selected = StudioDownloader.studioInstalledVersion(ctx)
        if (selected != null) {
            val active = runCatching { StudioFiles.versionDirectory(versionsDir, selected) }.getOrNull()
            active?.let { StudioFiles.findExe(it) }?.let { return it }
        }
        val official = versionsDir.listFiles { f -> f.isDirectory && f.name.startsWith("version-") }
            ?.sortedByDescending { it.lastModified() }
            ?.firstNotNullOfOrNull { StudioFiles.findExe(it) }
        if (official != null) return official
        val imported = File(root, "opt/roblox/RobloxStudioBeta.exe")
        if (imported.exists()) return imported
        // busca recursiva de reserva (instalações em pastas customizadas)
        return File(root, "opt/roblox").walkTopDown()
            .maxDepth(4)
            .filter { it.isFile && it.name.equals("RobloxStudioBeta.exe", ignoreCase = true) }
            .firstOrNull()
    }

    fun launch(ctx: Context, tmpDir: String, gameArgs: String = "", screenWidth: Int = 1280, screenHeight: Int = 720, onLog: (String) -> Unit, onExit: ((Int) -> Unit)? = null): Process {
        val root = RootFs.rootDir(ctx)
        val rootPath = root.absolutePath
        val nativeDir = ctx.applicationInfo.nativeLibraryDir

        val wineBin = wineBinDir(ctx)
        // Kron4ek 9.x amd64: `wine` é ELF i386 (Box64 NÃO executa 32 bits) e
        // `wineboot` é script. O loader válido é o `wine64` (ELF x86_64).
        val wine = listOf("wine64", "wine")
            .map { File(wineBin, it) }
            .firstOrNull { StudioDownloader.elfX8664(it) }
            ?: throw IllegalStateException(
                "Nenhum loader Wine x86_64 (wine64) válido em $wineBin — reinstale o Wine no app"
            )
        require(RootFs.isWineInstalled(ctx)) { "Wine não implantado no rootfs — rode o instalador antes" }

        val studioExe = findStudioExe(ctx)
            ?: throw IllegalStateException("RobloxStudioBeta.exe não encontrado — instale ou importe o Studio primeiro")
        onLog("Studio: ${studioExe.absolutePath.removePrefix(rootPath)}")

        val prefix = winePrefixDir(ctx)
        require(File(prefix, "system.reg").exists()) { "WINEPREFIX não inicializado — rode wineboot primeiro" }

        val box64 = File(nativeDir, "libbox64.so")
        require(box64.exists()) { "Box64 binary not found at ${box64.absolutePath}" }

        // Limpa resíduos de sessão anterior
        File("$rootPath/tmp").mkdirs()
        File("$rootPath/tmp/.X11-unix").apply { mkdirs(); setReadable(true, false); setExecutable(true, false); setWritable(true, false) }
        File("$rootPath/tmp/dxvk-logs").mkdirs()
        File("$rootPath/tmp/dxvk-cache").mkdirs()
        File("$rootPath/dev/shm").apply { mkdirs(); setReadable(true, false); setWritable(true, false); setExecutable(true, false) }

        // Aplica ClientAppSettings.json (FFlags do Studio) conforme as preferências
        try { StudioPrefs.applyTo(ctx, root, studioExe) } catch (e: Exception) { Log.w(TAG, "studio prefs apply failed: ${e.message}") }

        // ----- /etc do rootfs (hosts, dns, certs) — mesmo padrão do PolyDroid2 -----
        val etcDir = File("$rootPath/etc")
        etcDir.mkdirs()
        File(etcDir, "passwd").takeIf { !it.exists() }?.writeText(
            "root:x:0:0:root:/root:/bin/sh\nuser:x:1000:1000:user:/home/user:/bin/sh\n"
        )
        File(etcDir, "group").takeIf { !it.exists() }?.writeText(
            "root:x:0:\nuser:x:1000:\n"
        )
        val hostsBuilder = StringBuilder("127.0.0.1 localhost\n")
        for (hostname in listOf(
            "www.roblox.com", "apis.roblox.com", "setup.rbxcdn.com",
            "clientsettings.roblox.com", "assetdelivery.roblox.com",
            "content.rbxcdn.com", "roblox.com"
        )) {
            try {
                val addrs = java.net.InetAddress.getAllByName(hostname)
                val v4 = addrs.firstOrNull { it is java.net.Inet4Address }
                if (v4 != null) {
                    hostsBuilder.append("${v4.hostAddress} $hostname\n")
                    Log.i(TAG, "Resolved $hostname -> ${v4.hostAddress}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve $hostname: ${e.message}")
            }
        }
        File(etcDir, "hosts").writeText(hostsBuilder.toString())
        val dnsServers = getSystemDnsServers(ctx)
        File(etcDir, "resolv.conf").writeText(
            dnsServers.joinToString("\n") { "nameserver $it" } + "\n"
        )
        File(etcDir, "ld.so.conf").writeText(
            "$rootPath/opt/wine/lib\n$rootPath/usr/lib/x86_64-linux-gnu\n$rootPath/usr/lib\n"
        )
        val sslDir = File(etcDir, "ssl/certs")
        sslDir.mkdirs()
        val caBundle = File(sslDir, "ca-certificates.crt")
        if (!caBundle.exists()) {
            val systemCertsDir = java.io.File("/system/etc/security/cacerts")
            if (systemCertsDir.isDirectory) {
                val sb = StringBuilder()
                systemCertsDir.listFiles()?.sorted()?.forEach { certFile ->
                    try { sb.append(certFile.readText()) } catch (_: Exception) {}
                }
                caBundle.writeText(sb.toString())
                Log.i(TAG, "Built CA bundle from ${systemCertsDir.listFiles()?.size ?: 0} system certs")
            }
        }

        // ----- Ambiente do convidado (Wine sobre Box64) -----
        val env = guestEnv(ctx, screenWidth, screenHeight)

        val gameArgStr = if (gameArgs.isNotBlank()) " $gameArgs" else ""

        // Afixa o Box64 nos núcleos grandes (mesma estratégia do PolyDroid2)
        val bigMask = getBigCoresMask()
        val sysTaskset = listOf("/system/bin/taskset", "/system/xbin/taskset")
            .firstOrNull { File(it).canExecute() }
        val execPrefix = if (sysTaskset != null && bigMask != null) {
            Log.i(TAG, "Pinning Box64 via $sysTaskset 0x$bigMask")
            "$sysTaskset $bigMask "
        } else {
            Log.i(TAG, "Not pinning Box64 (taskset=$sysTaskset mask=$bigMask)")
            ""
        }

        val launchScript = File("$rootPath/tmp/launch.sh")
        val envExports = env.entries.joinToString("\n") { (k, v) ->
            "export $k=${StudioFiles.shellQuote(v)}"
        }

        val studioWinPath = toWindowsPath(studioExe, prefix)
        val wineCmd = listOf(box64.absolutePath, wine.absolutePath, studioWinPath)
            .joinToString(" ") { StudioFiles.shellQuote(it) } + gameArgStr
        val execLine = if (execPrefix.isNotBlank())
            "${execPrefix}/system/bin/true >/dev/null 2>&1 && exec $execPrefix$wineCmd\nexec $wineCmd"
        else
            "exec $wineCmd"
        launchScript.writeText("""#!/bin/sh
$envExports
cd "$rootPath/tmp"
export LD_PRELOAD="$nativeDir/libhost_syscall_shim.so"
$execLine
""")
        launchScript.setExecutable(true, false)

        val cmd = arrayOf("/system/bin/sh", launchScript.absolutePath)
        val launchEnv = System.getenv().map { (k, v) -> "$k=$v" }.toTypedArray()

        val cmdStr = cmd.joinToString(" ")
        startSessionLog(ctx)
        sessionLog("----------- session start ${java.util.Date()}")
        sessionLog("Launching: $cmdStr")
        sessionLog("Studio exe: ${studioExe.absolutePath}")
        env.entries.forEach { (k, v) -> sessionLog("  $k=$v") }
        Log.i(TAG, "Launching: $cmdStr")

        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = cm.activeNetwork
            if (network != null) {
                cm.bindProcessToNetwork(network)
                Log.i(TAG, "Bound process to network: $network")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bind process to network: ${e.message}")
        }

        val proc = Runtime.getRuntime().exec(cmd, launchEnv, root)
        process = proc

        fun logStream(stream: java.io.InputStream, label: String) {
            Thread({
                try {
                    stream.bufferedReader().use { reader ->
                        reader.lineSequence().forEach { line ->
                            Log.i(TAG, "[$label] $line")
                            sessionLog(line)
                        }
                    }
                } catch (_: java.io.IOException) {
                    // wine/box64 saiu e o pipe fechou. esperado.
                }
            }, "wine-$label").start()
        }
        logStream(proc.inputStream, "Wine")
        logStream(proc.errorStream, "Wine")

        Thread({
            val exit = proc.waitFor()
            Log.i(TAG, "Wine exited with code $exit")
            sessionLog("Wine exited with code $exit")
            onLog("Wine exited with code $exit")
            onExit?.invoke(exit)
        }, "wine-wait").start()

        return proc
    }

    /**
     * Ambiente completo do convidado (Wine sobre Box64).
     * Reutilizado pelo StudioDownloader para wineboot/registro.
     */
    fun guestEnv(ctx: Context, screenWidth: Int = 1280, screenHeight: Int = 720): Map<String, String> {
        val root = RootFs.rootDir(ctx)
        val rootPath = root.absolutePath
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val prefix = winePrefixDir(ctx)

        val safeMode = SettingsActivity.isSafeMode(ctx)
        val peakCpuKHz = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }
            ?.maxOfOrNull { c -> try { File(c, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() } catch (_: Exception) { 0L } } ?: 0L
        val lowEnd = !safeMode && peakCpuKHz in 1 until 2_500_000L
        if (lowEnd) Log.i(TAG, "Low end mode active. peak cpu frequency ${peakCpuKHz}kHz")

        val x86Pre = "$rootPath/usr/lib/x86_64-linux-gnu"
        return buildMap {
            put("HOME", "$rootPath/home/user")
            put("USER", "user")
            put("TMPDIR", "$rootPath/tmp")
            put("PATH", "$rootPath/opt/wine/bin:$rootPath/usr/bin:$rootPath/bin")
            put("PREFIX", "$rootPath/usr")
            put("XKB_CONFIG_ROOT", "$rootPath/usr/share/X11/xkb")
            put("XKB_CONFIG_EXTRA_PATH", "$rootPath/usr/share/X11/xkb")
            put("LD_LIBRARY_PATH", "$rootPath/usr/lib/arm64-native:$nativeDir:/system/lib64")

            // Vulkan: ICD ARM64 nativo (Turnip) — mesma ponte de surface do PolyDroid2
            put("VK_ICD_FILENAMES", "$rootPath/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json")
            put("MESA_VK_WSI_PRESENT_MODE", "mailbox")

            // Wine
            put("WINEPREFIX", prefix.absolutePath)
            put("WINEARCH", "win64")
            put("WINEDEBUG", "-all")
            put("WINEDLLOVERRIDES", "d3d11,d3d10core,d3d9,dxgi=n,b")   // força DXVK
            put("DXVK_LOG_PATH", "$rootPath/tmp/dxvk-logs")
            put("DXVK_STATE_CACHE_PATH", "$rootPath/tmp/dxvk-cache")
            put("DXVK_HUD", if (SettingsActivity.isDxvkHud(ctx)) "fps" else "0")

            // Box64: bibliotecas emuladas — Wine primeiro, depois libs do rootfs
            put("BOX64_LD_LIBRARY_PATH",
                "$rootPath/opt/wine/lib:" +
                "$rootPath/opt/wine/lib/wine:" +
                x86Pre + ":" +
                "$rootPath/usr/lib")
            put("BOX64_EMULATED_LIBS",
                "libudev.so.1:" +
                "libstdc++.so.6:libgcc_s.so.1:" +
                "libX11.so.6:libxcb.so.1:libXext.so.6:" +
                "libXau.so.6:libXdmcp.so.6:" +
                "libXrandr.so.2:libXi.so.6:libXcursor.so.1:" +
                "libXinerama.so.1:libXss.so.1:libXxf86vm.so.1:" +
                "libXfixes.so.3:libXrender.so.1:" +
                "libasound.so.2:" +
                "libpulse.so.0:libpulse-simple.so.0:" +
                "librt.so.1")

            // Shims x86_64 genéricos (mesmos do PolyDroid2, sem os de Unity/Godot)
            put("BOX64_LD_PRELOAD", listOf(
                "$x86Pre/libeaccess_shim.so",
                "$x86Pre/libpthread_recursive_fix.so",
                "$x86Pre/libctype_fix.so",
                "$x86Pre/libdns_resolver.so",
                "$x86Pre/libconnect_redirect.so"
            ).joinToString(":"))

            put("BOX64_LOG", if (safeMode) "1" else "0")
            put("BOX64_SHOWSEGV", "1")
            put("BOX64_SHOWBT", "1")
            put("BOX64_DLSYM_ERROR", "0")
            put("BOX64_CRASHHANDLER", "1")
            put("BOX64_DYNAREC", "1")
            // Perfil de dynarec estilo Winlator (Wine é mais sensível que engines nativas)
            put("BOX64_DYNAREC_BIGBLOCK", if (safeMode) "0" else "1")
            put("BOX64_DYNAREC_STRONGMEM", if (safeMode) "2" else "1")
            put("BOX64_DYNAREC_WEAKBARRIER", if (safeMode) "0" else "1")
            put("BOX64_DYNAREC_FASTNAN", if (safeMode) "0" else "1")
            put("BOX64_DYNAREC_FASTROUND", if (safeMode) "0" else "1")
            put("BOX64_DYNAREC_SAFEFLAGS", if (safeMode) "2" else "1")
            put("BOX64_DYNAREC_CALLRET", if (safeMode) "0" else "1")
            put("BOX64_DYNAREC_SEP", if (safeMode) "0" else "2")
            put("BOX64_DYNAREC_FORWARD", if (safeMode || lowEnd) "128" else "512")
            put("BOX64_DYNAREC_ALIGNED_ATOMICS", if (safeMode) "0" else "1")
            put("BOX64_DYNAREC_PAUSE", "1")
            put("BOX64_DYNAREC_WAIT", "0")
            put("BOX64_DYNAREC_DIRTY", "0")
            put("BOX64_DYNAREC_BLEEDING_EDGE", "0")
            put("BOX64_DYNAREC_NATIVEFLAGS", if (safeMode) "0" else "1")
            put("BOX64_DYNACACHE", "0")
            put("BOX64_NORCFILES", "1")
            put("BOX64_ALLOWMISSINGLIBS", "1")
            put("BOX64_MMAP32", "1")      // Wine costuma precisar de mapa baixo
            put("BOX64_AVX", "2")
            put("BOX64_AES", "0")
            put("BOX64_PCLMULQDQ", "0")
            put("BOX64_SHAEXT", "0")

            // Ponte nativa (libxvk_droid / vulkan_surface_shim)
            put("ROBLOXDROID_ROOTDIR", rootPath)
            put("ROBLOXDROID_POLYTORIA2", "1") // enables the bundled ALSA and touch stubs
            put("ROBLOXDROID_NATIVE_DIR", nativeDir)
            val ptrFile = java.io.File(ctx.filesDir, "vulkan_surface_ptr")
            if (ptrFile.exists()) {
                put("ROBLOXDROID_VULKAN_SURFACE_PTR", ptrFile.readText().trim())
            }
            val vulkanDriver = SettingsActivity.getVulkanDriver(ctx)
            if (vulkanDriver == SettingsActivity.VULKAN_DRIVER_SYSTEM) {
                put("ROBLOXDROID_FORCE_SYSTEM_DRIVER", "1")
            } else if (vulkanDriver == SettingsActivity.VULKAN_DRIVER_TURNIP) {
                put("ROBLOXDROID_FORCE_TURNIP", "1")
            }
            put("ROBLOXDROID_SCREEN_WIDTH", "$screenWidth")
            put("ROBLOXDROID_SCREEN_HEIGHT", "$screenHeight")
            val maxFps = SettingsActivity.getMaxFps(ctx)
            if (maxFps > 0) put("ROBLOXDROID_MAX_FPS", "$maxFps")
            val displayHz = try {
                if (android.os.Build.VERSION.SDK_INT >= 30) ctx.display?.refreshRate ?: 60f
                else {
                    @Suppress("DEPRECATION")
                    (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                        .defaultDisplay.refreshRate
                }
            } catch (_: Exception) { 60f }
            put("ROBLOXDROID_DISPLAY_FPS", "${kotlin.math.round(displayHz).toInt()}")

            put("SSL_CERT_FILE", "$rootPath/etc/ssl/certs/ca-certificates.crt")
            put("SSL_CERT_DIR", "$rootPath/etc/ssl/certs")
            put("CURL_CA_BUNDLE", "$rootPath/etc/ssl/certs/ca-certificates.crt")
            put("LC_ALL", "C")
            put("LANG", "C")
            put("OPENSSL_ia32cap", "0:0:0:0")

            // X11 (Termux:X11)
            put("SDL_VIDEODRIVER", "x11")
            put("DISPLAY", ":0")
            put("XMODIFIERS", "@im=none")
            put("GTK_IM_MODULE", "")
            put("QT_IM_MODULE", "")
            put("SDL_VIDEO_X11_VISUALID", "0x21")
            put("SDL_VIDEO_X11_XRANDR", "0")
            put("SDL_LOG_PRIORITY", "critical")
        }
    }

    /** Converte caminho do rootfs para Z:\... dentro do prefixo (drive Z mapeado para /). */
    private fun toWindowsPath(exe: File, prefix: File): String {
        // Studio instalado dentro do prefixo → caminho C:\ nativo
        val prefixPath = prefix.absolutePath
        if (exe.absolutePath.startsWith(prefixPath + File.separator)) {
            val rel = exe.absolutePath.removePrefix(prefixPath).trimStart('/')
            return "C:\\" + rel.replaceFirst("drive_c/", "").replace('/', '\\')
        }
        // Importado no rootfs → drive Z:\
        return "Z:\\" + exe.absolutePath.replace('/', '\\')
    }

    fun stop() {
        process?.destroy()
        process = null
    }

    private fun getBigCoresMask(): String? {
        return try {
            val freqs = (0 until 64).mapNotNull { i ->
                try {
                    val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                        .readText().trim().toLong()
                    if (f > 0) Pair(i, f) else null
                } catch (_: Exception) { null }
            }
            if (freqs.isEmpty()) return null
            val tiers = freqs.map { it.second }.distinct().sorted()
            if (tiers.size == 1) return null
            val skipFreq: Long? = if (tiers.size >= 3) tiers.first() else null
            var mask = 0L
            for ((i, f) in freqs) if (f != skipFreq) mask = mask or (1L shl i)
            if (mask == 0L) return null
            val allowed = readCpusAllowed()
            if (allowed != 0L && (mask and allowed) == 0L) {
                Log.i(TAG, "big cores ${mask.toString(2)} disjoint from cpuset ${allowed.toString(2)}, not pinning")
                return null
            }
            Log.i(TAG, "cpu tiers: $tiers | skipping ${skipFreq ?: "none"}")
            mask.toString(16)
        } catch (_: Exception) { null }
    }

    private fun readCpusAllowed(): Long {
        return try {
            val line = File("/proc/self/status").readLines()
                .firstOrNull { it.startsWith("Cpus_allowed:") } ?: return 0L
            val hex = line.removePrefix("Cpus_allowed:").trim().replace(",", "")
            if (hex.isEmpty()) 0L else java.math.BigInteger(hex, 16).toLong()
        } catch (_: Exception) { 0L }
    }

    private fun getSystemDnsServers(ctx: Context): List<String> {
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = cm.activeNetwork
            if (network != null) {
                val lp = cm.getLinkProperties(network)
                if (lp != null) {
                    val servers = lp.dnsServers.map { it.hostAddress!! }.filter { it.isNotEmpty() }
                    if (servers.isNotEmpty()) return servers
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get system DNS: ${e.message}")
        }
        return listOf("8.8.8.8", "8.8.4.4")
    }
}
