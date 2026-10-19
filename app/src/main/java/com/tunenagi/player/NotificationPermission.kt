package com.tunenagi.player

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * When to ask for the Android 13+ permission to show the media notification — owned.
 *
 * `SPEC/AUDIO_ENGINE.md` is explicit that a denied notification permission must not block playback:
 * the foreground playback service is what keeps audio alive, and the notification is how the system
 * and the user reach it. So this is a *request*, asked once, and nothing in the playback path checks
 * the answer. A user who says no keeps a working player, minus the drawer/lockscreen card they can
 * enable later in system settings.
 *
 * The `sdkInt` and `alreadyAsked` parameters exist so the rule can be asserted on the JVM without an
 * Android 12 or 13 runtime: the permission query itself is still the real one.
 */
// `POST_NOTIFICATIONS` was added in API 33, so lint reports the constant as inlined on the API 26
// minimum. Referencing it is still safe: the SDK check below means the constant is only *used* on
// the releases that define the permission. A string literal would silence the warning by duplicating
// the platform's value, which is the worse trade.
@SuppressLint("InlinedApi")
internal fun shouldAskForNotificationPermission(
    context: Context,
    sdkInt: Int = Build.VERSION.SDK_INT,
    alreadyAsked: Boolean = notificationPermissionWasAsked(context)
): Boolean {
    if (sdkInt < Build.VERSION_CODES.TIRAMISU) return false
    if (alreadyAsked) return false
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) != PackageManager.PERMISSION_GRANTED
}

/** Whether this install has already shown the request once, denied or granted. */
internal fun notificationPermissionWasAsked(context: Context): Boolean =
    context.getSharedPreferences(PERMISSION_PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, false)

/** Records that the request was shown, so a denial is not re-asked on every launch. */
internal fun markNotificationPermissionAsked(context: Context) {
    context.getSharedPreferences(PERMISSION_PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, true)
        .apply()
}

private const val PERMISSION_PREFERENCES = "tunenagi.permissions"
private const val KEY_NOTIFICATION_PERMISSION_ASKED = "post_notifications_asked"
