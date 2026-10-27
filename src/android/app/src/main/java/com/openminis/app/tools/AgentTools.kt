package com.openminis.app.tools

import com.openminis.app.browser.BrowserAction
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.SubAgentDefinition

/**
 * Central registry of all agent tool definitions.
 * Returns provider-agnostic AgentToolDefinition list used by the agent loop.
 * Tool definitions aligned with iOS AIChatViewModel.makeAgentTools().
 */
object AgentTools {

    fun makeAgentTools(
        supportsImageInput: Boolean = true,
        // [T-android-vision-group / GH#182] When the main model can't natively
        // see images but the user has bound a Vision Group, still expose
        // read_image: ReadImageTool routes the image through a vision-capable
        // group member and returns a text description. Mirrors iOS makeAgentTools
        // visionGroupConfigured. Neither native vision nor a Vision Group → tool
        // stays absent (current behaviour).
        visionGroupConfigured: Boolean = false,
        // [T-memory-toggle-gates-injection-and-tools-android] When the
        // user has turned memory off (via /memory or
        // Settings/SessionMemorySheet), drop both memory_write and
        // memory_get from the schema entirely so the model can't even
        // attempt those calls. Mirrors the iOS gate at
        // AIChatViewModel.makeAgentTools(memoryEnabled:).
        memoryEnabled: Boolean = true,
        // [T-p1-delegate-task] True for a helper (child) vm. Depth = 1: a
        // helper never sees delegate_task; it also loses memory_write (design
        // §4.3 — helpers have no long-term memory). memory_get stays so a
        // helper can still LOOK things up.
        isHelper: Boolean = false,
        // [T-p2-agent-settings] Settings › Agents can remove delegation globally.
        delegateEnabled: Boolean = true,
        // [T-tools-granular-switches] Settings › Agent Tools can remove
        // browser_use globally. Separate from delegateEnabled because the two
        // are independent user choices; mirrors iOS AgentToolSwitch.browser.
        browserEnabled: Boolean = true,
        // [T-sub-agents-v1] The names of the enabled sub agents, in roster
        // order. These become `subagent_task.agent`'s enum, and the caller
        // rebuilds them every turn: the schema is not cached, so a rename takes
        // effect on the next request and the model cannot emit a name that
        // would fail to resolve. Empty falls back to the built-in's name so the
        // enum is never an empty list (which some providers reject).
        rosterNames: List<String> = listOf(SubAgentDefinition.BUILT_IN_NAME),
        // [T-port-user-ask] Decided once per run by UserAskExposure (switch on,
        // not a sub agent, an interactive run). Not in the list = does not
        // exist: no schema, no prompt line, no retries on it. Last, as on iOS.
        userAskExposed: Boolean = false,
    ): List<AgentToolDefinition> = buildList {
        add(shellExecuteDefinition())
        add(FileReadTool.definition())
        add(FileWriteTool.definition())
        add(FileEditTool.definition())
        if (supportsImageInput || visionGroupConfigured) {
            add(ReadImageTool.definition())
        }
        if (browserEnabled) add(browserUseDefinition())
        if (memoryEnabled) {
            if (!isHelper) add(memoryWriteDefinition())
            add(memoryGetDefinition())
        }
        if (!isHelper && delegateEnabled) {
            add(subAgentTaskDefinition(rosterNames))
        }
        if (userAskExposed && !isHelper) add(com.openminis.app.agent.userask.UserAskPromptText.definition())
    }

    /**
     * [T-sub-agents-v1] The one sub agent tool: delegate, and inspect or stop
     * what you delegated.
     *
     * Replaces `delegate_task` + `agent_status`. The separate status tool is
     * folded in as an `action`, so the model sees ONE tool — the shape iOS
     * settled on. Description text is byte-identical to iOS
     * `AIChatViewModel+ToolDefinitions.swift`; the two clients present one
     * contract to the same models, so these strings are copied, never
     * paraphrased.
     *
     * [rosterNames] are the enabled sub agents' names, rebuilt every turn
     * (the schema is not cached), so a rename takes effect on the next request
     * and the model cannot invent a name that would fail to resolve.
     *
     * `task` is deliberately NOT in [required]: it is required for
     * action=delegate and meaningless for status/cancel, which JSON Schema
     * cannot express here. The dispatcher rejects a delegate call with no task.
     */
    private fun subAgentTaskDefinition(rosterNames: List<String>): AgentToolDefinition =
        AgentToolDefinition(
            name = SubAgentDefinition.TOOL_NAME,
            description = "Delegate a self-contained task to a sub agent — its own isolated context and tool loop, in a hidden child session running concurrently with you — and inspect or stop the ones you started. `action` defaults to `delegate`.\n\nDELEGATE work needing many rounds of exploration (reading lots of files or pages, trial-and-error), producing bulk output you only need a conclusion from, or splitting into independent sub-problems you can run in parallel (several calls in one turn). DO NOT delegate what you can finish in one or two tool calls, what needs the user's confirmation mid-way, or work depending on nuances of this conversation you cannot restate. A sub agent cannot see this conversation and has no memory: write `task` as a complete brief for a capable colleague who just walked in — goal, constraints, where things are, what exactly to return. It costs a full model run, so nothing trivial. Only ${com.openminis.app.agent.jobs.AgentJobRegistry.MAX_CONCURRENT_CHILD_JOBS_PER_PARENT} run at once in this conversation, but delegate everything you need anyway: extras return status=queued and start as slots free, so never re-delegate a queued task or wait for a slot.\n\nwait=false (default) returns at once with status=running and a job_id; the result arrives later as a NEW MESSAGE prefixed [Background task finished …] (also on cancel/timeout/failure). End your turn when you have nothing else to do — never poll in a loop, never promise to report back. You do NOT need action=status to receive results.",
            parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user on the block in the tool bar and in the transcript (e.g. 'Survey repo test layout', 'Check on the research agent'). Use the same language as the user."),
            "action" to AgentToolParam("string", "\"delegate\" (default): start a sub agent on `task`. \"status\": report this conversation's sub agents — state (queued/running/done/cancelled/failed/interrupted), current tool, elapsed, model, finished results; `job_id` for one, omit for all. \"steer\": course-correct a RUNNING one without stopping it (see `message`). \"cancel\": stop the one named by `job_id`; its partial result is still posted back. \"resume\": restart runs the app lost when it was killed (they report `interrupted`); `job_id`/`child_session_id` for one, omit both for all.", enumValues = listOf("delegate", "status", "steer", "cancel", "resume")),
            "task" to AgentToolParam("string", "action=delegate only, required. The complete, self-contained brief: goal, success criteria, relevant paths/URLs, constraints, and exactly what to return. The sub agent sees nothing else."),
            "agent" to AgentToolParam("string", "action=delegate only. Which sub agent runs this task. Pick the one whose description matches the work; omit it to use the general one.", enumValues = rosterNames),
            "model_choice" to AgentToolParam("string", "action=delegate only, and only when the chosen sub agent is set to Auto — one the user pinned to a group ignores it. DEFAULT TO \"same_as_me\". The user picked the model this conversation runs on, and that choice covers the work you delegate from it: a sub agent on a different model can cost far more, or be far weaker, than what they chose, and they never see it happen. Only depart from it when the task itself gives you a specific reason, judged by what the task demands and not by how long it will take. \"same_as_me\" (default): this conversation's model — anything continuing the work at hand, and every case where you are unsure. \"default_model\": the user's strongest group — only when this task clearly needs more capability than the current model, e.g. multi-step reasoning, design judgment, ambiguous requirements where a wrong answer is expensive. \"sub_model\": the user's light group — only when the task is clearly mechanical and well-bounded, verifiable at a glance (collecting files against a list, format conversion, fixed commands, lookups).", enumValues = listOf("same_as_me", "default_model", "sub_model")),
            "context" to AgentToolParam("string", "action=delegate only. Optional raw material to hand over verbatim (file excerpts, error output, a list of paths). Appended to the task."),
            "max_minutes" to AgentToolParam("integer", "action=delegate only. Wall-clock budget in minutes (default 10, maximum 60). The sub agent is stopped when it runs out and whatever it produced so far is returned with status=timeout."),
            "wait" to AgentToolParam("boolean", "action=delegate only. false (default): return at once with status=running; the result is posted here as a new message when done. true: block until it finishes and return the result here — only when the next step cannot proceed without it. If the user sends a message while you wait, the run moves to the background and the call returns status=running."),
            "progress_report" to AgentToolParam("string", "action=delegate only. Mid-run [Background task progress …] messages (status, current tool, elapsed, latest message). \"none\" (default): final result only. \"frequent\": every 15s when something changed. \"moderate\": once a minute. Each costs you a turn — leave at none unless the user asked to follow along or you must react mid-way. Answer one with at most a short sentence, or just carry on; never re-delegate or poll because of one. Ignored when wait=true.", enumValues = listOf("none", "frequent", "moderate")),
            "job_id" to AgentToolParam("string", "action=status/steer/cancel. The job_id this tool returned when it started the sub agent (a prefix is accepted). Required for steer and cancel; omit on status to list every sub agent of this conversation."),
            "message" to AgentToolParam("string", "action=steer only, required. The correction, phrased as an instruction to the running sub agent (e.g. 'focus on pricing, skip the migration notes'). Use when new information changes what it should do — it keeps the work already done, unlike cancelling and re-delegating. Read at its next turn, so a running tool call is not interrupted; if the run finishes first the result reports the steer as missed."),
            "child_session_id" to AgentToolParam("string", "action=resume only, optional. The child_session_id of one interrupted sub agent to restart. Omit to resume every interrupted sub agent in this conversation."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf(
                "tool_title", "action", "task", "agent", "model_choice", "context",
                "max_minutes", "wait", "progress_report", "job_id", "message",
                "child_session_id",
            ),
        )

    // Aligned with iOS AIChatViewModel.swift:4982-4993
    private fun shellExecuteDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "shell_execute",
        description = "Execute a command in an isolated Linux process (Ubuntu 24.04 arm64 via PRoot). " +
            "The command runs via /bin/sh -c with stdout and stderr merged. " +
            "Each invocation spawns a fresh process — there is no shared terminal session. " +
            "Default timeout is 15 minutes.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Install Python data analysis packages', 'List files in home directory'). Use the same language as the user."),
            "command" to AgentToolParam("string", "The shell command to execute. Supports multi-line commands directly — no special escaping needed. Keep under 1000 chars; for longer scripts, write to a file with file_write first, then run it."),
            // [T-android-parity-fixes] State the ceiling. It used to say only "use
            // a larger value" while values above 900 were silently cut to 900.
            "timeout" to AgentToolParam("integer", "Timeout in seconds (default: 900, maximum: 3600 — larger values are capped at 3600). Use a larger value for long-running commands like package installs."),
            "delay" to AgentToolParam("integer", "Delay in seconds before execution begins. The tool blocks the agent flow during this wait WITHOUT occupying the shell, so other concurrent tasks can use it. Use this instead of sleep commands to avoid resource contention."),
        ),
        required = listOf("tool_title", "command"),
        propertyOrdering = listOf("tool_title", "command", "timeout", "delay"),
    )

    // Aligned with iOS AIChatViewModel.swift browser_use definition
    private fun browserUseDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "browser_use",
        // [T-android-browser-tab-ownership] "up to 3" stopped being true once
        // the ceiling became dynamic (3 alone, +2 per active agent, capped at
        // 6), and an agent sees only its own tabs anyway — so the honest thing
        // to tell it is which tabs it may use, not a number that is now wrong.
        description = "Control a web browser with a few tabs (list_tabs shows the ones you may use). " +
            "Do NOT use this tool for minis:// action URLs (open_terminal, views, settings) — those are app deep links, use Markdown links in chat instead. " +
            "The browser supports both web URLs and minis:// resource URLs. Use minis:// URLs to preview session files (e.g. navigate to minis://workspace/index.html). " +
            "Sub-resources (JS, CSS, images, fonts) referenced via minis:// absolute paths or relative paths within HTML pages resolve correctly. " +
            "Use navigate to open URLs, screenshot to see the page (returns an image), " +
            "click/type to interact with elements, get_text/get_readable to extract content, " +
            "Selectors accept CSS, or smart forms: text=<visible label> (substring; quote for exact), aria-label=<label>, role=button[name=<label>] — these also search Shadow DOM and same-origin iframes (cross-origin iframe contents are unreachable; use coordinate_x/y to click inside them). " +
            "Use press_key to inject a REAL key event (Enter/Escape/Tab/arrows/F1-12/letters/digits; combos like 'ctrl+Enter', 'shift+Tab') — optionally focus 'selector' first. " +
            "scroll to navigate long pages, scroll_and_collect to scroll through infinite-scroll/virtual-rendered pages (like Twitter/X timelines) and accumulate unique content items across scroll positions in a single call, " +
            "find_elements to discover interactive elements, " +
            "get_page_info for page metadata, get_backbone to get a structural overview of the page DOM as a simplified tree, " +
            "fetch to download files/resources using the page's session (returns metadata and a minis:// URL), " +
            "new_tab to open an additional tab, close_tab to close a tab, and list_tabs to see all open tabs. " +
            "Use set_viewport with viewport_width + viewport_height to override the viewport for the current session (e.g. before screenshotting a 1920×1080 HTML composition that would otherwise be cropped to the phone viewport); pass reset=true to drop the session override and fall back to the global browser setting. " +
            "set_user_agent and set_viewport are remembered per-origin: setting them while on a site re-applies automatically the next time you visit that origin (survives restarts); set_viewport reset=true also clears the site's remembered override. " +
            "Use get_cookies to retrieve cookies for the current page URL / current site root domain only (including HttpOnly cookies). get_cookies supports optional 'keywords' (filter by cookie name) and 'fuzzy' (true=contains match, false=exact match, default true). It returns a summary (80-char previews) plus a shell-sourceable env file holding the FULL values: `. /var/minis/offloads/env_cookies_xxx.sh` defines MINIS_COOKIE_HEADER (usable directly as a Cookie: header) and one MINIS_COOKIE_<NAME> per cookie — call APIs from the shell without reopening the browser. " +
            "Use set_cookies to write cookies into the current page's cookie store via the native cookie store (so even HttpOnly cookies, which JS cannot set, land). Pass a 'cookies' array of objects, each with name + value (required) and optional domain (defaults to the current page host), path (defaults to '/'), secure, http_only, and expires (Unix timestamp in seconds; omit for a session cookie). " +
            "Use wait_for_dom_stable to wait until the page DOM stops changing (useful after navigation or interactions that trigger async data loading — polls every 0.5s, resolves when mutation rate gradient is stable for 3+ intervals, default timeout 10s). " +
            "Use upload_file to feed files into a page's <input type=file>: pass 'files' as a JSON array of /var/minis/workspace|attachments|shared paths (or workspace-relative paths); no prior click is needed — the files are injected into the input directly (optionally target it with 'selector', default input[type=file]). If a native picker happens to be open, it is answered instead. Files up to 4 MB each / 8 MB total. " +
            "Use get_console_logs to read the page's console output (optional log_level filter: log/info/warn/error/debug/all). " +
            "Use get_network_log to inspect fetch/XHR traffic (request+response headers, request body, response size/preview) with 'filter' (URL substring), 'limit'+'offset' (paging; up to 1000 captured, newest first) and 'full'=true (full stored response bodies); a page request trail (subresources incl. main frame) follows the list. Use get_response_log to focus on response bodies ('filter', 'limit', 'full'). Use replay_request to re-send a captured request from the page context ('index'=1 is the newest match, 'filter' narrows; optional method/body/headers overrides; cookies ride along). Use copy_as_curl for a paste-ready curl of a captured request (live cookies + browser fingerprint headers included — WAFs reject bare curl). " +
            "Use wait_for to wait until a selector appears and/or 'text' shows up (optional 'timeout' in seconds, default 10). Use select_option (selector + text = option label or value) to pick a dropdown option. " +
            "Use wait_for_url to wait for the page URL ('url' = substring or /regex/) and wait_for_cookie to wait for a cookie ('text' = cookie name) — both default 30s (max 120s); with wait_for_network_idle (waits until fetch/XHR drain and the page goes quiet; 'amount' = quiet window ms, default 800) these make redirect chains and scan-to-login flows waitable. wait_for_dom_stable no longer treats a blank body as 'stable'. " +
            "Use extract_qr to locate a QR code (visible square-ish img/canvas/svg >=96px): it saves up to 3 high-res crops under /var/minis/browser/ and returns their minis:// URLs — show one to the user with markdown so they can scan it, then wait with wait_for_cookie / wait_for_url. " +
            "Use fill_form to fill many fields in one call: 'fields' is a JSON array like [{\"selector\":\"text=E-mail\",\"value\":\"a@b.c\"},{\"selector\":\"#country\",\"value\":\"CN\",\"action\":\"select\"},{\"selector\":\"#agree\",\"action\":\"check\"}]; optional submit=true presses Enter on the last field. Use restore_tabs to reopen this chat's last saved tab set. " +
            "Use record_start / record_stop to capture a replayable macro of the actions you perform (read-only inspections are skipped; any recorded string may contain {{placeholders}}), then run_macro (text=name, body=optional JSON params) replays it step by step — list_macros shows what's saved. Fill-form / click / press_key / gesture results now include a short Page diff (\"+2 / -1 line(s)\", or \"no visible change\" when the action had no observable effect). Use keep_alive with a url to (1) ping it right now with the browser's live cookies and (2) get a ready-made minis-scheduled command that keeps the session warm on a timer — run the printed command in the shell to arm it. " +
            "navigate accepts 'selector' (an element that proves the RIGHT page is up; one automatic re-navigation happens when it's missing) and 'timeout' (seconds) for that wait. export_macro (text=name, amount=last N actions, default 300) turns this session's action journal into a replayable macro — no recording needed. Tab evictions and rebuilt tabs now surface as one-shot notices on the next result. Use hold_tab (tab_id, optional hold_minutes, default 10) to keep a tab through a login flow — held tabs are skipped by idle-timeout, registry and memory-pressure eviction. " +
            "Typing into contenteditable rich editors (CodeMirror and friends) now goes through the browser's real insertText pipeline, so framework models stay in sync and submit buttons unlock. Clicking a target=_blank link opens it as a real background tab (the popup blocker would otherwise swallow it silently) — the result reports the new tab. Submit clicks that fire async requests get a brief settle wait before the Page diff is taken. Pages with a malformed viewport meta (initial-scale=0, e.g. GitCode) are auto-corrected and the view is recovered automatically. " +
            "keep_alive also takes 'probe_url' (+ optional 'probe_expect'): a secondary URL whose response tells whether the LOGIN is still valid — a page can answer 200 while logged out — where probe_expect is a 3-digit status (\"200\") or a substring that must appear in the probe body; the armed watch then prints OK or SESSION-DEAD so a dead session gets flagged on schedule. " +
            "Probe on the SAME domain as url whenever possible: the armed curl reads the cookie snapshot of its own host, and a cross-domain probe whose jar is empty is refused (it would always read DEAD). For endpoints that answer 403 on a missing CSRF header even when logged in, use bodycontains:<logged-in marker> (e.g. bodycontains:userId) — it separates 'not logged in' from 'missing header'. " +
            "Probes now ride the in-app WebView (a dedicated background tab): strict WAFs reject curl at the TLS fingerprint while the browser passes, and a navigation carries the live cookie jar — so cross-domain probes work too. A status-only rule degrades to 'no login redirect' on that channel; bodycontains stays definitive. The armed watch drives the same probe through minis-browser-use — one CLI call per fire, no model turn. " +
            "type with NO selector types into the currently focused element via native key events — the only route into inputs inside CROSS-ORIGIN iframes (tap the field by coordinate first); ASCII only, capped at 2000 chars. Timeout/crash errors now append the native request trail for the action, so you can tell whether a failed click's POST actually went out before deciding to retry. Element selectors also accept placeholder=<text> / placeholder*=<text> (contains), and not-found errors suggest near-miss candidates. " +
            "go_back / go_forward / reload control navigation; clear_site_data wipes cookies + web storage (logs out of every site). " +
            "Use gesture for real touch gestures (injected as input, not script): long_press (context menus / selection), double_click, drag with to_x/to_y as the destination in viewport CSS px, or pinch (two-finger zoom around the target; 'amount' = how far each finger spreads in CSS px, default 100, negative zooms out). Use export_pdf to archive the current page as a multi-page PDF (saved under /var/minis/browser/ and returned as a path). " +
            "Use screenshot together with 'selector' to capture just that element (cropped; full_page still captures everything); 'scale' (0.1–1.5) downscales before encoding — scale=0.5 cuts the image payload ~4x. " +
            "Use tab_id to target a specific tab (defaults to the most recently used tab).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Open Wikipedia homepage', 'Take screenshot of current page'). Use the same language as the user."),
            "action" to AgentToolParam("string", "The browser action to perform",
                enumValues = BrowserAction.allValues),
            "url" to AgentToolParam("string", "URL to navigate to (navigate), a resource to download (fetch), or the pattern to wait for (wait_for_url: substring, or /regex/)."),
            "selector" to AgentToolParam("string", "Element selector for click/type/get_text/scroll/hover/find_elements/press_key-focus/screenshot-crop. Accepts CSS, or smart forms: text=<visible label> (substring; quote for exact), aria-label=<label>, role=button[name=<label>], placeholder=<text> / placeholder*=<text> (contains) — these also search Shadow DOM + same-origin iframes. For scroll: a scrollable container (e.g. 'div.timeline'); if omitted, auto-detects the best scrollable element."),
            "text" to AgentToolParam("string", "Text to type (type), option label/value (select_option), wait target text (wait_for), the cookie name to watch for (wait_for_cookie), or the macro name for record_start / run_macro / export_macro. For type, leaving 'selector' empty types into the focused element via native key events (the cross-origin-iframe route)."),
            "coordinate_x" to AgentToolParam("integer", "X coordinate for click (alternative to selector)"),
            "coordinate_y" to AgentToolParam("integer", "Y coordinate for click (alternative to selector)"),
            "direction" to AgentToolParam("string", "Scroll direction", enumValues = listOf("up", "down")),
            "amount" to AgentToolParam("integer", "Scroll amount in pixels (default 500), the keep-alive interval in minutes (default 30), or — for export_macro — how many recent actions to export (default 300)."),
            "script" to AgentToolParam("string", "JavaScript code to execute (for execute_js action). The script runs inside an async function wrapper — `await` and top-level `return` are both supported (e.g. `var r = await fetch(url); return await r.json()`). Optional 'timeout' (seconds, default 30, max 300); on timeout the result reports whether the URL changed — for a navigating script that is usually the success signal, not a failure. For LONG scripts (or \\u-escape-dense ones that risk transport truncation), write the body to /var/minis/workspace/x.js and pass script_file=/var/minis/workspace/x.js instead — the file path never truncates."),
            "user_agent" to AgentToolParam("string", "User agent profile to switch to", enumValues = listOf("desktop_chrome", "mobile_chrome")),
            "max_depth" to AgentToolParam("integer", "Maximum tree depth for get_backbone (default: 5)"),
            "scroll_count" to AgentToolParam("integer", "Number of scroll steps for scroll_and_collect (default: 10, max: 20). Each step scrolls by 'amount' pixels and waits for new content."),
            "item_selector" to AgentToolParam("string", "CSS selector for individual content items in scroll_and_collect (e.g. 'article', '[data-testid=\"tweet\"]'). If omitted, auto-detects repeated elements."),
            "tab_id" to AgentToolParam("integer", "Target tab ID (optional, defaults to your most recently used tab). Use list_tabs to see the tabs you may use; ids you did not receive from list_tabs/new_tab are rejected."),
            "keywords" to AgentToolParam("string", "Filter cookies by name (for get_cookies). A space-separated string or array of strings. With fuzzy=true (default), ALL keywords must appear in the cookie name (case-insensitive). With fuzzy=false, cookie name must exactly equal any one of the provided keywords (case-insensitive). Omit to return all cookies for the current site."),
            "fuzzy" to AgentToolParam("boolean", "Whether keyword matching is fuzzy (contains-all) or exact-any (for get_cookies, default: true)."),
            "cookies" to AgentToolParam("string", "For set_cookies: a JSON array of cookie objects to write. Pass it as a JSON array (a JSON-encoded string of the array is also accepted). Each object: {\"name\": str (required), \"value\": str (required), \"domain\": str (optional, defaults to current page host), \"path\": str (optional, defaults to \"/\"), \"secure\": bool (optional), \"http_only\": bool (optional — sets an HttpOnly cookie that JS cannot read/set), \"expires\": int (optional, Unix timestamp in seconds; omit for a session cookie)}. Field-name variants from common cookie exports are accepted: httpOnly (=http_only), expirationDate (=expires), sameSite, and case/camel variants — so you can paste cookies verbatim from browser extensions (EditThisCookie / Cookie-Editor) or Playwright/Puppeteer storage."),
            "files" to AgentToolParam("string", "For upload_file: the files to feed the page's file input — a JSON array of paths (a JSON-encoded string is also accepted). Paths may be /var/minis/workspace/…, /var/minis/attachments/…, /var/minis/shared/… or workspace-relative. The files are injected into the input directly; pair with 'selector' if the page's input is not the default input[type=file]. Size caps: 4 MB per file, 8 MB total."),
            "log_level" to AgentToolParam("string", "For get_console_logs: filter by level (log/info/warn/error/debug), or 'all' (default)."),
            "filter" to AgentToolParam("string", "For get_network_log: only return requests whose URL contains this substring."),
            "gesture" to AgentToolParam("string", "For gesture: the gesture to perform", enumValues = listOf("long_press", "double_click", "drag", "pinch")),
            "to_x" to AgentToolParam("integer", "For gesture=drag: destination X in viewport CSS pixels."),
            "to_y" to AgentToolParam("integer", "For gesture=drag: destination Y in viewport CSS pixels."),
            "key" to AgentToolParam("string", "For press_key: base key plus optional modifiers — Enter, Escape, Tab, Space, Backspace, Delete, ArrowUp/Down/Left/Right, Home, End, PageUp, PageDown, F1-F12, a-z, 0-9; combine with '+', e.g. 'ctrl+Enter', 'shift+Tab'. Injected as a real key event (React sees a trusted event)."),
            "scale" to AgentToolParam("number", "For screenshot: downscale factor 0.1–1.5 (1.0 = native size). 0.5 roughly quarters the image payload — use when you need the layout, not fine detail."),
            "index" to AgentToolParam("integer", "For replay_request / copy_as_curl: which captured request, counting from the newest (1 = most recent, default 1)."),
            "method" to AgentToolParam("string", "For replay_request: HTTP method override (default: the captured method)."),
            "body" to AgentToolParam("string", "For replay_request: request body override (default: the captured body). For run_macro: optional JSON object of params substituted into {{placeholders}}."),
            "headers" to AgentToolParam("string", "For replay_request: extra/overriding headers as a JSON object string, e.g. {\"X-Api-Key\":\"...\"}."),
            "fields" to AgentToolParam("string", "For fill_form: JSON array of {selector, value, action} entries — action is fill (default), select, check/uncheck, or press (value = key name)."),
            "submit" to AgentToolParam("boolean", "For fill_form: press Enter on the last field after filling (default false)."),
            "full" to AgentToolParam("boolean", "For get_network_log / get_response_log: return full stored response bodies instead of previews (default false)."),
            "script_file" to AgentToolParam("string", "For execute_js: read the script body from this guest path (e.g. /var/minis/workspace/big.js) instead of the 'script' string — immune to the long-argument transport truncation. 'action' can be 'execute_js' with only script_file set."),
            "hold_minutes" to AgentToolParam("integer", "For hold_tab: how long to keep the tab exempt from idle/memory eviction (default 10, cap 120). Use around login flows so the SMS-code tab isn't reclaimed mid-flow."),
            "offset" to AgentToolParam("integer", "For get_network_log: skip this many entries from the newest (paging, default 0)."),
            "limit" to AgentToolParam("integer", "For get_network_log / get_response_log: max entries to return (default 20, max 100). For keep_alive: how many scheduled pings to arm (default 20)."),
            "probe_url" to AgentToolParam("string", "For keep_alive: a secondary URL that proves the login is still valid (e.g. a get-notifications API that answers 403 when logged out while the dashboard still answers 200). Runs in the in-app WebView (real browser TLS, immune to the curl TLS-fingerprint WAF trap); the live browser jar is used, so cross-domain probes work too."),
            "probe_expect" to AgentToolParam("string", "For keep_alive probe: what a healthy session looks like — a 3-digit status (e.g. \"200\"), a bare substring, or an explicit bodycontains:<text> marker rule (e.g. bodycontains:userId)."),
            "timeout" to AgentToolParam("integer", "Timeout in seconds: wait_for_dom_stable (default 10) / wait_for (default 10) / wait_for_url / wait_for_cookie / wait_for_network_idle (default 30, max 120) / execute_js (default 30, max 300)."),
            "viewport_width" to AgentToolParam("integer", "Viewport width in CSS pixels for set_viewport (e.g. 1920). Required together with viewport_height unless reset=true."),
            "viewport_height" to AgentToolParam("integer", "Viewport height in CSS pixels for set_viewport (e.g. 1080). Required together with viewport_width unless reset=true."),
            "reset" to AgentToolParam("boolean", "For set_viewport: when true, clear the session-level viewport override and fall back to the global browser setting."),
            // [T-android-browser-full-page-schema] The capability was already
            // implemented end to end (BrowserActionInput parses `full_page`,
            // BrowserUseManager.screenshot stretches the WebView and caps at
            // MAX_FULL_PAGE_HEIGHT_PX) — it was simply never declared, so the
            // model had no way to ask for it. Wording adapted from iOS: the
            // mechanism differs (Android stretches the WebView's viewport
            // rather than resizing a WKWebView), the cap and the truncation
            // reporting are identical.
            "full_page" to AgentToolParam("boolean", "For screenshot: capture the entire scrollable page by temporarily stretching the browser viewport to document.documentElement.scrollHeight. Default false captures the visible viewport only. Capped at 32768px tall; when capped, the result text includes 'Truncated: true' and the original height, so you can scroll and capture the remainder separately."),
            // [T-android-init-scripts-2026-10] add_init_script / remove_init_script need a
            // stable id the model can name and later remove by. list_init_scripts takes none.
            "id" to AgentToolParam("string", "For add_init_script / remove_init_script: stable identifier for the document-start injection (e.g. 'fake-cam'). add registers (same id replaces), remove unregisters, list needs no id."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "tab_id", "url", "selector", "text", "coordinate_x", "coordinate_y", "direction", "amount", "scroll_count", "item_selector", "script", "user_agent", "max_depth", "keywords", "fuzzy", "cookies", "files", "log_level", "filter", "gesture", "to_x", "to_y", "key", "scale", "index", "method", "body", "headers", "fields", "submit", "full", "offset", "limit", "probe_url", "probe_expect", "timeout", "viewport_width", "viewport_height", "reset", "full_page", "id"),
    )

    // Aligned with iOS AIChatViewModel.swift:5059-5067
    private fun memoryWriteDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "memory_write",
        description = "Write a memory entry to today's daily log (YYYY-MM-DD.md). Memories persist across all sessions. " +
            "Each entry is prepended with a timestamp. " +
            "Save: user preferences, recurring patterns, key facts, project conventions, reusable knowledge. " +
            "Avoid saving passwords, API keys, tokens, or secrets unless the user explicitly confirms after being warned. " +
            "Keep entries concise and general-purpose. GLOBAL.md is writable but not through this tool; update it when the user asks.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Save user preference for Python', 'Note today's project context'). Use the same language as the user."),
            "content" to AgentToolParam("string", "The memory content to write. Use concise Markdown with a short heading (## Topic) and context about what was done/learned."),
        ),
        required = listOf("tool_title", "content"),
        propertyOrdering = listOf("tool_title", "content"),
    )

    // Aligned with iOS AIChatViewModel.swift:5069-5078
    private fun memoryGetDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "memory_get",
        description = "Retrieve memories from persistent storage. Supports keyword-based fuzzy search across memory files. " +
            "Returns matching lines with surrounding context. Use this to recall previous knowledge, user preferences, or past notes.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Recall user preferences', 'Search past notes'). Use the same language as the user."),
            "scope" to AgentToolParam("string", "Memory scope to search: 'daily' for daily logs only, 'all' for daily logs + GLOBAL.md.", enumValues = listOf("daily", "all")),
            "keywords" to AgentToolParam("string", "Space-separated keywords for fuzzy matching (e.g. 'python preference' or 'API key setup'). All keywords must appear in a line or its surrounding context for a match. Leave empty to return full memory files."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "scope", "keywords"),
    )
}
