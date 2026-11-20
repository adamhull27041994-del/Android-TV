package uk.co.cinematv.player

import android.app.Activity
import android.content.Context
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Keeps a foreground playback activity bright while it is actually visible.
 *
 * FLAG_KEEP_SCREEN_ON is the supported Android solution. Some OEM / external
 * display configurations do not honour that request consistently, so a
 * deprecated screen-bright wake lock is held only while the Activity is
 * resumed. It is deliberately NOT kept in the background and does not override
 * a user's power-button lock or device-administrator policy.
 */
internal class PlaybackScreenAwake(private val activity: Activity) {
    private val powerManager =
        activity.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var brightLock: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    fun activate() {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity.window.decorView.keepScreenOn = true
        activity.findViewById<View>(android.R.id.content)?.keepScreenOn = true

        try {
            if (brightLock == null) {
                brightLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                    activity.packageName + ":foreground_video"
                ).also { it.setReferenceCounted(false) }
            }
            if (brightLock?.isHeld != true) {
                // Timeout is a fail-safe if Android kills the Activity unexpectedly.
                brightLock?.acquire(4L * 60L * 60L * 1000L)
            }
            Log.d("CinemaTV_Screen", "Foreground playback: screen-awake requested")
        } catch (error: SecurityException) {
            Log.w("CinemaTV_Screen", "Screen wakelock unavailable; window flag is still set", error)
        } catch (error: RuntimeException) {
            Log.w("CinemaTV_Screen", "Wake lock could not be acquired", error)
        }
    }

    fun deactivate() {
        try {
            if (brightLock?.isHeld == true) brightLock?.release()
        } catch (error: RuntimeException) {
            Log.w("CinemaTV_Screen", "Wake lock release failed", error)
        }
        activity.findViewById<View>(android.R.id.content)?.keepScreenOn = false
        activity.window.decorView.keepScreenOn = false
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d("CinemaTV_Screen", "Foreground playback: screen-awake released")
    }
}
