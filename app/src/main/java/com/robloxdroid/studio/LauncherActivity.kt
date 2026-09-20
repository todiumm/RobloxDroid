package com.robloxdroid.studio

import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.concurrent.thread

/**
 * RobloxDroid — tela inicial.
 *
 * Fluxo (sem login web, diferente do PolyDroid2 — o login do Roblox acontece
 * dentro da própria janela do Studio):
 *
 *   1. Extrai o rootfs (assets, na primeira execução)
 *   2. "Instalar componentes" → Wine + DXVK + WINEPREFIX + Roblox Studio (CDN oficial)
 *   3. "Abrir Roblox Studio"  → GameActivity (X11 + Box64 + Wine)
 */
class LauncherActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RobloxDroid"
    }

    private var extractionInProgress = false
    private var installInProgress = false
    private var updateDialogShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        val root = findViewById<android.view.View>(R.id.launcher_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                bars.left + 32.dpToPx(),
                bars.top + 32.dpToPx(),
                bars.right + 32.dpToPx(),
                bars.bottom + 32.dpToPx()
            )
            insets
        }

        val status = findViewById<TextView>(R.id.launcher_status)
        val btnPlay = findViewById<android.view.View>(R.id.btn_play)
        val btnInstall = findViewById<android.view.View>(R.id.btn_install)

        btnPlay.setOnClickListener { startStudio() }
        btnInstall.setOnClickListener { installComponents() }

        findViewById<android.view.View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        refreshStatus(status)

        if (RootFs.needsExtraction(this)) startRootfsExtraction(status)
    }

    private fun startRootfsExtraction(status: TextView) {
        if (extractionInProgress) return
        extractionInProgress = true
        refreshStatus(status)
        val detailBar = ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val detailText = TextView(this).apply {
            text = ""
            setPadding(0, 16, 0, 0)
        }
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 48, 64, 48)
            addView(detailBar)
            addView(detailText)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Extraindo arquivos...")
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()

        // A extração descomprime XZ em Java puro (sem aceleração nativa) — em
        // aparelhos mais fracos isso pode levar vários minutos. Sem wake lock,
        // se a tela apagar/bloquear o sistema entra em Doze e o processamento
        // em background quase para, o que parece "travado para sempre" mesmo
        // sem ter travado de verdade. Mantém a CPU acordada e a tela ligada
        // enquanto a extração roda.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "RobloxDroid:rootfsExtraction"
        )
        wakeLock.acquire(30 * 60 * 1000L /* 30 min de teto de segurança */)

        thread {
            var failure: Exception? = null
            try {
                RootFs.extractAll(applicationContext) { detailPct, _, _, stageLabel, bytesDone, totalBytes ->
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            dialog.setTitle(stageLabel)
                            detailBar.isIndeterminate = totalBytes <= 0
                            detailBar.progress = detailPct
                            detailText.text = if (totalBytes > 0)
                                "${bytesDone / 1_048_576} MB / ${totalBytes / 1_048_576} MB"
                            else "${bytesDone / 1_048_576} MB extraídos…"
                        }
                    }
                }
            } catch (e: Exception) {
                failure = e
                Log.e(TAG, "Extração do rootfs falhou", e)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                val error = failure
                runOnUiThread {
                    extractionInProgress = false
                    if (!isFinishing && !isDestroyed) {
                        dialog.dismiss()
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        refreshStatus(status)
                        if (error == null) kickoffUpdateCheck()
                        else MaterialAlertDialogBuilder(this)
                            .setTitle("Falha ao preparar o ambiente")
                            .setMessage(error.message ?: error.toString())
                            .setPositiveButton("Tentar novamente") { _, _ -> startRootfsExtraction(status) }
                            .setNegativeButton("Fechar", null)
                            .show()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!extractionInProgress) {
            refreshStatus(findViewById(R.id.launcher_status))
            kickoffUpdateCheck()
        }
    }

    private fun refreshStatus(status: TextView) {
        val ready = !RootFs.needsExtraction(this)
        val busy = extractionInProgress || installInProgress
        findViewById<android.view.View>(R.id.btn_install).isEnabled = ready && !busy
        findViewById<android.view.View>(R.id.btn_settings).isEnabled = ready && !busy
        val wine = StudioDownloader.wineInstalledVersion(this) != null
        val dxvk = StudioDownloader.dxvkInstalledVersion(this) != null
        val prefix = StudioDownloader.prefixReady(this)
        val studio = Box64Launcher.findStudioExe(this) != null
        status.text = when {
            extractionInProgress -> "Preparando ambiente…"
            !ready -> "Ambiente incompleto — reabra o app para tentar novamente"
            installInProgress -> "Instalando componentes…"
            wine && !dxvk -> "Wine pronto — falta instalar o DXVK"
            studio && wine && prefix -> "Pronto: Roblox Studio ${StudioDownloader.studioInstalledVersion(this) ?: "(importado)"}"
            wine && prefix -> "Wine pronto — falta instalar o Roblox Studio"
            wine -> "Wine pronto — falta inicializar o prefixo (Instalar componentes)"
            else -> "Componentes não instalados — toque em \"Instalar componentes\""
        }
        btnPlayState(ready && !busy && studio && wine && prefix && dxvk)
    }

    private fun btnPlayState(enabled: Boolean) {
        findViewById<android.view.View>(R.id.btn_play).isEnabled = enabled
    }

    private fun startStudio() {
        try {
            startActivity(Intent(this, GameActivity::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "não iniciou GameActivity", e)
        }
    }

    /**
     * Pipeline de instalação na ordem correta:
     *   Wine → DXVK → wineboot (prefixo) → Roblox Studio (CDN oficial)
     */
    private fun installComponents() {
        if (installInProgress || extractionInProgress) return
        if (RootFs.needsExtraction(this)) return
        installInProgress = true
        refreshStatus(findViewById(R.id.launcher_status))
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
        }
        val label = TextView(this).apply { text = "Preparando…" }
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 48, 64, 48)
            addView(label)
            addView(bar)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Instalando componentes")
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()

        fun progress(pct: Int, text: String) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                label.text = text
                if (pct >= 0) {
                    bar.isIndeterminate = false
                    bar.progress = pct
                } else {
                    bar.isIndeterminate = true
                }
            }
        }

        thread {
            try {
                StudioDownloader.ensureWine(this) { p, l -> progress(p, l) }
                StudioDownloader.ensureDxvk(this) { p, l -> progress(p, l) }
                progress(-1, "Inicializando WINEPREFIX (pode levar minutos)…")
                StudioDownloader.ensurePrefix(this) { line ->
                    Log.i(TAG, "[wine] $line")
                }
                StudioDownloader.installOfficialStudio(this) { p, l -> progress(p, l) }
                runOnUiThread {
                    installInProgress = false
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    dialog.dismiss()
                    refreshStatus(findViewById(R.id.launcher_status))
                }
            } catch (e: Exception) {
                Log.e(TAG, "instalação falhou", e)
                runOnUiThread {
                    installInProgress = false
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    dialog.dismiss()
                    refreshStatus(findViewById(R.id.launcher_status))
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Falha na instalação")
                        .setMessage(e.message ?: e.toString())
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun kickoffUpdateCheck() {
        if (updateDialogShowing) return
        val current = currentVersionName()
        UpdateCheck.checkAsync(current) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result != null && result.outdated) {
                    showUpdateDialog(current, result.latestTag, result.htmlUrl)
                }
            }
        }
    }

    private fun showUpdateDialog(current: String, latestTag: String, url: String) {
        updateDialogShowing = true
        MaterialAlertDialogBuilder(this)
            .setTitle("Atualização disponível")
            .setMessage("Uma nova versão ($latestTag) está disponível.\nAtual: $current.")
            .setPositiveButton("Atualizar") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                } catch (e: Exception) {
                    Log.e(TAG, "sem navegador para abrir a atualização", e)
                }
            }
            .setNegativeButton("Depois", null)
            .setOnDismissListener { updateDialogShowing = false }
            .show()
    }

    private fun currentVersionName(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
    } catch (_: Exception) {
        "0"
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()
}
