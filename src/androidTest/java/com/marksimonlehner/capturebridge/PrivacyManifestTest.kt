package com.marksimonlehner.capturebridge

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyManifestTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun applicationBackupIsDisabled() {
        val backupEnabled = context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0

        assertFalse("Capture videos and metadata must not be eligible for Android backup", backupEnabled)
    }

    @Test
    @Suppress("DEPRECATION")
    fun manifestRequestsOnlyRequiredPlatformPermissions() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requestedPermissions = packageInfo.requestedPermissions.orEmpty().toSet()

        assertTrue(Manifest.permission.CAMERA in requestedPermissions)
        assertTrue(Manifest.permission.INTERNET in requestedPermissions)
        assertFalse(Manifest.permission.ACCESS_NETWORK_STATE in requestedPermissions)
        assertEquals(
            emptySet<String>(),
            requestedPermissions.filterTo(mutableSetOf()) { permission ->
                permission.startsWith("android.permission.") &&
                    permission !in setOf(Manifest.permission.CAMERA, Manifest.permission.INTERNET)
            },
        )
    }
}
