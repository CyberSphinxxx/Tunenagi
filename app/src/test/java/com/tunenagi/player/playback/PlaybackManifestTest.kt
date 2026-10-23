package com.tunenagi.player.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.tunenagi.core.playback.TunenagiPlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The parts of TUN-0201 that are declarations rather than code: the service component and the
 * permissions the platform reads.
 *
 * They are asserted against the **merged** manifest the build actually produces, not against the
 * XML on disk: a permission declared in the wrong module, or a service whose foreground-service
 * type was dropped during merging, is exactly the kind of change that looks fine in a diff and
 * fails on a device at 3am.
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackManifestTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val serviceComponent = ComponentName(context, TunenagiPlaybackService::class.java)

    @Test
    fun thePlaybackServiceIsDeclaredWithTheMediaPlaybackForegroundType() {
        val info = context.packageManager
            .getServiceInfo(serviceComponent, PackageManager.GET_META_DATA)

        assertNotNull("the playback service must be declared", info)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        assertFalse(
            "phase 02 serves only this app, so the service must not be exported",
            info.exported
        )
    }

    @Test
    fun theServiceAdvertisesBothMediaServiceInterfaces() {
        val declared = context.packageManager
            .queryIntentServices(Intent(MEDIA_LIBRARY_SERVICE_ACTION), PackageManager.MATCH_ALL)
            .map { it.serviceInfo.name }

        assertTrue(
            "the merged manifest must declare $MEDIA_LIBRARY_SERVICE_ACTION (found: $declared)",
            declared.any { it.endsWith("TunenagiPlaybackService") }
        )
    }

    @Test
    fun theServiceAdvertisesTheSessionServiceActionToo() {
        val declared = context.packageManager
            .queryIntentServices(Intent(MEDIA_SESSION_SERVICE_ACTION), PackageManager.MATCH_ALL)
            .map { it.serviceInfo.name }

        assertTrue(
            "the merged manifest must declare $MEDIA_SESSION_SERVICE_ACTION (found: $declared)",
            declared.any { it.endsWith("TunenagiPlaybackService") }
        )
    }

    /**
     * The receiver is what makes a headset/Bluetooth media button work while the app is cold. It is
     * exported because the sender is the platform or another device, and it must be exported exactly
     * for that one action - a receiver that silently stopped being exported is a control that only
     * works while the app is already open.
     */
    @Test
    fun mediaButtonsRouteToThePlaybackServiceThroughAnExportedReceiver() {
        val info = context.packageManager.getReceiverInfo(
            ComponentName(context, MEDIA_BUTTON_RECEIVER),
            PackageManager.GET_META_DATA
        )

        assertNotNull("the media button receiver must be declared", info)
        assertTrue("the platform has to be able to reach it", info.exported)
    }

    @Test
    fun thePermissionsPlaybackNeedsAreRequested() {
        listOf(
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.WAKE_LOCK"
        ).forEach { permission ->
            assertEquals(
                "$permission must be granted to the app",
                PackageManager.PERMISSION_GRANTED,
                context.packageManager.checkPermission(permission, context.packageName)
            )
        }
    }

    @Test
    fun permissionsTheAppHasNoBusinessHoldingAreNotInTheManifest() {
        // A recording permission would be a privacy defect, not a feature: Tunenagi plays audio.
        listOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.READ_CONTACTS",
            "android.permission.CAMERA"
        ).forEach { permission ->
            assertEquals(
                "$permission must never be requested",
                PackageManager.PERMISSION_DENIED,
                context.packageManager.checkPermission(permission, context.packageName)
            )
        }
    }

    private companion object {
        const val MEDIA_LIBRARY_SERVICE_ACTION = "androidx.media3.session.MediaLibraryService"
        const val MEDIA_SESSION_SERVICE_ACTION = "androidx.media3.session.MediaSessionService"
        const val MEDIA_BUTTON_RECEIVER = "androidx.media3.session.MediaButtonReceiver"
    }
}
