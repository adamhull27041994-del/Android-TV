package uk.co.cinematv.livetv

import android.content.Context
import java.security.MessageDigest

/**
 * Records a real video frame rendered by Cinema TV's Media3 video player.
 *
 * A successful HTTP request or HLS manifest does not prove playback; a
 * rendered frame is stronger on-device evidence, though it still does not
 * verify that a public feed is correctly labelled or authorised.
 */
object LivePlaybackEvidence {
    private const val PREFS = "cinema_live_playback"

    private fun key(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
        return "video_frame_" + digest.joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
    }

    fun recordVideoFrame(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(key(url), System.currentTimeMillis()).apply()
    }

    fun lastVideoFrame(context: Context, url: String): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(key(url), 0L)
}
