package com.openminis.app.browser

/**
 * All browser actions available to the agent, mirroring iOS BrowserAction.
 */
enum class BrowserAction(val value: String) {
    NAVIGATE("navigate"),
    SCREENSHOT("screenshot"),
    CLICK("click"),
    TYPE("type"),
    GET_TEXT("get_text"),
    SCROLL("scroll"),
    GET_PAGE_INFO("get_page_info"),
    EXECUTE_JS("execute_js"),
    FIND_ELEMENTS("find_elements"),
    HOVER("hover"),
    GET_READABLE("get_readable"),
    SET_USER_AGENT("set_user_agent"),
    SET_VIEWPORT("set_viewport"),
    GET_BACKBONE("get_backbone"),
    FETCH("fetch"),
    NEW_TAB("new_tab"),
    CLOSE_TAB("close_tab"),
    LIST_TABS("list_tabs"),
    GET_COOKIES("get_cookies"),
    SET_COOKIES("set_cookies"),
    SCROLL_AND_COLLECT("scroll_and_collect"),
    WAIT_FOR_DOM_STABLE("wait_for_dom_stable"),
    // [T-android-browser-observability] Agent-facing extensions:
    // feed a parked <input type=file> picker, read the page's console
    // trail, and read the recent-requests trail.
    UPLOAD_FILE("upload_file"),
    GET_CONSOLE_LOGS("get_console_logs"),
    GET_NETWORK_LOG("get_network_log"),
    // [T-android-browser-observability-v2] Navigation control, dropdowns,
    // condition waits, and captured fetch/XHR response bodies.
    GO_BACK("go_back"),
    GO_FORWARD("go_forward"),
    RELOAD("reload"),
    SELECT_OPTION("select_option"),
    WAIT_FOR("wait_for"),
    GET_RESPONSE_LOG("get_response_log"),
    CLEAR_SITE_DATA("clear_site_data"),
    // [T-android-browser-gestures] Real touch gestures (injected as input
    // events): long-press context menus/selection, double-tap, drag, pinch.
    GESTURE("gesture"),
    // [T-android-browser-pdf] Archive the page as a multi-page PDF.
    EXPORT_PDF("export_pdf"),
    // [T-android-browser-smart-selectors-2026-10] Keyboard injection (press_key),
    // URL / cookie / network-idle waits, and QR capture for scan-to-login flows.
    PRESS_KEY("press_key"),
    WAIT_FOR_URL("wait_for_url"),
    WAIT_FOR_COOKIE("wait_for_cookie"),
    WAIT_FOR_NETWORK_IDLE("wait_for_network_idle"),
    EXTRACT_QR("extract_qr"),
    // [T-android-net-capacity / request-replay / fill-form / restore-tabs 2026-10]
    REPLAY_REQUEST("replay_request"),
    COPY_AS_CURL("copy_as_curl"),
    FILL_FORM("fill_form"),
    RESTORE_TABS("restore_tabs"),
    // [T-android-browser-macros / keep-alive 2026-10]
    RECORD_START("record_start"),
    RECORD_STOP("record_stop"),
    RUN_MACRO("run_macro"),
    LIST_MACROS("list_macros"),
    KEEP_ALIVE("keep_alive"),
    // [T-android-macro-journal-2026-10] C-9: export the session's whole action
    // journal as a replayable macro (no recording needed).
    EXPORT_MACRO("export_macro"),
    // [T-android-hold-tab-2026-10] Keep a tab through a login flow.
    HOLD_TAB("hold_tab");

    /**
     * [T-browser-readaction-follow-tab-and-yolo-android] True when this action
     * opens / replaces the page (navigate) and may therefore fan out to
     * a fresh tab under the implicit-tab grace window. False for
     * operate-current-page actions (execute_js, get_text, click, …) which must
     * follow selectedTabId.
     */
    val opensNewPage: Boolean get() = this in opensNewPageActions

    companion object {
        private val map = entries.associateBy { it.value }
        fun fromString(s: String): BrowserAction? = map[s]

        /** Actions that visually change the page and warrant an auto-snapshot. */
        val visualChangeActions: Set<BrowserAction> = setOf(
            NAVIGATE, CLICK, SCROLL, HOVER, TYPE, GESTURE, PRESS_KEY, FILL_FORM
        )

        /**
         * [T-browser-readaction-follow-tab-and-yolo-android] Actions that open
         * or replace the page in a tab — these are the ONLY actions that should
         * fan out to a fresh tab when an implicit (tab_id-less) call lands while
         * the previously-used tab is still inside its inUse grace window. The
         * grace-based fan-out exists so N back-to-back *navigates* open N
         * distinct tabs instead of trampling tab 0.
         *
         * Every other action operates on the CURRENT page (read DOM, click,
         * scroll, screenshot, …). For those, fanning out to a fresh/other tab
         * is exactly the #612/#614 bug: `navigate A → execute_js` ran the JS on
         * a different (blank or stale) tab because navigate's tab was still in
         * grace. Those actions must follow `selectedTabId` (the most-recently
         * navigated tab), never fan out. See [opensNewPage].
         *
         * NEW_TAB / CLOSE_TAB / LIST_TABS / SET_VIEWPORT are handled at the pool
         * level before tab acquisition, so they're intentionally absent here.
         */
        val opensNewPageActions: Set<BrowserAction> = setOf(
            NAVIGATE
        )

        val allValues: List<String> = entries.map { it.value }
    }
}

enum class ScrollDirection(val value: String) {
    UP("up"), DOWN("down");
    companion object {
        fun fromString(s: String): ScrollDirection? = entries.find { it.value == s }
    }
}

enum class UserAgentProfile(val value: String) {
    MOBILE_CHROME("mobile_chrome"),
    DESKTOP_CHROME("desktop_chrome"),
    CUSTOM("custom");

    val userAgentString: String?
        get() = when (this) {
            MOBILE_CHROME -> "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"
            DESKTOP_CHROME -> "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
            CUSTOM -> null
        }

    val displayName: String
        get() = when (this) {
            MOBILE_CHROME -> "Mobile Chrome"
            DESKTOP_CHROME -> "Desktop Chrome"
            CUSTOM -> "Custom"
        }

    val viewportSize: Pair<Int, Int>
        get() = when (this) {
            DESKTOP_CHROME -> 1280 to 800
            MOBILE_CHROME, CUSTOM -> 412 to 915
        }

    companion object {
        fun fromString(s: String): UserAgentProfile? = entries.find { it.value == s }

        /**
         * [ISS-033] A user_agent argument: case-insensitive, "-" read as "_",
         * and "desktop" / "mobile" accepted as aliases. Null when unknown - it
         * used to fall back to mobile silently, so "desktop" switched to MOBILE.
         */
        fun parse(raw: String?): UserAgentProfile? {
            val s = raw?.trim()?.lowercase()?.replace('-', '_') ?: return null
            return when (s) {
                "desktop" -> DESKTOP_CHROME
                "mobile" -> MOBILE_CHROME
                else -> fromString(s)
            }
        }
    }
}
