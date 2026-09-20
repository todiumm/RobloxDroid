package com.robloxdroid.studio

import android.content.Context
import android.util.Log
import java.io.File

/**
 * RobloxDroid — ajustes de runtime do Roblox Studio.
 *
 * O Studio lê `ClientAppSettings.json` na pasta da versão (mesmo mecanismo de
 * FFlags usado pela comunidade no Windows). Aqui aplicamos:
 *
 *  - FFlagDebugGraphicsPreferD3D11 = true  → força D3D11 (caminho do DXVK;
 *    sem isso o Studio tenta D3D10/OpenGL e trava no Wine)
 *  - FIntDebugForceMSAASamples = 1         → reduz custo de MSAA na GPU mobile
 *  - DFIntTaskSchedulerTargetFps           → teto de fps configurável
 *
 * Também desliga o "hardware graphics acceleration" alternativo via registro
 * não é necessário: o override WINEDLLOVERRIDES no launcher já força DXVK.
 */
object StudioPrefs {
    private const val TAG = "RobloxDroid"
    private const val FILE_NAME = "ClientAppSettings.json"

    fun applyTo(ctx: Context, rootfs: File, studioExe: File) {
        val dir = studioExe.parentFile ?: return
        val file = File(dir, FILE_NAME)
        val prefs = ctx.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)

        val flags = linkedMapOf<String, Any>()
        // Ler o json existente (se houver) e mesclar
        if (file.exists()) {
            try {
                val obj = org.json.JSONObject(file.readText())
                for (k in obj.keys()) flags[k] = obj.get(k)
            } catch (e: Exception) {
                Log.w(TAG, "ClientAppSettings.json existente ilegível: ${e.message}")
            }
        }

        flags["FFlagDebugGraphicsPreferD3D11"] = true
        flags["FFlagDebugGraphicsDisableDirect3D11"] = false
        flags["FFlagDebugGraphicsDisableDirect3D10"] = true
        flags["FFlagDebugGraphicsPreferVulkan"] = false
        flags["FIntDebugForceMSAASamples"] = 1

        if (prefs.contains(SettingsActivity.KEY_STUDIO_TARGET_FPS)) {
            val fps = prefs.getInt(SettingsActivity.KEY_STUDIO_TARGET_FPS, 60)
            if (fps > 0) flags["DFIntTaskSchedulerTargetFps"] = fps
        }
        if (prefs.contains(SettingsActivity.KEY_STUDIO_QUALITY)) {
            // 1..21 (mesma escala do painel de qualidade do Studio)
            flags["FIntRenderShadowIntensity"] = prefs.getInt(SettingsActivity.KEY_STUDIO_QUALITY, 10)
            flags["DFFlagDebugRenderForceTechnologyVoxel"] = false
        }

        file.writeText(org.json.JSONObject(flags.toMap()).toString(2))
        Log.i(TAG, "ClientAppSettings.json gravado com ${flags.size} flags em ${dir.absolutePath}")
    }
}
