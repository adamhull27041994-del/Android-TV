package uk.co.cinematv.provider

import android.net.Uri

/**
 * VOE is a file host, not a TMDb catalogue. Use its own player for any
 * legitimate, explicitly supplied VOE link instead of extracting CDN URLs.
 */
object VoeLinks {
    private val fileCode = Regex("^[a-zA-Z0-9]{6,32}$")
    private val hosts = setOf("voe.sx", "www.voe.sx")

    fun embedUrl(input: String): String? {
        val uri = runCatching { Uri.parse(input.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host?.lowercase() !in hosts) return null
        if (uri.authority?.contains('@') == true) return null
        val path = uri.pathSegments
        val code = when {
            path.size == 1 -> path[0]
            path.size == 2 && path[0] == "e" -> path[1]
            else -> return null
        }
        if (!fileCode.matches(code)) return null
        return "https://voe.sx/e/$code"
    }
}
