package com.openminis.app.network

import android.webkit.HttpAuthHandler
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.openminis.app.logging.AppLogger
import java.util.concurrent.Executor

/**
 * [T-net-android-phase3] The "Browser" scope for the app's WebViews, via
 * androidx.webkit ProxyController - the counterpart of iOS setting
 * WKWebsiteDataStore.proxyConfigurations.
 *
 * The override is process-wide (every WebView in the app), as iOS's shared
 * data store is. It points at the local entry's HTTP front: Chromium cannot
 * authenticate to a SOCKS proxy, but answers an HTTP proxy's 407 through
 * WebViewClient.onReceivedHttpAuthRequest, which every WebViewClient in the
 * app forwards to [answerAuth]. Loopback is never proxied.
 *
 * Zero regression: ProxyController is not touched at all unless a route
 * needs it, and the override is cleared the moment it no longer does
 * (route back to direct, entry down, Restore Direct Connections). A WebView
 * without PROXY_OVERRIDE support stays direct and Settings says so.
 */
object WebViewNetworkProxy {

    private const val TAG = "Network"
    /** realm the entry's HTTP front sends in its challenge (nethub/httpentry.go). */
    private const val REALM = "Minis"

    private val direct = Executor { it.run() }

    @Volatile
    private var applied: String? = null

    /** Credentials for the current override, read by [answerAuth]. */
    @Volatile
    private var credentials: Pair<String, String>? = null

    fun isSupported(): Boolean =
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) }.getOrDefault(false)

    /** Called on every runtime publish (NetworkHub registers it). */
    fun apply(snapshot: NetworkRuntime.Snapshot) {
        // [fix26] A Google-auth borrow owns the override for the duration of
        // an OAuth flow; a runtime publish must not yank it mid-login (that
        // would re-break accounts.google.com while the user is on the consent
        // screen). endGoogleAuthOverride() releases it and re-applies.
        if (googleAuthBorrow) return
        val ep = snapshot.endpoint(NetworkScope.BROWSER.tag)
        val hp = ep?.httpPort
        val want = hp?.let { "127.0.0.1:$it" }
        if (want == applied) {
            credentials = if (ep != null && hp != null) ep.tag to ep.password else null
            return
        }
        if (!isSupported()) {
            if (want != null) AppLogger.warning(TAG, "[Net][Browser] this WebView has no PROXY_OVERRIDE: the browser stays direct")
            return
        }
        try {
            if (want == null) {
                credentials = null
                ProxyController.getInstance().clearProxyOverride(direct) {}
                AppLogger.info(TAG, "[Net][Browser] WebView -> direct (override cleared)")
            } else {
                credentials = ep!!.tag to ep.password
                val config = ProxyConfig.Builder()
                    .addProxyRule("http://$want")
                    .addBypassRule("localhost")
                    .addBypassRule("127.0.0.1")
                    .addBypassRule("[::1]")
                    .build()
                ProxyController.getInstance().setProxyOverride(config, direct) {}
                AppLogger.info(TAG, "[Net][Browser] WebView -> local entry $want (tag=${ep.tag})")
            }
            applied = want
        } catch (t: Throwable) {
            // Never let a WebView quirk take the app down; the browser then
            // keeps whatever it had (direct, if nothing was ever set).
            AppLogger.error(TAG, "[Net][Browser] proxy override failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * [fix26] Google sign-in rescue. accounts.google.com (and friends) die
     * with a TLS reset when the WebView dials direct, on networks where the
     * path to Google is blocked at the TLS layer. The Go-side entry is alive
     * and reaches Google (the shell proves it daily), so while a Google auth
     * flow is in progress we temporarily point the WebView at the entry's
     * HTTP front.
     *
     * Scope choice matters: the 407 username IS the routing tag, and a tag
     * routed Direct makes the entry itself dial direct — the same dead path.
     * So we take the browser scope's endpoint when it has one, and otherwise
     * borrow the first scope whose route actually leaves through the entry
     * (shell is the one the sandbox proves reaches Google). If every scope is
     * direct there is nothing to borrow; we log and stay direct.
     *
     * endGoogleAuthOverride() restores whatever apply(snapshot) last wanted.
     */
    @Volatile
    private var googleAuthBorrow: Boolean = false

    fun beginGoogleAuthOverride() {
        if (googleAuthBorrow) return
        val snap = NetworkRuntime.snapshot
        val ep = snap.endpoint(NetworkScope.BROWSER.tag)
            ?: NetworkScope.entries.firstNotNullOfOrNull { scope -> snap.endpoint(scope.tag) }
        if (ep == null || ep.httpPort == null) {
            AppLogger.info(TAG, "[Net][Browser] Google auth borrow: no entry-routed scope; staying direct")
            return
        }
        googleAuthBorrow = true
        val want = "127.0.0.1:${ep.httpPort}"
        if (want == applied) {
            // Same endpoint is already installed — but the 407 username is
            // the routing tag, so it must match the scope we just borrowed
            // from even when the address did not change.
            credentials = ep.tag to ep.password
            return
        }
        credentials = ep.tag to ep.password
        if (!isSupported()) {
            AppLogger.warning(TAG, "[Net][Browser] PROXY_OVERRIDE unsupported; Google auth stays direct")
            return
        }
        try {
            val config = ProxyConfig.Builder()
                .addProxyRule("http://$want")
                .addBypassRule("localhost")
                .addBypassRule("127.0.0.1")
                .addBypassRule("[::1]")
                .build()
            ProxyController.getInstance().setProxyOverride(config, direct) {}
            applied = want
            AppLogger.info(TAG, "[Net][Browser] Google auth borrow: WebView -> local entry $want (tag=${ep.tag})")
        } catch (t: Throwable) {
            AppLogger.error(TAG, "[Net][Browser] Google auth borrow failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun endGoogleAuthOverride() {
        if (!googleAuthBorrow) return
        googleAuthBorrow = false
        AppLogger.info(TAG, "[Net][Browser] Google auth borrow ended; restoring route")
        apply(NetworkRuntime.snapshot)
    }

    /**
     * For WebViewClient.onReceivedHttpAuthRequest: answers the local entry's
     * proxy challenge and returns true; false for any other site's auth
     * request, which the caller handles as before.
     */
    fun answerAuth(handler: HttpAuthHandler, host: String?, realm: String?, pageUrl: String?): Boolean {
        val c = credentials ?: return false
        if (realm != REALM) return false
        if (host != null && host != "127.0.0.1" && host != "localhost" && !host.startsWith("127.0.0.1:")) return false
        // Loopback pages bypass the proxy, so a challenge while one is shown
        // comes from a local server, not the entry: never hand it the password.
        val pageHost = pageUrl?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }
        if (pageHost == "127.0.0.1" || pageHost == "localhost" || pageHost == "::1") return false
        handler.proceed(c.first, c.second)
        return true
    }
}
