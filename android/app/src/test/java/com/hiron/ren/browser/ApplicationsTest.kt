package com.hiron.ren.browser

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The ren account's Applications: the apps ren lists, and the APK address a page's Install links to. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ApplicationsTest {
    @Test
    fun theListKeepsWebAndAndroidAppsThatHaveAnIdAndAName() {
        val list = Applications.parse(
            """{"applications":[
                {"id":"app-1","name":"Tide Times","kind":"web","location":"https://tide.example.app/","icon":"https://tide.example.app/icon.png"},
                {"id":"app-2","name":"Streaks","kind":"android","location":"/config/workspace/streaks/app-debug.apk"},
                {"id":"app-3","name":"Elsewhere","kind":"ios","location":"x"},
                {"id":"","name":"Nameless","kind":"web","location":"https://example.com/"}
            ]}""",
        )
        assertEquals(listOf("app-1" to false, "app-2" to true), list.map { it.id to it.android })
    }

    @Test
    fun onlyAnAppsApkOnRenNamesAnAppToInstall() {
        val apps = "${Applications.API}/context/applications"
        assertEquals("app-1a2b3c4d5e", Applications.apkId(Uri.parse("$apps/app-1a2b3c4d5e/apk?user_id=u1")))
        assertNull(Applications.apkId(Uri.parse("$apps/app-1a2b3c4d5e?user_id=u1")))
        assertNull(Applications.apkId(Uri.parse("$apps?user_id=u1")))
        assertNull(Applications.apkId(Uri.parse("https://example.com/context/applications/app-1a2b3c4d5e/apk")))
    }
}
