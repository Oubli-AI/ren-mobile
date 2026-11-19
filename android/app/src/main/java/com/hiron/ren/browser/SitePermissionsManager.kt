package com.hiron.ren.browser

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.concept.engine.permission.SitePermissions
import mozilla.components.concept.engine.permission.SitePermissionsStorage
import mozilla.components.feature.sitepermissions.OnDiskSitePermissionsStorage
import java.util.EnumMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A capability a web page can ask to use on its origin. */
internal enum class SitePermissionKind(internal val label: String) {
    Camera("Camera"),
    Microphone("Microphone"),
    Location("Location"),
    ProtectedMedia("Protected media"),
}

/** A pending permission prompt waiting for the user's decision in the browser chrome. */
internal data class SitePermissionPrompt(
    val id: String,
    val origin: String,
    val kinds: List<SitePermissionKind>,
    val incognito: Boolean,
)

/**
 * Coordinates per-origin web permissions (camera, microphone, geolocation and protected media) between WebView
 * callbacks, Android runtime permissions, persistent [SitePermissionsStorage] and ephemeral incognito session state.
 */
internal class SitePermissionsManager(
    private val context: Context,
    private val storage: SitePermissionsStorage = OnDiskSitePermissionsStorage(context),
) {
    private val _prompt: MutableStateFlow<SitePermissionPrompt?> = MutableStateFlow(null)
    internal val prompt: StateFlow<SitePermissionPrompt?> = _prompt.asStateFlow()

    /**
     * Requests missing Android runtime permissions when the activity is on screen; when null, falls back to checking
     * [ContextCompat.checkSelfPermission] directly.
     */
    internal var requestOsPermissions: (suspend (List<String>) -> Boolean)? = null

    private val promptLock: ReentrantLock = ReentrantLock()
    private var pending: PendingPrompt? = null

    private val sessionLock: ReentrantLock = ReentrantLock()
    private val sessionPermissions: HashMap<String, EnumMap<SitePermissionKind, SitePermissions.Status>> = HashMap()

    internal fun onWebPermissionRequest(
        request: PermissionRequest,
        incognito: Boolean,
        scope: CoroutineScope,
    ) {
        val origin = request.origin?.toString()?.let(::normalizeOrigin)
        val rawResources = request.resources
        if (origin == null || rawResources == null) {
            request.deny()
            return
        }

        val kinds = ArrayList<SitePermissionKind>(rawResources.size)
        val webResources = ArrayList<String>(rawResources.size)
        for (resource in rawResources) {
            val kind = when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> SitePermissionKind.Camera
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> SitePermissionKind.Microphone
                PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> SitePermissionKind.ProtectedMedia
                else -> null
            }
            if (kind != null) {
                if (kind !in kinds) kinds.add(kind)
                if (resource !in webResources) webResources.add(resource)
            }
        }
        if (kinds.isEmpty()) {
            request.deny()
            return
        }

        val grantedWebResources = webResources.toTypedArray()
        scope.launch(Dispatchers.Main.immediate) {
            val existing = withContext(Dispatchers.IO) {
                storage.findSitePermissionsBy(origin, includeTemporary = true, private = incognito)
            }
            val statuses = kinds.map { kind -> effectiveStatus(origin, kind, incognito, existing) }
            when {
                statuses.any { it == SitePermissions.Status.BLOCKED } -> request.deny()
                statuses.all { it == SitePermissions.Status.ALLOWED } -> {
                    if (ensureOsPermissions(kinds)) {
                        request.grant(grantedWebResources)
                    } else {
                        request.deny()
                    }
                }
                else -> {
                    val promptItem = SitePermissionPrompt(
                        id = UUID.randomUUID().toString(),
                        origin = origin,
                        kinds = kinds.toList(),
                        incognito = incognito,
                    )
                    enqueuePrompt(
                        PendingPrompt(
                            prompt = promptItem,
                            callback = PendingCallback.Web(request, grantedWebResources),
                        ),
                    )
                }
            }
        }
    }

    internal fun onGeolocationPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback,
        incognito: Boolean,
        scope: CoroutineScope,
    ) {
        val normalizedOrigin = normalizeOrigin(origin)
        if (normalizedOrigin == null) {
            callback.invoke(origin, false, false)
            return
        }

        scope.launch(Dispatchers.Main.immediate) {
            val existing = withContext(Dispatchers.IO) {
                storage.findSitePermissionsBy(normalizedOrigin, includeTemporary = true, private = incognito)
            }
            val kinds = listOf(SitePermissionKind.Location)
            when (effectiveStatus(normalizedOrigin, SitePermissionKind.Location, incognito, existing)) {
                SitePermissions.Status.BLOCKED -> callback.invoke(origin, false, false)
                SitePermissions.Status.ALLOWED -> {
                    val osGranted = ensureOsPermissions(kinds)
                    callback.invoke(origin, osGranted, false)
                }
                SitePermissions.Status.NO_DECISION -> {
                    val promptItem = SitePermissionPrompt(
                        id = UUID.randomUUID().toString(),
                        origin = normalizedOrigin,
                        kinds = kinds,
                        incognito = incognito,
                    )
                    enqueuePrompt(
                        PendingPrompt(
                            prompt = promptItem,
                            callback = PendingCallback.Geolocation(origin, callback),
                        ),
                    )
                }
            }
        }
    }

    internal fun respond(
        promptId: String,
        allow: Boolean,
        remember: Boolean,
        scope: CoroutineScope,
    ) {
        val target = promptLock.withLock {
            val current = pending
            if (current != null && current.prompt.id == promptId) {
                pending = null
                _prompt.value = null
                current
            } else {
                null
            }
        } ?: return

        scope.launch(Dispatchers.Main.immediate) {
            val decision = if (allow) SitePermissions.Status.ALLOWED else SitePermissions.Status.BLOCKED
            if (remember && !target.prompt.incognito) {
                withContext(Dispatchers.IO) {
                    persistDecision(target.prompt.origin, target.prompt.kinds, decision)
                }
            } else if (remember && target.prompt.incognito) {
                rememberInSession(target.prompt.origin, target.prompt.kinds, decision)
            }

            if (allow && ensureOsPermissions(target.prompt.kinds)) {
                target.callback.grant()
            } else {
                target.callback.deny()
            }
        }
    }

    internal fun dismissPrompt(promptId: String) {
        val removed = promptLock.withLock {
            val current = pending
            if (current != null && current.prompt.id == promptId) {
                pending = null
                _prompt.value = null
                current
            } else {
                null
            }
        }
        removed?.callback?.deny()
    }

    internal suspend fun allSaved(): List<SitePermissions> = withContext(Dispatchers.IO) { storage.all() }

    internal suspend fun revokeOrigin(origin: String) {
        val normalized = normalizeOrigin(origin)
        sessionLock.withLock {
            sessionPermissions.remove(origin)
            if (normalized != null) {
                sessionPermissions.remove(normalized)
            }
        }
        withContext(Dispatchers.IO) {
            val targetOrigin = normalized ?: origin
            val found = storage.findSitePermissionsBy(targetOrigin, includeTemporary = true, private = false)
                ?: if (targetOrigin != origin) {
                    storage.findSitePermissionsBy(origin, includeTemporary = true, private = false)
                } else {
                    null
                }
            if (found != null) {
                storage.remove(found, private = false)
            }
        }
    }

    internal suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            storage.removeAll()
        }
        clearTemporary()
    }

    internal fun clearTemporary() {
        sessionLock.withLock {
            sessionPermissions.clear()
        }
        storage.clearTemporaryPermissions()
    }

    private fun enqueuePrompt(next: PendingPrompt) {
        val stale = promptLock.withLock {
            val previous = pending
            pending = next
            _prompt.value = next.prompt
            previous
        }
        stale?.callback?.deny()
    }

    private fun effectiveStatus(
        origin: String,
        kind: SitePermissionKind,
        incognito: Boolean,
        existing: SitePermissions?,
    ): SitePermissions.Status {
        if (incognito) {
            val session = sessionLock.withLock { sessionPermissions[origin]?.get(kind) }
            if (session != null && session != SitePermissions.Status.NO_DECISION) {
                return session
            }
        }
        return existing?.statusFor(kind) ?: SitePermissions.Status.NO_DECISION
    }

    private fun rememberInSession(
        origin: String,
        kinds: List<SitePermissionKind>,
        status: SitePermissions.Status,
    ) {
        sessionLock.withLock {
            val map = sessionPermissions.getOrPut(origin) { EnumMap(SitePermissionKind::class.java) }
            for (kind in kinds) {
                map[kind] = status
            }
        }
    }

    private suspend fun persistDecision(
        origin: String,
        kinds: List<SitePermissionKind>,
        status: SitePermissions.Status,
    ) {
        val now = System.currentTimeMillis()
        val existing = storage.findSitePermissionsBy(origin, includeTemporary = true, private = false)
        if (existing != null) {
            storage.update(existing.withStatuses(kinds, status, now), private = false)
        } else {
            val created = SitePermissions(origin = origin, savedAt = now).withStatuses(kinds, status, now)
            storage.save(created, request = null, private = false)
        }
    }

    private suspend fun ensureOsPermissions(kinds: List<SitePermissionKind>): Boolean {
        if (areAllKindsOsGranted(kinds)) return true
        val requester = requestOsPermissions ?: return false
        val missing = LinkedHashSet<String>()
        for (kind in kinds) {
            when (kind) {
                SitePermissionKind.Camera -> {
                    if (!hasOsPermission(Manifest.permission.CAMERA)) {
                        missing.add(Manifest.permission.CAMERA)
                    }
                }
                SitePermissionKind.Microphone -> {
                    if (!hasOsPermission(Manifest.permission.RECORD_AUDIO)) {
                        missing.add(Manifest.permission.RECORD_AUDIO)
                    }
                }
                SitePermissionKind.Location -> {
                    if (
                        !hasOsPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
                        !hasOsPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    ) {
                        missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
                        missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
                }
                SitePermissionKind.ProtectedMedia -> Unit
            }
        }
        if (missing.isEmpty()) return true
        return requester(missing.toList()) || areAllKindsOsGranted(kinds)
    }

    private fun areAllKindsOsGranted(kinds: List<SitePermissionKind>): Boolean = kinds.all { kind ->
        when (kind) {
            SitePermissionKind.Camera -> hasOsPermission(Manifest.permission.CAMERA)
            SitePermissionKind.Microphone -> hasOsPermission(Manifest.permission.RECORD_AUDIO)
            SitePermissionKind.Location ->
                hasOsPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                    hasOsPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            SitePermissionKind.ProtectedMedia -> true
        }
    }

    private fun hasOsPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun SitePermissions.statusFor(kind: SitePermissionKind): SitePermissions.Status = when (kind) {
        SitePermissionKind.Camera -> camera
        SitePermissionKind.Microphone -> microphone
        SitePermissionKind.Location -> location
        SitePermissionKind.ProtectedMedia -> mediaKeySystemAccess
    }

    private fun SitePermissions.withStatuses(
        kinds: List<SitePermissionKind>,
        status: SitePermissions.Status,
        now: Long,
    ): SitePermissions = copy(
        camera = if (SitePermissionKind.Camera in kinds) status else camera,
        microphone = if (SitePermissionKind.Microphone in kinds) status else microphone,
        location = if (SitePermissionKind.Location in kinds) status else location,
        mediaKeySystemAccess = if (SitePermissionKind.ProtectedMedia in kinds) status else mediaKeySystemAccess,
        savedAt = now,
    )

    private class PendingPrompt(
        val prompt: SitePermissionPrompt,
        val callback: PendingCallback,
    )

    private sealed interface PendingCallback {
        fun grant()
        fun deny()

        class Web(
            private val request: PermissionRequest,
            private val grantedWebResources: Array<String>,
        ) : PendingCallback {
            private val answered: AtomicBoolean = AtomicBoolean(false)

            override fun grant() {
                if (answered.compareAndSet(false, true)) {
                    request.grant(grantedWebResources)
                }
            }

            override fun deny() {
                if (answered.compareAndSet(false, true)) {
                    request.deny()
                }
            }
        }

        class Geolocation(
            private val callbackOrigin: String,
            private val callback: GeolocationPermissions.Callback,
        ) : PendingCallback {
            private val answered: AtomicBoolean = AtomicBoolean(false)

            override fun grant() {
                if (answered.compareAndSet(false, true)) {
                    callback.invoke(callbackOrigin, true, false)
                }
            }

            override fun deny() {
                if (answered.compareAndSet(false, true)) {
                    callback.invoke(callbackOrigin, false, false)
                }
            }
        }
    }

    internal companion object {
        /**
         * Normalizes [raw] to `"${scheme}://${host}${portPart}"` for `https` origins and loopback `http` origins
         * (`localhost`, `127.0.0.1`, `[::1]`). Returns `null` for insecure non-loopback `http` origins and non-web URLs.
         */
        internal fun normalizeOrigin(raw: String): String? {
            val text = raw.trim()
            val schemeSep = text.indexOf("://")
            if (schemeSep <= 0) return null
            val scheme = text.substring(0, schemeSep).lowercase(Locale.ROOT)
            if (scheme != "https" && scheme != "http") return null

            val rest = text.substring(schemeSep + 3)
            val authEnd = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, authEnd).substringAfterLast('@')
            if (authority.isEmpty()) return null

            val host: String
            val port: Int?
            if (authority.startsWith('[')) {
                val closeBracket = authority.indexOf(']')
                if (closeBracket <= 1) return null
                host = authority.substring(0, closeBracket + 1).lowercase(Locale.ROOT)
                if (host.any(Char::isWhitespace)) return null
                val afterBracket = authority.substring(closeBracket + 1)
                port = when {
                    afterBracket.isEmpty() -> null
                    afterBracket.startsWith(':') -> afterBracket.substring(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                    else -> return null
                }
            } else {
                if (authority.contains('[') || authority.contains(']')) return null
                val firstColon = authority.indexOf(':')
                if (firstColon >= 0 && authority.indexOf(':', firstColon + 1) >= 0) return null
                val rawHost = if (firstColon < 0) authority else authority.substring(0, firstColon)
                val rawPort = if (firstColon < 0) null else authority.substring(firstColon + 1)
                host = rawHost.lowercase(Locale.ROOT).trimEnd('.')
                if (host.isEmpty() || host.any(Char::isWhitespace)) return null
                port = if (rawPort == null) null else rawPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            }

            val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "[::1]"
            if (scheme == "http" && !isLoopback) return null

            val defaultPort = if (scheme == "https") 443 else 80
            val portPart = if (port != null && port != defaultPort) ":$port" else ""
            return "$scheme://$host$portPart"
        }
    }
}
