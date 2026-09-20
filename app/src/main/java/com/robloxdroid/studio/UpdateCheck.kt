package com.robloxdroid.studio

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Verificação de atualização do app launcher.
 * Desabilitada por padrão: preencha REPO_OWNER/REPO_NAME se você publicar
 * um fork próprio do RobloxDroid no GitHub.
 */
object UpdateCheck {
    private const val TAG = "RobloxDroid"

    const val REPO_OWNER = ""      // ex.: "seu-usuario"
    const val REPO_NAME = ""       // ex.: "RobloxDroid"
    // "const" não aceita getter customizado — só permite valor literal fixo.
    val RELEASES_URL: String
        get() = if (REPO_OWNER.isNotBlank()) "https://github.com/$REPO_OWNER/$REPO_NAME/releases" else ""
    private val LATEST_API: String
        get() = if (REPO_OWNER.isNotBlank()) "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest" else ""

    data class Result(val latestTag: String, val htmlUrl: String, val outdated: Boolean)

    fun checkAsync(currentVersion: String, onResult: (Result?) -> Unit) {
        Thread {
            onResult(checkSync(currentVersion))
        }.start()
    }

    fun checkSync(currentVersion: String): Result? {
        if (LATEST_API.isBlank()) return null // verificação desabilitada
        try {
            val conn = URL(LATEST_API).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "RobloxDroid")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name") ?: return null
            val html = json.optString("html_url") ?: RELEASES_URL
            val latest = tag.removePrefix("v")
            return Result(tag, html, isNewer(currentVersion, latest))
        } catch (e: Exception) {
            Log.w(TAG, "update check failed: ${e.message}")
            return null
        }
    }

    private fun isNewer(current: String, latest: String): Boolean {
        val parse = { s: String ->
            s.replace(Regex("[^0-9.]"), "").split('.').map { it.toLongOrNull() ?: 0L }
        }
        val a = parse(current); val b = parse(latest)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0L }; val y = b.getOrElse(i) { 0L }
            if (y != x) return y > x
        }
        return false
    }
}
