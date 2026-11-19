package com.hiron.ren.browser

import android.Manifest
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mozilla.components.concept.engine.permission.SitePermissions
import mozilla.components.concept.engine.permission.SitePermissionsStorage
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class SitePermissionsManagerTest {
    private lateinit var storage: InMemorySitePermissionsStorage
    private lateinit var manager: SitePermissionsManager

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        storage = InMemorySitePermissionsStorage()
        manager = SitePermissionsManager(app, storage)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun insecureHttpPublicOriginsAreRejectedAndLoopbackNormalizesCleanly() {
        assertNull(SitePermissionsManager.normalizeOrigin("http://example.com"))
        assertNull(SitePermissionsManager.normalizeOrigin("http://example.com:8080/room"))
        assertNull(SitePermissionsManager.normalizeOrigin("file:///sdcard/index.html"))
        assertNull(SitePermissionsManager.normalizeOrigin("about:blank"))

        assertEquals("https://localhost:3000", SitePermissionsManager.normalizeOrigin("https://localhost:3000"))
        assertEquals("http://localhost:3000", SitePermissionsManager.normalizeOrigin("http://localhost:3000"))
        assertEquals("http://localhost", SitePermissionsManager.normalizeOrigin("http://localhost:80/app"))
        assertEquals("http://127.0.0.1:5173", SitePermissionsManager.normalizeOrigin("http://127.0.0.1:5173/test"))
        assertEquals("http://[::1]:8765", SitePermissionsManager.normalizeOrigin("http://[::1]:8765/api"))
        assertEquals("https://example.com", SitePermissionsManager.normalizeOrigin("https://EXAMPLE.COM:443/path?a=1"))
        assertEquals("https://example.com:8443", SitePermissionsManager.normalizeOrigin("https://example.com:8443/path"))

        val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
        val webRequest = FakeWebPermissionRequest(
            requestOrigin = Uri.parse("http://example.com/call"),
            requestedResources = arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE),
        )
        manager.onWebPermissionRequest(webRequest, incognito = false, scope = scope)
        assertTrue(webRequest.denied)
        assertNull(webRequest.granted)
        assertNull(manager.prompt.value)

        var geoAllowed: Boolean? = null
        manager.onGeolocationPrompt(
            origin = "http://example.com",
            callback = GeolocationPermissions.Callback { _, allow, _ -> geoAllowed = allow },
            incognito = false,
            scope = scope,
        )
        assertEquals(false, geoAllowed)
        assertNull(manager.prompt.value)
    }

    @Test
    fun rememberedAllowPersistsAndAutoGrantsSubsequentRequests(): Unit = runTest {
        val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
        val first = FakeWebPermissionRequest(
            requestOrigin = Uri.parse("https://meet.example.com/room"),
            requestedResources = arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE),
        )

        manager.onWebPermissionRequest(first, incognito = false, scope = scope)
        scope.awaitChildren()

        val prompt = manager.prompt.value
        assertNotNull(prompt)
        assertEquals("https://meet.example.com", prompt?.origin)
        assertEquals(listOf(SitePermissionKind.Camera), prompt?.kinds)
        assertFalse(prompt?.incognito ?: true)

        manager.respond(prompt!!.id, allow = true, remember = true, scope = scope)
        scope.awaitChildren()

        assertNull(manager.prompt.value)
        assertFalse(first.denied)
        assertArrayEquals(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE), first.granted)

        val saved = manager.allSaved()
        assertEquals(1, saved.size)
        assertEquals("https://meet.example.com", saved.single().origin)
        assertEquals(SitePermissions.Status.ALLOWED, saved.single().camera)

        val second = FakeWebPermissionRequest(
            requestOrigin = Uri.parse("https://meet.example.com/another"),
            requestedResources = arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE),
        )
        manager.onWebPermissionRequest(second, incognito = false, scope = scope)
        scope.awaitChildren()

        assertNull(manager.prompt.value)
        assertFalse(second.denied)
        assertArrayEquals(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE), second.granted)
    }

    @Test
    fun rememberedBlockPersistsAndAutoDeniesSubsequentRequests(): Unit = runTest {
        val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
        val first = FakeWebPermissionRequest(
            requestOrigin = Uri.parse("https://noisy.example.com"),
            requestedResources = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
        )

        manager.onWebPermissionRequest(first, incognito = false, scope = scope)
        scope.awaitChildren()

        val prompt = manager.prompt.value
        assertNotNull(prompt)
        manager.respond(prompt!!.id, allow = false, remember = true, scope = scope)
        scope.awaitChildren()

        assertTrue(first.denied)
        assertNull(first.granted)
        assertEquals(SitePermissions.Status.BLOCKED, manager.allSaved().single().microphone)

        val second = FakeWebPermissionRequest(
            requestOrigin = Uri.parse("https://noisy.example.com"),
            requestedResources = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE),
        )
        manager.onWebPermissionRequest(second, incognito = false, scope = scope)
        scope.awaitChildren()

        assertNull(manager.prompt.value)
        assertTrue(second.denied)
        assertNull(second.granted)
    }

    @Test
    fun incognitoNeverWritesPersistentStorageAndClearTemporaryWipesSessionState(): Unit = runTest {
        val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
        val first = FakeWebPermissionRequest(
            requestOrigin = Uri.parse("https://private.example.com"),
            requestedResources = arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE),
        )

        manager.onWebPermissionRequest(first, incognito = true, scope = scope)
        scope.awaitChildren()

        val prompt = manager.prompt.value
        assertNotNull(prompt)
        assertTrue(prompt!!.incognito)

        manager.respond(prompt.id, allow = true, remember = true, scope = scope)
        scope.awaitChildren()

        assertArrayEquals(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE), first.granted)
        assertTrue(manager.allSaved().isEmpty())
        assertEquals(0, storage.writeCount)

        val second = FakeWebPermissionRequest(
            requestOrigin = Uri.parse("https://private.example.com"),
            requestedResources = arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE),
        )
        manager.onWebPermissionRequest(second, incognito = true, scope = scope)
        scope.awaitChildren()

        assertNull(manager.prompt.value)
        assertArrayEquals(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE), second.granted)

        manager.clearTemporary()
        assertEquals(1, storage.clearTemporaryCalls)

        val third = FakeWebPermissionRequest(
            requestOrigin = Uri.parse("https://private.example.com"),
            requestedResources = arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE),
        )
        manager.onWebPermissionRequest(third, incognito = true, scope = scope)
        scope.awaitChildren()

        assertNotNull(manager.prompt.value)
        manager.dismissPrompt(manager.prompt.value!!.id)
        assertTrue(third.denied)
        assertNull(manager.prompt.value)
    }

    @Test
    fun revokeOriginAndClearAllRemoveStoredEntries(): Unit = runTest {
        val scope = CoroutineScope(Dispatchers.Main.immediate + Job())

        for (host in listOf("https://one.example.com", "https://two.example.com")) {
            val req = FakeWebPermissionRequest(
                requestOrigin = Uri.parse(host),
                requestedResources = arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID),
            )
            manager.onWebPermissionRequest(req, incognito = false, scope = scope)
            scope.awaitChildren()
            manager.respond(manager.prompt.value!!.id, allow = true, remember = true, scope = scope)
            scope.awaitChildren()
        }
        assertEquals(2, manager.allSaved().size)

        manager.revokeOrigin("https://one.example.com/")
        assertEquals(listOf("https://two.example.com"), manager.allSaved().map { it.origin })

        manager.clearAll()
        assertTrue(manager.allSaved().isEmpty())
    }

    @Test
    fun geolocationAndSupersedingPromptsNeverLeakCallbacks(): Unit = runTest {
        val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
        val staleRequest = FakeWebPermissionRequest(
            requestOrigin = Uri.parse("https://stale.example.com"),
            requestedResources = arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE),
        )
        manager.onWebPermissionRequest(staleRequest, incognito = false, scope = scope)
        scope.awaitChildren()
        assertNotNull(manager.prompt.value)
        assertFalse(staleRequest.denied)

        var geoAllowed: Boolean? = null
        var geoRetain: Boolean? = null
        manager.onGeolocationPrompt(
            origin = "https://maps.example.com",
            callback = GeolocationPermissions.Callback { _, allow, retain ->
                geoAllowed = allow
                geoRetain = retain
            },
            incognito = false,
            scope = scope,
        )
        scope.awaitChildren()

        assertTrue(staleRequest.denied)
        val geoPrompt = manager.prompt.value
        assertNotNull(geoPrompt)
        assertEquals(listOf(SitePermissionKind.Location), geoPrompt!!.kinds)

        manager.respond(geoPrompt.id, allow = true, remember = true, scope = scope)
        scope.awaitChildren()

        assertEquals(true, geoAllowed)
        assertEquals(false, geoRetain)
        assertEquals(SitePermissions.Status.ALLOWED, manager.allSaved().single().location)
    }

    @Test
    fun osPermissionRequesterGatesGrantWhenRuntimePermissionMissing(): Unit = runTest {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        val scope = CoroutineScope(Dispatchers.Main.immediate + Job())

        var askedPermissions: List<String>? = null
        manager.requestOsPermissions = { perms ->
            askedPermissions = perms
            false
        }

        val deniedByOs = FakeWebPermissionRequest(
            requestOrigin = Uri.parse("https://cam.example.com"),
            requestedResources = arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE),
        )
        manager.onWebPermissionRequest(deniedByOs, incognito = false, scope = scope)
        scope.awaitChildren()
        manager.respond(manager.prompt.value!!.id, allow = true, remember = false, scope = scope)
        scope.awaitChildren()

        assertEquals(listOf(Manifest.permission.CAMERA), askedPermissions)
        assertTrue(deniedByOs.denied)
        assertNull(deniedByOs.granted)

        manager.requestOsPermissions = { true }
        val grantedByOs = FakeWebPermissionRequest(
            requestOrigin = Uri.parse("https://cam.example.com"),
            requestedResources = arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE),
        )
        manager.onWebPermissionRequest(grantedByOs, incognito = false, scope = scope)
        scope.awaitChildren()
        manager.respond(manager.prompt.value!!.id, allow = true, remember = false, scope = scope)
        scope.awaitChildren()

        assertFalse(grantedByOs.denied)
        assertArrayEquals(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE), grantedByOs.granted)
    }

    private suspend fun CoroutineScope.awaitChildren() {
        coroutineContext[Job]?.children?.toList()?.joinAll()
    }

    private class FakeWebPermissionRequest(
        private val requestOrigin: Uri,
        private val requestedResources: Array<String>,
    ) : PermissionRequest() {
        var granted: Array<String>? = null
            private set
        var denied: Boolean = false
            private set

        override fun getOrigin(): Uri = requestOrigin

        override fun getResources(): Array<String> = requestedResources

        override fun grant(resources: Array<String>) {
            check(!denied && granted == null) { "PermissionRequest already answered" }
            granted = resources
        }

        override fun deny() {
            check(!denied && granted == null) { "PermissionRequest already answered" }
            denied = true
        }
    }

    private class InMemorySitePermissionsStorage : SitePermissionsStorage {
        private val entries: ConcurrentHashMap<String, SitePermissions> = ConcurrentHashMap()
        var writeCount: Int = 0
            private set
        var clearTemporaryCalls: Int = 0
            private set

        override suspend fun save(
            sitePermissions: SitePermissions,
            request: mozilla.components.concept.engine.permission.PermissionRequest?,
            private: Boolean,
        ) {
            if (private) return
            writeCount++
            entries[sitePermissions.origin] = sitePermissions
        }

        override suspend fun update(sitePermissions: SitePermissions, private: Boolean) {
            if (private) return
            writeCount++
            entries[sitePermissions.origin] = sitePermissions
        }

        override suspend fun findSitePermissionsBy(
            origin: String,
            includeTemporary: Boolean,
            private: Boolean,
        ): SitePermissions? = if (private) null else entries[origin]

        override suspend fun getSitePermissionsPaged(): Nothing =
            throw UnsupportedOperationException("Paging unused in unit tests")

        override suspend fun remove(sitePermissions: SitePermissions, private: Boolean) {
            if (!private) {
                entries.remove(sitePermissions.origin)
            }
        }

        override suspend fun removeAll() {
            entries.clear()
        }

        override suspend fun all(): List<SitePermissions> = entries.values.sortedBy { it.origin }

        override fun clearTemporaryPermissions() {
            clearTemporaryCalls++
        }
    }
}
