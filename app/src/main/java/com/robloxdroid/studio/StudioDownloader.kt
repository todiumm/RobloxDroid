package com.robloxdroid.studio

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * RobloxDroid — instalador do ambiente Windows para o Roblox Studio.
 *
 * Equivalente ao ClientDownloader do PolyDroid2 (que baixava o cliente Linux do
 * Polytoria). Aqui o "cliente" é o Roblox Studio Windows, então o instalador
 * também provê as camadas que o PolyDroid2 não precisava:
 *
 *   1. Wine x86_64  (Kron4ek Wine-Builds)      → executa binários Windows
 *   2. DXVK x64     (doitsujin/dxvk)           → D3D9/D3D10/D3D11 → Vulkan
 *   3. WINEPREFIX   (wineboot --init via Box64) → C:\ virtual
 *   4. Roblox Studio (zip oficial do CDN da Roblox, mesmo caminho do Grapejuice)
 *
 * Nenhum binário da Roblox é distribuído neste projeto: o Studio é baixado
 * sob demanda dos servidores oficiais da Roblox (mesma origem do launcher
 * oficial) ou importado pelo usuário.
 */
object StudioDownloader {
    private const val TAG = "RobloxDroid"
    private const val MARKER_WINE = ".rd_wine_version"
    private const val MARKER_DXVK = ".rd_dxvk_version"
    private const val MARKER_PREFIX = ".rd_prefix_ready"
    private const val MARKER_STUDIO = ".rd_studio_version"

    // --- Releases públicos usados na primeira instalação -------------------
    // Wine amd64 (Kron4ek Wine-Builds): ajuste em Settings → "Avançado"
    const val WINE_URL_DEFAULT = "https://github.com/Kron4ek/Wine-Builds/releases/download/9.0/wine-9.0-amd64.tar.xz"
    const val WINE_VERSION_DEFAULT = "9.0"
    // DXVK x64
    const val DXVK_URL_DEFAULT = "https://github.com/doitsujin/dxvk/releases/download/v2.4/dxvk-2.4.tar.gz"
    const val DXVK_VERSION_DEFAULT = "2.4"
    // Canais oficiais da Roblox. ATENÇÃO: a Roblox APOSENTOU os binaryTypes
    // antigos ("Studio"/"Studio64"/"PCClient" → HTTP 400 "Invalid binaryType.")
    // e renomeou os canais Windows para WindowsStudio64/WindowsPlayer. O nome
    // do zip no CDN também mudou: RobloxStudioBeta.zip → RobloxStudio.zip, e o
    // artefato é indexado pelo clientVersionUpload ("version-<hash>"), NÃO pelo
    // campo "version" numérico. Verificado ao vivo em 17/09/2026.
    const val STUDIO_VERSION_API_DEFAULT = "https://clientsettings.roblox.com/v2/client-version/WindowsStudio64"
    const val STUDIO_ZIP_TEMPLATE_DEFAULT = "https://setup.rbxcdn.com/%1\$s-RobloxStudio.zip"
    // Valores antigos — usados só para migrar prefs já gravadas no aparelho.
    const val STUDIO_VERSION_API_OLD = "https://clientsettings.roblox.com/v2/client-version/Studio"
    const val STUDIO_ZIP_TEMPLATE_OLD = "https://setup.rbxcdn.com/%1\$s-RobloxStudioBeta.zip"

    // "const" só aceita tipo primitivo ou String em tempo de compilação — List precisa ser "val" normal.
    private val DLLS = listOf("d3d9.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll")

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    // ---------------------------------------------------------------- marcadores
    fun wineDir(ctx: Context): File = Box64Launcher.wineDir(ctx)
    fun dxvkDir(ctx: Context): File = File(RootFs.rootDir(ctx), "opt/dxvk")
    fun prefixDir(ctx: Context): File = Box64Launcher.winePrefixDir(ctx)
    fun studioDir(ctx: Context): File = File(prefixDir(ctx), "drive_c/users/user/AppData/Local/Roblox/Versions")

    fun wineInstalledVersion(ctx: Context): String? = markerOf(File(RootFs.rootDir(ctx), MARKER_WINE))
    fun dxvkInstalledVersion(ctx: Context): String? = markerOf(File(RootFs.rootDir(ctx), MARKER_DXVK))
    fun prefixReady(ctx: Context): Boolean = File(RootFs.rootDir(ctx), MARKER_PREFIX).exists()
    fun studioInstalledVersion(ctx: Context): String? = markerOf(File(studioDir(ctx), MARKER_STUDIO))

    private fun markerOf(f: File): String? =
        if (f.exists()) f.readText().trim().ifEmpty { null } else null

    private fun setMarker(f: File, v: String) {
        f.parentFile?.mkdirs()
        f.writeText(v)
    }

    /** Pasta onde o usuário pode soltar um zip do Studio (import manual). */
    fun importDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "import").apply { mkdirs() }

    // ---------------------------------------------------------------- download helpers
    private fun download(url: String, dest: File, onProgress: (Int) -> Unit) {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} em $url")
            val body = resp.body ?: throw IOException("corpo vazio em $url")
            val total = body.contentLength()
            var done = 0L
            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done * 100 / total).toInt().coerceIn(0, 100))
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- patch /tmp
    // O Wine glibc/Linux (Kron4ek) FIXA o socket do wineserver em /tmp/.wine-<uid>
    // (server/request.c e dlls/ntdll/unix/server.c, ramo "#else" do __ANDROID__ —
    // "there's no /tmp dir on Android"). O Android não tem /tmp para apps →
    // "wineserver: mkdir /tmp/.wine-NNN: No such file or directory" → exit 1 no
    // wineboot. TMPDIR NÃO é consultado pelo wineserver — a string é hardcode.
    //
    // Tanto o servidor quanto o cliente (ntdll.so) fazem chdir(WINEPREFIX) ANTES
    // de usar o caminho (servidor: início de create_server_dir; cliente:
    // setup_config_dir — que inclusive CRIA o prefixo se faltar) e todos os
    // acessos seguintes são RELATIVOS. Trocar a string embutida pela versão
    // relativa move o socket para $WINEPREFIX/.wine-<uid>/server-<hash>, que é
    // gravável pelo app. Patch em bytes, mesmo tamanho (padding NUL), idempotente.
    private const val WS_ORIG_PREFIX = "/tmp/.wine-"

    private fun bytesIndexOf(hay: ByteArray, needle: ByteArray, from: Int = 0): Int {
        if (needle.isEmpty()) return from
        val last = hay.size - needle.size
        var i = from
        while (i <= last) {
            if (hay[i] == needle[0]) {
                var j = 1
                while (j < needle.size && hay[i + j] == needle[j]) j++
                if (j == needle.size) return i
            }
            i++
        }
        return -1
    }

    /**
     * Move o socket do wineserver de /tmp/.wine-<uid> para
     * $WINEPREFIX/.wine-<uid> via patch de bytes em bin/wineserver e
     * lib/wine/x86_64-unix/ntdll.so. Idempotente: roda em toda instalação de
     * Wine e no início de todo ensurePrefix (cobre instalações antigas).
     */
    private fun patchWineTmpSocket(ctx: Context, onLog: (String) -> Unit) {
        val wine = wineDir(ctx)
        // par (arquivo, string original embutida) — os 2 únicos lugares do Wine
        // 9.0 que citam /tmp/.wine (confirmado por strings no build Kron4ek)
        val targets = listOf(
            File(wine, "bin/wineserver") to "/tmp/.wine-%u",
            File(wine, "lib/wine/x86_64-unix/ntdll.so") to "/tmp/.wine-%u/server-%s"
        )
        for ((f, origStr) in targets) {
            val nome = f.name
            if (!f.exists()) {
                onLog("Patch socket: $nome ausente — pulando")
                continue
            }
            try {
                val data = f.readBytes()
                val orig = origStr.toByteArray(Charsets.ISO_8859_1).plus(0.toByte())  // inclui o NUL terminador
                val idx = bytesIndexOf(data, orig)
                if (idx >= 0) {
                    val newStr = origStr.removePrefix(WS_ORIG_PREFIX).toByteArray(Charsets.ISO_8859_1)
                    val out = data.copyOf()
                    // mesma região original: nova string + padding NUL (mantém tamanho/offsets)
                    System.arraycopy(newStr, 0, out, idx, newStr.size)
                    java.util.Arrays.fill(out, idx + newStr.size, idx + orig.size, 0.toByte())
                    f.writeBytes(out)
                    onLog("Patch socket: $nome → .wine-<uid> dentro da WINEPREFIX (Android não tem /tmp)")
                    Log.i(TAG, "patchWineTmpSocket: $nome patched (offset $idx)")
                } else {
                    // original ausente: ou já patcheado, ou Wine diferente do esperado
                    val already = origStr.removePrefix(WS_ORIG_PREFIX).toByteArray(Charsets.ISO_8859_1).plus(0.toByte())
                    onLog(
                        if (bytesIndexOf(data, already) >= 0) "Patch socket: $nome já aplicado"
                        else "Aviso: padrão do socket não encontrado em $nome (Wine diferente do Kron4ek 9.0?) — seguindo"
                    )
                }
            } catch (e: Exception) {
                onLog("Aviso: patch do socket falhou em $nome: ${e.message}")
                Log.w(TAG, "patchWineTmpSocket $nome: ${e.message}")
            }
        }
    }

    private fun extractTarXz(archive: File, destDir: File, stripFirst: Boolean = true) {
        BufferedInputStream(archive.inputStream(), 65536).use { buffered ->
            XZInputStream(buffered).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    extractTar(tar, destDir, stripFirst)
                }
            }
        }
    }

    private fun extractTarGz(archive: File, destDir: File, stripFirst: Boolean = true) {
        GZIPInputStream(BufferedInputStream(archive.inputStream(), 65536), 65536).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                extractTar(tar, destDir, stripFirst)
            }
        }
    }

    private fun extractTar(tar: TarArchiveInputStream, destDir: File, stripFirst: Boolean) {
        destDir.mkdirs()
        while (true) {
            val entry = tar.nextEntry ?: break
            val rel = if (stripFirst) entry.name.substringAfter('/', entry.name) else entry.name
            if (rel.isBlank() || rel.startsWith("..")) continue
            val out = File(destDir, rel)
            if (entry.isDirectory) {
                out.mkdirs()
            } else if (entry.isSymbolicLink) {
                try {
                    out.parentFile?.mkdirs()
                    java.nio.file.Files.createSymbolicLink(
                        out.toPath(), java.nio.file.Paths.get(entry.linkName)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "symlink falhou: ${entry.name} -> ${entry.linkName}")
                }
            } else {
                out.parentFile?.mkdirs()
                tar.copyTo(out.outputStream())
                if (entry.mode and 0b001_001_001 != 0) out.setExecutable(true, false)
                out.setReadable(true, false)
            }
        }
    }

    private fun extractZip(archive: File, destDir: File, onProgress: (Int) -> Unit) {
        destDir.mkdirs()
        ZipInputStream(BufferedInputStream(archive.inputStream(), 65536)).use { zip ->
            var count = 0
            while (true) {
                val e = zip.nextEntry ?: break
                val out = File(destDir, e.name)
                if (!out.canonicalPath.startsWith(destDir.canonicalPath)) {
                    throw IOException("zip suspeito: ${e.name}")
                }
                if (e.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    zip.copyTo(out.outputStream())
                    out.setReadable(true, false)
                    out.setWritable(true, false)
                }
                if (++count % 200 == 0) onProgress(-1)
            }
        }
    }

    // ---------------------------------------------------------------- 1) Wine
    fun ensureWine(ctx: Context, onProgress: (Int, String) -> Unit) {
        if (wineInstalledVersion(ctx) != null) return
        val prefs = ctx.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val url = prefs.getString(SettingsActivity.KEY_WINE_URL, WINE_URL_DEFAULT) ?: WINE_URL_DEFAULT
        val ver = prefs.getString(SettingsActivity.KEY_WINE_VERSION, WINE_VERSION_DEFAULT) ?: WINE_VERSION_DEFAULT

        onProgress(0, "Baixando Wine $ver…")
        val tmp = File(ctx.cacheDir, "wine.tar.xz")
        download(url, tmp) { onProgress(it, "Baixando Wine $ver…") }

        onProgress(0, "Extraindo Wine…")
        val dest = wineDir(ctx)
        dest.deleteRecursively()
        // Kron4ek empacota como wine-9.0-amd64/bin/... → strip do 1º nível faz
        // os binários caírem direto em opt/wine/bin
        extractTarXz(tmp, dest, stripFirst = true)
        // garante que binários marcados como executáveis sobreviveram
        File(dest, "bin").listFiles()?.forEach { it.setExecutable(true, false) }
        File(dest, "lib").listFiles()?.forEach { it.setReadable(true, false) }
        tmp.delete()
        // Android não tem /tmp: move o socket do wineserver para a WINEPREFIX
        patchWineTmpSocket(ctx) { m -> onProgress(-1, m) }
        setMarker(File(RootFs.rootDir(ctx), MARKER_WINE), ver)
        Log.i(TAG, "Wine $ver instalado em ${dest.absolutePath}")
    }

    // ---------------------------------------------------------------- 2) DXVK
    fun ensureDxvk(ctx: Context, onProgress: (Int, String) -> Unit) {
        if (dxvkInstalledVersion(ctx) != null) return
        val prefs = ctx.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val url = prefs.getString(SettingsActivity.KEY_DXVK_URL, DXVK_URL_DEFAULT) ?: DXVK_URL_DEFAULT
        val ver = prefs.getString(SettingsActivity.KEY_DXVK_VERSION, DXVK_VERSION_DEFAULT) ?: DXVK_VERSION_DEFAULT

        onProgress(0, "Baixando DXVK $ver…")
        val tmp = File(ctx.cacheDir, "dxvk.tar.gz")
        download(url, tmp) { onProgress(it, "Baixando DXVK $ver…") }

        onProgress(0, "Extraindo DXVK…")
        val dest = dxvkDir(ctx)
        dest.deleteRecursively()
        extractTarGz(tmp, dest, stripFirst = true)
        // dxvk-2.4/x64/*.dll → opt/dxvk/x64
        val x64 = File(dest, "x64")
        if (!x64.isDirectory) {
            // alguns pacotes vêm soltos; procura d3d11.dll
            dest.walkTopDown().filter { it.name == "d3d11.dll" }.firstOrNull()?.parentFile?.let { it.renameTo(File(dest, "x64")) }
        }
        tmp.delete()
        setMarker(File(RootFs.rootDir(ctx), MARKER_DXVK), ver)
        Log.i(TAG, "DXVK $ver instalado em ${dest.absolutePath}")
    }

    // ---------------------------------------------------------------- 3) Prefixo
    /**
     * Detecta um loader Wine executável pelo Box64 (ELF x86_64).
     *
     * CRÍTICO: no build Kron4ek 9.x amd64, `bin/wineboot` é um SCRIPT POSIX
     * (`#!/bin/sh` — que não existe no Android) e `bin/wine` é um ELF i386
     * de 32 bits (Box64 só executa x86_64). Qualquer um dos dois faz o Box64
     * abortar com exit -1 (255) — o erro "wineboot não criou o prefixo
     * (código 255)". O único caminho válido é o `wine64` (ELF x86_64 puro)
     * executando o wineboot embutido: `box64 wine64 wineboot --init`.
     */
    /** Detecta ELF x86_64 (usado também pelo Box64Launcher.launch). */
    fun elfX8664(f: File): Boolean {
        if (!f.exists() || f.length() < 20) return false
        return try {
            val hdr = ByteArray(20)
            // readFully existe apenas em DataInput/DataInputStream —
            // FileInputStream puro não tem o método (erro "Unresolved reference
            // 'readFully' on receiver of type 'FileInputStream'" no CodeAssist).
            java.io.DataInputStream(f.inputStream()).use { it.readFully(hdr) }
            // magic \x7fELF (0..3) + classe 2 = ELF64 (4) + machine 0x3e = x86-64 (18..19 LE)
            hdr[0] == 0x7f.toByte() && hdr[1] == 'E'.code.toByte() &&
                hdr[2] == 'L'.code.toByte() && hdr[3] == 'F'.code.toByte() &&
                hdr[4] == 2.toByte() &&
                hdr[18].toInt() == 0x3e && hdr[19].toInt() == 0
        } catch (_: Exception) {
            false
        }
    }

    /** Escolhe wine64 > wine (só ELF x86_64 real; nunca scripts .sh). */
    private fun wineLoaderBin(ctx: Context): File {
        val bin = Box64Launcher.wineBinDir(ctx)
        val w64 = File(bin, "wine64")
        val w = File(bin, "wine")
        return when {
            elfX8664(w64) -> w64
            elfX8664(w) -> w
            else -> throw IOException(
                "Nenhum loader Wine x86_64 válido em ${bin.absolutePath} " +
                "(wineboot/wine do Kron4ek 9.x são script/i386) — reinstale o Wine no app"
            )
        }
    }

    /**
     * Executa `wineboot --init` (via Box64 + wine64) para criar o WINEPREFIX.
     * Roda em thread de chamador; pode levar vários minutos em aparelhos lentos.
     */
    fun ensurePrefix(ctx: Context, onLog: (String) -> Unit) {
        val root = RootFs.rootDir(ctx)
        if (prefixReady(ctx) && File(prefixDir(ctx), "system.reg").exists()) return
        val env = Box64Launcher.guestEnv(ctx).toMutableMap()
        val envArr = System.getenv().map { (k, v) -> "$k=$v" }.toTypedArray()
        val loader = wineLoaderBin(ctx)
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val box64 = File(nativeDir, "libbox64.so")
        if (!box64.exists()) throw IOException("libbox64.so ausente em $nativeDir")

        // Perfil "modo seguro" para o wineboot (correção > velocidade).
        // O init do prefixo é a fase mais sensível do Wine sob Box64: o perfil
        // rápido do launch() (STRONGMEM=1/BIGBLOCK=1) pode derrubar o wineboot
        // com exit 1 em alguns SoCs. Mesmo perfil do safeMode do usuário.
        env["WINEDEBUG"] = "+err"          // erros do Wine visíveis no log (não usar -all no setup)
        env.remove("BOX64_LD_PRELOAD")     // shims de rede/eaccess não são usados no init
        env["BOX64_DYNAREC_BIGBLOCK"] = "0"
        env["BOX64_DYNAREC_SAFEFLAGS"] = "2"
        env["BOX64_DYNAREC_STRONGMEM"] = "2"
        env["BOX64_DYNAREC_WEAKBARRIER"] = "1"
        env["BOX64_DYNAREC_FASTNAN"] = "0"
        env["BOX64_DYNAREC_FASTROUND"] = "0"
        env["BOX64_DYNAREC_CALLRET"] = "0"
        env["BOX64_DYNAREC_SEP"] = "0"

        // paridade com launch(): diretórios que o wine/wineserver usam no init
        File(root, "tmp").mkdirs()
        File(root, "tmp/.X11-unix").mkdirs()
        File(root, "dev/shm").mkdirs()
        // o lado SERVIDOR do wine faz chdir(WINEPREFIX) com force=1 e aborta se
        // faltar — o cliente criaria, mas não dependa disso
        File(root, "wineprefix").mkdirs()

        // PATCH CRÍTICO (erro "código 1"): Android não tem /tmp — sem isto o
        // wineserver morre com "mkdir /tmp/.wine-NNN: No such file or directory".
        // Cobre também instalações de Wine feitas por versões anteriores do app.
        patchWineTmpSocket(ctx, onLog)

        // evita diálogos do mono/gecko no primeiro boot
        val overrides = "mscoree,mshtml="

        fun run(cmd: List<String>): Int {
            val script = File(root, "tmp/setup_wine.sh")
            script.parentFile?.mkdirs()
            script.writeText(
                "#!/bin/sh\n" +
                env.entries.joinToString("\n") { (k, v) -> "export $k=\"$v\"" } +
                "\nexport WINEDLLOVERRIDES=\"$overrides\"\n" +
                "cd \"${root.absolutePath}/tmp\"\nexec " + cmd.joinToString(" ") + "\n"
            )
            script.setExecutable(true, false)
            onLog("Executando: ${cmd.last()}")
            val p = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", script.absolutePath), envArr, root)
            val logFile = File(root, "tmp/setup_wine.log")
            val outT = Thread {
                p.inputStream.bufferedReader().lineSequence().forEach {
                    onLog(it); logFile.appendText("$it\n")
                }
            }
            val errT = Thread {
                p.errorStream.bufferedReader().lineSequence().forEach {
                    onLog(it); logFile.appendText("$it\n")
                }
            }
            outT.start(); errT.start()
            val code = p.waitFor()
            outT.join(); errT.join()
            return code
        }

        // Kron4ek: `wineboot` é script #!/bin/sh e `wine` é i386 — executar o
        // loader x86_64 (wine64) direto no Box64 evita o exit 255.
        val code = run(listOf("\"$box64\"", "\"$loader\"", "wineboot", "--init"))
        onLog("wineboot saiu com código $code")
        val sysReg = File(prefixDir(ctx), "system.reg")
        if (!sysReg.exists()) {
            // O wineboot pode sair ANTES do flush final do registro: os serviços
            // (services.exe etc.) seguem em background e quem grava system.reg é
            // o wineserver quando eles terminam. Sem esta espera, um init
            // PERFEITAMENTE BOM seria reportado como falha (código 0 sem prefixo).
            onLog("Aguardando flush do registro do prefixo…")
            var waited = 0
            while (!sysReg.exists() && waited < 20000) {
                Thread.sleep(2000)
                waited += 2000
            }
        }
        if (sysReg.exists()) {
            if (code != 0) onLog("Aviso: wineboot saiu com código $code, mas o prefixo foi criado — seguindo")
            setMarker(File(root, MARKER_PREFIX), "1")
        } else {
            // anexa o fim do log à mensagem — sem isso o diagnóstico fica cego
            val log = File(root, "tmp/setup_wine.log")
            val tail = try {
                log.readText().lines().filter { it.isNotBlank() }.takeLast(14).joinToString("\n")
            } catch (_: Exception) { "" }
            throw IOException(
                "wineboot não criou o prefixo (código $code)" +
                if (tail.isNotBlank()) " — últimas linhas do log:\n$tail"
                else " — log vazio em ${log.absolutePath}"
            )
        }
        installDxvkIntoPrefix(ctx, onLog)
    }

    /** Copia as DLLs do DXVK para o system32 do prefixo. */
    private fun installDxvkIntoPrefix(ctx: Context, onLog: (String) -> Unit) {
        val x64 = File(dxvkDir(ctx), "x64")
        if (!x64.isDirectory) {
            onLog("DXVK ausente — pulando cópia de DLLs")
            return
        }
        val sys32 = File(prefixDir(ctx), "drive_c/windows/system32")
        sys32.mkdirs()
        var n = 0
        for (dll in DLLS) {
            val src = File(x64, dll)
            if (src.exists()) {
                src.copyTo(File(sys32, dll), overwrite = true)
                n++
            }
        }
        onLog("DXVK: $n DLLs copiadas para system32")
    }

    // ---------------------------------------------------------------- 4) Studio
    /**
     * Instalação oficial: consulta o canal Studio da Roblox e baixa o zip
     * (mesma origem usada pelo launcher oficial do Windows).
     */
    fun installOfficialStudio(ctx: Context, onProgress: (Int, String) -> Unit): String {
        val prefs = ctx.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        // Migração: se a pref guardou o canal/zip antigos (aposentados pela
        // Roblox — API responde 400 "Invalid binaryType."), força os novos.
        val api = prefs.getString(SettingsActivity.KEY_STUDIO_API, null)
            ?.takeIf { it != STUDIO_VERSION_API_OLD } ?: STUDIO_VERSION_API_DEFAULT
        val template = prefs.getString(SettingsActivity.KEY_STUDIO_ZIP, null)
            ?.takeIf { it != STUDIO_ZIP_TEMPLATE_OLD } ?: STUDIO_ZIP_TEMPLATE_DEFAULT

        onProgress(0, "Consultando versão do Studio…")
        val req = Request.Builder().url(api).build()
        val version = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                // anexa o corpo do erro da Roblox (ex.: "Invalid binaryType.")
                // à mensagem — sem isso o diagnóstico fica cego no diálogo
                val err = try { resp.body?.string()?.take(160) } catch (_: Exception) { null }
                throw IOException(
                    "HTTP ${resp.code} ao consultar versão" +
                    if (err.isNullOrBlank()) "" else " — $err"
                )
            }
            val body = resp.body?.string() ?: throw IOException("corpo vazio")
            val json = org.json.JSONObject(body)
            // clientVersionUpload (ex.: "version-55808de4b1914919") é o nome real
            // do artefato no CDN; "version" numérico (0.739.0…) só como fallback.
            json.optString("clientVersionUpload").takeIf { it.isNotBlank() }
                ?: json.optString("version").takeIf { it.isNotBlank() }
                ?: throw IOException("resposta sem 'clientVersionUpload': ${body.take(200)}")
        }
        if (version == studioInstalledVersion(ctx)) {
            onProgress(100, "Studio $version já instalado")
            return version
        }

        val zipUrl = String.format(template, version)
        onProgress(0, "Baixando Studio $version…")
        val tmp = File(ctx.cacheDir, "studio.zip")
        download(zipUrl, tmp) { onProgress(it, "Baixando Studio $version…") }

        onProgress(0, "Extraindo Studio…")
        // evita "version-version-<hash>" quando o canal devolve clientVersionUpload
        val dest = File(studioDir(ctx), "version-" + version.removePrefix("version-"))
        dest.deleteRecursively()
        extractZip(tmp, dest) { onProgress(-1, "Extraindo Studio…") }
        tmp.delete()
        dest.listFiles()?.forEach { if (it.isFile) it.setWritable(false) }
        setMarker(File(studioDir(ctx), MARKER_STUDIO), version)
        try { StudioPrefs.applyTo(ctx, RootFs.rootDir(ctx), Box64Launcher.findStudioExe(ctx) ?: return version) } catch (_: Exception) {}
        onProgress(100, "Studio $version instalado")
        return version
    }

    /** Import manual: usuário coloca um zip do Studio (ou pasta) em Android/data/.../files/import. */
    fun importStudio(ctx: Context, archive: File, onProgress: (Int, String) -> Unit): String {
        val version = "import-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(java.util.Date())
        onProgress(0, "Importando ${archive.name}…")
        val dest = File(studioDir(ctx), "version-$version")
        extractZip(archive, dest) { onProgress(-1, "Importando ${archive.name}…") }
        val exe = dest.walkTopDown()
            .filter { it.isFile && it.name.equals("RobloxStudioBeta.exe", ignoreCase = true) }
            .firstOrNull() ?: throw IOException("RobloxStudioBeta.exe não encontrado no arquivo importado")
        setMarker(File(studioDir(ctx), MARKER_STUDIO), version)
        try { StudioPrefs.applyTo(ctx, RootFs.rootDir(ctx), exe) } catch (_: Exception) {}
        onProgress(100, "Importado: $version")
        return version
    }

    fun deleteStudio(ctx: Context) {
        studioDir(ctx).deleteRecursively()
    }

    fun deleteWine(ctx: Context) {
        wineDir(ctx).deleteRecursively()
        dxvkDir(ctx).deleteRecursively()
        prefixDir(ctx).deleteRecursively()
        val root = RootFs.rootDir(ctx)
        File(root, MARKER_WINE).delete()
        File(root, MARKER_DXVK).delete()
        File(root, MARKER_PREFIX).delete()
    }
}
