package com.tunenagi.core.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import com.tunenagi.core.R

/**
 * The media notification's identity, in one place — owned.
 *
 * `SPEC/AUDIO_ENGINE.md` requires the notification and lockscreen controls that make playback work
 * while the app is not in front, and `SPEC/EDGE_CASES.md` names the notification permission and
 * "notification action while app cold" as cases that must not break. Media3 already owns the hard
 * part: while this service is alive it posts a media notification with transport actions, keeps the
 * service foreground, and removes the notification when the session is released. What is left for
 * Tunenagi is the identity the user and the system see, and that is what this object fixes:
 *
 * - **A stable channel id.** A rename would orphan the user's notification settings and silently
 *   make the notification quiet again on a fresh install.
 * - **A stable notification id**, so a later update replaces the notification instead of stacking a
 *   second set of controls next to it.
 * - **Media3's own low-importance channel.** The default provider creates the channel at
 *   `IMPORTANCE_LOW` with no sound and no badge, which is what a playback control should be: visible
 *   on the lockscreen, never a noise of its own. Creating a custom channel with a higher importance
 *   would make every track change buzz.
 *
 * The channel name is a resource because it is user-facing text in Android's notification settings.
 */
object TunenagiNotifications {

    /** The notification channel the media notification uses. Never change this string. */
    const val CHANNEL_ID = "tunenagi.playback"

    /**
     * The one notification id the session posts.
     *
     * Positive and fixed: a media notification is a single ongoing control surface, not an inbox,
     * and `NotificationManager.notify` with the same id replaces the previous one.
     */
    const val NOTIFICATION_ID = 1001

    /**
     * Builds the provider the service installs before it builds its session.
     *
     * Media3 reads the provider once, when the session is added, so this has to be constructed from
     * the service (its `Context`) rather than from a static initializer. The provider itself is
     * stateless enough to be built per service instance; [DefaultMediaNotificationProvider] is what
     * Media3 uses when none is set, so this customises identity rather than replacing behaviour.
     */
    // Media3 marks the notification provider surface unstable in this release. The opt-in is on the
    // one function that names it, rather than on the object, so a later addition cannot inherit it.
    @OptIn(UnstableApi::class)
    fun provider(context: Context): MediaNotification.Provider =
        DefaultMediaNotificationProvider.Builder(context)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.notification_channel_playback)
            .setNotificationId(NOTIFICATION_ID)
            .build()
}
