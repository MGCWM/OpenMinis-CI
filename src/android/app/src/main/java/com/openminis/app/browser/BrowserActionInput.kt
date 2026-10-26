package com.openminis.app.browser

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parsed input for a browser_use tool call, mirroring iOS BrowserActionInput.
 */
data class BrowserActionInput(
    val action: BrowserAction,
    val url: String? = null,
    val selector: String? = null,
    val text: String? = null,
    val coordinateX: Int? = null,
    val coordinateY: Int? = null,
    val direction: ScrollDirection? = null,
    val amount: Int? = null,
    val script: String? = null,
    val userAgent: UserAgentProfile? = null,
    /** [ISS-033] The user_agent argument as given, for the error text when it is unknown. */
    val userAgentRaw: String? = null,
    val maxDepth: Int? = null,
    val tabId: Int? = null,
    /** Viewport width override (set_viewport action). */
    val viewportWidth: Int? = null,
    /** Viewport height override (set_viewport action). */
    val viewportHeight: Int? = null,
    /** When true on set_viewport, drop the session-level viewport override. */
    val reset: Boolean = false,
    /** Cookie-name / scroll_and_collect keyword filter. */
    val keywords: List<String>? = null,
    /** Fuzzy-match cookie names for get_cookies. */
    val fuzzy: Boolean = false,
    /** Item selector for scroll_and_collect (each matching element is captured). */
    val itemSelector: String? = null,
    /** Scroll iterations for scroll_and_collect. */
    val scrollCount: Int? = null,
    /** Milliseconds to wait (wait_for_dom_stable). */
    val timeoutMs: Int? = null,
    /** When true on screenshot, stretch viewport to full document.scrollHeight before capture. */
    val fullPage: Boolean = false,
    /**
     * Cookies to write (set_cookies). Each entry has keys name + value
     * (required) and optional domain, path, secure, http_only, expires
     * (Unix seconds). Values are loosely typed (String / Boolean / Number).
     */
    val cookies: List<Map<String, Any?>>? = null,
    /**
     * [T-android-browser-observability] Files to feed a page's pending
     * `<input type=file>` picker (upload_file). Guest paths under
     * `/var/minis/workspace|attachments|shared/…`, or workspace-relative.
     */
    val files: List<String>? = null,
    /** Console-log level filter for get_console_logs (log/info/warn/error/debug/all). */
    val logLevel: String? = null,
    /** URL-substring filter for get_network_log. */
    val filter: String? = null,
    /** [T-android-browser-gestures] Gesture type: long_press | double_click | drag. */
    val gesture: String? = null,
    /** Drag destination (viewport CSS px) for gesture=drag. */
    val toX: Int? = null,
    val toY: Int? = null,
    /**
     * [T-android-browser-smart-selectors-2026-10] press_key: the key to
     * press — a base key optionally combined with modifiers, run through
     * WebView.dispatchKeyEvent as a real key event (e.g. "Enter", "Tab",
     * "ctrl+Enter", "shift+Tab", "ArrowDown").
     */
    val key: String? = null,
    /** Downscale factor for screenshot (0.1–1.5; 1.0 = native size). */
    val scale: Double? = null,
    /** [T-android-net-capacity-2026-10] 1-based pick from the newest captured request (replay_request / copy_as_curl). */
    val index: Int? = null,
    /** HTTP method override for replay_request (default: the captured method). */
    val method: String? = null,
    /** Request body override for replay_request (default: the captured body). */
    val body: String? = null,
    /** Extra request headers (JSON object string) for replay_request. */
    val headers: String? = null,
    /** fill_form: JSON array of {selector, value, action} entries. */
    val fields: String? = null,
    /** fill_form: press Enter on the last field when true. */
    val submit: Boolean? = null,
    /** get_network_log / get_response_log: return full stored response bodies. */
    val full: Boolean? = null,
    /** get_network_log: entries to skip from the newest (paging). */
    val offset: Int? = null,
    /** get_network_log / get_response_log: page size. */
    val limit: Int? = null,
    /**
     * [T-android-execjs-scriptfile-2026-10] execute_js: read the script from a
     * guest file instead of the 'script' string. Long scripts (especially
     * \u-escape-dense ones) can get truncated in the tool-argument transport;
     * a file path bypasses that entirely.
     */
    val scriptFile: String? = null,
    /** [T-android-hold-tab-2026-10] hold_tab: minutes to keep this tab exempt from idle/memory eviction. */
    val holdMinutes: Int? = null,
    /**
     * [T-android-init-scripts-2026-10] add_init_script / remove_init_script:
     * caller-chosen stable id for the document-start script. Re-registering
     * an existing id replaces it.
     */
    val initScriptId: String? = null,
    /**
     * [T-android-keepalive-probe-2026-10] keep_alive: a secondary URL whose
     * response tells whether the login session is still valid (a page can
     * answer 200 while logged out).
     */
    val probeUrl: String? = null,
    /**
     * keep_alive probe expectation: a 3-digit status ("200"), or a substring
     * that must appear in the probe body.
     */
    val probeExpect: String? = null,
) {

    /**
     * [T-android-browser-macros-2026-10] Re-serialize this input for the macro
     * recorder — wire format, so a recorded step replays through [parse].
     * Deliberately omits `tab_id`: a macro replays onto whatever tab the flow
     * itself selects (stale ids recorded now would mis-route at replay time).
     */
    fun toRecordJson(): JSONObject {
        val o = JSONObject()
        o.put("action", action.value)
        url?.let { o.put("url", it) }
        selector?.let { o.put("selector", it) }
        text?.let { o.put("text", it) }
        coordinateX?.let { o.put("coordinate_x", it) }
        coordinateY?.let { o.put("coordinate_y", it) }
        direction?.let { o.put("direction", it.value) }
        amount?.let { o.put("amount", it) }
        script?.let { o.put("script", it) }
        userAgent?.let { o.put("user_agent", it.value) }
        maxDepth?.let { o.put("max_depth", it) }
        viewportWidth?.let { o.put("viewport_width", it) }
        viewportHeight?.let { o.put("viewport_height", it) }
        if (reset) o.put("reset", true)
        keywords?.let { o.put("keywords", JSONArray(it)) }
        if (fuzzy) o.put("fuzzy", true)
        itemSelector?.let { o.put("item_selector", it) }
        scrollCount?.let { o.put("scroll_count", it) }
        timeoutMs?.let { o.put("timeout", it) }
        if (fullPage) o.put("full_page", true)
        cookies?.let { list ->
            val arr = JSONArray()
            for (c in list) arr.put(JSONObject(c))
            o.put("cookies", arr)
        }
        files?.let { o.put("files", JSONArray(it)) }
        logLevel?.let { o.put("log_level", it) }
        filter?.let { o.put("filter", it) }
        gesture?.let { o.put("gesture", it) }
        toX?.let { o.put("to_x", it) }
        toY?.let { o.put("to_y", it) }
        key?.let { o.put("key", it) }
        scale?.let { o.put("scale", it) }
        index?.let { o.put("index", it) }
        method?.let { o.put("method", it) }
        body?.let { o.put("body", it) }
        headers?.let { o.put("headers", it) }
        fields?.let { o.put("fields", it) }
        if (submit == true) o.put("submit", true)
        if (full == true) o.put("full", true)
        offset?.let { o.put("offset", it) }
        limit?.let { o.put("limit", it) }
        return o
    }
    companion object {
        /**
         * [T-android-invalid-input-2026-10] B-5: explain WHY [parse] returned
         * null — invalid JSON vs missing / unknown action — instead of the old
         * bare "Invalid browser_use input", which left blind re-tries as the
         * only move when a quoting glitch was involved.
         */
        fun diagnose(raw: String?): String {
            if (raw.isNullOrBlank()) {
                return "input is empty. Send a JSON object like {\"action\":\"navigate\",\"url\":\"https://…\"}"
            }
            val obj = try {
                JSONObject(raw)
            } catch (t: Throwable) {
                val msg = t.message?.take(120) ?: t.javaClass.simpleName
                return "input is not valid JSON ($msg). Check quoting/escaping; first 80 chars: " +
                    raw.take(80)
            }
            val action = obj.optString("action").trim()
            if (action.isEmpty()) {
                return "missing 'action'. Valid actions: " + BrowserAction.allValues.joinToString(", ")
            }
            if (BrowserAction.fromString(action) == null) {
                return "unknown action '$action'. Valid actions: " + BrowserAction.allValues.joinToString(", ")
            }
            val keys = try {
                val it = obj.keys()
                val out = mutableListOf<String>()
                while (it.hasNext()) out.add(it.next())
                out.joinToString(", ")
            } catch (_: Throwable) {
                "?"
            }
            return "could not parse this input for action '$action' — keys: $keys"
        }

        fun parse(json: String): BrowserActionInput? {
            return try {
                val obj = JSONObject(json)
                val actionStr = obj.optString("action", "")
                val action = BrowserAction.fromString(actionStr) ?: return null

                BrowserActionInput(
                    action = action,
                    url = obj.optString("url").ifEmpty { null },
                    selector = obj.optString("selector").ifEmpty { null },
                    text = obj.optString("text").ifEmpty { null },
                    coordinateX = if (obj.has("coordinate_x")) obj.optInt("coordinate_x") else null,
                    coordinateY = if (obj.has("coordinate_y")) obj.optInt("coordinate_y") else null,
                    direction = obj.optString("direction").ifEmpty { null }?.let { ScrollDirection.fromString(it) },
                    amount = if (obj.has("amount")) obj.optInt("amount") else null,
                    script = obj.optString("script").ifEmpty { null },
                    userAgent = UserAgentProfile.parse(obj.optString("user_agent").ifEmpty { null }),
                    userAgentRaw = obj.optString("user_agent").ifEmpty { null },
                    maxDepth = if (obj.has("max_depth")) obj.optInt("max_depth") else null,
                    tabId = if (obj.has("tab_id")) obj.optInt("tab_id") else null,
                    viewportWidth = if (obj.has("viewport_width")) obj.optInt("viewport_width") else null,
                    viewportHeight = if (obj.has("viewport_height")) obj.optInt("viewport_height") else null,
                    reset = obj.optBoolean("reset", false),
                    keywords = obj.optJSONArray("keywords")?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                            .takeIf { it.isNotEmpty() }
                    },
                    fuzzy = obj.optBoolean("fuzzy", false),
                    itemSelector = obj.optString("item_selector").ifEmpty { null },
                    scrollCount = if (obj.has("scroll_count")) obj.optInt("scroll_count") else null,
                    timeoutMs = if (obj.has("timeout")) obj.optInt("timeout") else null,
                    fullPage = obj.optBoolean("full_page", false),
                    cookies = parseCookies(obj),
                    files = parseFiles(obj),
                    logLevel = obj.optString("log_level").ifEmpty { null },
                    filter = obj.optString("filter").ifEmpty { null },
                    gesture = obj.optString("gesture").ifEmpty { null },
                    toX = if (obj.has("to_x")) obj.optInt("to_x") else null,
                    toY = if (obj.has("to_y")) obj.optInt("to_y") else null,
                    key = obj.optString("key").ifEmpty { null },
                    scale = if (obj.has("scale")) obj.optDouble("scale") else null,
                    index = if (obj.has("index")) obj.optInt("index") else null,
                    method = obj.optString("method").ifEmpty { null },
                    body = obj.optString("body").ifEmpty { null },
                    headers = obj.optString("headers").ifEmpty { null },
                    fields = obj.optString("fields").ifEmpty { null },
                    submit = if (obj.has("submit")) obj.optBoolean("submit") else null,
                    full = if (obj.has("full")) obj.optBoolean("full") else null,
                    offset = if (obj.has("offset")) obj.optInt("offset") else null,
                    limit = if (obj.has("limit")) obj.optInt("limit") else null,
                    probeUrl = obj.optString("probe_url").ifEmpty { null },
                    probeExpect = obj.optString("probe_expect").ifEmpty { null },
                    scriptFile = obj.optString("script_file").ifEmpty { null },
                    holdMinutes = if (obj.has("hold_minutes")) obj.optInt("hold_minutes") else null,
                    initScriptId = obj.optString("init_script_id").ifEmpty { obj.optString("id") },
                )
            } catch (_: Exception) {
                null
            }
        }

        /**
         * `cookies` may arrive either as a real JSON array or — because the tool
         * schema can only type it as a string — as a JSON-encoded STRING like
         * `"[{\"name\":...}]"`. Accept both so a schema-faithful model (which
         * emits the string form) isn't silently dropped to empty. Returns null
         * when absent / unparseable / empty.
         */
        /**
         * `files` may arrive as a real JSON array or — because the tool schema
         * types it as a string — as a JSON-encoded string. Accept both, same
         * as [parseCookies].
         */
        private fun parseFiles(obj: JSONObject): List<String>? {
            val arr = obj.optJSONArray("files")
                ?: obj.optString("files").takeIf { it.isNotBlank() }
                    ?.let { runCatching { JSONArray(it) }.getOrNull() }
            arr ?: return null
            return (0 until arr.length())
                .mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
                .takeIf { it.isNotEmpty() }
        }

        private fun parseCookies(obj: JSONObject): List<Map<String, Any?>>? {
            val arr: JSONArray? = obj.optJSONArray("cookies")
                ?: obj.optString("cookies").takeIf { it.isNotBlank() }
                    ?.let { runCatching { JSONArray(it) }.getOrNull() }
            arr ?: return null
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { co ->
                    co.keys().asSequence().associateWith { k -> co.get(k) }
                }
            }.takeIf { it.isNotEmpty() }
        }
    }
}
