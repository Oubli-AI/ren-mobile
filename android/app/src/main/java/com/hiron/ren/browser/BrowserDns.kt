package com.hiron.ren.browser

import android.content.Context
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import okhttp3.Cache
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** How Ren resolves hostnames for OkHttp calls and WebView traffic; [wire] is the stored setting value. */
internal enum class DnsMode(
    internal val wire: String,
    internal val label: String,
    internal val endpoint: String?,
    internal val bootstrapIps: List<String>,
) {
    System("system", "Automatic", null, emptyList()),
    Cloudflare(
        "cloudflare",
        "Cloudflare (1.1.1.1)",
        "https://cloudflare-dns.com/dns-query",
        listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001"),
    ),
    Quad9(
        "quad9",
        "Quad9 (9.9.9.9)",
        "https://dns.quad9.net/dns-query",
        listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9"),
    ),
    AdGuard(
        "adguard",
        "AdGuard DNS",
        "https://dns.adguard-dns.com/dns-query",
        listOf("94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff"),
    ),
    Custom("custom", "Custom DoH", null, emptyList()),
    ;

    internal companion object {
        internal val Default: DnsMode = Cloudflare

        internal fun of(wire: String?): DnsMode? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * DNS-over-HTTPS resolver (`okhttp3.Dns`) paired with a loopback HTTP/`CONNECT` proxy bound to `127.0.0.1` so Android
 * WebView traffic uses the selected DoH provider and host-level Shields blocking without touching system VPN state.
 */
internal class BrowserDns(
    context: Context,
    initialMode: DnsMode = DnsMode.Default,
    initialCustomUrl: String = "",
    initialShieldsDnsBlock: Boolean = true,
    private val headerReadTimeoutMs: Int = DEFAULT_HEADER_READ_TIMEOUT_MS,
    private val systemDns: Dns = Dns.SYSTEM,
    private val bootstrapClient: OkHttpClient = defaultBootstrapClient(context),
    private val isSiteShieldsOff: (String) -> Boolean = { false },
) : Dns, Closeable {
    private val lock = ReentrantLock()
    private val directExecutor: Executor = Executor(Runnable::run)

    @Volatile
    internal var mode: DnsMode = initialMode
        private set

    @Volatile
    internal var customUrl: String = initialCustomUrl.trim()
        private set

    @Volatile
    internal var shieldsDnsBlock: Boolean = initialShieldsDnsBlock
        private set

    @Volatile
    private var doh: DnsOverHttps? = null

    @Volatile
    private var dohEndpoint: String? = null

    @Volatile
    private var proxy: LocalDohProxy? = null

    @Volatile
    private var closed: Boolean = false

    internal val activeEndpoint: String?
        get() = dohEndpoint

    internal val proxyPort: Int?
        get() = proxy?.port

    internal val activeSocketCount: Int
        get() = proxy?.activeSocketCount ?: 0

    init {
        configure(initialMode, initialCustomUrl, initialShieldsDnsBlock)
    }

    /** Updates the active DoH resolver, loopback proxy, and WebView proxy override to match the given settings. */
    internal fun configure(mode: DnsMode, customUrl: String = "", shieldsDnsBlock: Boolean = true) {
        lock.withLock {
            if (closed) return
            val trimmedCustom = customUrl.trim()
            this.mode = mode
            this.customUrl = trimmedCustom
            this.shieldsDnsBlock = shieldsDnsBlock

            val endpointText = when (mode) {
                DnsMode.System -> null
                DnsMode.Custom -> trimmedCustom.takeIf(::isValidDohUrl)
                DnsMode.Cloudflare, DnsMode.Quad9, DnsMode.AdGuard -> mode.endpoint
            }
            val endpointHttpUrl = endpointText?.toHttpUrlOrNull()?.takeIf { it.isHttps }
            if (endpointHttpUrl != null) {
                val builder = DnsOverHttps.Builder()
                    .client(bootstrapClient)
                    .url(endpointHttpUrl)
                    .systemDns(systemDns)
                    .includeIPv6(true)
                    .resolvePrivateAddresses(false)
                    .resolvePublicAddresses(true)
                if (mode.bootstrapIps.isNotEmpty()) {
                    val _ = builder.bootstrapDnsHosts(mode.bootstrapIps.map(InetAddress::getByName))
                }
                doh = builder.build()
                dohEndpoint = endpointHttpUrl.toString()
            } else {
                doh = null
                dohEndpoint = null
            }

            val shouldRunProxy = endpointHttpUrl != null || shieldsDnsBlock
            if (shouldRunProxy) {
                val runningProxy = proxy ?: LocalDohProxy().also { proxy = it }
                applyWebViewProxyOverride(runningProxy.port)
            } else {
                proxy?.close()
                proxy = null
                clearWebViewProxyOverride()
            }
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (isLocalOrLiteralHost(hostname)) {
            return systemDns.lookup(hostname)
        }
        val resolver = doh ?: return systemDns.lookup(hostname)
        return try {
            resolver.lookup(hostname)
        } catch (error: UnknownHostException) {
            if (shouldFallbackToSystemDns(hostname, error)) {
                systemDns.lookup(hostname)
            } else {
                throw error
            }
        }
    }

    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            doh = null
            dohEndpoint = null
            proxy?.close()
            proxy = null
            clearWebViewProxyOverride()
        }
        bootstrapClient.dispatcher.executorService.shutdown()
        bootstrapClient.connectionPool.evictAll()
        val _ = runCatching { bootstrapClient.cache?.close() }
    }

    private fun applyWebViewProxyOverride(port: Int) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            val config = ProxyConfig.Builder()
                .addProxyRule("127.0.0.1:$port")
                .addBypassRule("appassets.androidplatform.net")
                .addBypassRule("<local>")
                .addBypassRule("localhost")
                .addBypassRule("127.0.0.1")
                .addBypassRule("[::1]")
                .bypassSimpleHostnames()
                .build()
            ProxyController.getInstance().setProxyOverride(config, directExecutor) {}
        }
    }

    private fun clearWebViewProxyOverride() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            ProxyController.getInstance().clearProxyOverride(directExecutor) {}
        }
    }

    private fun isLocalOrLiteralHost(hostname: String): Boolean {
        val normalized = ContentBlocker.normalizeHost(hostname)
        if (normalized.isEmpty()) return true
        if (normalized == "localhost" || normalized.endsWith(".localhost") || normalized.endsWith(".local")) {
            return true
        }
        return ContentBlocker.isIpLiteral(normalized)
    }

    private fun isHostShieldsOff(host: String): Boolean {
        val normalized = ContentBlocker.normalizeHost(host)
        if (normalized.isEmpty()) return false
        if (isSiteShieldsOff(host) || (normalized != host && isSiteShieldsOff(normalized))) return true
        var cursor = normalized
        while (true) {
            val dot = cursor.indexOf('.')
            if (dot <= 0 || dot >= cursor.length - 1) return false
            cursor = cursor.substring(dot + 1)
            if (isSiteShieldsOff(cursor)) return true
        }
    }

    private data class HostPort(val host: String, val port: Int)

    private data class PlainTarget(val host: String, val port: Int, val originTarget: String)

    private data class HttpHeader(val name: String, val value: String)

    private inner class LocalDohProxy : Closeable {
        private val serverSocket = ServerSocket(0, PROXY_BACKLOG, InetAddress.getByName("127.0.0.1"))
        private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
        private val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "ren-doh-proxy").apply { isDaemon = true }
        }

        @Volatile
        private var running = true

        val port: Int = serverSocket.localPort
        val activeSocketCount: Int
            get() = activeSockets.size

        init {
            executor.execute(::acceptLoop)
        }

        private fun acceptLoop() {
            while (running) {
                val client = try {
                    serverSocket.accept()
                } catch (_: IOException) {
                    if (!running || serverSocket.isClosed) break
                    continue
                }
                if (!running) {
                    val _ = runCatching { client.close() }
                    break
                }
                val _ = activeSockets.add(client)
                try {
                    executor.execute { handleClient(client) }
                } catch (_: RuntimeException) {
                    val _ = activeSockets.remove(client)
                    val _ = runCatching { client.close() }
                }
            }
        }

        private fun handleClient(client: Socket) {
            try {
                client.keepAlive = true
                client.tcpNoDelay = true
                client.soTimeout = headerReadTimeoutMs
                val clientIn = BufferedInputStream(client.getInputStream(), BUFFER_SIZE)
                val clientOut = client.getOutputStream()
                val requestLine = readHeaderLine(clientIn) ?: return
                if (requestLine.isEmpty()) return
                val parts = requestLine.split(' ', limit = 3)
                if (parts.size < 3) {
                    writeAscii(clientOut, BAD_REQUEST_RESPONSE)
                    return
                }
                val method = parts[0]
                val rawTarget = parts[1]
                val version = parts[2]
                if (method.equals("CONNECT", ignoreCase = true)) {
                    handleConnect(rawTarget, client, clientIn, clientOut)
                } else {
                    handlePlainHttp(method, rawTarget, version, client, clientIn, clientOut)
                }
            } catch (_: IOException) {
                // Header timeout or peer disconnect closes the connection in finally.
            } finally {
                val _ = activeSockets.remove(client)
                val _ = runCatching { client.close() }
            }
        }

        private fun handleConnect(
            rawTarget: String,
            client: Socket,
            clientIn: InputStream,
            clientOut: OutputStream,
        ) {
            if (readHeaders(clientIn) == null) return
            val target = parseHostPort(rawTarget, DEFAULT_HTTPS_PORT) ?: run {
                writeAscii(clientOut, BAD_REQUEST_RESPONSE)
                return
            }
            if (shieldsDnsBlock && !isHostShieldsOff(target.host) && ContentBlocker.isBlockedHost(target.host)) {
                writeAscii(clientOut, FORBIDDEN_RESPONSE)
                return
            }
            val addresses = runCatching { lookup(target.host) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: run {
                writeAscii(clientOut, BAD_GATEWAY_RESPONSE)
                return
            }
            val upstream = try {
                connectUpstream(addresses, target.port)
            } catch (_: IOException) {
                writeAscii(clientOut, BAD_GATEWAY_RESPONSE)
                return
            }
            val _ = activeSockets.add(upstream)
            try {
                // Established tunnels carry long-lived SSE/WebSocket streams with unbounded read timeouts.
                client.soTimeout = 0
                upstream.soTimeout = 0
                writeAscii(clientOut, CONNECT_ESTABLISHED_RESPONSE)
                val upstreamIn = upstream.getInputStream()
                val upstreamOut = upstream.getOutputStream()
                val clientToUpstream = executor.submit {
                    try {
                        relayStream(clientIn, upstreamOut)
                        val _ = runCatching { upstream.shutdownOutput() }
                    } catch (_: IOException) {
                        val _ = runCatching { upstream.close() }
                        val _ = runCatching { client.close() }
                    }
                }
                try {
                    relayStream(upstreamIn, clientOut)
                    val _ = runCatching { client.shutdownOutput() }
                } catch (_: IOException) {
                    val _ = runCatching { client.close() }
                    val _ = runCatching { upstream.close() }
                } finally {
                    try {
                        val _ = clientToUpstream.get(RELAY_DRAIN_MS, TimeUnit.MILLISECONDS)
                    } catch (_: Exception) {
                        val _ = clientToUpstream.cancel(true)
                    }
                }
            } finally {
                val _ = activeSockets.remove(upstream)
                val _ = runCatching { upstream.close() }
            }
        }

        private fun handlePlainHttp(
            method: String,
            rawTarget: String,
            version: String,
            client: Socket,
            clientIn: InputStream,
            clientOut: OutputStream,
        ) {
            val headers = readHeaders(clientIn) ?: return
            val target = resolvePlainTarget(rawTarget, headers) ?: run {
                writeAscii(clientOut, BAD_REQUEST_RESPONSE)
                return
            }
            if (shieldsDnsBlock && !isHostShieldsOff(target.host) && ContentBlocker.isBlockedHost(target.host)) {
                writeAscii(clientOut, FORBIDDEN_RESPONSE)
                return
            }
            val addresses = runCatching { lookup(target.host) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: run {
                writeAscii(clientOut, BAD_GATEWAY_RESPONSE)
                return
            }
            val upstream = try {
                connectUpstream(addresses, target.port)
            } catch (_: IOException) {
                writeAscii(clientOut, BAD_GATEWAY_RESPONSE)
                return
            }
            val _ = activeSockets.add(upstream)
            try {
                // Once headers are parsed and upstream is connected, lift the header read timeout for body/response streaming.
                client.soTimeout = 0
                upstream.soTimeout = 0
                val upstreamOut = upstream.getOutputStream()
                val rewritten = StringBuilder(256)
                rewritten.append(method).append(' ').append(target.originTarget).append(' ').append(version).append("\r\n")
                var sawHost = false
                var contentLength = 0L
                for (header in headers) {
                    if (header.name.equals("Proxy-Connection", ignoreCase = true)) continue
                    if (header.name.equals("Proxy-Authorization", ignoreCase = true)) continue
                    if (header.name.equals("Connection", ignoreCase = true)) continue
                    if (header.name.equals("Host", ignoreCase = true)) sawHost = true
                    if (header.name.equals("Content-Length", ignoreCase = true)) {
                        contentLength = header.value.trim().toLongOrNull() ?: 0L
                    }
                    rewritten.append(header.name).append(": ").append(header.value).append("\r\n")
                }
                if (!sawHost) {
                    rewritten.append("Host: ")
                        .append(formatHostHeader(target.host, target.port.takeIf { it != DEFAULT_HTTP_PORT }))
                        .append("\r\n")
                }
                rewritten.append("Connection: close\r\n\r\n")
                upstreamOut.write(rewritten.toString().toByteArray(Charsets.ISO_8859_1))
                if (contentLength > 0L) {
                    copyExact(clientIn, upstreamOut, contentLength)
                } else {
                    upstreamOut.flush()
                }
                relayStream(upstream.getInputStream(), clientOut)
            } finally {
                val _ = activeSockets.remove(upstream)
                val _ = runCatching { upstream.close() }
            }
        }

        private fun connectUpstream(addresses: List<InetAddress>, port: Int): Socket {
            var lastError: IOException? = null
            for (address in addresses) {
                val candidate = Socket()
                try {
                    candidate.keepAlive = true
                    candidate.tcpNoDelay = true
                    candidate.soTimeout = 0
                    candidate.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                    return candidate
                } catch (error: IOException) {
                    val _ = runCatching { candidate.close() }
                    lastError = error
                }
            }
            throw lastError ?: IOException("Unable to connect to upstream on port $port")
        }

        private fun relayStream(src: InputStream, dst: OutputStream) {
            val buffer = ByteArray(BUFFER_SIZE)
            while (running) {
                val read = src.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    dst.write(buffer, 0, read)
                    dst.flush()
                }
            }
        }

        private fun copyExact(src: InputStream, dst: OutputStream, byteCount: Long) {
            var remaining = byteCount
            val buffer = ByteArray(BUFFER_SIZE)
            while (remaining > 0L && running) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = src.read(buffer, 0, toRead)
                if (read < 0) break
                if (read > 0) {
                    dst.write(buffer, 0, read)
                    remaining -= read.toLong()
                }
            }
            dst.flush()
        }

        private fun readHeaders(input: InputStream): List<HttpHeader>? {
            val headers = ArrayList<HttpHeader>(16)
            while (headers.size < MAX_HEADER_LINES) {
                val line = readHeaderLine(input) ?: return null
                if (line.isEmpty()) return headers
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val name = line.substring(0, colon).trim()
                    val value = line.substring(colon + 1).trim()
                    if (name.isNotEmpty()) {
                        val _ = headers.add(HttpHeader(name, value))
                    }
                }
            }
            return null
        }

        private fun readHeaderLine(input: InputStream): String? {
            val buffer = ByteArrayOutputStream(128)
            var sawByte = false
            while (buffer.size() < MAX_HEADER_LINE_BYTES) {
                val next = input.read()
                if (next < 0) return if (sawByte) String(buffer.toByteArray(), Charsets.ISO_8859_1) else null
                sawByte = true
                if (next == '\n'.code) break
                if (next != '\r'.code) buffer.write(next)
            }
            return String(buffer.toByteArray(), Charsets.ISO_8859_1)
        }

        private fun writeAscii(output: OutputStream, response: String) {
            output.write(response.toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }

        override fun close() {
            running = false
            val _ = runCatching { serverSocket.close() }
            val open = activeSockets.toList()
            activeSockets.clear()
            for (socket in open) {
                val _ = runCatching { socket.close() }
            }
            val _ = executor.shutdownNow()
        }
    }

    internal companion object {
        private const val DOH_CACHE_BYTES = 5L * 1024L * 1024L
        private const val BOOTSTRAP_TIMEOUT_SECONDS = 5L
        private const val PROXY_BACKLOG = 128
        private const val CONNECT_TIMEOUT_MS = 5_000
        internal const val DEFAULT_HEADER_READ_TIMEOUT_MS = 15_000
        private const val RELAY_DRAIN_MS = 2_000L
        private const val BUFFER_SIZE = 8 * 1024
        private const val MAX_HEADER_LINE_BYTES = 16 * 1024
        private const val MAX_HEADER_LINES = 128
        private const val DEFAULT_HTTP_PORT = 80
        private const val DEFAULT_HTTPS_PORT = 443

        private const val CONNECT_ESTABLISHED_RESPONSE = "HTTP/1.1 200 Connection Established\r\n\r\n"
        private const val FORBIDDEN_RESPONSE = "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        private const val BAD_REQUEST_RESPONSE = "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        private const val BAD_GATEWAY_RESPONSE = "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

        private fun defaultBootstrapClient(context: Context): OkHttpClient = OkHttpClient.Builder()
            .cache(Cache(File(context.cacheDir, "ren-doh-cache"), DOH_CACHE_BYTES))
            .connectTimeout(BOOTSTRAP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(BOOTSTRAP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        /** Whether [url] is a well-formed `https://` DoH endpoint URL. */
        internal fun isValidDohUrl(url: String): Boolean = url.trim().toHttpUrlOrNull()?.isHttps == true

        /**
         * Distinguishes DoH transport failures (unreachable endpoint, TLS error, timeout, captive portal) from
         * authoritative DNS answers in OkHttp 4.12.0's [DnsOverHttps]:
         * - `DnsRecordCodec.decodeAnswers` throws `UnknownHostException("$hostname: NXDOMAIN")` (RCODE 3) or
         *   `UnknownHostException("$hostname: SERVFAIL")` (RCODE 2) with `cause == null`.
         * - `DnsOverHttps.throwBestFailure` throws `UnknownHostException(hostname)` with `cause == null` on empty
         *   NOERROR answers (NODATA), rethrows `UnknownHostException` directly when `failures[0]` is one (which also
         *   covers a failed DNS lookup of a custom DoH endpoint's own hostname), and wraps every other failure
         *   (connect, TLS, timeout, HTTP status, malformed response) in `UnknownHostException(hostname)` with
         *   `cause = failures[0]` and remaining failures in `suppressed`.
         * - `DnsOverHttps.lookup` throws `UnknownHostException("private hosts not resolved")` when a host has no
         *   public suffix (`resolvePrivateAddresses = false`).
         */
        internal fun shouldFallbackToSystemDns(hostname: String, error: UnknownHostException): Boolean {
            if (isDefinitiveNxdomain(hostname, error)) return false
            if (error.suppressed.any { isDefinitiveNxdomain(hostname, it) }) return false
            if (error.cause != null) return true
            val message = error.message ?: return false
            if (message == hostname || message == "$hostname: SERVFAIL" || message == "public hosts not resolved") {
                return false
            }
            return true
        }

        private fun isDefinitiveNxdomain(hostname: String, error: Throwable): Boolean =
            error is UnknownHostException && error.cause == null && error.message == "$hostname: NXDOMAIN"

        private fun parseHostPort(authority: String, defaultPort: Int): HostPort? {
            val trimmed = authority.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith('[')) {
                val closeBracket = trimmed.indexOf(']')
                if (closeBracket <= 1) return null
                val ipv6 = trimmed.substring(1, closeBracket)
                val rest = trimmed.substring(closeBracket + 1)
                val port = when {
                    rest.isEmpty() -> defaultPort
                    rest.startsWith(':') -> rest.substring(1).toIntOrNull() ?: return null
                    else -> return null
                }
                if (port !in 1..65535) return null
                return HostPort(ipv6, port)
            }
            val colon = trimmed.lastIndexOf(':')
            if (colon > 0 && trimmed.indexOf(':') == colon) {
                val host = trimmed.substring(0, colon).trim()
                val port = trimmed.substring(colon + 1).toIntOrNull() ?: return null
                if (host.isEmpty() || port !in 1..65535) return null
                return HostPort(host, port)
            }
            if (defaultPort !in 1..65535) return null
            return HostPort(trimmed, defaultPort)
        }

        private fun resolvePlainTarget(
            rawTarget: String,
            headers: List<HttpHeader>,
        ): PlainTarget? {
            if (rawTarget.startsWith("http://", ignoreCase = true)) {
                val afterScheme = rawTarget.substring(7)
                val splitIdx = firstPathOrQueryOrFragment(afterScheme)
                val authorityPart = (if (splitIdx >= 0) afterScheme.substring(0, splitIdx) else afterScheme)
                    .substringAfterLast('@')
                val hostPort = parseHostPort(authorityPart, DEFAULT_HTTP_PORT) ?: return null
                val rawRest = if (splitIdx >= 0) afterScheme.substring(splitIdx).substringBefore('#') else "/"
                val originTarget = when {
                    rawRest.isEmpty() -> "/"
                    rawRest.startsWith('?') -> "/$rawRest"
                    else -> rawRest
                }
                return PlainTarget(hostPort.host, hostPort.port, originTarget)
            }
            if (rawTarget.startsWith('/')) {
                val hostHeader = headers.firstOrNull { it.name.equals("Host", ignoreCase = true) }?.value?.trim()
                    ?: return null
                val hostPort = parseHostPort(hostHeader, DEFAULT_HTTP_PORT) ?: return null
                val originTarget = rawTarget.substringBefore('#').ifEmpty { "/" }
                return PlainTarget(hostPort.host, hostPort.port, originTarget)
            }
            return null
        }

        private fun firstPathOrQueryOrFragment(value: String): Int {
            for (index in value.indices) {
                val ch = value[index]
                if (ch == '/' || ch == '?' || ch == '#') return index
            }
            return -1
        }

        private fun formatHostHeader(host: String, port: Int?): String {
            val formattedHost = if (':' in host && !host.startsWith('[')) "[$host]" else host
            return if (port != null) "$formattedHost:$port" else formattedHost
        }
    }
}
