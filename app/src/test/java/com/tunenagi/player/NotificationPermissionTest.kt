package com.tunenagi.player

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * TUN-0206's permission rule: ask once, on Android 13+, and never make playback depend on the answer.
 *
 * `sdkInt` is a parameter so the pre-13 branch is exercised on this JVM instead of pretending one
 * test run covers every OS release. The permission query is the real `ContextCompat` one.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationPermissionTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun android12AndOlderAreNeverAsked() {
        assertFalse(
            shouldAskForNotificationPermission(context, sdkInt = 32, alreadyAsked = false)
        )
    }

    @Test
    fun android13IsAskedWhenThePermissionIsNotGranted() {
        assertTrue(
            "a fresh Android 13 install has not granted POST_NOTIFICATIONS",
            shouldAskForNotificationPermission(context, sdkInt = 33, alreadyAsked = false)
        )
    }

    @Test
    fun aGrantedPermissionOrAnEarlierRequestIsNotAskedAgain() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(shouldAskForNotificationPermission(context, sdkInt = 33, alreadyAsked = false))
        assertFalse(shouldAskForNotificationPermission(context, sdkInt = 33, alreadyAsked = true))
    }

    @Test
    fun theAskedFlagPersistsSoADenialIsNotRepeatedOnEveryLaunch() {
        assertFalse(notificationPermissionWasAsked(context))

        markNotificationPermissionAsked(context)

        assertTrue(notificationPermissionWasAsked(context))
    }
}
