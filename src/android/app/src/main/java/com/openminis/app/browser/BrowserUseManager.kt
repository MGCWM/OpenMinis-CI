package com.openminis.app.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Manages a single Android WebView for browser automation.
 * Mirrors iOS BrowserUseManager.
 */
class BrowserUseManager(
    val webView: WebView,
    profile: UserAgentProfile = UserAgentProfile.MOBILE_CHROME,
    /**
     * [T-android-minis-url-session-scope] Which chat session's sandbox a
     * `minis://` URL should resolve against, or null when unknown.
     *
     * A LAMBDA, not a value: tabs are created before `BrowserTabPool.setSession`
     * runs, so a snapshot taken at construction time would be permanently null
     * for the first tab. Reading it lazily at intercept time gets whatever the
     * pool knows when the request actually fires.
     */
    private val sessionIdProvider: () -> String? = { null },
    /** App context for the session-scoped path resolver. Null disables it. */
    private val appContext: android.content.Context? = null,
) {
    companion object {
        private const val TAG = "BrowserUseManager"
        private const val NAVIGATION_TIMEOUT_MS = 30_000L
        private const val SCREENSHOT_QUALITY = 80        // Explicit screenshot action (iOS: 0.8)
        private const val SNAPSHOT_QUALITY = 70          // Auto-snapshot after visual-change actions (iOS: 0.7)
        // [T-android-browser-observability] Bounded trails + upload caps.
        private const val MAX_LOG_ENTRIES = 200
        private const val UPLOAD_WAIT_MS = 10_000L
        // Direct File injection moves bytes through a JS string; keep it bounded.
        private const val MAX_UPLOAD_BYTES = 4L * 1024 * 1024
        private const val MAX_UPLOAD_TOTAL_BYTES = 8L * 1024 * 1024
        private const val DEFAULT_DOM_STABLE_TIMEOUT_MS = 5_000

        /**
         * [T-android-page-diff-2026-10] Actions whose result gets a before/after
         * page diff appended. "No visible change" is often the single most
         * useful signal (a click that landed on nothing). Navigate is excluded
         * (a new page differs entirely); scroll/hover/type are excluded as
         * noise.
         */
        private val PAGE_DIFF_ACTIONS = setOf(
            BrowserAction.CLICK, BrowserAction.PRESS_KEY, BrowserAction.GESTURE, BrowserAction.FILL_FORM,
        )

        /**
         * [T-android-js-error-detail] Completes execute_js's deferred when the
         * wrapped script never ran (it does not parse). The NUL keeps it apart
         * from anything a page script can resolve with.
         */
        private const val EXECUTE_JS_DID_NOT_PARSE = "\u0000minis:execute_js-did-not-parse"
        // [T-android-execjs-autolarge-2026-10] inline-script spill threshold
        private const val INLINE_SPILL_BYTES = 4096
        // [T-android-host-blocklist-2026-10] Suffix-match list. Keep it to
        // pure ad/analytics domains; anything a real page needs to function
        // does NOT belong here.
        private val HOST_BLOCKLIST_SUFFIX = setOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "google-analytics.com", "googletagmanager.com", "googletagservices.com",
            "adservice.google.com", "pagead2.googlesyndication.com",
            "facebook.net", "connect.facebook.net",
            "scorecardresearch.com", "quantserve.com", "adnxs.com",
            "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
            "hotjar.com", "fullstory.com", "amplitude.com", "mixpanel.com",
            "branch.io", "appsflyer.com", "adjust.com",
        )

        fun isBlockedHost(host: String): Boolean =
            HOST_BLOCKLIST_SUFFIX.any { host == it || host.endsWith(".$it") }

        /**
         * Cap full_page screenshot stretched viewport at 32768 px. Above this,
         * Bitmap.createBitmap risks OOM (e.g. 32768 × ~1130 px × 4 B/ARGB ≈ 144 MB
         * at desktop 1280 CSS × 2.75 density). Pages taller than the cap are
         * truncated and the metadata exposes `truncated:true` + the original
         * scrollHeight so the agent can scroll-then-stitch if it needs more.
         */
        private const val MAX_FULL_PAGE_HEIGHT_PX = 32768

        /**
         * [T-android-browser-screenshot-scroll-offset] Budget for the JS probe
         * that reads the page's scroll offset before a capture. Short on
         * purpose: the probe is a correctness refinement, and a wedged
         * renderer must not be able to stall every screenshot. On timeout the
         * capture proceeds uncompensated, i.e. the pre-fix behaviour.
         */
        private const val SCROLL_PROBE_TIMEOUT_MS = 500L

        /**
         * [T-android-browser-fullpage-repaint] How long to let the renderer
         * paint the newly exposed area after the full-page capture stretches
         * the WebView's layout box. Without it the stretch and the draw happen
         * in the same main-thread pass, so the region below the original
         * viewport can be captured before it has been rendered.
         */
        private const val FULL_PAGE_REPAINT_DELAY_MS = 120L

        /**
         * Minimal HTML used in place of `about:blank` when a fresh tab
         * needs a page refresh (e.g. `set_viewport` before any navigation).
         * `<meta name="viewport" content="width=device-width">` makes
         * `window.innerWidth` track the container we just laid out, instead
         * of Android WebView's hardcoded 980px fallback for blank pages.
         * Loaded via `loadDataWithBaseURL` so WebView accepts raw HTML
         * without URL encoding.
         */
        private const val BLANK_PAGE_HTML =
            "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width\"></head><body></body></html>"
        // [T-android-domstable-min-budget] C5: with the old 200ms floor and a
        // 200ms poll interval the loop could only ever take ONE sample
        // (first sample never matches lastSize=-1, then the delay exhausts
        // the budget) — wait_for_dom_stable failed on every page at the
        // minimum. 1000ms fits >=4 samples so the smallest budget can
        // actually observe two equal readings.
        private const val MIN_DOM_STABLE_TIMEOUT_MS = 1_000
        private const val MAX_DOM_STABLE_TIMEOUT_MS = 60_000

        @SuppressLint("SetJavaScriptEnabled")
        fun configureWebView(webView: WebView, profile: UserAgentProfile, customUA: String? = null) {
            // [T-android-browser-blank] The browser lives inside a Material3
            // ModalBottomSheet, which hosts content in its own secondary
            // window. On some OEM GPUs the WebView's hardware draw functor
            // fails to composite in that window — the page loads (title/URL
            // update normally) but the content area stays black or white.
            // Rendering the WebView through its own hardware layer texture is
            // the standard workaround for WebView-in-dialog blank rendering.
            webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                // [T-android-browser-user-zoom-2026-10] Users couldn't pinch-
                // zoom the browser panel: builtInZoomControls=false disables
                // the pinch gesture wholesale (the old comment in
                // applyShrinkToFit assumed the opposite — it was wrong).
                // Support zoom on, built-in pinch on, on-screen buttons off.
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                setSupportMultipleWindows(true)
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                val ua = customUA ?: profile.userAgentString
                if (ua != null) userAgentString = ua
            }
            // T-android-webview-v3-port: enable first- + third-party cookies.
            // WebView ships with third-party cookies disabled by default; that
            // breaks hCaptcha / Turnstile / reCAPTCHA flows where the
            // verification widget lives in a cross-origin iframe and posts its
            // token back to the parent through a Set-Cookie round-trip. The
            // agent-driven browser is the user's surrogate; matching Chrome's
            // default unblocks the same captcha flows the user would clear in
            // a real browser tab. First-party setAcceptCookie defaults to
            // true on every Android version we support — calling it
            // explicitly anyway so the intent is grep-able.
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
        }
    }

    @Volatile
    var isDisposed: Boolean = false
        private set

    /**
     * [fix24-W3] Release every document-start artifact this tab owns: the
     * agent-registered init scripts, the kill-guard noop handles, and any
     * legacy execjs-spill files. Called from BOTH release paths — dispose()
     * (sheet close) and BrowserTabPool.releaseTabResources() (pool reclaim,
     * which tears the WebView down without ever calling dispose()).
     *
     * On providers where ScriptHandler.remove() is a no-op (WebView 138) the
     * remove calls are harmless no-ops; on providers where it works they free
     * the provider-side script slots. Either way our bookkeeping is cleared
     * so the handles GC with the tab. Idempotent: the deques are empty after
     * the first call, and the spill delete is best-effort.
     */
    fun releaseDocumentStartArtifacts() {
        for (entry in initScripts) {
            runCatching { entry.second.remove() }
        }
        initScripts.clear()
        for (h in _noopHandles) {
            runCatching { h.remove() }
        }
        _noopHandles.clear()
        // [fix24-W2] Legacy spill files (pre-UUID naming, never deleted)
        // die with the tab. The directory is tiny and cache-scoped; delete
        // best-effort, never crash a release over an IO error.
        runCatching {
            java.io.File(webView.context.cacheDir, "execjs-spill").listFiles()?.forEach { it.delete() }
        }
    }

    /** Permanent release, always on Main; a hidden sheet must not call this. */
    fun dispose() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (isDisposed) return
        isDisposed = true
        // [fix24-W3] Drop every document-start handle this tab registered.
        releaseDocumentStartArtifacts()
        navigationDeferred?.cancel()
        navigationDeferred = null
        asyncJsDeferred?.cancel()
        asyncJsDeferred = null
        onNewWindow = null
        onCloseWindow = null
        onDownloadStart = null
        onBlobDownloadData = null
        webView.stopLoading()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.setDownloadListener(null)
        webView.removeJavascriptInterface("__minis__")
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.removeAllViews()
        webView.destroy()
    }

    // -- [T-android-browser-observability] Agent-facing trails + uploads --

    /** Bounded, newest-last page console trail (level: message (src:line)). */
    private val consoleLog = ArrayDeque<String>()

    /** Bounded, newest-last request trail (METHOD url [main]). No bodies. */
    private val networkLog = ArrayDeque<String>()
    private val logLock = Any()

    /** Parked `<input type=file>` callback until upload_file answers it. */
    @Volatile
    private var pendingFileChooser: android.webkit.ValueCallback<Array<android.net.Uri>>? = null

    /** [T-android-browser-observability-v2] Page-start fallback when the
     *  WebView lacks DOCUMENT_START_SCRIPT support. */
    @Volatile
    private var earlyScriptFallback: String? = null

    private fun appendLog(buf: ArrayDeque<String>, line: String) {
        synchronized(logLock) {
            if (buf.size >= MAX_LOG_ENTRIES) buf.removeFirst()
            buf.addLast(line.take(600))
        }
    }

    fun hasPendingFileChooser(): Boolean = pendingFileChooser != null

    fun readConsoleLog(level: String?, limit: Int = 100): List<String> = synchronized(logLock) {
        val want = level?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "all" }
        consoleLog
            .filter { want == null || it.lowercase().startsWith("$want:") }
            .takeLast(limit.coerceIn(1, MAX_LOG_ENTRIES))
    }

    fun readNetworkLog(filter: String?, limit: Int = 100): List<String> = synchronized(logLock) {
        val f = filter?.trim()?.takeIf { it.isNotEmpty() }
        networkLog
            .filter { f == null || it.contains(f, ignoreCase = true) }
            .takeLast(limit.coerceIn(1, MAX_LOG_ENTRIES))
    }

    private data class UploadFile(val name: String, val file: File)

    /**
     * Resolve the agent's `/var/minis/…` paths (or workspace-relative paths)
     * to host files. The per-session workspace and attachments live under
     * `minis-sessions/<sid>/`, shared under `minis-global/shared`.
     */
    private fun resolveUploadFiles(paths: List<String>): Pair<List<UploadFile>, List<String>> {
        val ctx = appContext ?: return emptyList<UploadFile>() to paths
        val sid = sessionIdProvider()
        val files = mutableListOf<UploadFile>()
        val missing = mutableListOf<String>()
        fun sessionDir(kind: String): File? =
            sid?.let { com.openminis.app.sandbox.SessionMounts.sessionDir(ctx.filesDir, it, kind) }
        for (raw in paths) {
            val path = raw.trim()
            if (path.isEmpty()) continue
            val file: File? = when {
                path.startsWith("/var/minis/workspace/") ->
                    sessionDir("workspace")?.let { File(it, path.removePrefix("/var/minis/workspace/")) }
                path.startsWith("/var/minis/attachments/") ->
                    sessionDir("attachments")?.let { File(it, path.removePrefix("/var/minis/attachments/")) }
                path.startsWith("/var/minis/shared/") ->
                    File(File(ctx.filesDir, "minis-global/shared"), path.removePrefix("/var/minis/shared/"))
                path.startsWith("/") -> null
                else -> sessionDir("workspace")?.let { File(it, path) }
            }
            if (file == null || !file.isFile) {
                missing.add(raw)
                continue
            }
            files.add(UploadFile(name = file.name, file = file))
        }
        return files to missing
    }

    /**
     * [T-android-browser-upload] Deliver files to a file input.
     *
     * Two paths, because WebView only opens the NATIVE picker on a genuine
     * user gesture — an agent's script-style click never counts:
     *   1. a picker is already parked (the user, or a real gesture, opened it)
     *      -> answer it with FileProvider content URIs;
     *   2. otherwise -> inject real `File` objects into the input via
     *      `DataTransfer` + dispatch `change`. Chromium supports assigning
     *      `input.files`, so this works without any gesture at all.
     *
     * [selector] targets the input (default `input[type=file]`).
     */
    suspend fun deliverUpload(paths: List<String>, selector: String?): BrowserActionResult {
        if (isDisposed) return BrowserActionResult.error("Browser tab is closed")
        val (files, missing) = resolveUploadFiles(paths)
        if (files.isEmpty()) {
            return BrowserActionResult.error(
                "No readable files to upload: ${missing.joinToString(", ")}",
            )
        }
        val note = if (missing.isEmpty()) "" else " (could not read: ${missing.joinToString(", ")})"

        // 1) Native picker already parked -> hand it real content URIs.
        val cb = pendingFileChooser
        if (cb != null) {
            val ctx = appContext
                ?: return BrowserActionResult.error("No app context for upload")
            val uris = files.mapNotNull { f ->
                runCatching {
                    androidx.core.content.FileProvider.getUriForFile(
                        ctx, "${ctx.packageName}.fileprovider", f.file,
                    )
                }.getOrNull()
            }
            if (uris.isEmpty()) return BrowserActionResult.error("Could not build content URIs$note")
            pendingFileChooser = null
            withContext(Dispatchers.Main) { cb.onReceiveValue(uris.toTypedArray()) }
            return BrowserActionResult(text = "Delivered ${uris.size} file(s) to the page's file picker$note")
        }

        // 2) No picker -> inject File objects straight into the input.
        val sel = selector?.takeIf { it.isNotBlank() } ?: "input[type=file]"
        val oversize = files.firstOrNull { it.file.length() > MAX_UPLOAD_BYTES }
        if (oversize != null) {
            return BrowserActionResult.error(
                "File too large for direct injection: ${oversize.name} " +
                    "(${oversize.file.length() / 1024 / 1024} MB, limit ${MAX_UPLOAD_BYTES / 1024 / 1024} MB)",
            )
        }
        val total = files.sumOf { it.file.length() }
        if (total > MAX_UPLOAD_TOTAL_BYTES) {
            return BrowserActionResult.error(
                "Files too large for direct injection (${total / 1024 / 1024} MB, limit " +
                    "${MAX_UPLOAD_TOTAL_BYTES / 1024 / 1024} MB total)",
            )
        }
        val entries = withContext(Dispatchers.IO) {
            files.map { f ->
                val b64 = android.util.Base64.encodeToString(
                    f.file.readBytes(), android.util.Base64.NO_WRAP,
                )
                Triple(f.name, guessUploadMime(f.name), b64)
            }
        }
        val payload = entries.joinToString(",") { (name, mime, b64) ->
            "{\"name\":${JSONObject.quote(name)},\"type\":${JSONObject.quote(mime)},\"b64\":${JSONObject.quote(b64)}}"
        }
        val js = "(function(){" +
            "var el=document.querySelector(${JSONObject.quote(sel)});" +
            "if(!el){return JSON.stringify({error:'selector not found: ' + ${JSONObject.quote(sel)}});}" +
            "try{" +
            "var list=[$payload]; if(el.multiple === false) list=list.slice(0,1);" +
            "var dt=new DataTransfer();" +
            "for(var i=0;i<list.length;i++){" +
            "var bin=atob(list[i].b64);var arr=new Uint8Array(bin.length);" +
            "for(var j=0;j<bin.length;j++)arr[j]=bin.charCodeAt(j);" +
            "dt.items.add(new File([arr], list[i].name, {type:list[i].type}));}" +
            "el.files=dt.files;" +
            "el.dispatchEvent(new Event('change',{bubbles:true}));" +
            "return JSON.stringify({ok:true,count:dt.files.length});" +
            "}catch(e){return JSON.stringify({error:String(e)});}" +
            "})()"
        val raw = runCatching { evaluateJavascript(js) }.getOrElse { t ->
            return BrowserActionResult.error("Upload injection failed: ${t.message}")
        }
        val obj = runCatching { JSONObject(raw) }.getOrNull()
            ?: return BrowserActionResult.error("Upload injection returned: $raw")
        val err = obj.optString("error").takeIf { it.isNotBlank() }
        if (err != null) return BrowserActionResult.error("Upload failed: $err")
        val count = obj.optInt("count", files.size)
        return BrowserActionResult(
            text = "Injected $count file(s) into ${JSONObject.quote(sel)}" +
                " — the page received the change event$note",
        )
    }

    private fun guessUploadMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "log", "md" -> "text/plain"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "html", "htm" -> "text/html"
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
    private val _currentURL = MutableStateFlow("")
    val currentURL: StateFlow<String> = _currentURL.asStateFlow()

    /**
     * [T-android-js-dialogs-256] JS dialogs this tab intercepted but did not
     * show, drained into the next tool result so the model learns the page
     * tried to open one. Per-tab (the manager is per-tab) and in-memory only,
     * like the other diagnostics here. See [InterceptedDialogQueue].
     */
    private val dialogQueue = InterceptedDialogQueue()

    private val _pageTitle = MutableStateFlow("")
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    private val _canGoForward = MutableStateFlow(false)
    val canGoForward: StateFlow<Boolean> = _canGoForward.asStateFlow()

    // [ISS-033] This tab's own profile. Exposed so the browser panel shows the
    // selected tab's mode, which set_user_agent can make differ from the default.
    private val _profile = MutableStateFlow(profile)
    val profileFlow: StateFlow<UserAgentProfile> = _profile.asStateFlow()
    // [T-android-init-scripts-2026-10] Agent-registered document-start
    // scripts, alive across navigations/reloads/same-origin iframes. The
    // built-in trio (net ring / query helpers / viewport guard) is registered
    // unconditionally at init; these are the dynamic user additions.
    private val initScripts = java.util.concurrent.ConcurrentLinkedDeque<Pair<String, androidx.webkit.ScriptHandler>>()

    // [T-android-init-script-remove-noop-2026-10] Handles of noop replacement scripts
    // used to disarm removes on providers where ScriptHandler.remove() is a no-op.
    private val _noopHandles = java.util.concurrent.ConcurrentLinkedDeque<androidx.webkit.ScriptHandler>()

    private var currentProfile: UserAgentProfile
        get() = _profile.value
        set(value) { _profile.value = value }

    /**
     * [ISS-033] Session / global custom viewport, or null for the profile's
     * own size. Set by [BrowserTabPool] so set_user_agent keeps the usual
     * precedence (session > global custom > profile default).
     */
    var viewportOverride: () -> Pair<Int, Int>? = { null }

    /** Callback for window.open / target="_blank" — TabPool hooks this. */
    var onNewWindow: ((Message) -> Unit)? = null

    /** Callback for window.close — TabPool hooks this. */
    var onCloseWindow: (() -> Unit)? = null

    /**
     * [T-android-browser-origin-overrides-2026-10] Fired when the tab starts /
     * finishes a navigation. The pool uses it to keep the saved-tab set fresh
     * and to apply per-origin UA / viewport overrides.
     */
    var onPageChanged: ((String?) -> Unit)? = null

    /**
     * [T-android-blank-link-2026-10] A clicked anchor wanted a new window
     * (target=_blank) but the popup blocker would swallow it; wire this to have
     * the pool open the URL in a real (background) tab instead.
     */
    var onNewTabRequest: ((String) -> Unit)? = null

    /**
     * Callback when the page triggers a file download over http/https
     * (Content-Disposition attachment, <a download>, unrenderable MIME type).
     * TabPool hooks this and streams the URL into the session workspace.
     */
    var onDownloadStart: ((url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, contentLength: Long) -> Unit)? = null

    /**
     * Callback delivering the bytes of a blob: download. blob: URLs only exist
     * inside the page's JS context, so [fetchBlobDownload] reads them via an
     * injected FileReader and hands the decoded bytes back through the bridge.
     */
    var onBlobDownloadData: ((data: ByteArray, filename: String, mimeType: String?) -> Unit)? = null

    /** Deferred for awaiting navigation completion. */
    private var navigationDeferred: CompletableDeferred<Unit>? = null

    /** Screenshots directory. */
    private val screenshotsDir: File by lazy {
        File(webView.context.cacheDir, "browser_screenshots").also { it.mkdirs() }
    }

    /** Deferred used by executeJS to receive results from async scripts via JS bridge. */
    private var asyncJsDeferred: CompletableDeferred<String>? = null

    /** JavaScript interface for async script result callbacks. */
    private val jsBridge = object {
        @JavascriptInterface
        fun resolve(result: String) {
            asyncJsDeferred?.complete(result)
        }

        @JavascriptInterface
        fun reject(error: String) {
            asyncJsDeferred?.complete("{\"error\":${JSONObject.quote(error)}}")
        }

        /**
         * Receives a blob: download read as a data URL by [fetchBlobDownload]'s
         * injected FileReader. Runs on the WebView's JavaBridge thread — file
         * I/O downstream is fine, but don't touch the WebView from here.
         */
        @JavascriptInterface
        fun saveBlobDownload(dataUrl: String, filename: String) {
            val comma = dataUrl.indexOf(',')
            if (comma < 0 || !dataUrl.startsWith("data:")) {
                Log.w(TAG, "blob download: malformed data URL (len=${dataUrl.length})")
                return
            }
            val header = dataUrl.substring(5, comma)
            val mime = header.substringBefore(';').ifEmpty { null }
            val bytes = try {
                if (header.endsWith(";base64")) {
                    android.util.Base64.decode(dataUrl.substring(comma + 1), android.util.Base64.DEFAULT)
                } else {
                    // Non-base64 data: URL — payload is percent-encoded text.
                    java.net.URLDecoder.decode(dataUrl.substring(comma + 1), "UTF-8")
                        .toByteArray(Charsets.UTF_8)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "blob download: payload decode failed: ${t.message}")
                return
            }
            Log.i(TAG, "blob download decoded: $filename (${bytes.size} bytes, mime=$mime)")
            onBlobDownloadData?.invoke(bytes, filename, mime)
        }

        @JavascriptInterface
        fun blobDownloadError(error: String) {
            Log.w(TAG, "blob download failed in page JS: $error")
        }
    }

    init {
        configureWebView(webView, profile)
        webView.addJavascriptInterface(jsBridge, "__minis__")
        setupWebViewClient()
        setupWebChromeClient()
        installEarlyScripts()
        // Intercept page-triggered downloads (Content-Disposition attachment,
        // <a download>, unrenderable MIME types). Without a listener, WebView
        // silently drops these — the user taps "download" and nothing happens.
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            Log.i(TAG, "onDownloadStart: ${url.take(120)} mime=$mimetype len=$contentLength")
            when {
                // blob: object URLs only exist inside the page — read via JS.
                url.startsWith("blob:") -> fetchBlobDownload(url, contentDisposition, mimetype)
                // data: URLs carry the payload inline — decode directly
                // (java.net.URL can't fetch them in the pool's downloader).
                url.startsWith("data:") -> {
                    val name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
                    jsBridge.saveBlobDownload(url, name)
                }
                else -> onDownloadStart?.invoke(url, userAgent, contentDisposition, mimetype, contentLength)
            }
        }
        // Track the on-screen WebView width so applyViewport() can compute a
        // shrink-to-fit initial scale. The synthetic measure/layout pass that
        // applyViewport performs to make `window.innerWidth` match the agent
        // viewport is independent of the container the AndroidView is hosted
        // in — without this listener we have no way to learn the container's
        // visible width, and oversize CSS viewports (e.g. 1280×800 on a
        // ~1080px-wide phone) would render off-screen to the right.
        webView.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val w = r - l
            if (w > 0 && w != lastKnownContainerWidthPx) {
                lastKnownContainerWidthPx = w
                // Re-apply the initial scale if we already have an active
                // viewport — covers rotation, sheet resize, container layout
                // settling after the WebView is first parented.
                lastAppliedViewport?.let { (vw, _) -> applyShrinkToFit(vw) }
            }
        }
    }

    /**
     * Read a blob: URL from inside the page's JS context and deliver its bytes
     * through the `__minis__.saveBlobDownload` bridge. blob: object URLs are
     * scoped to the page — they cannot be fetched from native code, so this
     * injected fetch + FileReader round-trip is the only way to get the data.
     */
    private fun fetchBlobDownload(blobUrl: String, contentDisposition: String?, mimeType: String?) {
        val guessedName = android.webkit.URLUtil.guessFileName(blobUrl, contentDisposition, mimeType)
        val js = """
            (function() {
                fetch(${JSONObject.quote(blobUrl)})
                    .then(function(r) { return r.blob(); })
                    .then(function(blob) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            __minis__.saveBlobDownload(reader.result, ${JSONObject.quote(guessedName)});
                        };
                        reader.onerror = function() { __minis__.blobDownloadError('FileReader error'); };
                        reader.readAsDataURL(blob);
                    })
                    .catch(function(e) { __minis__.blobDownloadError(String(e)); });
            })();
        """.trimIndent()
        webView.post { webView.evaluateJavascript(js, null) }
    }

    /**
     * Latest on-screen width (in physical pixels) of the AndroidView hosting
     * this WebView. Updated by the layout listener installed in [init];
     * 0 until the WebView is parented and laid out for the first time.
     */
    private var lastKnownContainerWidthPx: Int = 0

    /**
     * Compute and install a `setInitialScale` so a page authored at
     * [cssWidth] CSS pixels fits inside the visible WebView container. Called
     * after every [applyViewport] and on every container size change.
     *
     * `setInitialScale(percent)` is sticky — it applies on the next page
     * load. The tab pool's `applyViewportToAllTabs()` already reloads each
     * tab after viewport changes, so the scale is picked up on that reload.
     * Plain navigation between pages reuses whatever scale was last set.
     *
     * Passing 0 restores WebView's default behavior (use page's own scale).
     * We pass 0 whenever the CSS viewport already fits — no point shrinking
     * a 412-wide viewport on a 1080-wide container.
     */
    private fun applyShrinkToFit(cssWidth: Int) {
        val containerPx = lastKnownContainerWidthPx
        if (containerPx <= 0 || cssWidth <= 0) return
        val density = webView.resources.displayMetrics.density
        val cssWidthPx = (cssWidth * density).toInt()
        var scalePct = if (cssWidthPx > containerPx) {
            ((containerPx.toLong() * 100) / cssWidthPx).toInt().coerceAtLeast(1)
        } else {
            0 // CSS viewport already fits — let WebView use its default scale.
        }
        // [T-android-browser-blank] Guard against a corrupted container width
        // (e.g. a synthetic 1px layout leaking into the layout listener): a
        // microscopic sticky initial scale renders the next page load
        // effectively blank. No legitimate shrink-to-fit is below ~10%
        // (desktop 1280 CSS on a 360dp phone is ~28%) — fall back to the
        // WebView default instead.
        if (scalePct in 1..9) {
            Log.w(TAG, "applyShrinkToFit: implausible scale $scalePct% (container=${containerPx}px, css=${cssWidthPx}px) — using default")
            scalePct = 0
        }
        webView.setInitialScale(scalePct)
    }

    @SuppressLint("SetJavaScriptEnabled")
    /**
     * [T-android-webview-render-process-gone] (GH#341) True once this tab's
     * renderer died. Every later automation call on this manager would be
     * driving a WebView the system has torn down.
     *
     * [webView] is a constructor `val` and there is no recreate path here, so
     * the manager cannot heal itself. A flag is the smallest honest fix: the
     * pool checks it and rebuilds the tab, and any call that still slips
     * through gets a readable error instead of silently doing nothing (or
     * hanging on a JS evaluation whose renderer no longer exists).
     */
    @Volatile
    var isRendererDead: Boolean = false
        private set

    /**
     * (GH#245) True once this tab's renderer is alive but no longer answering:
     * a JS evaluation got no callback within
     * [BrowserActionGuard.JS_EVAL_TIMEOUT_MS], or the pool gave up on an action
     * that ran past its deadline. Such a renderer never fires
     * `onRenderProcessGone`, so [isRendererDead] alone left the tab in rotation
     * forever. The pool treats both flags the same way: drop and rebuild.
     */
    @Volatile
    var isWedged: Boolean = false
        private set

    /** Why [isWedged] was set, for the rebuild notice and logs. */
    @Volatile
    var wedgeReason: String? = null
        private set

    /** Either failure mode: this manager's WebView cannot be driven any more. */
    val isUnusable: Boolean get() = isRendererDead || isWedged

    fun markWedged(reason: String) {
        if (isWedged) return
        wedgeReason = reason
        isWedged = true
        Log.w(TAG, "Tab marked unresponsive: $reason")
    }

    private fun setupWebViewClient() {
        webView.webViewClient = object : WebViewClient() {
            // [T-net-android-phase3] Settings > Network: answer the local entry's
            // proxy challenge (WebViewNetworkProxy); any other auth request as before.
            override fun onReceivedHttpAuthRequest(view: android.webkit.WebView?, handler: android.webkit.HttpAuthHandler?, host: String?, realm: String?) {
                if (handler != null && com.openminis.app.network.WebViewNetworkProxy.answerAuth(handler, host, realm, view?.url)) return
                super.onReceivedHttpAuthRequest(view, handler, host, realm)
            }

            // [T-android-webview-render-process-gone] (GH#341) Nothing here
            // owns a teardown routine the way the Katex pool and WebViewHolder
            // do, so we only mark the tab unusable and let the pool replace it.
            // Deliberately NOT calling webView.destroy(): the pool still holds
            // this instance and may have it attached to a live composition
            // (BrowserWebView mounts it into a FrameLayout), so destroying it
            // from under the view tree is how you turn a survivable renderer
            // death into a UI crash.
            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?,
            ): Boolean {
                isRendererDead = true
                return com.openminis.app.ui.webview.WebViewRenderProcess
                    .handle("BrowserUseManager", detail)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val urlStr = request.url?.toString()
                // T234: Google permanently disallows WebView for sign-in /
                // OAuth. Hand any auth-domain navigation to Chrome Custom
                // Tab so the user can complete login in their real Chrome
                // session instead of hitting 403 disallowed_useragent.
                if (GoogleAuthRouter.shouldRouteExternally(urlStr)) {
                    if (urlStr != null) {
                        GoogleAuthRouter.openInCustomTab(view.context, urlStr)
                    }
                    return true
                }
                // T134: route intent://, market://, tel:, mailto:, … out
                // of the WebView so they reach the matching app instead of
                // surfacing as ERR_UNKNOWN_URL_SCHEME.
                //
                // [T-android-user-initiated-scheme-dispatch] AGENT_BACKGROUND:
                // this WebView is pool-managed and never rendered to the user,
                // so any navigation here came from the PAGE, not a tap. Keep
                // T318's block — otherwise a page the agent visits can throw
                // the user into another app mid-task.
                return com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                    .handle(
                        view.context,
                        request.url,
                        com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                            .Origin.AGENT_BACKGROUND,
                    )
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // [T-android-browser-observability-v2] Best-effort fallback for
                // WebViews without DOCUMENT_START_SCRIPT; idempotent in-page.
                earlyScriptFallback?.let { runCatching { view.evaluateJavascript(it, null) } }
                onPageChanged?.invoke(url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                _isLoading.value = false
                _currentURL.value = url ?: ""
                _pageTitle.value = view.title ?: ""
                // [T-android-browser-origin-overrides-2026-10] Keep the saved-tab
                // set fresh; the override applier is a per-origin no-op here.
                onPageChanged?.invoke(url)
                // [T-android-viewport-guard-2026-10] Post-load sanity check for
                // malformed viewport metas (fires once per load).
                view.postDelayed({
                    postLoadScope.launch {
                        runCatching { recoverBrokenViewportIfNeeded() }
                    }
                }, 450)
                // A second, later check: sites can rewrite the viewport meta a
                // second or two after load (GitCode's hydration does exactly
                // that) — by then the first check is long past.
                view.postDelayed({
                    postLoadScope.launch {
                        runCatching { recoverBrokenViewportIfNeeded() }
                    }
                }, 2_600)
                _canGoBack.value = view.canGoBack()
                _canGoForward.value = view.canGoForward()
                navigationDeferred?.complete(Unit)
                navigationDeferred = null
                // Record in browser history
                val histUrl = url ?: ""
                val histTitle = view.title ?: ""
                if (histUrl.isNotEmpty() && histUrl != "about:blank") {
                    BrowserHistoryStore.getInstance(view.context).record(histUrl, histTitle)
                }
                // T-webview-popup-d3c6e10f (Issue 1): after the pool WebView's
                // setInitialScale settles, force a JS `resize` event so
                // `position:fixed` elements (sticky headers, cookie banners,
                // floating chat) recompute against the post-scale visual
                // viewport instead of the pre-scale layout viewport. Without
                // this, fixed-positioned UI on some sites drifts off the
                // visible region after the synthetic measure+layout pass.
                view.postDelayed({
                    view.evaluateJavascript(
                        "window.dispatchEvent(new Event('resize'));", null,
                    )
                }, 80)
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    _isLoading.value = false
                    Log.e(TAG, "Navigation error: ${error.description}")
                    navigationDeferred?.complete(Unit)
                    navigationDeferred = null
                }
            }

            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): android.webkit.WebResourceResponse? {
                val url = request.url ?: return null
                // [T-android-host-blocklist-2026-10] Ad/tracker hosts die at
                // the network layer, before the request leaves the device —
                // faster page loads, less data, less fingerprinting surface.
                // Blocked requests get an empty 204-ish response so page JS
                // sees a completed (not failed) load and error handlers don't
                // spiral. The list is deliberately tiny: only high-noise
                // trackers that NEVER break page function when absent.
                if (!request.isForMainFrame) {
                    val h = url.host?.lowercase()
                    if (h != null && isBlockedHost(h)) {
                        return android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0))).apply {
                            setStatusCodeAndReasonPhrase(204, "No Content")
                        }
                    }
                }
                if (url.scheme != "minis") {
                    // [T-android-browser-observability] Record the request trail
                    // (never bodies); data:/blob: are noise.
                    if (url.scheme != "data" && url.scheme != "blob") {
                        appendLog(
                            networkLog,
                            "${request.method} $url" + if (request.isForMainFrame) " [main]" else "",
                        )
                    }
                    return null
                }
                return interceptMinisURL(url)
            }
        }
    }

    /** Resolve minis:// URLs to local workspace files. */
    private fun interceptMinisURL(uri: android.net.Uri): android.webkit.WebResourceResponse? {
        try {
            // minis://workspace/foo.html → /var/minis/workspace/foo.html, then
            // resolve to the host file via PRoot bind mounts (per-session
            // workspace lives under filesDir/minis-sessions/<sid>/workspace/).
            val host = uri.host ?: return null
            val path = uri.path ?: ""
            val linuxPath = "/var/minis/$host$path"

            // [T-android-minis-url-session-scope] Resolve against THIS session
            // first, and only then fall back to the global bind-mount map.
            //
            // `workspace`, `attachments`, `offloads` and `browser` live under
            // `minis-sessions/<sid>/`, but the global map only gains a
            // `/var/minis/workspace` entry while some session's PRoot shell is
            // running — and it is last-writer-wins across sessions. So the old
            // global-only lookup failed in two ordinary situations: the shell
            // had exited (path fell through to the rootfs copy of
            // /var/minis/workspace, which is empty), or another session had
            // booted more recently and the mount pointed at ITS workspace.
            //
            // Measured on a GEM-W09: `minis://workspace/jump-jump.html` 404'd
            // while the file sat intact at 5969 bytes in
            // minis-sessions/145d6883…/workspace/. The rootfs directory the
            // resolver actually reached contained nothing but `.` and `..`.
            // That is also why the failure looked intermittent and looked like
            // it depended on subdirectory depth — it depends on neither, only
            // on whether the global mount happens to point at the right
            // session at that moment.
            val sessionId = sessionIdProvider()
            val ctx = appContext
            val localFile = if (sessionId != null && ctx != null) {
                com.openminis.app.sandbox.PRootKernel
                    .resolveSessionHostPath(sessionId, linuxPath, ctx)
                    ?.takeIf { it.isFile }
                    ?: com.openminis.app.sandbox.PRootKernel.resolveHostPath(linuxPath)
            } else {
                com.openminis.app.sandbox.PRootKernel.resolveHostPath(linuxPath)
            }
            if (localFile == null || !localFile.exists() || !localFile.isFile) {
                return android.webkit.WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                    emptyMap(), "File not found: $host$path".byteInputStream())
            }
            val mimeType = guessMimeType(localFile.name)
            // For HTML mainframe responses, inject a `<meta viewport>` matching
            // the agent's session viewport when the page doesn't declare one.
            // Without this, Android WebView falls back to a hardcoded 980 CSS
            // px width regardless of the WebView's measured size, making
            // `set_viewport` look like a no-op for `minis://` HTML pages.
            val stream = if (mimeType == "text/html" && lastAppliedViewport != null) {
                ensureMetaViewport(localFile.readBytes(), lastAppliedViewport!!.first)
            } else {
                localFile.inputStream()
            }
            return android.webkit.WebResourceResponse(mimeType, "UTF-8", 200, "OK",
                mapOf("Access-Control-Allow-Origin" to "*"),
                stream)
        } catch (e: Exception) {
            Log.w(TAG, "minis:// intercept error: ${e.message}")
            return null
        }
    }

    /**
     * If the HTML lacks a `<meta name="viewport">`, splice one in matching
     * the agent's CSS-px viewport. Leaves pages that already declare a
     * viewport untouched so author intent (e.g. `width=1200`) wins.
     */
    private fun ensureMetaViewport(html: ByteArray, cssWidth: Int): java.io.InputStream {
        val text = String(html, Charsets.UTF_8)
        if (text.contains("name=\"viewport\"", ignoreCase = true) ||
            text.contains("name='viewport'", ignoreCase = true)) {
            return text.toByteArray(Charsets.UTF_8).inputStream()
        }
        // user-scalable=yes is the default but state it explicitly so a
        // future change to WebView defaults can't silently disable
        // pinch-zoom on the agent's auto-injected viewport.
        val meta = "<meta name=\"viewport\" content=\"width=$cssWidth, initial-scale=1.0, user-scalable=yes\">"
        val headIdx = text.indexOf("<head", ignoreCase = true).takeIf { it >= 0 }?.let {
            text.indexOf('>', it).takeIf { gt -> gt >= 0 }?.plus(1)
        }
        val rewritten = if (headIdx != null) {
            text.substring(0, headIdx) + meta + text.substring(headIdx)
        } else {
            // No <head>: prepend the meta so it's still parsed before body.
            "$meta$text"
        }
        return rewritten.toByteArray(Charsets.UTF_8).inputStream()
    }

    private fun guessMimeType(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "txt", "md" -> "text/plain"
            "xml" -> "text/xml"
            else -> "application/octet-stream"
        }
    }

    private fun setupWebChromeClient() {
        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                _pageTitle.value = title ?: ""
            }

            // [T-android-browser-observability] Keep a bounded trail of page
            // console output so the agent can read it (get_console_logs).
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                if (msg != null) {
                    val level = when (msg.messageLevel()) {
                        android.webkit.ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                        android.webkit.ConsoleMessage.MessageLevel.WARNING -> "WARN"
                        android.webkit.ConsoleMessage.MessageLevel.DEBUG -> "DEBUG"
                        android.webkit.ConsoleMessage.MessageLevel.TIP -> "LOG"
                        else -> "LOG"
                    }
                    appendLog(
                        consoleLog,
                        "$level: ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})",
                    )
                }
                return true
            }

            // [T-android-browser-observability] Park the page's file picker so
            // the agent can answer it with upload_file; without this override
            // <input type=file> silently does nothing in the pool WebView.
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                if (isDisposed || filePathCallback == null) return false
                pendingFileChooser?.onReceiveValue(null)
                pendingFileChooser = filePathCallback
                return true
            }

            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
            ): Boolean {
                onNewWindow?.invoke(resultMsg)
                return onNewWindow != null
            }

            override fun onCloseWindow(window: WebView) {
                onCloseWindow?.invoke()
            }

            // [T-android-js-dialogs-256] Answer JS dialogs with a conservative
            // default INSTEAD of showing them, and record what happened.
            //
            // This is unattended automation: showing a modal would block the
            // page's JS thread on a human who isn't there, wedging the agent
            // loop until the action dead-timeout fires. So the page is always
            // answered immediately and never actually blocks — the only change
            // versus the old silent-swallow behaviour is that the model now
            // finds out it happened (see drainInterceptedDialogReport).
            //
            // confirm() answers false and prompt() answers null deliberately:
            // the agent must not silently consent on the user's behalf to
            // whatever was asked. The user-facing web preview does the exact
            // opposite and shows real dialogs; see WebViewHolder.
            //
            // Returning true is what claims the dialog. Returning false (the
            // WebChromeClient default) would let WebView apply the same default
            // itself, but with no record — which is exactly the bug.

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?,
            ): Boolean {
                recordInterceptedDialog(
                    kind = "alert",
                    message = message.orEmpty(),
                    defaultText = null,
                    defaultResponse = "(dismissed)",
                )
                result?.confirm()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?,
            ): Boolean {
                recordInterceptedDialog(
                    kind = "confirm",
                    message = message.orEmpty(),
                    defaultText = null,
                    defaultResponse = "false",
                )
                result?.cancel()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: android.webkit.JsPromptResult?,
            ): Boolean {
                recordInterceptedDialog(
                    kind = "prompt",
                    message = message.orEmpty(),
                    defaultText = defaultValue,
                    defaultResponse = "null",
                )
                result?.cancel()
                return true
            }
        }
    }

    /** [T-android-js-dialogs-256] Record an intercepted dialog for the model. */
    private fun recordInterceptedDialog(
        kind: String,
        message: String,
        defaultText: String?,
        defaultResponse: String,
    ) {
        val url = _currentURL.value
        dialogQueue.record(
            kind = kind,
            message = message,
            defaultText = defaultText,
            pageURL = url.ifEmpty { null },
            defaultResponse = defaultResponse,
        )
        Log.i(TAG, "[JSDialog] intercepted $kind on $url — answered $defaultResponse")
    }

    /**
     * Drain this tab's intercepted-dialog queue into text for the model, or null
     * when nothing was intercepted. Draining clears it, so each dialog is
     * reported exactly once. Called by [BrowserTabPool] on the next tool result.
     */
    fun drainInterceptedDialogReport(): String? = dialogQueue.drainReport()

    // -- Execute Action --

    suspend fun execute(input: BrowserActionInput): BrowserActionResult {
        // (GH#245) Fail fast instead of driving a WebView that cannot answer.
        // The pool normally rebuilds such a tab before handing it out; this
        // catches the call that was already routed here when the flag flipped.
        if (isUnusable) {
            throw WebViewWedgedException(
                if (isRendererDead) "browser tab renderer process died"
                else "browser tab stopped responding (${wedgeReason ?: "unknown"})",
            )
        }
        val prevUrl = withContext(Dispatchers.Main) { webView.url }
        // [T-android-page-diff-2026-10] Fingerprint the page before a
        // diff-tracked action so the result can report what actually changed.
        val beforeFingerprint = if (input.action in PAGE_DIFF_ACTIONS) capturePageFingerprint() else null
        var result: BrowserActionResult = when (input.action) {
            BrowserAction.NAVIGATE -> navigate(input.url, input.selector, input.timeoutMs)
            BrowserAction.SCREENSHOT -> return screenshot(
                fullPage = input.fullPage,
                elementSelector = input.selector,
                scale = input.scale?.toFloat(),
            )
            BrowserAction.GESTURE -> return performGesture(input)
            BrowserAction.EXPORT_PDF -> return exportPdf()
            BrowserAction.CLICK -> click(input.selector, input.coordinateX, input.coordinateY)
            BrowserAction.TYPE -> type(input.selector, input.text)
            BrowserAction.GET_TEXT -> return getText(input.selector)
            BrowserAction.SCROLL -> scroll(input.selector, input.direction, input.amount)
            BrowserAction.GET_PAGE_INFO -> return getPageInfo()
            BrowserAction.EXECUTE_JS -> {
                // [T-android-execjs-scriptfile-2026-10] Long scripts can be
                // truncated in the tool-argument transport (\\u-escape-dense
                // payloads are the worst case — the whole argument stream dies
                // and the tool layer reports "unknown action"). script_file
                // reads the body from disk instead.
                // [T-android-execjs-autolarge-2026-10] Hardening for payloads
                // that DID arrive: when an INLINE script is large (>4KB), the
                // WebView bridge wrap (async IIFE + __minis__.resolve) can
                // still choke on the evaluateJavascript hop. Auto-spill the
                // body to a temp file and execute through the same read-back
                // path script_file uses — the model never has to know. The
                // transport-level truncation itself (argument stream dies →
                // "unknown action") is upstream of the app and stays mitigated
                // by the script_file discipline.
                if (input.scriptFile.isNullOrBlank() && input.script != null &&
                    input.script!!.length > INLINE_SPILL_BYTES
                ) {
                    // [fix24-W2] Spill owns a file now: read the body back
                    // (verified identical by the spill writer), execute it,
                    // and ALWAYS delete the file — success, JS exception and
                    // timeout alike. cacheDir no longer accumulates spills.
                    val spillFile = spillLargeInlineScript(input.script!!)
                    if (spillFile != null) {
                        try {
                            val body = spillFile.readText(Charsets.UTF_8)
                            return executeJS(body, input.timeoutMs)
                        } finally {
                            spillFile.delete()
                        }
                    }
                }
                val body = if (!input.scriptFile.isNullOrBlank()) {
                    val (file, err) = resolveScriptFile(input.scriptFile!!)
                    if (file == null) return BrowserActionResult.error("script_file: $err")
                    try {
                        file.readText(Charsets.UTF_8)
                    } catch (t: Throwable) {
                        return BrowserActionResult.error("script_file: read failed — ${t.message}")
                    }
                } else {
                    input.script
                }
                return executeJS(body, input.timeoutMs)
            }
            BrowserAction.FIND_ELEMENTS -> return findElements(input.selector)
            BrowserAction.HOVER -> hover(input.selector)
            BrowserAction.GET_READABLE -> return getReadable()
            BrowserAction.SET_USER_AGENT -> return setUserAgent(input.userAgent, input.userAgentRaw)
            BrowserAction.SET_VIEWPORT ->
                return BrowserActionResult.error("set_viewport must be routed through BrowserTabPool")
            BrowserAction.GET_BACKBONE -> return getBackbone(input.maxDepth)
            BrowserAction.FETCH -> return fetch(input.url)
            BrowserAction.GET_COOKIES -> return getCookies(input.keywords, input.fuzzy)
            BrowserAction.SET_COOKIES -> return setCookies(input.cookies)
            BrowserAction.SCROLL_AND_COLLECT -> return scrollAndCollect(
                input.scrollCount, input.itemSelector, input.keywords,
            )
            BrowserAction.WAIT_FOR_DOM_STABLE -> return waitForDomStable(input.timeoutMs)
            BrowserAction.UPLOAD_FILE -> return deliverUpload(input.files.orEmpty(), input.selector)
            BrowserAction.GET_CONSOLE_LOGS -> {
                val lines = readConsoleLog(input.logLevel, 100)
                return BrowserActionResult(
                    text = if (lines.isEmpty()) {
                        "No console output captured for this tab."
                    } else {
                        // [T-android-console-crosspage-note-2026-10] The trail
                        // deliberately survives navigation so a log fired
                        // right before a redirect is still readable; say so,
                        // or entries from the previous page look like noise
                        // from the current one.
                        "Console log (last ${lines.size} entries; spans pages loaded in this tab — entries may predate the current page):\n" + lines.joinToString("\n")
                    },
                )
            }
            BrowserAction.GET_NETWORK_LOG -> return getNetworkLogAction(input)
            BrowserAction.ADD_INIT_SCRIPT -> return addInitScriptAction(input)
            BrowserAction.REMOVE_INIT_SCRIPT -> return removeInitScriptAction(input)
            BrowserAction.LIST_INIT_SCRIPTS -> return listInitScriptsAction()
            BrowserAction.GO_BACK -> {
                withContext(Dispatchers.Main) { goBack() }
                return BrowserActionResult(text = "Navigated back (if a previous entry existed)")
            }
            BrowserAction.GO_FORWARD -> {
                withContext(Dispatchers.Main) { goForward() }
                return BrowserActionResult(text = "Navigated forward (if a forward entry existed)")
            }
            BrowserAction.RELOAD -> {
                withContext(Dispatchers.Main) { reload() }
                return BrowserActionResult(text = "Reloading the current page")
            }
            BrowserAction.SELECT_OPTION -> return selectOption(input.selector, input.text)
            BrowserAction.WAIT_FOR -> return waitFor(input.selector, input.text, input.timeoutMs)
            BrowserAction.CLEAR_SITE_DATA -> return clearSiteData()
            BrowserAction.PRESS_KEY -> pressKey(input.key, input.selector)
            BrowserAction.REPLAY_REQUEST -> return replayRequest(input)
            BrowserAction.COPY_AS_CURL -> return copyAsCurl(input)
            BrowserAction.FILL_FORM -> return fillForm(input.fields, input.submit)
            BrowserAction.WAIT_FOR_URL -> return waitForUrl(input.url, input.timeoutMs)
            BrowserAction.WAIT_FOR_COOKIE -> return waitForCookie(input.text, input.timeoutMs)
            BrowserAction.WAIT_FOR_NETWORK_IDLE -> return waitForNetworkIdle(input.timeoutMs, input.amount)
            BrowserAction.EXTRACT_QR -> return extractQr()
            BrowserAction.GET_RESPONSE_LOG -> return getResponseLogAction(input)
            BrowserAction.NEW_TAB, BrowserAction.CLOSE_TAB, BrowserAction.LIST_TABS,
            BrowserAction.RESTORE_TABS ->
                return BrowserActionResult.error("Tab management actions must be routed through BrowserTabPool")
            BrowserAction.RECORD_START, BrowserAction.RECORD_STOP, BrowserAction.RUN_MACRO,
            BrowserAction.LIST_MACROS, BrowserAction.KEEP_ALIVE, BrowserAction.EXPORT_MACRO,
            BrowserAction.HOLD_TAB ->
                return BrowserActionResult.error("Macro + keep-alive actions must be routed through BrowserTabPool")
        }

        // Auto-capture screenshot after visual-change actions
        if (result.success && BrowserAction.visualChangeActions.contains(input.action)) {
            // [T-android-selection-cleanup-2026-10] A long-press (or a drag /
            // coordinate tap that lands on text) leaves the WebView in its
            // text-selection state. The highlighted region then persists on
            // the page — users saw a giant blue selection "dome" over the
            // content after an automation session — and it pollutes later
            // screenshots and text-based hit testing. Collapse the selection
            // after every side-effecting action; typing/copy flows that need
            // a selection establish their own.
            runCatching {
                evaluateJavascript(BrowserUseJS.CLEAR_SELECTION_JS)
            }
            result = attachSnapshot(result)
        }

        // [T-android-page-diff-2026-10] What actually changed? Compare against
        // the fingerprint taken before the action and append a short diff.
        if (beforeFingerprint != null && result.success && input.action in PAGE_DIFF_ACTIONS) {
            val newUrl = withContext(Dispatchers.Main) { webView.url }
            val sameUrl = prevUrl == null || newUrl == null ||
                prevUrl.substringBefore("#") == newUrl.substringBefore("#")
            if (sameUrl) {
                // [T-android-diff-async-settle-2026-10] Submit clicks often fire
                // an async POST and update the page 0.5–2s later; fingerprinting
                // immediately reported "no visible change" for three separate
                // sites whose submissions all succeeded. Wait for a bounded
                // settle (min beat + network drain) before the after-shot.
                awaitPageSettleForDiff()
                capturePageFingerprint()?.let { after ->
                    result = result.copy(text = result.text + "\n" + computePageDiff(beforeFingerprint, after))
                }
            }
        }

        // Detect URL change after visual-change actions (ignore hash-only changes)
        if (result.success && BrowserAction.visualChangeActions.contains(input.action)) {
            val newUrl = withContext(Dispatchers.Main) { webView.url }
            if (prevUrl != null && newUrl != null) {
                val prevNoHash = prevUrl.substringBefore("#")
                val curNoHash = newUrl.substringBefore("#")
                if (prevNoHash != curNoHash) {
                    result = result.copy(
                        text = result.text + "\n[URL Changed] Page navigated: $prevUrl -> $newUrl. Take a screenshot to see the current state before continuing."
                    )
                }
            }
        }

        return result
    }

    private suspend fun attachSnapshot(result: BrowserActionResult): BrowserActionResult {
        return try {
            delay(300) // Let page settle
            val bitmap = captureWebViewBitmap(gpu = true) ?: return result
            val file = saveBitmapToFile(bitmap, "snapshot", SNAPSHOT_QUALITY)
            bitmap.recycle()
            result.copy(imageFilePath = file.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "Auto-snapshot failed: ${e.message}")
            result
        }
    }

    // -- [T-android-page-diff-2026-10] Before/after page diff --

    private suspend fun capturePageFingerprint(): JSONObject? {
        return try {
            val raw = withTimeoutOrNull(1_500L) {
                evaluateJavascript(BrowserUseJS.pageFingerprint())
            } ?: return null
            JSONObject(raw)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private fun computePageDiff(before: JSONObject, after: JSONObject): String {
        fun counter(jo: JSONObject, k: String) = jo.optInt(k)
        val bText = before.optString("text")
        val aText = after.optString("text")
        val sameCounts = counter(before, "a") == counter(after, "a") &&
            counter(before, "b") == counter(after, "b") &&
            counter(before, "i") == counter(after, "i")
        if (bText == aText && sameCounts) {
            return "Page diff: no visible change (same text, links, buttons, images) — the action had no observable effect."
        }
        val b = bText.split("\n")
        val a = aText.split("\n")
        var p = 0
        while (p < b.size && p < a.size && b[p] == a[p]) p++
        var s = 0
        while (s < b.size - p && s < a.size - p && b[b.size - 1 - s] == a[a.size - 1 - s]) s++
        val removed = b.subList(p, b.size - s)
        val added = a.subList(p, a.size - s)
        val sb = StringBuilder("Page diff: ")
        sb.append('+').append(added.size).append(" / -").append(removed.size).append(" line(s)")
        val da = counter(after, "a") - counter(before, "a")
        val db = counter(after, "b") - counter(before, "b")
        val di = counter(after, "i") - counter(before, "i")
        val deltas = mutableListOf<String>()
        if (da != 0) deltas.add((if (da > 0) "+" else "") + da + " links")
        if (db != 0) deltas.add((if (db > 0) "+" else "") + db + " buttons")
        if (di != 0) deltas.add((if (di > 0) "+" else "") + di + " images")
        if (deltas.isNotEmpty()) sb.append("  (").append(deltas.joinToString(", ")).append(')')
        for (l in added.take(3)) sb.append("\n  + ").append(l.take(120))
        if (added.size > 3) sb.append("\n  + …").append(added.size - 3).append(" more")
        for (l in removed.take(2)) sb.append("\n  - ").append(l.take(120))
        if (removed.size > 2) sb.append("\n  - …").append(removed.size - 2).append(" more")
        return sb.toString().take(700)
    }

    // -- [T-android-diff-async-settle-2026-10] --

    /**
     * Bounded settle before diffing: a minimum beat for framework async work
     * plus a drain wait on the page's in-flight fetch/XHR counter (pages
     * without instrumentation just get the minimum beat).
     */
    private suspend fun awaitPageSettleForDiff() {
        val started = System.currentTimeMillis()
        val hardDeadline = started + 3_200L
        while (System.currentTimeMillis() < hardDeadline) {
            val elapsed = System.currentTimeMillis() - started
            val raw = runCatching { evaluateJavascript(BrowserUseJS.netIdleProbe()) }.getOrNull()
            val j = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            val pending = j?.optInt("pending", -1) ?: -1
            val lastAgo = j?.optInt("lastAgo", -1) ?: -1
            val idleNow = pending <= 0 && (lastAgo < 0 || lastAgo >= 400)
            if (idleNow && elapsed >= 700L) break
            kotlinx.coroutines.delay(150)
        }
        kotlinx.coroutines.delay(120)
    }

    // -- [T-android-viewport-guard-2026-10] Malformed viewport meta recovery --

    /**
     * Pages with an invalid `initial-scale=0` (e.g. GitCode) get clamped to the
     * 25% minimum page scale with a 4×-stretched layout. The injected guard has
     * already rewritten the meta; this post-load check rescues the CURRENT view
     * (zoomBy — the same fallback the pinch gesture uses) and, when the guard
     * actually corrected the declaration, gives the layout one clean reload.
     */
    private val viewportRecoveredUrls = mutableSetOf<String>()

    private val postLoadScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    private suspend fun readViewportState(): Triple<Double, Boolean, Int>? {
        val raw = runCatching {
            evaluateJavascript(
                "(function(){try{var vv=window.visualViewport;" +
                    "return JSON.stringify({s:(vv?vv.scale:1),f:!!window.__minis_vp_fixed," +
                    "iw:window.innerWidth,cw:(document.documentElement?document.documentElement.clientWidth:0)});" +
                    "}catch(e){return '{}';}})()",
            )
        }.getOrNull() ?: return null
        val j = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return Triple(j.optDouble("s", 1.0), j.optBoolean("f", false), j.optInt("iw", 0))
    }

    private suspend fun recoverBrokenViewportIfNeeded() {
        val url = _currentURL.value
        if (url.isEmpty()) return
        val st = readViewportState() ?: return
        val scale = st.first
        if (scale.isNaN() || scale >= 0.5) return
        // [T-android-viewport-guard-2026-10] First ask the page-side guard to
        // re-fix every viewport meta. Proven on GitCode: Chromium re-evaluates
        // the viewport the moment a malformed declaration is corrected — scale
        // AND the 4x-stretched layout snap back with no reload.
        runCatching {
            evaluateJavascript(
                "(function(){try{if(window.__minis_vp_fix_all){window.__minis_vp_fix_all();return 'ok';}return 'no';}catch(e){return 'err';}})()",
            )
        }
        kotlinx.coroutines.delay(450)
        val st2 = readViewportState()
        if (st2 != null && !st2.first.isNaN() && st2.first >= 0.5) {
            Log.w(TAG, "Recovered broken viewport via meta fix on $url (was $scale)")
            return
        }
        // Fallback (guard unavailable / fix ineffective): pinch-style zoom
        // rescue, then one guarded reload when the meta was actually corrected.
        withContext(Dispatchers.Main) {
            runCatching { webView.zoomBy((1.0 / scale).toFloat()) }
        }
        Log.w(TAG, "Viewport zoom rescue on $url (was $scale)")
        val fixedNow = st2?.second ?: st.second
        if (fixedNow) {
            if (viewportRecoveredUrls.size > 200) viewportRecoveredUrls.clear()
            if (viewportRecoveredUrls.add(url.take(300))) {
                withContext(Dispatchers.Main) {
                    runCatching { webView.reload() }
                }
            }
        }
    }

    // -- Navigate --

    private suspend fun navigate(urlString: String?, waitSelector: String?, waitTimeoutMs: Int?): BrowserActionResult {
        if (urlString.isNullOrEmpty()) return BrowserActionResult.error("Missing 'url' parameter")

        var normalized = urlString
        if (!normalized.contains("://")) normalized = "https://$normalized"

        val deferred = CompletableDeferred<Unit>()
        navigationDeferred = deferred
        _isLoading.value = true

        withContext(Dispatchers.Main) {
            // Re-assert the last applied viewport before loadUrl. Intercepted
            // navigations (minis://) served via shouldInterceptRequest skip
            // the layout pass that a real network load triggers, so without
            // this the page reports Android WebView's 980px no-meta fallback
            // even when a session override (e.g. 960x540) is active.
            lastAppliedViewport?.let { (w, h) -> applyViewport(w, h) }
            webView.loadUrl(normalized)
        }

        // Wait with timeout
        val handler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (navigationDeferred === deferred) {
                Log.w(TAG, "Navigation timed out for $normalized")
                _isLoading.value = false
                deferred.complete(Unit)
                navigationDeferred = null
            }
        }
        handler.postDelayed(timeoutRunnable, NAVIGATION_TIMEOUT_MS)

        try {
            deferred.await()
        } finally {
            handler.removeCallbacks(timeoutRunnable)
        }

        _currentURL.value = _currentURL.value.ifEmpty { normalized }
        _isLoading.value = false

        val meta = navigationMetadata()
        // [T-android-navigate-wait-2026-10] B-6: optional post-load settle.
        // `selector` names an element that proves the RIGHT page is up; when it
        // doesn't appear we re-navigate once (GitCode /issues/new bounced back
        // to the list page) and check again. The load itself still counts as a
        // successful navigation — the note explains what actually happened.
        if (!waitSelector.isNullOrBlank()) {
            val waitSec = (waitTimeoutMs?.takeIf { it > 0 } ?: 8).coerceAtMost(30)
            val waitMs = waitSec * 1000L
            if (awaitSelector(waitSelector, waitMs)) {
                return BrowserActionResult(text = meta + "\n[wait_for_selector] '$waitSelector' present — the right page is up.")
            }
            withContext(Dispatchers.Main) { runCatching { webView.reload() } }
            withTimeoutOrNull(waitMs) { while (_isLoading.value) kotlinx.coroutines.delay(100) }
            kotlinx.coroutines.delay(300)
            val okSecond = awaitSelector(waitSelector, waitMs)
            return BrowserActionResult(
                text = meta + "\n[wait_for_selector] '$waitSelector' " + if (okSecond) {
                    "was missing on the first load; re-navigated once and it appeared — the page looks correct now."
                } else {
                    "still missing after one automatic re-navigation — the page may have landed somewhere unexpected; check the URL before continuing."
                },
            )
        }
        return BrowserActionResult(text = meta)
    }

    /** [T-android-navigate-wait-2026-10] Poll for a selector via the shared probe. */
    private suspend fun awaitSelector(selector: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val raw = runCatching { evaluateJavascript(BrowserUseJS.waitProbe(selector, null)) }.getOrNull()
            val obj = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (obj?.optBoolean("ok") == true) return true
            kotlinx.coroutines.delay(300)
        }
        return false
    }

    private suspend fun navigationMetadata(): String {
        val url = _currentURL.value
        val title = _pageTitle.value
        // Read the viewport directly from the page (`window.innerWidth/Height`)
        // so a session or global viewport override shows the actual layout
        // size, not the UA profile default. Matches iOS which likewise queries
        // the WKWebView's live bounds rather than a profile constant.
        //
        // (GH#245) If the page cannot answer, degrade to URL + title instead of
        // failing the navigate: the load itself may well have finished, and the
        // tab is already flagged for rebuild by evaluateJavascript.
        val scrollInfo = try {
            evaluateJavascript(
                "JSON.stringify({" +
                    "sx:window.scrollX||0,sy:window.scrollY||0," +
                    "pw:document.documentElement.scrollWidth||0," +
                    "ph:document.documentElement.scrollHeight||0," +
                    "vw:window.innerWidth||0,vh:window.innerHeight||0" +
                    "})"
            )
        } catch (e: WebViewWedgedException) {
            return buildString {
                appendLine("Navigated to $url")
                if (title.isNotEmpty()) appendLine("  Title: $title")
                append("  (page metrics unavailable: ${e.message})")
            }
        }
        var scrollX = 0; var scrollY = 0; var pageW = 0; var pageH = 0
        var vpW = 0; var vpH = 0
        try {
            val info = JSONObject(scrollInfo)
            scrollX = info.optInt("sx"); scrollY = info.optInt("sy")
            pageW = info.optInt("pw"); pageH = info.optInt("ph")
            vpW = info.optInt("vw"); vpH = info.optInt("vh")
        } catch (_: Exception) {}

        // Fall back to the UA profile default when the page hasn't populated
        // `window.inner*` yet (e.g. navigation failure / about:blank).
        val fallback = currentProfile.viewportSize
        val effectiveVpW = if (vpW > 0) vpW else fallback.first
        val effectiveVpH = if (vpH > 0) vpH else fallback.second

        return buildString {
            appendLine("Navigated to $url")
            if (title.isNotEmpty()) appendLine("  Title: $title")
            appendLine("  Viewport: ${effectiveVpW}x$effectiveVpH")
            if (pageW > 0 || pageH > 0) appendLine("  Page size: ${pageW}x$pageH")
            append("  Scroll position: ($scrollX, $scrollY)")
        }
    }

    // -- Screenshot --

    private data class FullPageCapture(
        val bitmap: Bitmap,
        val truncated: Boolean,
        val originalHeightPx: Int,
    )

    private suspend fun screenshot(
        fullPage: Boolean = false,
        elementSelector: String? = null,
        scale: Float? = null,
    ): BrowserActionResult {
        if (!fullPage && !elementSelector.isNullOrBlank()) return elementScreenshot(elementSelector, scale)
        val k = clampShotScale(scale)
        if (fullPage) {
            val captured = captureFullPageBitmap()
                ?: return BrowserActionResult.error("Failed to capture the full page")
            return finishScreenshot(
                captured.bitmap,
                fullPage = true,
                truncated = captured.truncated,
                originalHeightPx = captured.originalHeightPx,
                scale = k,
            )
        }
        // [T-android-shot-gpu-first-2026-10] A detached pool WebView's
        // rasterised tiles are dropped by Chromium; the software draw then
        // re-rasters asynchronously and `View.draw` reads back a half-painted
        // frame — heavy pages (GitCode dashboard) and GPU layers (WebGL
        // canvases) came out uniformly grey/blank while the DOM was fine.
        // The GPU path (HeadlessRenderHost) parents the WebView into a real
        // virtual-display window for the capture and waits for a settled
        // composited frame, which is exactly this case. Try it first; any
        // failure (null) falls through to the software draw, so the worst
        // case is the previous behaviour.
        val bitmap = captureWebViewBitmap(gpu = true) ?: captureWebViewBitmap()
            ?: return BrowserActionResult.error("Failed to capture screenshot")
        return finishScreenshot(bitmap, scale = k)
    }

    /** [T-android-browser-shot-scale-2026-10] Clamp a caller scale to 0.1–1.5 (1.0 = native). */
    private fun clampShotScale(scale: Float?): Float {
        if (scale == null || scale.isNaN() || scale <= 0f) return 1f
        return scale.coerceIn(0.1f, 1.5f)
    }

    /** Full-page capture: stretch → capture → restore, shared by screenshot + PDF. */
    private suspend fun captureFullPageBitmap(): FullPageCapture? {
        var truncated = false
        var originalHeightPx = 0
        var didStretch = false
        var savedW = 0
        var savedH = 0

        // Measure full document height in CSS pixels.
        val cssScrollHeight = evaluateJavascript("document.documentElement.scrollHeight").let {
            it.trim().toIntOrNull() ?: 0
        }
        val density = webView.resources.displayMetrics.density
        val scrollHeightPx = if (cssScrollHeight > 0) {
            (cssScrollHeight * density).toInt()
        } else {
            withContext(Dispatchers.Main) { webView.height }
        }
        originalHeightPx = scrollHeightPx
        val cappedPx = scrollHeightPx.coerceAtMost(MAX_FULL_PAGE_HEIGHT_PX)
        truncated = scrollHeightPx > MAX_FULL_PAGE_HEIGHT_PX

        // Eagerize lazy images and wait two RAFs so layout settles before capture.
        try {
            evaluateJavascript(
                """
                (async () => {
                    document.querySelectorAll('img[loading="lazy"]').forEach(i => i.loading = 'eager');
                    await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
                    return 'ok';
                })()
                """.trimIndent()
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) { /* best-effort */ }
        delay(50)

        // Snapshot current viewport, stretch to cssScrollHeight, capture, restore.
        val applied = lastAppliedViewport ?: currentProfile.viewportSize
        savedW = applied.first
        savedH = applied.second
        val cssCappedHeight = (cappedPx / density).toInt().coerceAtLeast(savedH)
        withContext(Dispatchers.Main) {
            applyViewport(savedW, cssCappedHeight)
        }
        didStretch = true
        // [T-android-browser-fullpage-repaint] Give the renderer a chance to
        // paint the area the stretch just exposed. applyViewport only
        // measure/layouts the view; without a gap the capture below runs
        // in the same main-thread pass and can read back a region that has
        // never been drawn, which is why --full-page shots came out
        // partially blank below the first viewport.
        delay(FULL_PAGE_REPAINT_DELAY_MS)
        Log.i(TAG, "full_page stretch: ${savedW}x$cssCappedHeight CSS (px=$cappedPx, original=$scrollHeightPx, truncated=$truncated)")

        val bitmap = try {
            // A stretched full page is far taller than a display can be.
            captureWebViewBitmap(gpu = !didStretch)
        } finally {
            if (didStretch) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    applyViewport(savedW, savedH)
                }
            }
        } ?: return null

        return FullPageCapture(bitmap = bitmap, truncated = truncated, originalHeightPx = originalHeightPx)
    }

    /** [T-android-browser-pdf] Archive the current page as a multi-page A4-ish PDF. */
    private suspend fun exportPdf(): BrowserActionResult {
        val captured = captureFullPageBitmap()
            ?: return BrowserActionResult.error("Failed to capture the page for PDF")
        val bitmap = captured.bitmap
        val ctx = appContext
            ?: run {
                bitmap.recycle()
                return BrowserActionResult.error("No app context for PDF export")
            }
        val sid = sessionIdProvider()
        val outDir = if (sid != null) {
            File(File(File(ctx.filesDir, "minis-sessions"), sid), "browser").apply { mkdirs() }
        } else {
            File(ctx.cacheDir, "browser").apply { mkdirs() }
        }
        val name = "page_" + (System.currentTimeMillis() / 1000) + ".pdf"
        val outFile = File(outDir, name)
        val pages: Int
        try {
            pages = withContext(Dispatchers.IO) { buildPdf(bitmap, outFile) }
        } catch (t: Throwable) {
            bitmap.recycle()
            return BrowserActionResult.error("PDF export failed: " + t.message)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        val guestPath = if (sid != null) "/var/minis/browser/$name" else outFile.absolutePath
        return BrowserActionResult(
            text = "PDF saved: " + guestPath +
                "\n  Pages: " + pages + ", " + (outFile.length() / 1024) + "KB" +
                (if (captured.truncated) {
                    "\n  Note: the page was taller than the capture cap; the PDF covers up to the cap."
                } else ""),
        )
    }

    /** Slice the (budget-bounded) full-page bitmap into A4-ish PDF pages. */
    private fun buildPdf(bitmap: Bitmap, outFile: File): Int {
        val pageW = 1240
        val pageH = 1754 // A4 at ~150 dpi
        val scale = pageW.toFloat() / bitmap.width
        val sliceSrcH = (pageH / scale).toInt().coerceAtLeast(1)
        val doc = android.graphics.pdf.PdfDocument()
        var pageNo = 0
        try {
            var y = 0
            while (y < bitmap.height) {
                val h = minOf(sliceSrcH, bitmap.height - y)
                val page = doc.startPage(
                    android.graphics.pdf.PdfDocument.PageInfo.Builder(pageW, pageH, ++pageNo).create(),
                )
                val src = android.graphics.Rect(0, y, bitmap.width, y + h)
                val dst = android.graphics.Rect(0, 0, pageW, (h * scale).toInt().coerceAtLeast(1))
                val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                page.canvas.drawBitmap(bitmap, src, dst, paint)
                doc.finishPage(page)
                y += h
            }
            java.io.FileOutputStream(outFile).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        return pageNo
    }

    /** Two-finger pinch: pointers spread out (zoom in) or in (zoom out). */
    private suspend fun performPinch(centerCssX: Float, centerCssY: Float, spreadCss: Float) {
        val density = withContext(Dispatchers.Main) { webView.resources.displayMetrics.density }
        val cx = centerCssX * density
        val cy = centerCssY * density
        val offset = 60f * density
        val spread = spreadCss * density
        val ax0 = cx - offset
        val bx0 = cx + offset
        val ax1 = ax0 - spread
        val bx1 = bx0 + spread

        val t0 = uptime()
        withContext(Dispatchers.Main) {
            sendMultiTouch(t0, t0, android.view.MotionEvent.ACTION_DOWN, listOf(ax0 to cy))
            sendMultiTouch(
                t0, t0 + 50,
                android.view.MotionEvent.ACTION_POINTER_DOWN or
                    (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(ax0 to cy, bx0 to cy),
            )
        }
        delay(60) // brief settle so the recognizer sees two stable contacts first
        val steps = 12
        for (i in 1..steps) {
            delay(30)
            val f = i / steps.toFloat()
            val ax = ax0 + (ax1 - ax0) * f
            val bx = bx0 + (bx1 - bx0) * f
            withContext(Dispatchers.Main) {
                sendMultiTouch(
                    t0, uptime(), android.view.MotionEvent.ACTION_MOVE,
                    listOf(ax to cy, bx to cy),
                )
            }
        }
        withContext(Dispatchers.Main) {
            sendMultiTouch(
                t0, uptime(),
                android.view.MotionEvent.ACTION_POINTER_UP or
                    (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(ax1 to cy, bx1 to cy),
            )
            sendMultiTouch(t0, uptime() + 10, android.view.MotionEvent.ACTION_UP, listOf(ax1 to cy))
        }
    }

    private fun sendMultiTouch(
        downTime: Long,
        eventTime: Long,
        action: Int,
        points: List<Pair<Float, Float>>,
    ) {
        val props = Array(points.size) { i ->
            android.view.MotionEvent.PointerProperties().apply {
                id = i
                toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(points.size) { i ->
            android.view.MotionEvent.PointerCoords().apply {
                x = points[i].first
                y = points[i].second
                pressure = 1f
                size = 1f
            }
        }
        val ev = android.view.MotionEvent.obtain(
            downTime, eventTime, action, points.size, props, coords,
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        webView.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** Shared tail for viewport / full-page / element screenshots. */
    private suspend fun finishScreenshot(
        bitmap: Bitmap,
        fullPage: Boolean = false,
        truncated: Boolean = false,
        originalHeightPx: Int = 0,
        note: String? = null,
        scale: Float = 1f,
    ): BrowserActionResult {
        // [T-android-browser-shot-scale-2026-10] Optional downscale before
        // encoding — the JPEG bytes are the token cost, so half-size shots
        // (scale=0.5) cut it ~4x.
        val outBitmap = if (scale < 0.999f) {
            val sw = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val sh = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val scaled = try { Bitmap.createScaledBitmap(bitmap, sw, sh, true) } catch (_: Throwable) { null }
            if (scaled != null) { bitmap.recycle(); scaled } else bitmap
        } else bitmap
        val w = outBitmap.width; val h = outBitmap.height
        val file = try {
            withContext(Dispatchers.IO) { saveBitmapToFile(outBitmap, "screenshot") }
        } finally {
            outBitmap.recycle()
        }
        // Encode once and release native pixels before creating the protocol payload.
        val base64 = withContext(Dispatchers.IO) {
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        }
        val fileSize = file.length().toInt()
        Log.i(TAG, "Screenshot saved: ${file.absolutePath}, ${w}x$h, $fileSize bytes (full_page=$fullPage)")

        val meta = buildString {
            append(viewportMetadata(
                imageW = w,
                imageH = h,
                fileSize = fileSize,
                fullPage = fullPage,
                truncated = truncated,
                originalHeightPx = originalHeightPx,
            ))
            if (note != null) {
                append("\n  ").append(note)
            }
        }

        return BrowserActionResult(
            text = meta, base64Image = base64, imageFilePath = file.absolutePath
        )
    }

    /** [T-android-browser-gestures] Crop the current viewport to one element. */
    private suspend fun elementScreenshot(selector: String, scale: Float? = null): BrowserActionResult {
        val rectRaw = runCatching { evaluateJavascript(BrowserUseJS.elementRect(selector)) }.getOrNull()
            ?: return BrowserActionResult.error("Element screenshot failed: no rect for $selector")
        val rect = runCatching { JSONObject(rectRaw) }.getOrNull()
            ?: return BrowserActionResult.error("Element screenshot failed: bad rect JSON")
        rect.optString("error").takeIf { it.isNotBlank() }?.let {
            return BrowserActionResult.error("Element screenshot failed: $it")
        }
        val cssX = rect.optDouble("x", -1.0)
        val cssY = rect.optDouble("y", -1.0)
        val cssW = rect.optDouble("w", 0.0)
        val cssH = rect.optDouble("h", 0.0)
        if (cssX < 0 || cssY < 0 || cssW < 1 || cssH < 1) {
            return BrowserActionResult.error("Element has no visible box (is it hidden?)")
        }
        delay(250) // let scrollIntoView settle
        val density = withContext(Dispatchers.Main) { webView.resources.displayMetrics.density }
        // [T-android-shot-gpu-first-2026-10] Same rationale as screenshot():
        // the software draw of a detached WebView misses GPU-composited
        // layers and half-rastered frames; prefer the GPU capture path and
        // fall back to software when it does not apply.
        val bitmap = captureWebViewBitmap(gpu = true) ?: captureWebViewBitmap()
            ?: return BrowserActionResult.error("Failed to capture the viewport")
        val phys = withContext(Dispatchers.Main) { webView.width to webView.height }
        if (phys.first <= 0 || phys.second <= 0) {
            bitmap.recycle()
            return BrowserActionResult.error("WebView has no layout box")
        }
        // The captured bitmap may be budget-scaled; map CSS -> bitmap space.
        val scale = bitmap.width.toFloat() / phys.first
        var bx = (cssX * density * scale).toInt()
        var by = (cssY * density * scale).toInt()
        var bw = (cssW * density * scale).toInt()
        var bh = (cssH * density * scale).toInt()
        bx = bx.coerceIn(0, bitmap.width - 1)
        by = by.coerceIn(0, bitmap.height - 1)
        bw = bw.coerceIn(1, bitmap.width - bx)
        bh = bh.coerceIn(1, bitmap.height - by)
        val cropped = try {
            Bitmap.createBitmap(bitmap, bx, by, bw, bh)
        } catch (t: Throwable) {
            null
        } finally {
            bitmap.recycle()
        }
        if (cropped == null) return BrowserActionResult.error("Element crop failed")
        return finishScreenshot(
            cropped,
            note = "Element: $selector (${cssW.toInt()}x${cssH.toInt()} CSS px)",
            scale = clampShotScale(scale),
        )
    }

    // -- [T-android-browser-gestures] Injected touch gestures --

    private fun uptime() = android.os.SystemClock.uptimeMillis()

    /** Current visualViewport.scale, or null when unavailable. */
    private suspend fun readVisualScale(): Float? {
        val raw = runCatching {
            evaluateJavascript("String(window.visualViewport ? window.visualViewport.scale : '')")
        }.getOrNull()?.trim() ?: return null
        return raw.toFloatOrNull()
    }

    private fun sendTouchEvent(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
        val ev = android.view.MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        webView.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private suspend fun resolveCssPoint(selector: String?, x: Int?, y: Int?): Pair<Float, Float>? {
        if (!selector.isNullOrBlank()) {
            val raw = runCatching { evaluateJavascript(BrowserUseJS.elementRect(selector)) }.getOrNull()
                ?: return null
            val rect = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            if (rect.optString("error").isNotBlank()) return null
            val cx = rect.optDouble("x", -1.0) + rect.optDouble("w", 0.0) / 2
            val cy = rect.optDouble("y", -1.0) + rect.optDouble("h", 0.0) / 2
            if (cx < 0 || cy < 0) return null
            delay(150)
            return Pair(cx.toFloat(), cy.toFloat())
        }
        if (x != null && y != null) return Pair(x.toFloat(), y.toFloat())
        return null
    }

    /**
     * Real input injection through WebView.dispatchTouchEvent — the same
     * path a finger takes, so touch handlers, context menus and drag-sorting
     * all see genuine events (script-only dispatches do not).
     */
    private suspend fun performGesture(input: BrowserActionInput): BrowserActionResult {
        if (isDisposed) return BrowserActionResult.error("Browser tab is closed")
        val type = input.gesture?.trim()?.lowercase()?.replace("-", "_")
            ?: return BrowserActionResult.error("gesture requires 'gesture' (long_press | double_click | drag | pinch)")
        val point = resolveCssPoint(input.selector, input.coordinateX, input.coordinateY)
            ?: return BrowserActionResult.error(
                "Could not resolve the gesture target (selector not found, or pass coordinate_x / coordinate_y)",
            )
        val density = withContext(Dispatchers.Main) { webView.resources.displayMetrics.density }
        val sx = point.first * density
        val sy = point.second * density
        return when (type) {
            "long_press" -> {
                val t0 = uptime()
                withContext(Dispatchers.Main) { sendTouchEvent(t0, t0, android.view.MotionEvent.ACTION_DOWN, sx, sy) }
                delay(300)
                withContext(Dispatchers.Main) { sendTouchEvent(t0, uptime(), android.view.MotionEvent.ACTION_MOVE, sx, sy) }
                delay(350)
                withContext(Dispatchers.Main) { sendTouchEvent(t0, uptime(), android.view.MotionEvent.ACTION_UP, sx, sy) }
                BrowserActionResult(
                    text = "Long-pressed (" + point.first.toInt() + ", " + point.second.toInt() + ") for ~650ms",
                )
            }
            "double_click", "double_tap" -> {
                val t0 = uptime()
                withContext(Dispatchers.Main) {
                    sendTouchEvent(t0, t0, android.view.MotionEvent.ACTION_DOWN, sx, sy)
                    sendTouchEvent(t0, t0 + 40, android.view.MotionEvent.ACTION_UP, sx, sy)
                }
                delay(110)
                withContext(Dispatchers.Main) {
                    val t1 = uptime()
                    sendTouchEvent(t1, t1, android.view.MotionEvent.ACTION_DOWN, sx, sy)
                    sendTouchEvent(t1, t1 + 40, android.view.MotionEvent.ACTION_UP, sx, sy)
                }
                BrowserActionResult(
                    text = "Double-tapped (" + point.first.toInt() + ", " + point.second.toInt() + ")",
                )
            }
            "drag" -> {
                val tx = input.toX
                val ty = input.toY
                if (tx == null || ty == null) {
                    return BrowserActionResult.error("gesture=drag requires 'to_x' and 'to_y' (viewport CSS px)")
                }
                val ex = tx * density
                val ey = ty * density
                val t0 = uptime()
                withContext(Dispatchers.Main) { sendTouchEvent(t0, t0, android.view.MotionEvent.ACTION_DOWN, sx, sy) }
                delay(200) // press-and-hold so lists enter drag mode
                val steps = 12
                for (i in 1..steps) {
                    delay(30)
                    val f = i / steps.toFloat()
                    val x = sx + (ex - sx) * f
                    val y = sy + (ey - sy) * f
                    withContext(Dispatchers.Main) {
                        sendTouchEvent(t0, uptime(), android.view.MotionEvent.ACTION_MOVE, x, y)
                    }
                }
                withContext(Dispatchers.Main) { sendTouchEvent(t0, uptime(), android.view.MotionEvent.ACTION_UP, ex, ey) }
                BrowserActionResult(
                    text = "Dragged (" + point.first.toInt() + ", " + point.second.toInt() +
                        ") -> (" + tx + ", " + ty + ")",
                )
            }
            "pinch" -> {
                val spread = (input.amount ?: 100).coerceIn(-600, 600).toFloat()
                if (spread == 0f) {
                    return BrowserActionResult.error("gesture=pinch needs a non-zero 'amount' (CSS px)")
                }
                val before = readVisualScale()
                performPinch(point.first, point.second, spread)
                delay(250)
                val after = readVisualScale()
                val zoomed = before != null && after != null && kotlin.math.abs(after - before) > 0.01f
                if (zoomed) {
                    BrowserActionResult(
                        text = "Pinched " + (if (spread > 0) "out (zoom in)" else "in (zoom out)") +
                            " around (" + point.first.toInt() + ", " + point.second.toInt() + ") by " +
                            kotlin.math.abs(spread).toInt() + "px" +
                            " — scale " + before + " -> " + after,
                    )
                } else {
                    // [T-android-browser-pinch-fallback] The synthetic stream
                    // reaches the page, but the native pinch recognizer can
                    // ignore it (it normally consumes real window input). Fall
                    // back to a programmatic zoom so the visible result lands.
                    val factor = (1f + spread / 400f).coerceIn(0.4f, 3f)
                    withContext(Dispatchers.Main) { webView.zoomBy(factor) }
                    delay(350)
                    val now = readVisualScale()
                    BrowserActionResult(
                        text = "Pinch injected (page handlers received it); native zoom did not engage, " +
                            "so a programmatic zoom was applied: zoomBy(" + factor + ")" +
                            ", scale " + before + " -> " + now +
                            (if (spread < 0) " (zoom out)" else " (zoom in)"),
                    )
                }
            }
            else -> BrowserActionResult.error(
                "Unknown gesture '" + type + "' (use long_press | double_click | drag | pinch)",
            )
        }
    }

    /** Collect viewport + page metadata for screenshot results. Mirrors iOS viewportMetadata. */
    private suspend fun viewportMetadata(
        imageW: Int,
        imageH: Int,
        fileSize: Int,
        fullPage: Boolean = false,
        truncated: Boolean = false,
        originalHeightPx: Int = 0,
    ): String {
        val url = _currentURL.value
        val title = _pageTitle.value

        val scrollInfo = evaluateJavascript(
            "JSON.stringify({" +
                "sx:window.scrollX||0,sy:window.scrollY||0," +
                "pw:document.documentElement.scrollWidth||0," +
                "ph:document.documentElement.scrollHeight||0," +
                "vw:window.innerWidth||0,vh:window.innerHeight||0" +
                "})"
        )
        var scrollX = 0; var scrollY = 0; var pageW = 0; var pageH = 0
        var vpW = 0; var vpH = 0
        try {
            val info = JSONObject(scrollInfo)
            scrollX = info.optInt("sx"); scrollY = info.optInt("sy")
            pageW = info.optInt("pw"); pageH = info.optInt("ph")
            vpW = info.optInt("vw"); vpH = info.optInt("vh")
        } catch (_: Exception) {}

        val fallback = currentProfile.viewportSize
        val effectiveVpW = if (vpW > 0) vpW else fallback.first
        val effectiveVpH = if (vpH > 0) vpH else fallback.second

        return buildString {
            appendLine("Screenshot captured")
            appendLine("  URL: $url")
            if (title.isNotEmpty()) appendLine("  Title: $title")
            appendLine("  Image: ${imageW}x$imageH (${fileSize / 1024}KB)")
            appendLine("  Viewport: ${effectiveVpW}x$effectiveVpH")
            if (pageW > 0 || pageH > 0) appendLine("  Page size: ${pageW}x$pageH")
            if (fullPage) {
                appendLine("  Full page: true")
                if (originalHeightPx > 0) appendLine("  Original height: ${originalHeightPx}px")
                if (truncated) appendLine("  Truncated: true (capped at ${MAX_FULL_PAGE_HEIGHT_PX}px)")
            }
            append("  Scroll position: ($scrollX, $scrollY)")
        }
    }

    /**
     * Public live-preview snapshot — mirrors iOS `webView.takeSnapshot()`.
     * Called by the UI on a timer (e.g. every 3s while a tool is streaming) so
     * the Minis Computer sheet and FloatingToolStatusBar can show the browser
     * state even for actions that don't save an imageFilePath (get_readable,
     * get_text, execute_js, fetch, etc.).
     */
    suspend fun captureLiveSnapshot(): Bitmap? = captureWebViewBitmap()

    /**
     * [T-android-browser-screenshot-scroll-offset] The page's current scroll
     * offset in CSS pixels, as (x, y).
     *
     * Read from JS rather than from `webView.scrollX/scrollY` because the pool
     * WebView is usually detached, and a detached View never receives the
     * scroll pass that would update those fields — measured on device as
     * `scrollY=0` while the page was scrolled 300 CSS px. The authoritative
     * value lives in the renderer.
     *
     * Must be called on the main thread (the caller already is). Uses its own
     * short timeout and degrades to (0,0): a capture that is merely
     * uncompensated is the old behaviour, which is far better than no capture
     * at all, and `evaluateJavascript` cannot answer while the renderer is
     * wedged — exactly when a screenshot is most wanted.
     */
    private suspend fun readCssScrollOffset(): Pair<Float, Float> {
        val raw = withTimeoutOrNull(SCROLL_PROBE_TIMEOUT_MS) {
            val d = CompletableDeferred<String>()
            webView.evaluateJavascript(
                "(function(){return (window.scrollX||window.pageXOffset||0)+','+" +
                    "(window.scrollY||window.pageYOffset||0);})()",
            ) { v -> if (!d.isCompleted) d.complete(v ?: "") }
            d.await()
        } ?: return 0f to 0f
        // evaluateJavascript hands back a JSON string literal: "12,300"
        val cleaned = raw.trim().removeSurrounding("\"")
        val parts = cleaned.split(',')
        if (parts.size != 2) return 0f to 0f
        val x = parts[0].toFloatOrNull() ?: 0f
        val y = parts[1].toFloatOrNull() ?: 0f
        return x to y
    }

    /**
     * [gpu] routes the capture through [HeadlessRenderHost] — a real GPU frame,
     * so WebGL / video are included — whenever the WebView is headless (not on
     * screen in the browser sheet). Agent-facing captures pass true; the 3 s
     * live-preview thumbnail and the stretched full-page capture keep the
     * cheaper software draw. Any GPU-path miss falls through to that draw.
     */
    private suspend fun captureWebViewBitmap(gpu: Boolean = false): Bitmap? = withContext(Dispatchers.Main) {
        try {
            // WebView may be detached (pool-owned, never added to a window), so
            // width/height can be 0. Ensure it has a layout box matching the
            // agent viewport before drawing.
            val density = webView.resources.displayMetrics.density
            var w = webView.width
            var h = webView.height
            // [T-android-shot-layout-sync-2026-10] A stale layout box is as
            // bad as none: after set_viewport/reset the pool re-asserts the
            // CSS size, but if any path skipped the re-layout the capture
            // still produced buffers at the PREVIOUS size (observed: profile
            // 412x915 reported, 2688x1512 bitmap out). Validate the box
            // against the APPLIED viewport on EVERY capture and re-measure
            // when they disagree — not just when w<=0.
            //
            // The authority is `lastAppliedViewport` (what applyViewport last
            // laid out — includes the pool's session override), NOT
            // currentProfile: the profile is the UA default (412x915) and
            // re-asserting it here would fight set_viewport's 800x600, right
            // after the pool had correctly laid the bigger box out.
            val vp = lastAppliedViewport ?: currentProfile.viewportSize
            val targetW = (vp.first * density).toInt().coerceAtLeast(1)
            val targetH = (vp.second * density).toInt().coerceAtLeast(1)
            if (w != targetW || h != targetH) {
                webView.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(targetW, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(targetH, android.view.View.MeasureSpec.EXACTLY),
                )
                webView.layout(0, 0, targetW, targetH)
                w = targetW; h = targetH
            }
            if (gpu) HeadlessRenderHost.capture(webView, w, h)?.let { return@withContext it }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            // [T-android-browser-screenshot-scroll-offset] Undo the scroll
            // before drawing, or the capture is the scrolled-away region.
            //
            // `View.draw(Canvas)` does NOT compensate scroll — that is done by
            // the PARENT, in `draw(Canvas, ViewGroup, long)`:
            //     canvas.translate(mLeft - sx, mTop - sy)   (View.java)
            // Drawing straight into our own bitmap skips that parent pass, so
            // the content lands `scrollOffset` lower than it should: the top of
            // the image is blank and the bottom is cropped. Measured on a
            // Pixel 6 at CSS scrollY=300 — 788 physical px of white
            // (788 / 2.625 density = 300.2 CSS px, i.e. exactly scrollY) and
            // the Y300 band sitting at viewport y=300 instead of y=0. Once
            // scrollY reaches the viewport height the blank band fills the
            // whole frame, which is the "scroll 1500 → 100% white" report;
            // same defect, not a second one.
            //
            // The offset CANNOT be read from `webView.scrollY` alone. This
            // WebView is pool-owned and, whenever the browser sheet is closed,
            // not attached to a window — and a detached View never gets the
            // scroll pass that would update its own scrollX/scrollY. Verified
            // on device: after `window.scrollTo(0, 300)` the capture logged
            // `scrollY=0 attached=false` while the bitmap was still offset by
            // 300 CSS px, because the scroll lives in Chromium's compositor.
            // A plain `canvas.translate(-webView.scrollY)` would translate by
            // zero there and fix nothing, which is why the offset is probed
            // from JS as well.
            //
            // The two sources describe ONE quantity, so take whichever is
            // populated — never their sum. When the sheet is mounted the View
            // mirrors the compositor scroll, and adding both double-counts:
            // measured on device at CSS scrollY=300 as
            // `css=300.19 view=788 offset=1576` (788 = 300.19 × 2.625), which
            // scrolls the capture twice as far as the page actually is.
            // `webView.scrollY` wins when non-zero because it is already in
            // physical px and carries no density-rounding error; the CSS probe
            // covers the detached case, where the View stays at 0.
            val cssScroll = readCssScrollOffset()
            val offsetX = if (webView.scrollX != 0) webView.scrollX.toFloat() else cssScroll.first * density
            val offsetY = if (webView.scrollY != 0) webView.scrollY.toFloat() else cssScroll.second * density
            if (offsetX != 0f || offsetY != 0f) canvas.translate(-offsetX, -offsetY)
            webView.draw(canvas)
            Log.d(TAG, "captureWebViewBitmap ${w}x$h offset=(${offsetX},${offsetY}) " +
                "css=(${cssScroll.first},${cssScroll.second}) view=(${webView.scrollX},${webView.scrollY})")
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "captureWebViewBitmap failed: ${e.message}")
            null
        }
    }

    private suspend fun selectOption(selector: String?, want: String?): BrowserActionResult {
        if (selector == null) return BrowserActionResult.error("select_option requires 'selector'")
        if (want == null) return BrowserActionResult.error("select_option requires 'text' (option label or value)")
        return evaluateAndReturn(BrowserUseJS.selectOption(selector, want))
    }

    /** Poll until a selector appears and/or a text shows up (default 10 s). */
    private suspend fun waitFor(selector: String?, text: String?, timeoutMs: Int?): BrowserActionResult {
        if (selector == null && text == null) {
            return BrowserActionResult.error("wait_for requires 'selector' and/or 'text'")
        }
        // The tool contract passes 'timeout' in SECONDS (like wait_for_dom_stable).
        val timeoutSec = (timeoutMs?.takeIf { it > 0 } ?: 10).coerceAtMost(120)
        val timeout = timeoutSec * 1000L
        val started = System.currentTimeMillis()
        val deadline = started + timeout
        val sel = selector ?: "html"
        while (System.currentTimeMillis() < deadline) {
            if (isDisposed) return BrowserActionResult.error("Browser tab is closed")
            val raw = runCatching { evaluateJavascript(BrowserUseJS.waitProbe(sel, text)) }.getOrNull()
            val obj = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (obj?.optBoolean("ok") == true) {
                val matched = obj.optString("matched")
                val tag = obj.optString("tag")
                val secs = (System.currentTimeMillis() - started) / 1000.0
                return BrowserActionResult(
                    text = "Condition met ($matched" + (if (tag.isNotBlank()) ", <$tag>" else "") + ") after " + secs + "s",
                )
            }
            kotlinx.coroutines.delay(250)
        }
        return BrowserActionResult.error(
            "Timed out after " + timeoutSec + "s waiting for " +
                (selector ?: "") + (if (text != null) " text=" + JSONObject.quote(text) else ""),
        )
    }

    // -- [T-android-net-capacity-2026-10] Network log v2 (headers, bodies, paging) --

    private suspend fun getNetworkLogAction(input: BrowserActionInput): BrowserActionResult {
        val limit = (input.limit ?: 20).coerceIn(1, 100)
        val offset = (input.offset ?: 0).coerceAtLeast(0)
        val full = input.full == true
        val raw = runCatching {
            evaluateJavascript(BrowserUseJS.networkLogQuery(input.filter, offset, limit, full))
        }.getOrNull() ?: return BrowserActionResult.error("get_network_log: page did not answer")
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return BrowserActionResult.error("get_network_log: bad JSON from page")
        val total = json.optInt("total", 0)
        val entries = json.optJSONArray("entries")
        val sb = StringBuilder()
        if (entries == null || entries.length() == 0) {
            sb.append(
                "No matching fetch/XHR requests (ring total: $total" +
                    (input.filter?.let { ", filter: $it" } ?: "") + ").",
            )
        } else {
            sb.appendLine("Fetch/XHR log — ${entries.length()} of $total (offset $offset, newest first, full=$full):")
            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
                sb.appendLine(
                    "#${e.optInt("id")} [${e.optLong("tf")}ms ago, ${e.optInt("s")}, ${e.optLong("ms")}ms] " +
                        "${e.optString("m")} ${e.optString("u")}",
                )
                val meta = mutableListOf<String>()
                meta += "resp ${formatBytes(e.optInt("bl", 0))}" + if (e.optBoolean("tb")) " (full stored)" else ""
                val qb = e.optString("qb")
                if (qb.isNotEmpty()) meta += "req body: " + qb.replace("\n", " ").take(160)
                val qh = e.optJSONObject("qh")
                if (qh != null) {
                    val names = mutableListOf<String>()
                    val keys = qh.keys()
                    while (keys.hasNext()) names += keys.next()
                    if (names.isNotEmpty()) meta += "req headers: " + names.joinToString(", ")
                }
                if (meta.isNotEmpty()) sb.appendLine("    " + meta.joinToString("  ·  "))
                val body = e.optString("b")
                if (body.isNotBlank()) sb.appendLine("    body: " + body.replace("\n", " ").replace("\r", " "))
            }
        }
        val trail = readNetworkLog(input.filter, 15)
        if (trail.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Page request trail (subresources incl. main frame; last ${trail.size}):")
            for (line in trail) sb.appendLine("  $line")
        }
        return BrowserActionResult(text = sb.toString().trimEnd())
    }

    private suspend fun getResponseLogAction(input: BrowserActionInput): BrowserActionResult {
        val limit = (input.limit ?: 20).coerceIn(1, 100)
        val full = input.full == true
        val raw = runCatching {
            evaluateJavascript(BrowserUseJS.networkLogQuery(input.filter, 0, limit, full))
        }.getOrNull() ?: return BrowserActionResult.error("get_response_log: page did not answer")
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return BrowserActionResult.error("get_response_log: bad JSON from page")
        val entries = json.optJSONArray("entries")
        if (entries == null || entries.length() == 0) {
            return BrowserActionResult(
                text = "No fetch/XHR responses captured for this tab yet" +
                    (input.filter?.let { " (filter: $it)" } ?: "") + ".",
            )
        }
        val sb = StringBuilder()
        sb.appendLine(
            "Response bodies — last ${entries.length()} fetch/XHR entries " +
                (if (full) "(full stored bodies where available)" else "(previews; full=true for full bodies)") + ":",
        )
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            sb.appendLine(
                "[#${e.optInt("id")} ${e.optLong("tf")}ms ago, ${e.optInt("s")}, ${e.optLong("ms")}ms] " +
                    "${e.optString("m")} ${e.optString("u")} (${formatBytes(e.optInt("bl", 0))}" +
                    (if (e.optBoolean("tb")) ", full stored" else "") + ")",
            )
            val b = e.optString("b")
            if (b.isNotBlank()) sb.appendLine("    " + b.replace("\n", " ").replace("\r", " ").take(3000))
        }
        return BrowserActionResult(text = sb.toString().trimEnd())
    }

    // -- [T-android-request-replay-2026-10] --

    private suspend fun replayRequest(input: BrowserActionInput): BrowserActionResult {
        val js = BrowserUseJS.replayRequest(input.index, input.filter, input.method, input.body, input.headers)
        val raw = awaitPromiseJs(js)
            ?: return BrowserActionResult.error("replay_request timed out (60s)")
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return BrowserActionResult.error("replay_request: bad result from page")
        if (json.has("error")) return BrowserActionResult.error("replay_request: ${json.optString("error")}")
        val sb = StringBuilder()
        sb.appendLine("Replayed ${json.optString("method")} ${json.optString("url")}")
        sb.appendLine("  Status: ${json.optInt("status")} (${json.optLong("ms")}ms, ${formatBytes(json.optInt("size", 0))})")
        val body = json.optString("body")
        if (body.isNotBlank()) {
            sb.appendLine("  Body" + (if (json.optBoolean("truncated")) " (first 4000 chars)" else "") + ":")
            sb.append(body)
        }
        return BrowserActionResult(text = sb.toString().trimEnd())
    }

    // -- [T-android-net-capacity-2026-10] copy_as_curl --

    private suspend fun copyAsCurl(input: BrowserActionInput): BrowserActionResult {
        val idx = (input.index ?: 1).coerceAtLeast(1)
        val raw = runCatching {
            evaluateJavascript(BrowserUseJS.networkLogQuery(input.filter, idx - 1, 1, false))
        }.getOrNull() ?: return BrowserActionResult.error("copy_as_curl: page did not answer")
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return BrowserActionResult.error("copy_as_curl: bad JSON from page")
        val total = json.optInt("total", 0)
        val entries = json.optJSONArray("entries")
        if (entries == null || entries.length() == 0) {
            return BrowserActionResult(
                text = "copy_as_curl: no captured request at index $idx" +
                    (input.filter?.let { " (filter: $it)" } ?: "") + " — $total matching in total." +
                    " Only in-page fetch/XHR requests are captured (the main document navigation is not);" +
                    " trigger the request from the page, or check get_network_log's page request trail.",
                success = false,
            )
        }
        val e = entries.optJSONObject(0) ?: return BrowserActionResult.error("copy_as_curl: empty entry")
        val method = e.optString("m", "GET")
        val url = e.optString("u")
        val qh = e.optJSONObject("qh")
        val qb = e.optString("qb").takeIf { it.isNotEmpty() && !it.startsWith("[") }
        val liveCookie = withContext(Dispatchers.Main) {
            runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        }
        val cmd = StringBuilder("curl")
        if (!method.equals("GET", ignoreCase = true)) cmd.append(" -X ").append(method)
        cmd.append(' ').append(shQuote(url))
        // [T-android-net-capacity-v2-fu-2026-10] Real-browser fingerprint
        // headers: WAFs (Huawei devdata etc.) reject bare curl, and the captured
        // fetch headers never include the browser's forbidden headers — add
        // them here, skipping any the capture already carries.
        val capturedNames = mutableSetOf<String>()
        if (qh != null) {
            val ck = qh.keys()
            while (ck.hasNext()) capturedNames.add(ck.next().lowercase())
        }
        val uaForCurl = withContext(Dispatchers.Main) {
            runCatching { webView.settings.userAgentString }.getOrNull()
        }
        if (!uaForCurl.isNullOrEmpty() && "user-agent" !in capturedNames) {
            cmd.append(" -H ").append(shQuote("User-Agent: $uaForCurl"))
        }
        if ("accept" !in capturedNames) cmd.append(" -H ").append(shQuote("Accept: */*"))
        if ("accept-language" !in capturedNames) {
            cmd.append(" -H ").append(shQuote("Accept-Language: zh-CN,zh;q=0.9,en;q=0.8"))
        }
        if ("sec-fetch-site" !in capturedNames) cmd.append(" -H ").append(shQuote("sec-fetch-site: same-origin"))
        if ("sec-fetch-mode" !in capturedNames) cmd.append(" -H ").append(shQuote("sec-fetch-mode: cors"))
        if ("sec-fetch-dest" !in capturedNames) cmd.append(" -H ").append(shQuote("sec-fetch-dest: empty"))
        if (qh != null) {
            val keys = qh.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                when (k.lowercase()) {
                    "cookie", "host", "content-length" -> {}
                    else -> cmd.append(" -H ").append(shQuote("$k: ${qh.optString(k)}"))
                }
            }
        }
        if (!liveCookie.isNullOrEmpty()) cmd.append(" -H ").append(shQuote("Cookie: $liveCookie"))
        if (qb != null) cmd.append(" --data-raw ").append(shQuote(qb))
        return BrowserActionResult(
            text = buildString {
                appendLine("cURL for #${e.optInt("id")} $method $url:")
                appendLine()
                appendLine(cmd.toString())
                appendLine()
                append(
                    "Cookies come from the live browser jar for this URL" +
                        (if (qb != null) "; body from the captured request (${qb.length} chars)" else "") +
                        ". Browser fingerprint headers (UA/Accept/sec-fetch-*) were added so WAFs don't reject a bare curl.",
                )
            },
        )
    }

    // -- [T-android-fill-form-2026-10] Batch form filling --

    private suspend fun fillForm(fieldsJson: String?, submit: Boolean?): BrowserActionResult {
        if (fieldsJson.isNullOrBlank()) {
            return BrowserActionResult.error(
                "fill_form requires 'fields' = a JSON array like " +
                    "[{\"selector\":\"text=E-mail\",\"value\":\"a@b.c\"},{\"selector\":\"#country\",\"value\":\"CN\",\"action\":\"select\"}]",
            )
        }
        val arr = runCatching { org.json.JSONArray(fieldsJson) }.getOrNull()
            ?: return BrowserActionResult.error("fill_form: 'fields' is not a JSON array")
        if (arr.length() == 0) return BrowserActionResult.error("fill_form: empty fields array")
        val lines = mutableListOf<String>()
        var ok = 0
        var lastSelector: String? = null
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val sel = f.optString("selector").takeIf { it.isNotBlank() }
            if (sel == null) {
                lines += "  #${i + 1} skipped (no selector)"
                continue
            }
            val action = f.optString("action").lowercase().ifBlank { "fill" }
            val value = f.optString("value")
            val r: BrowserActionResult = when (action) {
                "select" -> selectOption(sel, value)
                "check", "uncheck", "click", "toggle" -> click(sel, null, null)
                "press" -> pressKey(value, sel)
                else -> type(sel, value)
            }
            if (r.success) {
                ok++
                lines += "  #${i + 1} $action $sel → OK"
            } else {
                lines += "  #${i + 1} $action $sel → FAILED: " + r.text.lineSequence().first().take(140)
            }
            lastSelector = sel
            kotlinx.coroutines.delay(120)
        }
        var submitNote = ""
        if (submit == true && lastSelector != null) {
            val pr = pressKey("Enter", lastSelector)
            submitNote = "\nSubmit: pressed Enter on $lastSelector — " + if (pr.success) "OK" else pr.text.lineSequence().first().take(140)
        }
        return BrowserActionResult(
            text = "fill_form: $ok/${arr.length()} field(s) applied:\n" + lines.joinToString("\n") + submitNote,
            success = ok > 0,
        )
    }

    /** Wipe cookies + web storage for the whole WebView profile. */
    private suspend fun clearSiteData(): BrowserActionResult {
        return try {
            withContext(Dispatchers.Main) {
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                android.webkit.CookieManager.getInstance().flush()
                android.webkit.WebStorage.getInstance().deleteAllData()
            }
            BrowserActionResult(text = "Cleared cookies and web storage for this browser profile (site logins are gone).")
        } catch (t: Throwable) {
            BrowserActionResult.error("clear_site_data failed: " + t.message)
        }
    }

    /** Register the fetch/XHR+error instrumentation as early as possible. */
    private fun installEarlyScripts() {
        val net = BrowserUseJS.EARLY_NET_INSTRUMENTATION_JS
        val helpers = BrowserUseJS.QUERY_HELPERS_JS
        // [T-android-viewport-guard-2026-10] Third script: rewrites malformed
        // viewport metas (initial-scale=0) before the page can be clamped.
        val vpGuard = BrowserUseJS.VIEWPORT_GUARD_JS
        try {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(
                    androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT,
                )
            ) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, net, setOf("*"))
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, helpers, setOf("*"))
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, vpGuard, setOf("*"))
                Log.d(TAG, "net + query + viewport instrumentation registered at document start")
                return
            }
        } catch (t: Throwable) {
            Log.w(TAG, "document-start script unsupported: ${t.message}")
        }
        // Fallback: inject on page start (all three scripts are idempotent).
        earlyScriptFallback = net + "\n" + helpers + "\n" + vpGuard
        Log.d(TAG, "net + query + viewport instrumentation registered via page-start fallback")
    }

    private fun saveBitmapToFile(bitmap: Bitmap, prefix: String, quality: Int = SCREENSHOT_QUALITY): File {
        val filename = "${prefix}_${System.currentTimeMillis()}.jpg"
        val file = File(screenshotsDir, filename)
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        return file
    }

    // -- Click --

    private suspend fun click(selector: String?, x: Int?, y: Int?): BrowserActionResult {
        val js = when {
            selector != null -> BrowserUseJS.click(selector)
            x != null && y != null -> BrowserUseJS.clickCoordinate(x, y)
            else -> return BrowserActionResult.error("click requires 'selector' or 'coordinate_x'/'coordinate_y'")
        }
        // Raw evaluation (not evaluateAndReturn) so a target=_blank pre-empt can
        // be detected before formatting — see below.
        val raw = try {
            evaluateJavascript(js)
        } catch (e: Exception) {
            return BrowserActionResult.error("JavaScript error: ${e.message}")
        }
        val json = runCatching { JSONObject(raw) }.getOrNull()
        // [T-android-blank-link-2026-10] The popup blocker swallows new windows
        // from scripted clicks; the JS handed back the href instead of clicking.
        if (json != null && json.optBoolean("needsNewTab")) {
            val url = json.optString("url")
            if (url.isNotEmpty()) {
                onNewTabRequest?.invoke(url)
                val label = json.optString("text").take(60)
                return BrowserActionResult(
                    text = "Clicked a target=_blank link" + (if (label.isNotBlank()) " (\"$label\")" else "") +
                        " — the popup blocker swallows new windows from scripted clicks, so it was opened " +
                        "as a background browser tab instead: $url (find its tab_id via list_tabs).",
                )
            }
        }
        val result = if (json != null) {
            if (json.has("error")) BrowserActionResult.error(json.getString("error"))
            else BrowserActionResult(text = formatJSONResult(json))
        } else {
            BrowserActionResult(text = raw)
        }
        // [T-android-browser-click-nav-2026-10] A click that starts a
        // navigation returns while the load is still in flight. Wait
        // (bounded) so the URL-change suffix below reports the settled URL
        // instead of a stale one, and the auto-snapshot doesn't catch a
        // half-white page.
        if (result.success && _isLoading.value) {
            withTimeoutOrNull(3_000L) {
                while (_isLoading.value) kotlinx.coroutines.delay(100)
            }
        }
        return result
    }

    // -- [T-android-browser-smart-selectors-2026-10] press_key --

    /**
     * Inject a REAL key event through [WebView.dispatchKeyEvent] — the same
     * native-input channel as the touch gestures — so React/Vue key handlers
     * (Enter-to-send, Escape, Tab focus walks) see a trusted event instead of
     * a synthetic KeyboardEvent. Combos: "ctrl+enter", "shift+tab", "meta+a".
     */
    private suspend fun pressKey(key: String?, selector: String?): BrowserActionResult {
        if (key.isNullOrBlank()) {
            return BrowserActionResult.error("press_key requires 'key' (e.g. Enter, Tab, ctrl+Enter)")
        }
        var meta = 0
        var base: String? = null
        for (part in key.split("+").map { it.trim() }.filter { it.isNotEmpty() }) {
            when (part.lowercase()) {
                "ctrl", "control" -> meta = meta or android.view.KeyEvent.META_CTRL_ON
                "shift" -> meta = meta or android.view.KeyEvent.META_SHIFT_ON
                "alt" -> meta = meta or android.view.KeyEvent.META_ALT_ON
                "meta", "win", "cmd", "super" -> meta = meta or android.view.KeyEvent.META_META_ON
                else -> base = part
            }
        }
        val b = base ?: return BrowserActionResult.error("press_key: no base key in '$key' (modifiers only)")
        val code = keyCodeFor(b) ?: return BrowserActionResult.error(
            "press_key: unsupported key '$b'. Supported: Enter, Escape, Tab, Space, Backspace, Delete, " +
                "ArrowUp/Down/Left/Right, Home, End, PageUp, PageDown, F1-F12, a-z, 0-9 (combine with ctrl/shift/alt/meta).",
        )
        if (!selector.isNullOrBlank()) {
            val raw = runCatching { evaluateJavascript(BrowserUseJS.focusElement(selector)) }.getOrNull().orEmpty()
            val j = runCatching { JSONObject(raw) }.getOrNull()
            if (j == null || j.has("error")) {
                return BrowserActionResult.error(
                    "press_key: could not focus '$selector'" +
                        (j?.optString("error")?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""),
                )
            }
            kotlinx.coroutines.delay(80)
        }
        withContext(Dispatchers.Main) {
            webView.requestFocus()
            val now = android.os.SystemClock.uptimeMillis()
            webView.dispatchKeyEvent(
                android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, code, 0, meta),
            )
            webView.dispatchKeyEvent(
                android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, code, 0, meta),
            )
        }
        val label = buildString {
            if (meta and android.view.KeyEvent.META_CTRL_ON != 0) append("Ctrl+")
            if (meta and android.view.KeyEvent.META_SHIFT_ON != 0) append("Shift+")
            if (meta and android.view.KeyEvent.META_ALT_ON != 0) append("Alt+")
            if (meta and android.view.KeyEvent.META_META_ON != 0) append("Meta+")
            append(b)
        }
        return BrowserActionResult(
            text = "Pressed $label (real key event via WebView)" +
                if (!selector.isNullOrBlank()) " — focused $selector first" else " — sent to the focused element",
        )
    }

    private fun keyCodeFor(name: String): Int? {
        return when (name.lowercase()) {
            "enter", "return" -> android.view.KeyEvent.KEYCODE_ENTER
            "escape", "esc" -> android.view.KeyEvent.KEYCODE_ESCAPE
            "tab" -> android.view.KeyEvent.KEYCODE_TAB
            "space", "spacebar" -> android.view.KeyEvent.KEYCODE_SPACE
            "backspace" -> android.view.KeyEvent.KEYCODE_DEL
            "delete", "del" -> android.view.KeyEvent.KEYCODE_FORWARD_DEL
            "arrowup", "up" -> android.view.KeyEvent.KEYCODE_DPAD_UP
            "arrowdown", "down" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
            "arrowleft", "left" -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
            "arrowright", "right" -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            "home" -> android.view.KeyEvent.KEYCODE_MOVE_HOME
            "end" -> android.view.KeyEvent.KEYCODE_MOVE_END
            "pageup" -> android.view.KeyEvent.KEYCODE_PAGE_UP
            "pagedown" -> android.view.KeyEvent.KEYCODE_PAGE_DOWN
            "f1" -> android.view.KeyEvent.KEYCODE_F1
            "f2" -> android.view.KeyEvent.KEYCODE_F2
            "f3" -> android.view.KeyEvent.KEYCODE_F3
            "f4" -> android.view.KeyEvent.KEYCODE_F4
            "f5" -> android.view.KeyEvent.KEYCODE_F5
            "f6" -> android.view.KeyEvent.KEYCODE_F6
            "f7" -> android.view.KeyEvent.KEYCODE_F7
            "f8" -> android.view.KeyEvent.KEYCODE_F8
            "f9" -> android.view.KeyEvent.KEYCODE_F9
            "f10" -> android.view.KeyEvent.KEYCODE_F10
            "f11" -> android.view.KeyEvent.KEYCODE_F11
            "f12" -> android.view.KeyEvent.KEYCODE_F12
            else -> {
                if (name.length == 1) {
                    val c = name[0]
                    when {
                        c in 'a'..'z' -> android.view.KeyEvent.KEYCODE_A + (c - 'a')
                        c in 'A'..'Z' -> android.view.KeyEvent.KEYCODE_A + (c - 'A')
                        c in '0'..'9' -> android.view.KeyEvent.KEYCODE_0 + (c - '0')
                        else -> null
                    }
                } else null
            }
        }
    }

    // -- Type --

    private suspend fun type(selector: String?, text: String?): BrowserActionResult {
        if (text == null) return BrowserActionResult.error("type requires 'text'")
        // [T-android-focus-typing-2026-10] No selector = type into whatever the
        // WebView has focused, via REAL key events. That is the only route into
        // an input inside a CROSS-ORIGIN iframe (page JS cannot reach it by
        // design); pair it with a coordinate tap to focus the field first.
        if (selector.isNullOrBlank()) return typeIntoFocusedElement(text)
        return evaluateAndReturn(BrowserUseJS.type(selector, text))
    }

    /**
     * [T-android-focus-typing-2026-10] Dispatch [text] as native key events
     * (KeyCharacterMap-based, so the focused frame sees real input — including
     * a cross-origin iframe, which JavaScript injection can never reach).
     * ASCII only: characters without a key mapping are reported, never
     * silently dropped.
     */
    private suspend fun typeIntoFocusedElement(text: String): BrowserActionResult {
        if (text.isEmpty()) return BrowserActionResult.error("type requires 'text'")
        if (text.length > 2000) {
            return BrowserActionResult.error(
                "typing without a selector is capped at 2000 chars per call — split longer text into multiple calls",
            )
        }
        val events = try {
            android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
                .getEvents(text.toCharArray())
        } catch (t: Throwable) {
            null
        }
        if (events.isNullOrEmpty()) {
            return BrowserActionResult.error(
                "This text has no key mapping (non-ASCII), so it can't be typed into a cross-origin frame. " +
                    "ASCII works here; for CJK, use a selector on a normal page.",
            )
        }
        withContext(Dispatchers.Main) {
            val now = android.os.SystemClock.uptimeMillis()
            for (ev in events) {
                webView.dispatchKeyEvent(
                    android.view.KeyEvent(now, now, ev.action, ev.keyCode, 0, ev.metaState),
                )
            }
        }
        return BrowserActionResult(
            text = "Typed ${text.length} char(s) into the focused element via native key events " +
                "(the route that works inside cross-origin iframes).",
        )
    }

    // -- Get Text --

    private suspend fun getText(selector: String?): BrowserActionResult {
        val js = BrowserUseJS.getText(selector)
        return evaluateJSAndParse(js)
    }

    // -- Get Readable --

    private suspend fun getReadable(): BrowserActionResult {
        return evaluateJSAndParse(BrowserUseJS.getReadable())
    }

    // -- Scroll --

    private suspend fun scroll(selector: String?, direction: ScrollDirection?, amount: Int?): BrowserActionResult {
        val dir = direction ?: ScrollDirection.DOWN
        val px = amount ?: 500
        val js = BrowserUseJS.scroll(dir, px, selector)
        return evaluateJSAndParse(js)
    }

    // -- Get Page Info --

    private suspend fun getPageInfo(): BrowserActionResult {
        return evaluateAndReturn(BrowserUseJS.getPageInfo())
    }

    // -- Execute JS --

    /**
     * [T-android-execjs-scriptfile-2026-10] Resolve a guest path (e.g.
     * /var/minis/workspace/big.js) to a host File, using the same rules as
     * upload_file. Returns null (with the reason) when unresolvable.
     */
    private fun resolveScriptFile(raw: String): Pair<File?, String?> {
        val ctx = appContext ?: return null to "no session context for path resolution"
        val sid = sessionIdProvider()
        val path = raw.trim()
        if (path.isEmpty()) return null to "empty path"
        fun sessionDir(kind: String): File? =
            sid?.let { com.openminis.app.sandbox.SessionMounts.sessionDir(ctx.filesDir, it, kind) }
        val file: File? = when {
            path.startsWith("/var/minis/workspace/") ->
                sessionDir("workspace")?.let { File(it, path.removePrefix("/var/minis/workspace/")) }
            path.startsWith("/var/minis/attachments/") ->
                sessionDir("attachments")?.let { File(it, path.removePrefix("/var/minis/attachments/")) }
            path.startsWith("/var/minis/shared/") ->
                File(File(ctx.filesDir, "minis-global/shared"), path.removePrefix("/var/minis/shared/"))
            path.startsWith("/") -> null
            else -> sessionDir("workspace")?.let { File(it, path) }
        }
        if (file == null) {
            return null to "path not under /var/minis/{workspace,attachments,shared} (got: $path)"
        }
        if (!file.isFile) return null to "file not found: $path"
        if (file.length() > 2L * 1024 * 1024) return null to "script file too large (${file.length()} B, cap 2MB)"
        return file to null
    }

    /**
     * [T-android-execjs-autolarge-2026-10] Write a large inline script body to
     * a temp file under the sandbox tmp dir and read it back through the same
     * channel script_file uses. Returns the (unchanged) body when the spill
     * fails — the direct path is still attempted rather than dropped.
     */
    /**
     * [fix24-W2] Auto-spill for oversized inline scripts. Returns the FILE so
     * the caller can (a) read the body back through the same script_file path
     * and (b) delete the file in a finally block. Two defects closed here:
     * 1. The old name was `inline-${System.currentTimeMillis()}.js` — two
     *    calls in the same millisecond (batch execute_js) wrote the SAME
     *    file and each read the other's script back. The name now carries a
     *    UUID, so collisions are impossible.
     * 2. Nothing ever deleted the spill files; cacheDir accumulated them
     *    forever. Callers own deletion now (see EXECUTE_JS spill branch).
     * The read-back verify stays: a truncated write is caught before the
     * WebView ever sees the body.
     */
    private fun spillLargeInlineScript(script: String): java.io.File? = runCatching {
        val dir = java.io.File(webView.context.cacheDir, "execjs-spill").apply { mkdirs() }
        val f = java.io.File(dir, "inline-${java.util.UUID.randomUUID()}.js")
        f.writeText(script, Charsets.UTF_8)
        val back = f.readText(Charsets.UTF_8)
        if (back == script) f else run { f.delete(); null }
    }.getOrNull()

    /**
     * [T-android-init-scripts-2026-10] Register a document-start script on
     * this tab's WebView. Same channel the built-in helpers use
     * (WebViewCompat.addDocumentStartJavaScript, origin rules "*"), so it
     * runs before ANY page JS on every new document — reloads and full-page
     * refreshes included. That is the property execute_js can never offer and
     * the reason the LAB_EXAM-class "refresh killed my hook" pattern needs
     * this action. Body comes from `script` or `script_file` (same resolution
     * as execute_js); `id` is a caller-chosen stable name for later removal.
     * Re-registering an existing id replaces it (remove + add).
     */
    private fun addInitScriptAction(input: BrowserActionInput): BrowserActionResult {
        val id = input.initScriptId?.trim().takeUnless { it.isNullOrBlank() }
            ?: return BrowserActionResult.error("add_init_script requires 'id' (stable name for remove/list)")
        val body = if (!input.scriptFile.isNullOrBlank()) {
            val (file, err) = resolveScriptFile(input.scriptFile!!)
            if (file == null) return BrowserActionResult.error("script_file: $err")
            try { file.readText(Charsets.UTF_8) } catch (t: Throwable) {
                return BrowserActionResult.error("script_file: read failed — ${t.message}")
            }
        } else {
            input.script
        }
        if (body.isNullOrBlank()) return BrowserActionResult.error("add_init_script requires 'script' or 'script_file'")
        // replace-by-id
        initScripts.firstOrNull { it.first == id }?.let { old ->
            runCatching { old.second.remove() }
            initScripts.remove(old)
        }
        // [T-android-init-script-kill-guard-2026-10] guard reads the DEAD table set
        // by a kill-setter script registered at remove time. Setter runs after this
        // script within a doc (registration order), so the kill lands from the NEXT
        // document on — exactly what remove must guarantee.
        val guard = "try{var __d=(JSON.parse(localStorage.getItem('__MINIS_DEAD__')||'[]'));if(__d.indexOf('" + id.replace('-','_') + "')>=0)return;}catch(e){}"
        val wrapped = "(function(){try{" + guard + body + "\n}catch(e){window.__minis_init_err_" + id.replace('-','_') + "=String(e)}})();"
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            return BrowserActionResult.error("add_init_script: document-start API unsupported on this WebView provider")
        }
        val handle = runCatching {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, wrapped, setOf("*"))
        }.getOrNull() ?: return BrowserActionResult.error("add_init_script: registration failed")
        initScripts.add(id to handle)
        return BrowserActionResult(
            text = "Init script '$id' registered — runs at document start on every new document " +
                "(reloads, navigations, same-origin iframes). Survives page refresh. ${body.length} bytes.",
        )
    }

    private fun removeInitScriptAction(input: BrowserActionInput): BrowserActionResult {
        val id = input.initScriptId?.trim().takeUnless { it.isNullOrBlank() }
            ?: return BrowserActionResult.error("remove_init_script requires 'id'")
        val entry = initScripts.firstOrNull { it.first == id }
            ?: return BrowserActionResult.error("No init script '$id' on this tab")
        initScripts.remove(entry)
        // [T-android-init-script-remove-noop-2026-10] On some WebView providers
        // (Chromium 138 observed) ScriptHandler.remove() silently no-ops, leaving the
        // script injected into every new document. Replace-with-noop is the reliable
        // disarm: re-register the same id with an empty body so the stale script is
        // overwritten, then drop our bookkeeping entry. The leftover no-op script in
        // the provider is harmless (empty IIFE).
        runCatching { entry.second.remove() }
        var disarmed = false
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                // [T-android-init-script-kill-guard-2026-10] kill-setter: marks the id
                // dead in every future document; the guard baked into the original
                // wrapped script checks it BEFORE running its body.
                val setter = "try{var __d=JSON.parse(localStorage.getItem('__MINIS_DEAD__')||'[]');if(__d.indexOf('" + id.replace('-','_') + "')<0){__d.push('" + id.replace('-','_') + "');localStorage.setItem('__MINIS_DEAD__',JSON.stringify(__d));}}catch(e){}"
                val noopHandle = androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    setter,
                    setOf("*"),
                )
                disarmed = true
                _noopHandles.add(noopHandle)
            }
        }
        return BrowserActionResult(
            text = "Init script '$id' removed." + (if (disarmed) " (kill-guard armed: persisted to localStorage; the script's guard sees it from the next document)" else "")
        )
    }

    private fun listInitScriptsAction(): BrowserActionResult {
        val names = initScripts.map { it.first }
        return BrowserActionResult(
            text = if (names.isEmpty()) "No agent init scripts on this tab (built-in net/query/viewport helpers always active)."
            else "Init scripts on this tab: " + names.joinToString(", "),
        )
    }

    private suspend fun executeJS(script: String?, timeoutSec: Int?): BrowserActionResult {
        if (script.isNullOrEmpty()) return BrowserActionResult.error("execute_js requires 'script'")
        // [T-android-browser-execjs-2026-10] 'timeout' (seconds) makes the
        // budget caller-controlled; a timeout on a navigation-triggering
        // script is often the SUCCESS signal, so the message now says what
        // the page actually did.
        val budgetMs = ((timeoutSec?.takeIf { it > 0 } ?: 30).coerceAtMost(300)) * 1000L
        val urlBefore = _currentURL.value
        // Wrap in an async IIFE so `await` works in user scripts.
        // Android WebView doesn't resolve Promises from evaluateJavascript,
        // so we use a JS bridge callback (__minis__.resolve / __minis__.reject).
        return try {
            val deferred = CompletableDeferred<String>()
            asyncJsDeferred = deferred
            val wrapped = """
                (async function(){
                    try {
                        var __r__ = (async function(){ $script })();
                        var __v__ = await __r__;
                        if (__v__ === undefined || __v__ === null) {
                            __minis__.resolve(String(__v__));
                        } else if (typeof __v__ === 'object') {
                            __minis__.resolve(JSON.stringify(__v__));
                        } else {
                            __minis__.resolve(String(__v__));
                        }
                    } catch(e) {
                        __minis__.reject(${BrowserUseJS.SCRIPT_THREW_JS});
                    }
                })();
            """.trimIndent()
            withContext(Dispatchers.Main) {
                // [T-android-js-error-detail] The wrapper evaluates to a
                // Promise (the callback gets "{}"). A null means it never
                // ran: the user script does not parse, the bridge will never
                // be called, and the caller used to wait out the full 30s.
                webView.evaluateJavascript(wrapped) { value ->
                    if (BrowserJSResult.decode(value) == null) deferred.complete(EXECUTE_JS_DID_NOT_PARSE)
                }
            }
            val raw = withTimeoutOrNull(budgetMs) { deferred.await() }
                ?: run {
                    asyncJsDeferred = null
                    val urlAfter = _currentURL.value
                    val navigated = urlBefore.isNotEmpty() && urlAfter.isNotEmpty() && urlBefore != urlAfter
                    val rs = try {
                        withTimeoutOrNull(1_500L) {
                            evaluateJavascript(
                                "(function(){try{return document.readyState;}catch(e){return '';}})()"
                            )
                        }.orEmpty().trim('"')
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        ""
                    }
                    val hint = buildString {
                        append("JavaScript execution timed out after ${budgetMs / 1000}s — page URL ")
                        if (navigated) {
                            append("changed: $urlBefore -> $urlAfter (the navigation itself likely succeeded; re-check the page before retrying)")
                        } else {
                            append("unchanged")
                        }
                        if (rs.isNotEmpty()) append("; readyState=$rs") else append("; the page is still busy (readyState probe did not answer)")
                    }
                    return BrowserActionResult.error(hint)
                }
            asyncJsDeferred = null
            if (raw == EXECUTE_JS_DID_NOT_PARSE) {
                val detail = try {
                    evaluateJavascriptValue(BrowserUseJS.syntaxCheck(script))
                } catch (_: Exception) {
                    null
                }
                return BrowserActionResult.error(BrowserJSResult.syntaxErrorMessage(detail))
            }
            val json = try { JSONObject(raw) } catch (_: Exception) { null }
            if (json != null) {
                if (json.has("error")) {
                    BrowserActionResult.error(json.getString("error"))
                } else {
                    BrowserActionResult(text = formatJSONResult(json))
                }
            } else {
                BrowserActionResult(text = raw)
            }
        } catch (e: Exception) {
            asyncJsDeferred = null
            BrowserActionResult.error("JavaScript error: ${e.message}")
        }
    }

    // -- Find Elements --

    private suspend fun findElements(selector: String?): BrowserActionResult {
        if (selector == null) return BrowserActionResult.error("find_elements requires 'selector'")
        return evaluateAndReturn(BrowserUseJS.findElements(selector))
    }

    // -- Hover --

    private suspend fun hover(selector: String?): BrowserActionResult {
        if (selector == null) return BrowserActionResult.error("hover requires 'selector'")
        return evaluateAndReturn(BrowserUseJS.hover(selector))
    }

    // -- Get Backbone --

    private suspend fun getBackbone(maxDepth: Int?): BrowserActionResult {
        val depth = maxDepth ?: 5
        return evaluateActionScript(BrowserUseJS.getBackbone(depth)) { BrowserActionResult(text = formatBackboneResult(it)) }
    }

    // -- Fetch --

    private suspend fun fetch(urlString: String?): BrowserActionResult {
        if (urlString.isNullOrEmpty()) return BrowserActionResult.error("fetch requires 'url' parameter")

        // The fetch JS runs `await fetch(...)` inside an async IIFE, which
        // resolves to a Promise. Android's `WebView.evaluateJavascript` does
        // NOT await Promises, so calling `evaluateJavascript(js)` returns the
        // Promise's `{}` string representation and the caller sees a
        // "No value for base64" parse error. Route through the __minis__
        // bridge so we actually wait for the Promise to resolve.
        val raw = awaitPromiseJs(BrowserUseJS.fetch(urlString))
            ?: return BrowserActionResult.error("fetch timed out")
        return try {
            val json = JSONObject(raw)
            if (json.has("error")) {
                return BrowserActionResult.error("Fetch failed: ${json.getString("error")}")
            }
            val contentType = json.optString("contentType", "")
            val status = json.optInt("status", 0)
            val finalURL = json.optString("url", urlString)

            // `base64` is optional — only present when the JS captured bytes.
            // If missing, fall back to `text` (JSON / plain text) so callers
            // can fetch human-readable payloads without forcing a byte roundtrip.
            val data: ByteArray? = json.optString("base64").takeIf { it.isNotEmpty() }?.let {
                try { Base64.decode(it, Base64.DEFAULT) } catch (_: Exception) { null }
            } ?: json.optString("text").takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)

            if (data == null) {
                return BrowserActionResult.error("Fetch returned no body (status=$status)")
            }
            val size = json.optInt("size", data.size)
            val filename = "fetch_${System.currentTimeMillis()}.${extensionForMimeType(contentType)}"

            val text = buildString {
                appendLine("Fetched $finalURL")
                appendLine("  Status: $status")
                appendLine("  Content-Type: $contentType")
                appendLine("  Size: ${formatBytes(size)}")
                append("  Filename: $filename")
            }

            BrowserActionResult(
                text = text,
                fetchedFileData = data,
                fetchedFileName = filename,
            )
        } catch (e: Exception) {
            BrowserActionResult.error("Fetch parse error: ${e.message}")
        }
    }

    /**
     * Evaluate an `(async function(){...})()` expression and wait for the
     * returned Promise to resolve via the `__minis__` bridge. Returns the
     * resolved string (JSON or plain) or null on timeout. Mirrors the same
     * pattern used by [executeJS].
     */
    private suspend fun awaitPromiseJs(js: String): String? {
        val deferred = CompletableDeferred<String>()
        asyncJsDeferred = deferred
        val wrapped = """
            (async function(){
                try {
                    var __v__ = await ($js);
                    if (__v__ === undefined || __v__ === null) {
                        __minis__.resolve('null');
                    } else if (typeof __v__ === 'object') {
                        __minis__.resolve(JSON.stringify(__v__));
                    } else {
                        __minis__.resolve(String(__v__));
                    }
                } catch(e) {
                    __minis__.reject(e && e.message ? e.message : String(e));
                }
            })();
        """.trimIndent()
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(wrapped, null)
        }
        val raw = withTimeoutOrNull(60_000L) { deferred.await() }
        asyncJsDeferred = null
        return raw
    }

    // -- Set User Agent --

    /**
     * [T-android-browser-origin-overrides-2026-10] Set UA (and profile) only —
     * no viewport touch, no reload. The pool's per-origin applier owns those,
     * so a silent switch doesn't fight the viewport override it is about to apply.
     */
    fun applyUserAgentQuiet(profile: UserAgentProfile, customUA: String?) {
        currentProfile = profile
        val ua = if (profile == UserAgentProfile.CUSTOM && !customUA.isNullOrEmpty()) customUA
            else profile.userAgentString
        if (ua != null) {
            webView.settings.userAgentString = ua
        }
    }

    /** Set user agent from UI settings (public, non-result). */
    fun setUserAgent(profile: UserAgentProfile, customUA: String? = null) {
        currentProfile = profile
        val ua = if (profile == UserAgentProfile.CUSTOM && !customUA.isNullOrEmpty()) customUA
            else profile.userAgentString
        if (ua != null) {
            webView.settings.userAgentString = ua
        }
        applyViewport()
        if (_currentURL.value.isNotEmpty()) {
            webView.reload()
        }
    }

    /**
     * Force the detached pool WebView to lay out at the agent viewport size so
     * page scripts see `window.innerWidth` matching the selected profile (Mobile
     * 412×915 / Desktop 1280×800). Without this, a detached WebView has
     * width/height = 0 and pages render using WebView defaults.
     *
     * The profile dimensions are CSS pixels (iOS "points"). Android WebView
     * uses physical pixels for measure/layout and derives CSS px via
     * window.devicePixelRatio = system density. Passing 412 px directly on a
     * 2.75-density device makes the CSS viewport ~150px wide, causing pages
     * to render at a tiny logical width then upscale, making elements look
     * oversized and clipping on the right. Scale by density so the CSS
     * viewport actually matches the profile.
     */
    fun applyViewport() {
        val vp = currentProfile.viewportSize
        applyViewport(vp.first, vp.second)
    }

    /**
     * Last CSS-pixel viewport applied via [applyViewport]. Used so [navigate]
     * can re-assert the same size before `loadUrl()` — intercepted
     * (`minis://`) loads skip WebView's measure pass, otherwise stranding the
     * page at the 980px no-meta fallback.
     */
    private var lastAppliedViewport: Pair<Int, Int>? = null

    /**
     * Lay out the detached WebView at the given CSS-pixel viewport. Used by
     * [BrowserTabPool] to apply a session or global custom viewport override
     * — mirrors iOS `BrowserUseManager.setViewport(width:height:...)`.
     */
    fun applyViewport(cssWidth: Int, cssHeight: Int) {
        val density = webView.resources.displayMetrics.density
        val w = ((cssWidth * density).toInt()).coerceAtLeast(1)
        val h = ((cssHeight * density).toInt()).coerceAtLeast(1)
        webView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, w, h)
        lastAppliedViewport = cssWidth to cssHeight
        // Pages authored at this CSS width need to fit inside the visible
        // container. Compute and stash a setInitialScale so the next reload
        // (the tab pool always reloads after a viewport change) renders at
        // the shrink-to-fit ratio. pinch-zoom remains enabled because we
        // never touch builtInZoomControls or the page's user-scalable hint.
        applyShrinkToFit(cssWidth)
    }

    /**
     * [ISS-033] set_user_agent switches THIS tab only: nothing is persisted,
     * the default in Browser Settings and the other tabs are untouched, and a
     * rebuilt tab starts from the default again. A missing or unknown value is
     * an error (it used to fall back to mobile silently).
     */
    private suspend fun setUserAgent(profile: UserAgentProfile?, raw: String?): BrowserActionResult {
        if (profile == null || profile == UserAgentProfile.CUSTOM) {
            return BrowserActionResult.error(
                "set_user_agent needs user_agent desktop_chrome or mobile_chrome (aliases: desktop, mobile); " +
                    "got ${raw?.let { "'$it'" } ?: "nothing"}",
            )
        }
        val newProfile = profile
        currentProfile = newProfile
        val ua = newProfile.userAgentString
        // Every WebView method must be called on the main thread, but the
        // offload handler's `runBlocking { ... execute(...) }` dispatches on
        // a worker. `applyViewport(...)` measures/layouts the detached
        // WebView; settings / reload likewise. Hop to main so we don't
        // crash with "A WebView method was called on thread 'worker-N'".
        var reloadSettled = false
        withContext(Dispatchers.Main) {
            if (ua != null) {
                webView.settings.userAgentString = ua
            }
            val (vw, vh) = viewportOverride() ?: newProfile.viewportSize
            applyViewport(vw, vh)
            val oldUrl = _currentURL.value
            if (oldUrl.isNotEmpty()) {
                // [T-android-ua-instant-2026-10] reload() is async: the old
                // document stays the page's live JS context until it finishes,
                // so navigator.userAgent read right after the switch still
                // showed the PREVIOUS UA ("didn't take effect" false alarm —
                // httpbin proved the network side was already correct). Reuse
                // reloadAndWait() so the action returns only when the new UA
                // is actually the page's live context. Must run on Main.
                reloadAndWait()
                reloadSettled = true
            }
        }
        val vp = viewportOverride() ?: newProfile.viewportSize
        // [T-android-ua-response-clarity-2026-10] The viewport half of this
        // message only lands on the NEXT navigation (the CSS viewport is
        // snapshotted at load time), which made the old text look like a lie
        // when get_page_info still showed the old size right after switching.
        val reloaded = _currentURL.value.isNotEmpty()
        return BrowserActionResult(
            text = "Switched this tab to ${newProfile.value} (${vp.first}x${vp.second})" +
                (if (reloadSettled) " — page reloaded and settled, new UA + viewport in effect (requiresNavigation=false)"
                 else if (reloaded) " — page reload issued (still loading; requiresNavigation=false)"
                 else " — applies on the next navigation (requiresNavigation=true)") +
                ". Other tabs and the default in Browser Settings are unchanged; a tab opened or rebuilt later uses the default.",
        )
    }

    // -- User Navigation --

    /**
     * [T-android-newtab-profile-inherit-2026-10] Read-only peek at this tab's
     * current UA profile, so BrowserTabPool.newTab can inherit the profile the
     * conversation's active tab is actually on (instead of silently resetting
     * a desktop conversation to Mobile on every new tab).
     */
    fun currentProfileSnapshot(): UserAgentProfile = currentProfile

    fun goBack() { if (webView.canGoBack()) webView.goBack() }
    fun goForward() { if (webView.canGoForward()) webView.goForward() }
    fun reload() { webView.reload() }
    fun stopLoading() { webView.stopLoading(); _isLoading.value = false }

    /**
     * Reload the current page and suspend until `onPageFinished` fires (or
     * the navigation timeout expires). Used by [BrowserTabPool] after a
     * viewport change so a follow-up `get_page_info` reads the new CSS
     * viewport instead of a stale snapshot. Must be called on the main
     * thread.
     *
     * When the tab has no loaded URL (fresh WebView) or is sitting on
     * `about:blank`, a bare `webView.reload()` is a no-op and
     * `onPageFinished` never fires — we'd time out for no reason. Explicit
     * `loadUrl("about:blank")` always triggers the lifecycle, so the
     * viewport-change callers still get a deterministic page refresh.
     */
    suspend fun reloadAndWait() {
        val deferred = CompletableDeferred<Unit>()
        navigationDeferred = deferred
        _isLoading.value = true
        val url = _currentURL.value
        if (url.isEmpty() || url == "about:blank") {
            // Android WebView's `about:blank` reports `window.innerWidth=980`
            // regardless of container size (the no-meta-viewport fallback),
            // so a plain blank reload wouldn't reflect the new viewport. Load
            // an empty page that declares `width=device-width` instead —
            // `window.innerWidth` then tracks the container we just laid out.
            // `loadDataWithBaseURL(null, html, ...)` lands on `about:blank`
            // as the reported URL but with our meta-viewport in effect.
            webView.loadDataWithBaseURL(null, BLANK_PAGE_HTML, "text/html", "utf-8", null)
        } else {
            webView.reload()
        }
        val handler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (navigationDeferred === deferred) {
                _isLoading.value = false
                deferred.complete(Unit)
                navigationDeferred = null
            }
        }
        handler.postDelayed(timeoutRunnable, NAVIGATION_TIMEOUT_MS)
        try { deferred.await() } finally { handler.removeCallbacks(timeoutRunnable) }
        _isLoading.value = false
    }

    /**
     * Load a minimal HTML page with `<meta viewport content="width=device-width">`
     * so `window.innerWidth` tracks the just-laid-out container size. Used by
     * [BrowserTabPool.createTab] when no initial URL is supplied, so a follow-up
     * `get_page_info` on a fresh tab reports the session viewport instead of
     * WebView's hardcoded `about:blank` 980px fallback.
     *
     * Suspends until `onPageFinished` fires so a follow-up JS evaluation sees
     * `document.body` populated. Must be called on the main thread.
     */
    suspend fun loadBlankPage() {
        val deferred = CompletableDeferred<Unit>()
        navigationDeferred = deferred
        _isLoading.value = true
        webView.loadDataWithBaseURL(null, BLANK_PAGE_HTML, "text/html", "utf-8", null)
        val handler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (navigationDeferred === deferred) {
                _isLoading.value = false
                deferred.complete(Unit)
                navigationDeferred = null
            }
        }
        handler.postDelayed(timeoutRunnable, NAVIGATION_TIMEOUT_MS)
        try { deferred.await() } finally { handler.removeCallbacks(timeoutRunnable) }
        _isLoading.value = false
    }

    fun loadURL(urlString: String) {
        var normalized = urlString
        if (!normalized.contains("://")) normalized = "https://$normalized"
        _isLoading.value = true
        webView.loadUrl(normalized)
    }

    // -- JS Evaluation Helpers --

    /**
     * (GH#245) Bounded by [timeoutMs]. The callback of
     * `WebView.evaluateJavascript` never fires when the renderer hangs, and
     * this helper used to await it with no limit — the real place navigate
     * got stuck (its own 30s load timeout covered only the load, then
     * [navigationMetadata] hung here). On timeout the tab is flagged via
     * [markWedged] so the pool rebuilds it, then [WebViewWedgedException] is
     * thrown. The await sits outside `withContext(Main)` so the wait does not
     * occupy a main-dispatcher continuation.
     */
    private suspend fun evaluateJavascript(
        js: String,
        timeoutMs: Long = BrowserActionGuard.JS_EVAL_TIMEOUT_MS,
    ): String = evaluateJavascriptValue(js, timeoutMs) ?: "null"

    /**
     * [T-android-js-error-detail] [evaluateJavascript] without folding JS
     * null into the string "null": returns `null` when the script evaluated
     * to null/undefined or threw (Android reports both the same way), so the
     * action scripts can tell "failed" from a result. See [BrowserJSResult].
     */
    private suspend fun evaluateJavascriptValue(
        js: String,
        timeoutMs: Long = BrowserActionGuard.JS_EVAL_TIMEOUT_MS,
    ): String? {
        if (isRendererDead) throw WebViewWedgedException("browser tab renderer process died")
        val deferred = CompletableDeferred<String>()
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(js) { result -> deferred.complete(result ?: "null") }
        }
        val raw = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: run {
            markWedged("evaluateJavascript got no callback within ${timeoutMs / 1000}s")
            throw WebViewWedgedException(
                "browser tab stopped responding (JS evaluation timed out after ${timeoutMs / 1000}s)",
            )
        }
        // Android WebView returns JSON-encoded strings, so unquote
        return BrowserJSResult.decode(raw)
    }

    private suspend fun evaluateAndReturn(js: String): BrowserActionResult =
        evaluateActionScript(js) { BrowserActionResult(text = formatJSONResult(it)) }

    private suspend fun evaluateJSAndParse(js: String): BrowserActionResult =
        evaluateActionScript(js) { BrowserActionResult(text = formatJSONResult(it)) }

    /**
     * [T-android-js-error-detail] Runs a [BrowserUseJS] action script, which
     * must return a JSON string: `{error}` → that error, a JSON object →
     * [onJson], other text → passed through, and nothing at all (it threw,
     * swallowed by WebView) → [BrowserJSResult.NO_RESULT_ERROR] instead of
     * the success text "null".
     */
    private suspend fun evaluateActionScript(
        js: String,
        onJson: (JSONObject) -> BrowserActionResult,
    ): BrowserActionResult {
        val decoded = try {
            evaluateJavascriptValue(js)
        } catch (e: Exception) {
            return BrowserActionResult.error("JavaScript error: ${e.message}")
        }
        return when (val outcome = BrowserJSResult.classify(decoded)) {
            BrowserJSResult.Outcome.NoResult -> BrowserActionResult.error(BrowserJSResult.NO_RESULT_ERROR)
            is BrowserJSResult.Outcome.ScriptError -> BrowserActionResult.error(outcome.message)
            is BrowserJSResult.Outcome.Json -> try {
                onJson(outcome.json)
            } catch (e: Exception) {
                BrowserActionResult.error("JavaScript error: ${e.message}")
            }
            is BrowserJSResult.Outcome.Text -> BrowserActionResult(text = outcome.text)
        }
    }

    // -- [T-android-browser-smart-waits-2026-10] URL / cookie / network-idle waits --

    private suspend fun waitForUrl(pattern: String?, timeoutMs: Int?): BrowserActionResult {
        if (pattern.isNullOrBlank()) {
            return BrowserActionResult.error("wait_for_url requires 'url' (substring, or /regex/ e.g. /login|dashboard/)")
        }
        val timeoutSec = (timeoutMs?.takeIf { it > 0 } ?: 30).coerceAtMost(120)
        val regex = if (pattern.length > 2 && pattern.startsWith("/") && pattern.endsWith("/")) {
            runCatching { Regex(pattern.substring(1, pattern.length - 1)) }.getOrNull()
        } else null
        val started = System.currentTimeMillis()
        val deadline = started + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val u = _currentURL.value
            if (u.isNotEmpty()) {
                val hit = if (regex != null) regex.containsMatchIn(u) else u.contains(pattern)
                if (hit) {
                    return BrowserActionResult(text = "wait_for_url: matched after ${System.currentTimeMillis() - started}ms — $u")
                }
            }
            kotlinx.coroutines.delay(250)
        }
        return BrowserActionResult(
            text = "wait_for_url: no match within ${timeoutSec}s (current: ${_currentURL.value})",
            success = false,
        )
    }

    private suspend fun waitForCookie(name: String?, timeoutMs: Int?): BrowserActionResult {
        if (name.isNullOrBlank()) {
            return BrowserActionResult.error("wait_for_cookie requires 'text' = the cookie name to watch for")
        }
        val url = _currentURL.value.takeIf { it.isNotEmpty() }
            ?: return BrowserActionResult.error("wait_for_cookie: no page is loaded (navigate first)")
        val mgr = runCatching { CookieManager.getInstance() }.getOrNull()
            ?: return BrowserActionResult.error("wait_for_cookie: CookieManager unavailable")
        val timeoutSec = (timeoutMs?.takeIf { it > 0 } ?: 30).coerceAtMost(120)
        val started = System.currentTimeMillis()
        val deadline = started + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val header = mgr.getCookie(url).orEmpty()
            val hit = header.split(";").map { it.trim() }.firstOrNull { it.startsWith("$name=") }
            if (hit != null) {
                val valueLen = (hit.length - name.length - 1).coerceAtLeast(0)
                return BrowserActionResult(
                    text = "wait_for_cookie: '$name' appeared after ${System.currentTimeMillis() - started}ms " +
                        "(value: $valueLen chars — use get_cookies to export the full values)",
                )
            }
            kotlinx.coroutines.delay(300)
        }
        return BrowserActionResult(
            text = "wait_for_cookie: '$name' did not appear within ${timeoutSec}s",
            success = false,
        )
    }

    private suspend fun waitForNetworkIdle(timeoutMs: Int?, idleMsRaw: Int?): BrowserActionResult {
        val timeoutSec = (timeoutMs?.takeIf { it > 0 } ?: 30).coerceAtMost(120)
        val idleMs = (idleMsRaw?.takeIf { it > 0 } ?: 800).coerceIn(200, 5_000)
        val started = System.currentTimeMillis()
        val deadline = started + timeoutSec * 1000L
        var lastLogCount = -1
        var lastLogChange = System.currentTimeMillis()
        var report = ""
        while (System.currentTimeMillis() < deadline) {
            val probeRaw = runCatching { evaluateJavascript(BrowserUseJS.netIdleProbe()) }.getOrNull()
            val j = probeRaw?.let { runCatching { JSONObject(it) }.getOrNull() }
            val pending = j?.optInt("pending", -1) ?: -1
            val lastAgo = j?.optInt("lastAgo", -1) ?: -1
            val readyState = j?.optString("readyState", "") ?: ""
            val textLen = j?.optInt("textLen", 0) ?: 0
            val hasMedia = j?.optBoolean("hasMedia", false) ?: false
            val logCount = synchronized(logLock) { networkLog.size }
            if (logCount != lastLogCount) {
                lastLogCount = logCount
                lastLogChange = System.currentTimeMillis()
            }
            val quietMs = System.currentTimeMillis() - lastLogChange
            val netQuiet = (pending <= 0) && (lastAgo < 0 || lastAgo >= idleMs) && quietMs >= idleMs
            val blank = textLen == 0 && !hasMedia
            report = "pending=$pending quiet=${quietMs}ms readyState=$readyState visibleText=$textLen"
            if (netQuiet && readyState != "loading" && !blank) {
                return BrowserActionResult(
                    text = "wait_for_network_idle: idle after ${System.currentTimeMillis() - started}ms ($report)",
                )
            }
            kotlinx.coroutines.delay(300)
        }
        return BrowserActionResult(
            text = "wait_for_network_idle: not idle within ${timeoutSec}s (last: $report)" +
                if (report.contains("visibleText=0")) " — the page still looks blank." else "",
            success = false,
        )
    }

    // -- [T-android-browser-qr-2026-10] extract_qr --

    private suspend fun extractQr(): BrowserActionResult {
        val raw = runCatching { evaluateJavascript(BrowserUseJS.qrCandidates()) }.getOrNull()
            ?: return BrowserActionResult.error("extract_qr: page did not answer")
        val j = runCatching { JSONObject(raw) }.getOrNull()
            ?: return BrowserActionResult.error("extract_qr: bad candidates JSON")
        val arr = j.optJSONArray("candidates")
            ?: return BrowserActionResult.error("extract_qr: no candidates array")
        if (arr.length() == 0) {
            return BrowserActionResult(
                text = "extract_qr: no QR-plausible element found (a visible square-ish img/canvas/svg/video at least 96px is expected). " +
                    "Scroll the code into view and retry, or take a screenshot to inspect the page.",
                success = false,
            )
        }
        val sessionBrowserDir: File? = try {
            val ctx = appContext
            val sid = sessionIdProvider()?.takeIf { it.isNotBlank() }
            if (ctx != null && sid != null) {
                com.openminis.app.sandbox.SessionMounts.sessionDir(ctx.filesDir, sid, "browser").apply { mkdirs() }
            } else null
        } catch (_: Throwable) {
            null
        }
        val outDir = sessionBrowserDir ?: screenshotsDir
        val viewport = captureWebViewBitmap()
            ?: return BrowserActionResult.error("extract_qr: viewport capture failed")
        val phys = withContext(Dispatchers.Main) { webView.width to webView.height }
        val density = withContext(Dispatchers.Main) { webView.resources.displayMetrics.density }
        val bScale = if (phys.first > 0) viewport.width.toFloat() / phys.first else 1f
        val stamp = System.currentTimeMillis() / 1000
        val lines = mutableListOf<String>()
        var firstHostPath: String? = null
        val count = minOf(arr.length(), 3)
        for (i in 0 until count) {
            val c = arr.optJSONObject(i) ?: continue
            val cssX = c.optDouble("x", -1.0)
            val cssY = c.optDouble("y", -1.0)
            val cssW = c.optDouble("w", 0.0)
            val cssH = c.optDouble("h", 0.0)
            if (cssX < 0 || cssY < 0 || cssW < 8 || cssH < 8) continue
            var bx = (cssX * density * bScale).toInt()
            var by = (cssY * density * bScale).toInt()
            var bw = (cssW * density * bScale).toInt()
            var bh = (cssH * density * bScale).toInt()
            bx = bx.coerceIn(0, maxOf(0, viewport.width - 1))
            by = by.coerceIn(0, maxOf(0, viewport.height - 1))
            bw = bw.coerceIn(1, viewport.width - bx)
            bh = bh.coerceIn(1, viewport.height - by)
            val crop = try { Bitmap.createBitmap(viewport, bx, by, bw, bh) } catch (_: Throwable) { null } ?: continue
            // Upscale up to ~3x (long edge capped at 2048) so a phone scanner locks on fast.
            val longEdge = maxOf(bw, bh)
            val up = if (longEdge in 1..2047) {
                val target = (longEdge * 3).coerceAtMost(2048)
                val k = target.toFloat() / longEdge
                val scaled = try {
                    Bitmap.createScaledBitmap(crop, (bw * k).toInt().coerceAtLeast(1), (bh * k).toInt().coerceAtLeast(1), true)
                } catch (_: Throwable) { null }
                if (scaled != null) { crop.recycle(); scaled } else crop
            } else crop
            val fname = "qr_${stamp}_${i + 1}.png"
            val f = File(outDir, fname)
            val ok = withContext(Dispatchers.IO) {
                runCatching { f.outputStream().use { up.compress(Bitmap.CompressFormat.PNG, 100, it) } }.isSuccess
            }
            up.recycle()
            if (!ok) continue
            if (firstHostPath == null) firstHostPath = f.absolutePath
            val ref = if (sessionBrowserDir != null) "/var/minis/browser/$fname  (minis://browser/$fname)" else f.absolutePath
            lines += "QR #${i + 1}: $ref  [tag=${c.optString("tag")}, ${c.optInt("w")}x${c.optInt("h")} css px]"
        }
        viewport.recycle()
        if (lines.isEmpty()) return BrowserActionResult.error("extract_qr: crops failed")
        val text = buildString {
            appendLine("extract_qr: ${lines.size} candidate(s) captured — show one to the user to scan (e.g. ![qr](minis://browser/qr_…)):")
            for (l in lines) appendLine("  $l")
            append("After the user scans, wait for the login to land with wait_for_cookie / wait_for_url.")
        }
        return BrowserActionResult(text = text, imageFilePath = firstHostPath)
    }

    // -- Result Formatting --

    private fun formatJSONResult(json: JSONObject): String = buildString {
        when {
            json.optBoolean("clicked") -> {
                val tag = json.optString("tag", "?")
                appendLine("Clicked <$tag>")
                if (json.has("x") && json.has("y")) appendLine("  Position: (${json.optInt("x")}, ${json.optInt("y")})")
                val text = json.optString("text", "")
                if (text.isNotEmpty()) append("  Text: ${text.take(200)}")
            }
            json.optBoolean("typed") -> {
                val sel = json.optString("selector", "?")
                val len = json.optInt("length", 0)
                append("Typed $len chars into $sel")
            }
            json.optBoolean("scrolled") -> {
                val dir = json.optString("direction", "?")
                val amt = json.optInt("amount", 0)
                appendLine("Scrolled $dir ${amt}px")
                if (json.has("scrollY")) appendLine("  Scroll Y: ${json.optInt("scrollY")}")
                if (json.has("scrollHeight")) appendLine("  Page height: ${json.optInt("scrollHeight")}")
                if (json.has("viewportHeight")) append("  Viewport height: ${json.optInt("viewportHeight")}")
            }
            json.has("scrolledTo") -> {
                val sel = json.optString("scrolledTo")
                val tag = json.optString("tag", "?")
                append("Scrolled to <$tag> ($sel)")
            }
            json.optBoolean("hovered") -> {
                val tag = json.optString("tag", "?")
                appendLine("Hovered <$tag>")
                val text = json.optString("text", "")
                if (text.isNotEmpty()) append("  Text: ${text.take(200)}")
            }
            json.has("text") && json.has("length") -> {
                val title = json.optString("title", "")
                if (title.isNotEmpty()) appendLine("Title: $title")
                val text = json.optString("text", "")
                val len = json.optInt("length", text.length)
                appendLine("Text ($len chars):")
                append(text.take(10000))
            }
            json.has("count") && json.has("elements") -> {
                val count = json.optInt("count")
                val elements = json.optJSONArray("elements")
                val shown = json.optInt("shown", elements?.length() ?: 0)
                appendLine("Found $count element(s) (showing $shown):")
                if (elements != null) {
                    for (i in 0 until elements.length()) {
                        val el = elements.getJSONObject(i)
                        val idx = el.optInt("index")
                        val tag = el.optString("tag", "?")
                        val text = el.optString("text", "").take(80)
                        val line = buildString {
                            append("  [$idx] <$tag>")
                            val id = el.optString("id", "")
                            if (id.isNotEmpty()) append(" #$id")
                            if (text.isNotEmpty()) append(" \"$text\"")
                            val href = el.optString("href", "")
                            if (href.isNotEmpty()) append(" -> $href")
                        }
                        appendLine(line)
                    }
                }
            }
            else -> {
                // Fallback: format each key-value pair
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    appendLine("  $key: ${json.opt(key)}")
                }
            }
        }
    }.trimEnd()

    private fun formatBackboneResult(json: JSONObject): String = buildString {
        val nodeCount = json.optInt("nodeCount")
        val depth = json.optInt("depth")
        val merged = json.optInt("merged")
        appendLine("Page backbone: $nodeCount nodes, depth $depth, $merged merged")

        val backbone = json.optJSONArray("backbone")
        if (backbone != null) {
            for (i in 0 until backbone.length()) {
                formatBackboneNode(backbone.getJSONObject(i), 0, this)
            }
        }
    }.trimEnd()

    private fun formatBackboneNode(node: JSONObject, indent: Int, sb: StringBuilder) {
        val pad = "  ".repeat(indent)
        val tag = node.optString("tag", "?")
        val sel = node.optString("sel", "")
        val rect = node.optString("rect", "")

        val header = buildString {
            append("$pad<$tag>")
            val id = node.optString("id", "")
            if (id.isNotEmpty()) append(" #$id")
            val cls = node.optString("cls", "")
            if (cls.isNotEmpty()) append(" .${cls.replace(" ", ".")}")
            val role = node.optString("role", "")
            if (role.isNotEmpty()) append(" [$role]")
            append(" | $sel | $rect")
        }
        sb.appendLine(header)

        val text = node.optString("text", "")
        if (text.isNotEmpty()) sb.appendLine("$pad  \"$text\"")
        val href = node.optString("href", "")
        if (href.isNotEmpty()) sb.appendLine("$pad  -> $href")
        val img = node.optString("img", "")
        if (img.isNotEmpty()) sb.appendLine("$pad  img: $img")
        val input = node.optString("input", "")
        if (input.isNotEmpty()) sb.appendLine("$pad  input: $input")

        val children = node.optJSONArray("children")
        if (children != null) {
            for (i in 0 until children.length()) {
                formatBackboneNode(children.getJSONObject(i), indent + 1, sb)
            }
        }
    }

    private fun extensionForMimeType(mime: String): String {
        val lower = mime.lowercase().split(";").firstOrNull()?.trim() ?: ""
        return when (lower) {
            "text/html" -> "html"; "text/plain" -> "txt"; "text/css" -> "css"; "text/csv" -> "csv"
            "application/json" -> "json"; "application/xml", "text/xml" -> "xml"
            "application/pdf" -> "pdf"; "image/png" -> "png"; "image/jpeg" -> "jpg"
            "image/gif" -> "gif"; "image/webp" -> "webp"; "image/svg+xml" -> "svg"
            "application/zip" -> "zip"; "application/gzip" -> "gz"
            else -> "bin"
        }
    }

    private fun formatBytes(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    // -- Get Cookies --

    /**
     * Return cookies for the current page's origin, filtered by keyword.
     * Android exposes cookies as a single `Cookie` header string via
     * [CookieManager]; we split on `;` and match each `name=value` pair.
     *
     * `fuzzy=false` (default): exact name match (case-insensitive).
     * `fuzzy=true`: substring match within the cookie name.
     */
    private fun getCookies(keywords: List<String>?, fuzzy: Boolean): BrowserActionResult {
        val url = _currentURL.value.takeIf { it.isNotEmpty() }
            ?: return BrowserActionResult.error("get_cookies: no page is loaded (navigate first)")
        val cookieMgr = runCatching { CookieManager.getInstance() }.getOrNull()
            ?: return BrowserActionResult.error("get_cookies: CookieManager unavailable")
        val raw = cookieMgr.getCookie(url).orEmpty()
        if (raw.isEmpty()) {
            return BrowserActionResult(text = "No cookies set for $url")
        }
        val pairs = raw.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull {
                val eq = it.indexOf('=')
                if (eq <= 0) null else it.substring(0, eq).trim() to it.substring(eq + 1).trim()
            }
        val filtered = if (keywords.isNullOrEmpty()) pairs else pairs.filter { (name, _) ->
            keywords.any { kw ->
                if (fuzzy) name.contains(kw, ignoreCase = true)
                else name.equals(kw, ignoreCase = true)
            }
        }
        // [T-android-browser-cookies-export-2026-10] The tool description has
        // always promised a shell-usable export; it is actually written now.
        // FULL values go to the session's /var/minis/offloads (mode 0600) and
        // never enter the tool response — the summary keeps 80-char previews.
        val exportPath = exportCookiesEnv(url, filtered)
        val text = buildString {
            appendLine("Cookies for $url (${filtered.size} of ${pairs.size}):")
            for ((name, value) in filtered) {
                val preview = if (value.length > 80) value.take(77) + "…" else value
                appendLine("  $name = $preview")
            }
            if (exportPath != null) {
                appendLine()
                appendLine("Full values exported: $exportPath")
                appendLine("Source it in the shell (. $exportPath) — it defines MINIS_COOKIE_HEADER plus one MINIS_COOKIE_<NAME> per cookie.")
            } else {
                appendLine()
                appendLine("Full-value export unavailable in this context (no session dir); the values above are previews only.")
            }
        }.trimEnd()
        return BrowserActionResult(text = text)
    }

    /**
     * [T-android-browser-cookies-export-2026-10] Write the FULL cookie values
     * (filtered set + the combined Cookie header) as a shell-sourceable env
     * file under the session's /var/minis/offloads. Returns the Linux path,
     * or null when no session context is available.
     */
    private fun exportCookiesEnv(url: String, pairs: List<Pair<String, String>>): String? {
        val ctx = appContext ?: return null
        val sid = sessionIdProvider()?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val dir = com.openminis.app.sandbox.SessionMounts.sessionDir(ctx.filesDir, sid, "offloads")
                .apply { mkdirs() }
            val file = File(dir, "env_cookies_${System.currentTimeMillis() / 1000}.sh")
            val sb = StringBuilder()
            sb.append("#!/bin/sh\n")
            sb.append("# minis browser cookies — ").append(url).append('\n')
            sb.append("# generated ").append(
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()),
            ).append('\n')
            sb.append("# source me:  . /var/minis/offloads/").append(file.name).append('\n')
            sb.append("export MINIS_COOKIE_HEADER=")
                .append(shQuote(pairs.joinToString("; ") { (n, v) -> "$n=$v" })).append('\n')
            for ((n, v) in pairs) {
                sb.append("export MINIS_COOKIE_").append(envVarName(n)).append('=').append(shQuote(v)).append('\n')
            }
            file.writeText(sb.toString())
            try {
                file.setReadable(true, true)
                file.setWritable(true, true)
            } catch (_: Throwable) {
            }
            "/var/minis/offloads/${file.name}"
        } catch (t: Throwable) {
            Log.w(TAG, "cookie export failed: ${t.message}")
            null
        }
    }

    /** POSIX single-quote ( ' becomes '\'' ) so values survive `source` verbatim. */
    private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Cookie name to env suffix: uppercase alnum/underscore, never a leading digit. */
    private fun envVarName(name: String): String {
        val cleaned = buildString {
            for (ch in name.uppercase(java.util.Locale.US)) {
                append(if (ch.isLetterOrDigit() || ch == '_') ch else '_')
            }
        }
        return if (cleaned.isEmpty() || cleaned[0].isDigit()) "_$cleaned" else cleaned
    }

    // -- Set Cookies --

    /**
     * Write cookies into the WebView cookie store via [CookieManager.setCookie],
     * which accepts a Set-Cookie-style string and (unlike `document.cookie`) can
     * set HttpOnly cookies. Symmetric with [getCookies]. Each entry needs
     * `name` + `value`; `domain` defaults to the current page host, `path` to
     * "/". Mirrors iOS `setCookies`.
     */
    private fun setCookies(cookies: List<Map<String, Any?>>?): BrowserActionResult {
        val url = _currentURL.value.takeIf { it.isNotEmpty() }
            ?: return BrowserActionResult.error("set_cookies: no page is loaded (navigate first)")
        // Distinguish "field omitted/unparseable" from "field present but empty".
        // The schema types `cookies` as a string, so a model may send a JSON
        // STRING that failed to re-parse, or the CLI's shell-escaping mangled the
        // array — guide the caller instead of a confusing empty result.
        if (cookies == null) {
            return BrowserActionResult.error(
                "set_cookies: 'cookies' must be a JSON array of cookie objects " +
                    "(e.g. [{\"name\":\"foo\",\"value\":\"bar\"}]). It was missing or could not be " +
                    "parsed — if you passed it as a string, ensure it is valid JSON; from the CLI " +
                    "prefer --cookies-file <path> to avoid shell escaping.",
            )
        }
        if (cookies.isEmpty()) {
            return BrowserActionResult.error(
                "set_cookies: 'cookies' array is empty — provide at least one {name, value} object.",
            )
        }
        val cookieMgr = runCatching { CookieManager.getInstance() }.getOrNull()
            ?: return BrowserActionResult.error("set_cookies: CookieManager unavailable")

        // Default domain = current page host.
        val defaultDomain = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()

        // expires (Unix seconds) → RFC-1123 "Expires=" date in GMT.
        val httpDateFmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }

        val setNames = mutableListOf<String>()
        val domainsTouched = linkedSetOf<String>()
        val failures = mutableListOf<String>()

        for (raw in cookies) {
            // Accept the field-name variants common cookie exports use (browser
            // extensions EditThisCookie / Cookie-Editor, Playwright / Puppeteer
            // storage), so a model can paste cookies verbatim. [set-cookies-formats]
            val name = cookieString(raw, "name")?.takeIf { it.isNotEmpty() }
            val value = cookieString(raw, "value")
            if (name == null || value == null) {
                failures.add("(missing name/value)")
                continue
            }
            val domain = cookieString(raw, "domain")?.takeIf { it.isNotEmpty() } ?: defaultDomain
            val path = cookieString(raw, "path")?.takeIf { it.isNotEmpty() } ?: "/"

            val sb = StringBuilder()
            sb.append(name).append('=').append(value)
            if (domain.isNotEmpty()) sb.append("; Domain=").append(domain)
            sb.append("; Path=").append(path)
            if (cookieBool(raw, "secure") == true) sb.append("; Secure")
            // camelCase httpOnly (extensions / Playwright) + snake_case http_only.
            if (cookieBool(raw, "http_only", "httpOnly") == true) sb.append("; HttpOnly")
            // Expiry in Unix seconds. Aliases: expires (Puppeteer) + expirationDate
            // (EditThisCookie / Cookie-Editor, often fractional). <= 0 (Puppeteer's
            // -1, or 0) → session cookie (no Expires attribute).
            cookieNumber(raw, "expires", "expirationDate")?.takeIf { it > 0 }?.let { expires ->
                val date = java.util.Date(expires.toLong() * 1000L)
                sb.append("; Expires=").append(httpDateFmt.format(date))
            }
            // sameSite accepted (Lax/Strict/None, any case) so exports including
            // it aren't rejected, but NOT applied yet — CookieManager.setCookie
            // honors a SameSite attribute, but wiring it needs validation against
            // the cross-site captcha flows. TODO [set-cookies-samesite].
            @Suppress("UNUSED_VARIABLE")
            val sameSite = cookieString(raw, "sameSite", "same_site")

            cookieMgr.setCookie(url, sb.toString())
            setNames.add(name)
            domainsTouched.add(domain)
        }
        cookieMgr.flush()

        if (setNames.isEmpty()) {
            return BrowserActionResult.error(
                "set_cookies: no cookies were set (every entry was invalid: ${failures.joinToString(", ")})",
            )
        }
        val domainLabel = if (domainsTouched.size == 1) domainsTouched.first() else domainsTouched.joinToString(", ")
        var text = "Set ${setNames.size} cookie(s) for $domainLabel: ${setNames.joinToString(", ")}"
        if (failures.isNotEmpty()) {
            text += "\nSkipped ${failures.size} invalid entry(ies): ${failures.joinToString(", ")}"
        }
        return BrowserActionResult(text = text)
    }

    // -- Cookie field readers (format-tolerant) --

    /** Look up `aliases` in the map: exact match first, then case-insensitive,
     *  so httpOnly / HttpOnly / http_only all resolve. */
    private fun cookieValue(raw: Map<String, Any?>, vararg aliases: String): Any? {
        for (key in aliases) raw[key]?.let { return it }
        val lowered = aliases.map { it.lowercase() }.toSet()
        for ((k, v) in raw) if (k.lowercase() in lowered && v != null) return v
        return null
    }

    /** String reader; numbers are stringified so a numeric `value` still works. */
    private fun cookieString(raw: Map<String, Any?>, vararg aliases: String): String? =
        when (val v = cookieValue(raw, *aliases)) {
            is String -> v
            is Number -> v.toString()
            else -> null
        }

    /** Bool reader; tolerates JSON bool, 0/1, and stringified "true"/"false". */
    private fun cookieBool(raw: Map<String, Any?>, vararg aliases: String): Boolean? =
        when (val v = cookieValue(raw, *aliases)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.lowercase() in setOf("true", "1", "yes")
            else -> null
        }

    /** Numeric (seconds) reader; accepts JSON number or numeric string. */
    private fun cookieNumber(raw: Map<String, Any?>, vararg aliases: String): Double? =
        when (val v = cookieValue(raw, *aliases)) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }

    // -- Wait for DOM Stable --

    /**
     * Poll the DOM for stability: repeatedly measures `document.body.innerHTML.length`
     * at ~200ms intervals and returns when two successive readings match, or the
     * timeout elapses. Matches iOS `wait_for_dom_stable`.
     */
    private suspend fun waitForDomStable(timeoutMs: Int?): BrowserActionResult {
        val budget = (timeoutMs ?: DEFAULT_DOM_STABLE_TIMEOUT_MS).coerceIn(
            MIN_DOM_STABLE_TIMEOUT_MS, MAX_DOM_STABLE_TIMEOUT_MS,
        )
        val pollInterval = 200L
        var lastSize = -1L
        var stable = false
        val deadline = System.currentTimeMillis() + budget
        // [T-android-domstable-min-budget] C5 fast path: a document that has
        // already finished loading (readyState === 'complete') is almost
        // always stable — confirm with two samples 50ms apart and return
        // without paying the 200ms poll interval. Keeps trivial static
        // pages (e.g. minis:// docs) fast at any budget.
        val readyState = evaluateJavascript(
            "(function(){try{return document.readyState;}catch(e){return '';}})()"
        ).trim('"')
        if (readyState == "complete") {
            val first = evaluateJavascript(
                "(function(){try{return (document.body&&document.body.innerHTML.length)||0;}catch(e){return -1;}})()"
            ).toLongOrNull() ?: -1L
            delay(50)
            val second = evaluateJavascript(
                "(function(){try{return (document.body&&document.body.innerHTML.length)||0;}catch(e){return -1;}})()"
            ).toLongOrNull() ?: -1L
            // [T-android-review-p1-fixes] F4: require a NON-EMPTY body.
            // SPAs report readyState=complete with an empty/skeleton body
            // before the JS app renders — `first >= 0` declared those
            // "stable" instantly and the agent read a blank page. Empty
            // bodies fall through to the polling loop unchanged (which can
            // legitimately conclude an actually-empty page is stable, but
            // only after giving scripts the full budget to render).
            if (first == second && first > 0) {
                return BrowserActionResult(
                    text = "DOM stable immediately (readyState=complete, body length=$first)",
                )
            }
            // Fast path inconclusive (DOM still mutating post-load, or body
            // still empty) — fall through to the normal polling loop with
            // lastSize untouched so its stability criterion stays exactly
            // as before.
        }
        while (System.currentTimeMillis() < deadline) {
            val raw = evaluateJavascript(
                "(function(){try{return (document.body&&document.body.innerHTML.length)||0;}catch(e){return -1;}})()"
            )
            val size = raw.toLongOrNull() ?: -1L
            // [T-android-domstable-blank-2026-10] A blank body (length 0) is NOT
            // stability — SPAs report an empty skeleton and the agent ended up
            // "stably" reading a white page. Require a non-empty, unchanged body.
            if (size == lastSize && size > 0) { stable = true; break }
            lastSize = size
            delay(pollInterval)
        }
        val elapsed = budget - (deadline - System.currentTimeMillis())
        return if (stable) {
            BrowserActionResult(text = "DOM stable after ${elapsed}ms (body length=$lastSize)")
        } else {
            val blankNote = if (lastSize <= 0L) {
                " — the page still looks blank (still loading?); try wait_for_network_idle"
            } else ""
            BrowserActionResult(
                text = "DOM did not stabilize within ${budget}ms (last body length=$lastSize$blankNote)",
                success = false,
            )
        }
    }

    // -- Scroll and Collect --

    /**
     * Scroll the page [scrollCount] times, collecting text of every element
     * matching [itemSelector]. Optional [keywords] filter the collected text
     * (case-insensitive substring match). Mirrors iOS `scroll_and_collect`.
     */
    private suspend fun scrollAndCollect(
        scrollCount: Int?,
        itemSelector: String?,
        keywords: List<String>?,
    ): BrowserActionResult {
        val iterations = (scrollCount ?: 5).coerceIn(1, 50)
        val selector = itemSelector?.takeIf { it.isNotBlank() }
            ?: return BrowserActionResult.error("scroll_and_collect requires --item-selector")

        val collected = LinkedHashSet<String>()
        for (i in 0 until iterations) {
            val escaped = selector.replace("\\", "\\\\").replace("'", "\\'")
            val raw = evaluateJavascript(
                """(function(){
                    try {
                        var nodes = document.querySelectorAll('$escaped');
                        var out = [];
                        for (var i=0;i<nodes.length;i++) {
                            var t = (nodes[i].innerText || nodes[i].textContent || '').trim();
                            if (t) out.push(t);
                        }
                        return JSON.stringify(out);
                    } catch(e) { return '[]'; }
                })()"""
            )
            try {
                val arr = org.json.JSONArray(raw)
                for (j in 0 until arr.length()) {
                    val s = arr.optString(j)
                    if (s.isNotBlank()) collected.add(s)
                }
            } catch (_: Exception) { /* ignore malformed batches */ }

            // Scroll one viewport down and let the page settle before re-querying.
            evaluateJavascript("window.scrollBy(0, window.innerHeight);")
            delay(400)
        }

        val filtered = if (keywords.isNullOrEmpty()) collected.toList()
            else collected.filter { text -> keywords.any { k -> text.contains(k, ignoreCase = true) } }

        val text = buildString {
            appendLine("scroll_and_collect: $iterations scrolls, selector='$selector'")
            appendLine("  matched: ${filtered.size} / ${collected.size} total")
            for ((i, item) in filtered.withIndex()) {
                val preview = if (item.length > 160) item.take(157) + "…" else item
                appendLine("  [${i + 1}] $preview")
            }
        }.trimEnd()
        return BrowserActionResult(text = text)
    }

}
