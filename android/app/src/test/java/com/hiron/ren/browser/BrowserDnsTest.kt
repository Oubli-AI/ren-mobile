package com.hiron.ren.browser

import android.net.Uri
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowserDnsTest {
    @Test
    fun isBlockedHostMatchesTrackersAndSparesCdnsAndLocalHosts() {
        assertTrue(ContentBlocker.blockedDomains.size >= 100)

        assertTrue(ContentBlocker.isBlockedHost("doubleclick.net"))
        assertTrue(ContentBlocker.isBlockedHost("a.b.doubleclick.net"))
        assertTrue(ContentBlocker.isBlockedHost("ADS.DoubleClick.NET."))
        assertTrue(ContentBlocker.isBlockedHost("pagead2.googlesyndication.com"))
        assertTrue(ContentBlocker.isBlockedHost("www.google-analytics.com"))
        assertTrue(ContentBlocker.isBlockedHost("connect.facebook.net"))
        assertTrue(ContentBlocker.isBlockedHost("analytics.tiktok.com"))
        assertTrue(ContentBlocker.isBlockedHost("sub.analytics.tiktok.com"))
        assertTrue(ContentBlocker.isBlockedHost("unityads.unity3d.com"))
        assertTrue(ContentBlocker.isBlockedHost("s0.2mdn.net"))

        listOf(
            "cloudflare.com",
            "cdnjs.cloudflare.com",
            "gstatic.com",
            "fonts.gstatic.com",
            "googleapis.com",
            "jsdelivr.net",
            "unpkg.com",
            "cloudfront.net",
            "d111111abcdef8.cloudfront.net",
            "ytimg.com",
            "i.ytimg.com",
            "twimg.com",
            "pbs.twimg.com",
            "githubassets.com",
            "tiktok.com",
            "www.tiktok.com",
            "unity3d.com",
            "notdoubleclick.net",
            "appassets.androidplatform.net",
            "localhost",
            "dev.localhost",
            "nas.local",
            "127.0.0.1",
            "192.168.1.1",
            "::1",
            "[::1]",
        ).forEach { host ->
            assertFalse("Expected $host not to be blocked", ContentBlocker.isBlockedHost(host))
        }
    }

    @Test
    fun shouldBlockRequestEnforcesThirdPartyAllowlistAndAppAssetsRules() {
        val trackerScript = Uri.parse("https://securepubads.g.doubleclick.net/tag/js/gpt.js")
        val newsPage = "https://www.nytimes.com/2026/world/story.html"

        assertTrue(ContentBlocker.shouldBlockRequest(trackerScript, newsPage, shieldsEnabled = true))
        assertFalse(ContentBlocker.shouldBlockRequest(trackerScript, newsPage, shieldsEnabled = false))

        assertFalse(
            ContentBlocker.shouldBlockRequest(
                trackerScript,
                newsPage,
                shieldsEnabled = true,
                disabledHosts = setOf("NYTimes.com."),
            ),
        )
        assertTrue(ContentBlocker.isHostAllowlisted("www.nytimes.com.", setOf("nytimes.com")))
        assertFalse(ContentBlocker.isHostAllowlisted("www.theguardian.com", setOf("nytimes.com")))

        // First-party subresources on the same registrable domain are never blocked.
        assertFalse(
            ContentBlocker.shouldBlockRequest(
                Uri.parse("https://analytics.tiktok.com/i18n/pixel/events.js"),
                "https://www.tiktok.com/@ren",
                shieldsEnabled = true,
            ),
        )
        assertTrue(ContentBlocker.sameSite("stats.bbc.co.uk", "www.bbc.co.uk"))
        assertFalse(ContentBlocker.sameSite("bbc.co.uk", "itv.co.uk"))
        assertTrue(ContentBlocker.sameSite("cdn.shop.com.au", "www.shop.com.au"))
        assertTrue(ContentBlocker.sameSite("img.portal.co.jp", "portal.co.jp"))
        assertTrue(ContentBlocker.sameSite("donate.charity.org.uk", "www.charity.org.uk"))

        // Bundled app assets are never blocked.
        assertFalse(
            ContentBlocker.shouldBlockRequest(
                Uri.parse("https://appassets.androidplatform.net/assets/ren/web/search/index.html"),
                newsPage,
                shieldsEnabled = true,
            ),
        )

        val empty = ContentBlocker.emptyResponse()
        assertEquals(204, empty.statusCode)
        assertEquals("No Content", empty.reasonPhrase)
        assertEquals("text/plain", empty.mimeType)
        assertEquals("utf-8", empty.encoding)
        assertEquals("*", empty.responseHeaders["Access-Control-Allow-Origin"])
        assertEquals(0, empty.data.readBytes().size)
    }

    @Test
    fun stripTrackingParametersRemovesTrackersAndPreservesCleanParamsAndHash() {
        val dirty = "https://example.com/articles/42?q=best%20ramen&utm_source=newsletter&sort=top+rated" +
            "&utm_medium=email&utm_campaign=launch&fbclid=IwAR123&gclid=Cj0KC&msclkid=99&keep=%2Fhome#section-2"
        assertEquals(
            "https://example.com/articles/42?q=best%20ramen&sort=top+rated&keep=%2Fhome#section-2",
            ContentBlocker.stripTrackingParameters(dirty),
        )

        assertEquals(
            "https://example.com/landing#hero",
            ContentBlocker.stripTrackingParameters(
                "https://example.com/landing?utm_id=1&dclid=2&gbraid=3&wbraid=4&mc_eid=5&igshid=6" +
                    "&twclid=7&yclid=8&_hsenc=9&_hsmi=10&mkt_tok=11&utm_term=a&utm_content=b#hero",
            ),
        )

        val clean = "https://example.com/search?q=a%20b&page=2#results"
        assertEquals(clean, ContentBlocker.stripTrackingParameters(clean))

        val internalUrl = "ren://search?q=hello&utm_source=keep"
        assertEquals(internalUrl, ContentBlocker.stripTrackingParameters(internalUrl))
    }

    @Test
    fun dnsModeAndDohValidationFollowSpec() {
        assertEquals(DnsMode.Cloudflare, DnsMode.Default)
        assertEquals(DnsMode.Cloudflare, DnsMode.of("cloudflare"))
        assertEquals(DnsMode.Quad9, DnsMode.of("quad9"))
        assertEquals(DnsMode.AdGuard, DnsMode.of("adguard"))
        assertEquals(DnsMode.System, DnsMode.of("system"))
        assertEquals(DnsMode.Custom, DnsMode.of("custom"))
        assertNull(DnsMode.of("invalid"))
        assertNull(DnsMode.of(null))

        assertTrue(BrowserDns.isValidDohUrl("https://cloudflare-dns.com/dns-query"))
        assertTrue(BrowserDns.isValidDohUrl("  https://dns.quad9.net/dns-query  "))
        assertFalse(BrowserDns.isValidDohUrl("http://cloudflare-dns.com/dns-query"))
        assertFalse(BrowserDns.isValidDohUrl(""))
        assertFalse(BrowserDns.isValidDohUrl("not a url"))

        BrowserDns(RuntimeEnvironment.getApplication()).use { dns ->
            assertEquals(DnsMode.Cloudflare, dns.mode)
            assertEquals("https://cloudflare-dns.com/dns-query", dns.activeEndpoint)
            assertNotNull(dns.proxyPort)

            dns.configure(DnsMode.System, shieldsDnsBlock = false)
            assertEquals(DnsMode.System, dns.mode)
            assertNull(dns.activeEndpoint)
            assertNull(dns.proxyPort)

            dns.configure(DnsMode.Custom, customUrl = "https://dns.google/dns-query", shieldsDnsBlock = true)
            assertEquals(DnsMode.Custom, dns.mode)
            assertEquals("https://dns.google/dns-query", dns.activeEndpoint)
            assertNotNull(dns.proxyPort)

            val loopback = dns.lookup("127.0.0.1")
            assertTrue(loopback.any { it.hostAddress == "127.0.0.1" })
        }
    }

    @Test
    fun localDohProxyRelaysConnectTunnelRewritesPlainHttpAndBlocksAds() {
        BrowserDns(
            RuntimeEnvironment.getApplication(),
            initialMode = DnsMode.System,
            initialShieldsDnsBlock = true,
        ).use { dns ->
            val proxyPort = dns.proxyPort
            assertNotNull(proxyPort)
            val port = proxyPort!!

            // 1. CONNECT tunnel relays bidirectional bytes end-to-end.
            ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).use { upstreamServer ->
                upstreamServer.soTimeout = 5_000
                val upstreamReceived = AtomicReference<String>()
                val done = CountDownLatch(1)
                val serverThread = Thread {
                    upstreamServer.accept().use { accepted ->
                        accepted.soTimeout = 5_000
                        val reader = BufferedReader(InputStreamReader(accepted.getInputStream(), Charsets.UTF_8))
                        val line = reader.readLine()
                        upstreamReceived.set(line)
                        val out = accepted.getOutputStream()
                        out.write("pong:$line\n".toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    done.countDown()
                }.apply {
                    isDaemon = true
                    start()
                }

                Socket().use { client ->
                    client.soTimeout = 5_000
                    client.connect(InetSocketAddress("127.0.0.1", port), 5_000)
                    val out = client.getOutputStream()
                    val req = "CONNECT 127.0.0.1:${upstreamServer.localPort} HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:${upstreamServer.localPort}\r\n\r\n"
                    out.write(req.toByteArray(Charsets.ISO_8859_1))
                    out.flush()

                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                    val statusLine = reader.readLine()
                    assertEquals("HTTP/1.1 200 Connection Established", statusLine)
                    while (true) {
                        val header = reader.readLine()
                        if (header.isNullOrEmpty()) break
                    }

                    out.write("hello-over-connect\n".toByteArray(Charsets.UTF_8))
                    out.flush()
                    val echoed = reader.readLine()
                    assertEquals("pong:hello-over-connect", echoed)
                }

                assertTrue(done.await(5, TimeUnit.SECONDS))
                serverThread.join(5_000)
                assertEquals("hello-over-connect", upstreamReceived.get())
            }

            // 2. Plain HTTP proxy request strips Proxy-Connection and rewrites absolute-URI to origin-form.
            ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).use { upstreamServer ->
                upstreamServer.soTimeout = 5_000
                val capturedRequestLine = AtomicReference<String>()
                val capturedHeaders = ArrayList<String>()
                val done = CountDownLatch(1)
                val serverThread = Thread {
                    upstreamServer.accept().use { accepted ->
                        accepted.soTimeout = 5_000
                        val reader = BufferedReader(InputStreamReader(accepted.getInputStream(), Charsets.ISO_8859_1))
                        capturedRequestLine.set(reader.readLine())
                        while (true) {
                            val header = reader.readLine()
                            if (header.isNullOrEmpty()) break
                            capturedHeaders.add(header)
                        }
                        val body = "plain-ok"
                        val resp = "HTTP/1.1 200 OK\r\n" +
                            "Content-Length: ${body.length}\r\n" +
                            "Connection: close\r\n\r\n$body"
                        val out = accepted.getOutputStream()
                        out.write(resp.toByteArray(Charsets.ISO_8859_1))
                        out.flush()
                    }
                    done.countDown()
                }.apply {
                    isDaemon = true
                    start()
                }

                Socket().use { client ->
                    client.soTimeout = 5_000
                    client.connect(InetSocketAddress("127.0.0.1", port), 5_000)
                    val upstreamPort = upstreamServer.localPort
                    val rawReq = "GET http://127.0.0.1:$upstreamPort/hello?a=1 HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:$upstreamPort\r\n" +
                        "Proxy-Connection: keep-alive\r\n" +
                        "Proxy-Authorization: Basic dXNlcjpwYXNz\r\n" +
                        "X-Ren-Check: yes\r\n\r\n"
                    val out = client.getOutputStream()
                    out.write(rawReq.toByteArray(Charsets.ISO_8859_1))
                    out.flush()

                    val fullResponse = String(client.getInputStream().readBytes(), Charsets.ISO_8859_1)
                    assertTrue(fullResponse.startsWith("HTTP/1.1 200 OK\r\n"))
                    assertTrue(fullResponse.endsWith("\r\n\r\nplain-ok"))
                }

                assertTrue(done.await(5, TimeUnit.SECONDS))
                serverThread.join(5_000)
                assertEquals("GET /hello?a=1 HTTP/1.1", capturedRequestLine.get())
                assertFalse(capturedHeaders.any { it.startsWith("Proxy-Connection:", ignoreCase = true) })
                assertFalse(capturedHeaders.any { it.startsWith("Proxy-Authorization:", ignoreCase = true) })
                assertTrue(capturedHeaders.any { it.equals("Connection: close", ignoreCase = true) })
                assertTrue(capturedHeaders.any { it.equals("X-Ren-Check: yes", ignoreCase = true) })
            }

            // 3. CONNECT to a blocked tracker host returns 403 Forbidden immediately.
            Socket().use { client ->
                client.soTimeout = 5_000
                client.connect(InetSocketAddress("127.0.0.1", port), 5_000)
                val out = client.getOutputStream()
                out.write(
                    "CONNECT ads.doubleclick.net:443 HTTP/1.1\r\nHost: ads.doubleclick.net:443\r\n\r\n"
                        .toByteArray(Charsets.ISO_8859_1),
                )
                out.flush()
                val response = String(client.getInputStream().readBytes(), Charsets.ISO_8859_1)
                assertTrue("Expected 403 response, got: $response", response.startsWith("HTTP/1.1 403 Forbidden\r\n"))
            }
        }
    }

    @Test
    fun connectTunnelSurvivesQuietPausesServerPushAndHalfCloseCleanly() {
        BrowserDns(
            RuntimeEnvironment.getApplication(),
            initialMode = DnsMode.System,
            initialShieldsDnsBlock = true,
            headerReadTimeoutMs = 100,
        ).use { dns ->
            val port = requireNotNull(dns.proxyPort)
            ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).use { upstreamServer ->
                upstreamServer.soTimeout = 5_000
                val upstreamClosed = CountDownLatch(1)
                val serverThread = Thread {
                    upstreamServer.accept().use { accepted ->
                        accepted.soTimeout = 5_000
                        val reader = BufferedReader(InputStreamReader(accepted.getInputStream(), Charsets.UTF_8))
                        val out = accepted.getOutputStream()

                        // Echo after initial quiet pause longer than headerReadTimeoutMs (100 ms).
                        val firstLine = reader.readLine()
                        out.write("ack:$firstLine\n".toByteArray(Charsets.UTF_8))
                        out.flush()

                        // Push several server-only chunks while the client sends nothing, each past headerReadTimeoutMs.
                        for (index in 0 until 3) {
                            Thread.sleep(150L)
                            out.write("event:$index\n".toByteArray(Charsets.UTF_8))
                            out.flush()
                        }

                        // Wait for client half-close (EOF), then send a final line before closing.
                        assertNull(reader.readLine())
                        out.write("done-after-half-close\n".toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    upstreamClosed.countDown()
                }.apply {
                    isDaemon = true
                    start()
                }

                Socket().use { client ->
                    client.soTimeout = 5_000
                    client.connect(InetSocketAddress("127.0.0.1", port), 5_000)
                    val out = client.getOutputStream()
                    val req = "CONNECT 127.0.0.1:${upstreamServer.localPort} HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:${upstreamServer.localPort}\r\n\r\n"
                    out.write(req.toByteArray(Charsets.ISO_8859_1))
                    out.flush()

                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                    assertEquals("HTTP/1.1 200 Connection Established", reader.readLine())
                    while (!reader.readLine().isNullOrEmpty()) {
                        // Drain CONNECT response headers.
                    }

                    // Quiet pause > 3x headerReadTimeoutMs (100 ms) with zero traffic in either direction.
                    Thread.sleep(320L)
                    out.write("still-alive\n".toByteArray(Charsets.UTF_8))
                    out.flush()
                    assertEquals("ack:still-alive", reader.readLine())

                    // Receive server-push-only chunks while sending nothing upstream (~450 ms > 4x headerReadTimeoutMs).
                    val pushed = listOf(reader.readLine(), reader.readLine(), reader.readLine())
                    assertEquals(listOf("event:0", "event:1", "event:2"), pushed)

                    // Half-close client output; upstream finishes sending and closes cleanly.
                    client.shutdownOutput()
                    assertEquals("done-after-half-close", reader.readLine())
                    assertNull(reader.readLine())
                }

                assertTrue(upstreamClosed.await(5, TimeUnit.SECONDS))
                serverThread.join(5_000)

                // Wait briefly for proxy worker finally blocks to remove tracked sockets.
                val deadlineMs = System.nanoTime() / 1_000_000L + 2_000L
                while (dns.activeSocketCount > 0 && System.nanoTime() / 1_000_000L < deadlineMs) {
                    Thread.sleep(20L)
                }
                assertEquals(0, dns.activeSocketCount)
            }
        }
    }

    @Test
    fun plainHttpRelaySurvivesQuietPausePastHeaderTimeout() {
        BrowserDns(
            RuntimeEnvironment.getApplication(),
            initialMode = DnsMode.System,
            initialShieldsDnsBlock = true,
            headerReadTimeoutMs = 100,
        ).use { dns ->
            val port = requireNotNull(dns.proxyPort)
            ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).use { upstreamServer ->
                upstreamServer.soTimeout = 5_000
                val done = CountDownLatch(1)
                val serverThread = Thread {
                    upstreamServer.accept().use { accepted ->
                        accepted.soTimeout = 5_000
                        val reader = BufferedReader(InputStreamReader(accepted.getInputStream(), Charsets.ISO_8859_1))
                        while (!reader.readLine().isNullOrEmpty()) {
                            // Consume request headers.
                        }
                        // Pause > 3x headerReadTimeoutMs before streaming response (simulates slow SSE/plain HTTP).
                        Thread.sleep(320L)
                        val body = "slow-stream-ok"
                        val resp = "HTTP/1.1 200 OK\r\n" +
                            "Content-Length: ${body.length}\r\n" +
                            "Connection: close\r\n\r\n$body"
                        val out = accepted.getOutputStream()
                        out.write(resp.toByteArray(Charsets.ISO_8859_1))
                        out.flush()
                    }
                    done.countDown()
                }.apply {
                    isDaemon = true
                    start()
                }

                Socket().use { client ->
                    client.soTimeout = 5_000
                    client.connect(InetSocketAddress("127.0.0.1", port), 5_000)
                    val upstreamPort = upstreamServer.localPort
                    val rawReq = "GET http://127.0.0.1:$upstreamPort/sse HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:$upstreamPort\r\n\r\n"
                    val out = client.getOutputStream()
                    out.write(rawReq.toByteArray(Charsets.ISO_8859_1))
                    out.flush()

                    val response = String(client.getInputStream().readBytes(), Charsets.ISO_8859_1)
                    assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
                    assertTrue(response.endsWith("\r\n\r\nslow-stream-ok"))
                }

                assertTrue(done.await(5, TimeUnit.SECONDS))
                serverThread.join(5_000)
            }
        }
    }

    @Test
    fun closingProxyTearsDownActiveRelaySocketsCleanly() {
        val dns = BrowserDns(
            RuntimeEnvironment.getApplication(),
            initialMode = DnsMode.System,
            initialShieldsDnsBlock = true,
            headerReadTimeoutMs = 2_000,
        )
        val port = requireNotNull(dns.proxyPort)
        ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).use { upstreamServer ->
            upstreamServer.soTimeout = 5_000
            val upstreamConnected = CountDownLatch(1)
            val upstreamDisconnected = CountDownLatch(1)
            val serverThread = Thread {
                upstreamServer.accept().use { accepted ->
                    accepted.soTimeout = 5_000
                    upstreamConnected.countDown()
                    try {
                        while (accepted.getInputStream().read() != -1) {
                            // Wait for proxy close to tear down the upstream socket.
                        }
                    } catch (_: IOException) {
                        // Expected on teardown.
                    }
                }
                upstreamDisconnected.countDown()
            }.apply {
                isDaemon = true
                start()
            }

            Socket().use { client ->
                client.soTimeout = 5_000
                client.connect(InetSocketAddress("127.0.0.1", port), 5_000)
                val out = client.getOutputStream()
                val req = "CONNECT 127.0.0.1:${upstreamServer.localPort} HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:${upstreamServer.localPort}\r\n\r\n"
                out.write(req.toByteArray(Charsets.ISO_8859_1))
                out.flush()

                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                assertEquals("HTTP/1.1 200 Connection Established", reader.readLine())
                while (!reader.readLine().isNullOrEmpty()) {
                    // Drain CONNECT response headers.
                }
                assertTrue(upstreamConnected.await(5, TimeUnit.SECONDS))
                assertEquals(2, dns.activeSocketCount)

                // Closing the proxy tears down both tracked sockets even with unbounded relay soTimeout.
                dns.close()
                assertEquals(0, dns.activeSocketCount)

                val clientClosed = try {
                    reader.readLine() == null
                } catch (_: IOException) {
                    true
                }
                assertTrue("Expected client socket to see teardown on proxy.close()", clientClosed)
            }

            assertTrue("Expected upstream socket to see teardown on proxy.close()", upstreamDisconnected.await(5, TimeUnit.SECONDS))
            serverThread.join(5_000)
        }
    }

    @Test
    fun headerReadTimeoutClosesSilentClientBeforeRelay() {
        BrowserDns(
            RuntimeEnvironment.getApplication(),
            initialMode = DnsMode.System,
            initialShieldsDnsBlock = true,
            headerReadTimeoutMs = 120,
        ).use { dns ->
            val port = requireNotNull(dns.proxyPort)
            Socket().use { client ->
                client.soTimeout = 3_000
                client.connect(InetSocketAddress("127.0.0.1", port), 3_000)
                val startedMs = System.nanoTime() / 1_000_000L
                val eof = try {
                    client.getInputStream().read() == -1
                } catch (_: IOException) {
                    true
                }
                val elapsedMs = (System.nanoTime() / 1_000_000L) - startedMs
                assertTrue("Expected silent header connection to close", eof)
                assertTrue("Header timeout fired too late (${elapsedMs}ms)", elapsedMs < 2_000L)
            }
        }
    }

    @Test
    fun dohTransportFailuresFallBackToSystemDns() {
        val fallbackAddr = InetAddress.getByName("93.184.216.34")
        val lookedUp = CopyOnWriteArrayList<String>()
        val recordingSystemDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val _ = lookedUp.add(hostname)
                return listOf(fallbackAddr)
            }
        }

        // 1. Real TCP connection refusal against an unreachable loopback DoH port.
        val closedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        BrowserDns(
            RuntimeEnvironment.getApplication(),
            initialMode = DnsMode.Custom,
            initialCustomUrl = "https://127.0.0.1:$closedPort/dns-query",
            initialShieldsDnsBlock = false,
            systemDns = recordingSystemDns,
        ).use { dns ->
            val resolved = dns.lookup("example.com")
            assertEquals(listOf(fallbackAddr), resolved)
            assertEquals(listOf("example.com"), lookedUp.toList())
        }

        // 2. TLS handshake failure (e.g., captive portal intercepting 1.1.1.1:443).
        lookedUp.clear()
        val tlsFailClient = OkHttpClient.Builder()
            .addInterceptor { throw SSLHandshakeException("captive portal certificate mismatch") }
            .build()
        BrowserDns(
            RuntimeEnvironment.getApplication(),
            initialMode = DnsMode.Cloudflare,
            initialShieldsDnsBlock = false,
            systemDns = recordingSystemDns,
            bootstrapClient = tlsFailClient,
        ).use { dns ->
            assertEquals(listOf(fallbackAddr), dns.lookup("example.com"))
            assertEquals(listOf("example.com"), lookedUp.toList())
        }

        // 3. DoH read timeout.
        lookedUp.clear()
        val timeoutClient = OkHttpClient.Builder()
            .addInterceptor { throw SocketTimeoutException("DoH endpoint timed out") }
            .build()
        BrowserDns(
            RuntimeEnvironment.getApplication(),
            initialMode = DnsMode.Cloudflare,
            initialShieldsDnsBlock = false,
            systemDns = recordingSystemDns,
            bootstrapClient = timeoutClient,
        ).use { dns ->
            assertEquals(listOf(fallbackAddr), dns.lookup("example.com"))
            assertEquals(listOf("example.com"), lookedUp.toList())
        }

        // 4. Custom DoH endpoint whose own hostname fails DNS resolution (unwrapped UnknownHostException).
        lookedUp.clear()
        val unresolvableEndpointClient = OkHttpClient.Builder()
            .addInterceptor { throw UnknownHostException("Unable to resolve host \"doh.offline.example\"") }
            .build()
        BrowserDns(
            RuntimeEnvironment.getApplication(),
            initialMode = DnsMode.Custom,
            initialCustomUrl = "https://doh.offline.example/dns-query",
            initialShieldsDnsBlock = false,
            systemDns = recordingSystemDns,
            bootstrapClient = unresolvableEndpointClient,
        ).use { dns ->
            assertEquals(listOf(fallbackAddr), dns.lookup("example.com"))
            assertEquals(listOf("example.com"), lookedUp.toList())
        }
    }

    @Test
    fun dohNxdomainNodataAndServfailDoNotFallBackToSystemDns() {
        val fallbackAddr = InetAddress.getByName("93.184.216.34")
        val lookedUp = CopyOnWriteArrayList<String>()
        val recordingSystemDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val _ = lookedUp.add(hostname)
                return listOf(fallbackAddr)
            }
        }
        val dnsMessageType = "application/dns-message".toMediaType()

        // 1. Definitive NXDOMAIN (RFC 1035 RCODE 3: flags 0x8183) decoded by real OkHttp DnsRecordCodec.
        val nxdomainPacket = byteArrayOf(
            0x00, 0x00, // query id
            0x81.toByte(), 0x83.toByte(), // QR=1, RD=1, RA=1, RCODE=3 (NXDOMAIN)
            0x00, 0x00, // QDCOUNT=0
            0x00, 0x00, // ANCOUNT=0
            0x00, 0x00, // NSCOUNT=0
            0x00, 0x00, // ARCOUNT=0
        )
        val nxdomainClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_2)
                    .code(200)
                    .message("OK")
                    .body(nxdomainPacket.toResponseBody(dnsMessageType))
                    .build()
            }
            .build()
        BrowserDns(
            RuntimeEnvironment.getApplication(),
            initialMode = DnsMode.AdGuard,
            initialShieldsDnsBlock = false,
            systemDns = recordingSystemDns,
            bootstrapClient = nxdomainClient,
        ).use { dns ->
            val thrown = assertThrows(UnknownHostException::class.java) {
                val _ = dns.lookup("blocked-tracker.example.com")
            }
            assertEquals("blocked-tracker.example.com: NXDOMAIN", thrown.message)
            assertTrue("NXDOMAIN must never fall back to system DNS", lookedUp.isEmpty())
        }

        // 2. Dual-stack race where one family timed out and the other returned NXDOMAIN in suppressed.
        val raced = UnknownHostException("blocked-tracker.example.com").apply {
            val _ = initCause(SocketTimeoutException("A timed out"))
            addSuppressed(UnknownHostException("blocked-tracker.example.com: NXDOMAIN"))
        }
        assertFalse(BrowserDns.shouldFallbackToSystemDns("blocked-tracker.example.com", raced))

        // 3. Definitive NOERROR with zero A/AAAA answers (NODATA: flags 0x8180) must also not fall back.
        val nodataPacket = byteArrayOf(
            0x00, 0x00,
            0x81.toByte(), 0x80.toByte(), // QR=1, RD=1, RA=1, RCODE=0 (NOERROR)
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,
        )
        val nodataClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_2)
                    .code(200)
                    .message("OK")
                    .body(nodataPacket.toResponseBody(dnsMessageType))
                    .build()
            }
            .build()
        BrowserDns(
            RuntimeEnvironment.getApplication(),
            initialMode = DnsMode.AdGuard,
            initialShieldsDnsBlock = false,
            systemDns = recordingSystemDns,
            bootstrapClient = nodataClient,
        ).use { dns ->
            val thrown = assertThrows(UnknownHostException::class.java) {
                val _ = dns.lookup("nodata.example.com")
            }
            assertEquals("nodata.example.com", thrown.message)
            assertTrue("NODATA must not fall back to system DNS", lookedUp.isEmpty())
        }

        // 4. Definitive SERVFAIL (RFC 1035 RCODE 2: flags 0x8182) decoded by real OkHttp DnsRecordCodec.
        val servfailPacket = byteArrayOf(
            0x00, 0x00,
            0x81.toByte(), 0x82.toByte(), // QR=1, RD=1, RA=1, RCODE=2 (SERVFAIL)
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,
        )
        val servfailClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_2)
                    .code(200)
                    .message("OK")
                    .body(servfailPacket.toResponseBody(dnsMessageType))
                    .build()
            }
            .build()
        BrowserDns(
            RuntimeEnvironment.getApplication(),
            initialMode = DnsMode.AdGuard,
            initialShieldsDnsBlock = false,
            systemDns = recordingSystemDns,
            bootstrapClient = servfailClient,
        ).use { dns ->
            val thrown = assertThrows(UnknownHostException::class.java) {
                val _ = dns.lookup("broken-dnssec.example.com")
            }
            assertEquals("broken-dnssec.example.com: SERVFAIL", thrown.message)
            assertTrue("SERVFAIL must not fall back to system DNS", lookedUp.isEmpty())
        }
    }
}
