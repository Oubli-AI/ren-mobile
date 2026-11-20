package com.hiron.ren.browser

import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The loopback listener a social sign-in's provider redirects to, `http://127.0.0.1:<port>/auth/callback`, as ren
 * desktop's Electron keeps one (its `auth.oauthRedirect`) and ren's Supabase project allows. It answers each request
 * with a page that sends the user back to Ren, hands the callback's address to [received] on the main thread, and
 * stops after it or after [TIMEOUT_MS].
 */
internal class OAuthLoopback(private val packageName: String, private val received: (String) -> Unit) {
    private var server: ServerSocket? = null

    /** Starts listening (closing an earlier listener) and returns the redirect address. */
    fun start(): String {
        server?.close()
        val socket = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress()).apply { soTimeout = TIMEOUT_MS }
        server = socket
        val redirect = "http://127.0.0.1:${socket.localPort}$CALLBACK"
        val _ = thread(name = "ren-oauth", isDaemon = true) {
            try {
                while (!socket.isClosed) {
                    val path = socket.accept().use { client ->
                        val path = client.getInputStream().bufferedReader().readLine().orEmpty().split(' ').getOrNull(1).orEmpty()
                        client.getOutputStream().write(page().toByteArray())
                        path
                    }
                    if (path.startsWith(CALLBACK)) {
                        val address = "http://127.0.0.1:${socket.localPort}$path"
                        Handler(Looper.getMainLooper()).post { received(address) }
                        break
                    }
                }
            } catch (_: IOException) {
                // Nobody signed in within the time, or a newer sign-in took over.
            } finally {
                socket.close()
            }
        }
        return redirect
    }

    private fun page(): String {
        val back = "intent://home#Intent;package=$packageName;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;end"
        val body = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>Ren</title>" +
            "<body style='font:16px system-ui;margin:48px 24px;text-align:center'><p>Signing in to ren.</p>" +
            "<p><a href='$back' style='color:#5543FF;font-weight:600'>Return to Ren</a></p></body>"
        return "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n$body"
    }

    private companion object {
        const val CALLBACK = "/auth/callback"
        const val BACKLOG = 4

        /** As long as ren desktop's Electron keeps its listener: ten minutes. */
        const val TIMEOUT_MS = 600_000
    }
}
