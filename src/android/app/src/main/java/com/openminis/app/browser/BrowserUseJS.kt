package com.openminis.app.browser

import org.json.JSONObject
/**
 * Injectable JavaScript for browser_use actions.
 * Each function returns a self-executing IIFE that JSON.stringify()s the result.
 * Ported 1:1 from iOS BrowserUseJavaScript.swift.
 */
object BrowserUseJS {

    /**
     * [T-android-google-auth-stealth-2026-10] document-start facade for the
     * Google sign-in / OAuth surface. Google permanently rejects WebView-based
     * sign-in via multi-signal detection (UA, X-Requested-With, Sec-CH-UA, JS
     * surface, TLS ClientHello). The TLS ClientHello and X-Requested-With are
     * not addressable from JS, but the JS-surface half IS, and combined with a
     * desktop-Chrome UA it clears enough signals that accounts.google.com
     * serves the normal login page instead of 403 disallowed_useragent.
     *
     * Gated by host so it only ever runs on Google auth domains — it must NOT
     * alter the JS surface for the page the user is actually trying to sign
     * into (InstaBox, GitHub, whatever). Kept idempotent like the other
     * document-start scripts.
     */
    val GOOGLE_AUTH_STEALTH_JS: String = """
        (function(){
          if (window.__minis_gauth__) return;
          var ok = false;
          try {
            var h = (location.hostname || '').toLowerCase();
            ok = h === 'accounts.google.com' || h === 'signin.google.com' ||
                 h === 'myaccount.google.com' || h === 'oauth2.googleapis.com' ||
                 h === 'accounts.youtube.com' ||
                 /(^|[.])google\.com$/.test(h) || /(^|[.])gstatic\.com$/.test(h);
          } catch (e) { ok = false; }
          if (!ok) return;
          window.__minis_gauth__ = true;
          try {
            // WebView advertises itself through navigator.userAgentData brands.
            // Override with a plain Chrome brand list; wrapped in try since
            // userAgentData may be undefined on older providers.
            if (navigator.userAgentData && navigator.userAgentData.brands) {
              try {
                Object.defineProperty(navigator.userAgentData, 'brands', {
                  get: function() { return [
                    { brand: 'Google Chrome', version: '134' },
                    { brand: 'Chromium', version: '134' },
                    { brand: 'Not_A Brand', version: '24' }
                  ]; },
                  configurable: true
                });
              } catch (_) {}
              try {
                Object.defineProperty(navigator.userAgentData, 'mobile', {
                  get: function() { return false; }, configurable: true
                });
              } catch (_) {}
            }
          } catch (_) {}
          try {
            // channels/isContact/maxTouchPoints: WebView reports touch input
            // and an unknown channel. A desktop Chrome reports no touch and
            // 'stable'. Both are cheap to lie about on this surface.
            Object.defineProperty(navigator, 'maxTouchPoints', { get: function() { return 0; }, configurable: true });
          } catch (_) {}
        })();
    """

    /**
     * [T-android-browser-observability-v2] Installed at document start (or on
     * page start as a fallback): wraps fetch + XMLHttpRequest to record
     * RESPONSE bodies into `window.__minis_net__` (bounded ring), and forwards
     * uncaught errors / rejections into the page console so the native
     * console capture sees them too. Idempotent — safe to inject twice.
     */
    val EARLY_NET_INSTRUMENTATION_JS: String = """
        (function(){
          if (window.__minis_net__) return;
          // [T-android-antifingerprint-2026-10] Automation surface reduction:
          // navigator.webdriver is the #1 bot-detection signal; WebView never
          // sets it, but document-start clearing costs nothing and guarantees
          // it stays false for pages that check it before our helpers land.
          try {
            Object.defineProperty(Navigator.prototype, 'webdriver', {
              get: function() { return false; }, configurable: true
            });
          } catch (e) { /* prototype locked — WebView default is false anyway */ }
          var ring = []; window.__minis_net__ = ring;
          var state = {pending: 0, last: Date.now()}; window.__minis_net_state = state;
          var seq = 0;
          function touch() { state.last = Date.now(); }
          var FULL_MAX = 131072, FULL_TOTAL = 8388608, FULL_KEEP = 60;
          var full = {}; var fullOrder = []; var fullBytes = 0;
          window.__minis_net_full = full;
          function storeFull(id, text) {
            try {
              if (text == null) return false;
              text = String(text);
              if (text.length === 0 || text.length > FULL_MAX) return false;
              if (full[id] != null) return true;
              full[id] = text; fullOrder.push(id); fullBytes += text.length;
              while ((fullOrder.length > FULL_KEEP || fullBytes > FULL_TOTAL) && fullOrder.length > 0) {
                var old = fullOrder.shift();
                if (full[old] != null) { fullBytes -= full[old].length; delete full[old]; }
              }
              return true;
            } catch (_) { return false; }
          }
          function preview(s) {
            try {
              if (s == null) return '';
              s = String(s);
              return s.length > 2000 ? s.slice(0, 2000) + '...[' + s.length + ' chars]' : s;
            } catch (_) { return ''; }
          }
          function capBody(s, n) {
            try {
              if (s == null) return null;
              if (typeof s !== 'string') {
                if (typeof URLSearchParams !== 'undefined' && s instanceof URLSearchParams) { s = s.toString(); }
                else if (typeof FormData !== 'undefined' && s instanceof FormData) { return '[FormData]'; }
                else if (typeof Blob !== 'undefined' && s instanceof Blob) { return '[Blob ' + s.size + ' bytes]'; }
                else if (typeof ArrayBuffer !== 'undefined' && s instanceof ArrayBuffer) { return '[ArrayBuffer ' + s.byteLength + ' bytes]'; }
                else { try { s = JSON.stringify(s); } catch (_) { return '[unserializable]'; } }
              }
              s = String(s);
              return s.length > n ? s.slice(0, n) + '...[' + s.length + ' chars]' : s;
            } catch (_) { return null; }
          }
          function headerObj(h) {
            var out = null;
            try {
              if (!h) return null;
              if (typeof Headers !== 'undefined' && h instanceof Headers) {
                out = {}; h.forEach(function(v, k) { out[k] = String(v).slice(0, 400); });
              } else if (Object.prototype.toString.call(h) === '[object Array]') {
                out = {}; for (var i = 0; i < h.length; i++) { out[h[i][0]] = String(h[i][1]).slice(0, 400); }
              } else if (typeof h === 'object') {
                out = {}; for (var k in h) { try { out[k] = String(h[k]).slice(0, 400); } catch (_) {} }
              }
            } catch (_) {}
            return out;
          }
          function rawHeaders(text) {
            var out = null;
            try {
              if (!text) return null;
              var lines = String(text).split('\n');
              for (var i = 0; i < lines.length; i++) {
                var line = lines[i];
                var idx = line.indexOf(':');
                if (idx > 0) { if (!out) out = {}; out[line.slice(0, idx).trim()] = line.slice(idx + 1).trim().slice(0, 400); }
              }
            } catch (_) {}
            return out;
          }
          function push(e) { ring.push(e); if (ring.length > 1000) ring.shift(); }
          try {
            var of = window.fetch;
            if (of) {
              window.fetch = function() {
                var args = arguments; var url = ''; var method = 'GET'; var qh = null; var qb = null;
                try {
                  url = (typeof args[0] === 'string') ? args[0] : (args[0] && args[0].url) || '';
                  var init = args[1] || {};
                  method = (init.method || (args[0] && args[0].method) || 'GET').toUpperCase();
                  qh = headerObj(init.headers || (args[0] && args[0].headers));
                  qb = capBody(init.body, 2000);
                } catch (_) {}
                state.pending++; touch();
                var t0 = Date.now();
                var id = ++seq;
                return of.apply(this, args).then(function(resp) {
                  try {
                    var c = resp.clone();
                    c.text().then(function(b) {
                      var stored = storeFull(id, b);
                      push({t:'fetch', id:id, m:method, u:String(url), s:resp.status, ms:Date.now()-t0, b:preview(b), bl:(b == null ? 0 : String(b).length), tb:stored, qh:qh, qb:qb, rh:headerObj(resp.headers)});
                    }).catch(function() {
                      push({t:'fetch', id:id, m:method, u:String(url), s:resp.status, ms:Date.now()-t0, b:'[body unreadable]', qh:qh, qb:qb, rh:headerObj(resp.headers)});
                    });
                  } catch (_) {}
                  state.pending--; touch();
                  return resp;
                }, function(err) {
                  push({t:'fetch', id:id, m:method, u:String(url), s:0, ms:Date.now()-t0, b:'ERROR: ' + err, qh:qh, qb:qb});
                  state.pending--; touch();
                  throw err;
                });
              };
            }
          } catch (_) {}
          try {
            var oo = XMLHttpRequest.prototype.open;
            var os = XMLHttpRequest.prototype.send;
            var osh = XMLHttpRequest.prototype.setRequestHeader;
            XMLHttpRequest.prototype.setRequestHeader = function(k, v) {
              try {
                if (!this.__minis) this.__minis = { m: 'GET', u: '' };
                if (!this.__minis.qh) this.__minis.qh = {};
                this.__minis.qh[String(k)] = String(v).slice(0, 400);
              } catch (_) {}
              return osh.apply(this, arguments);
            };
            XMLHttpRequest.prototype.open = function(m, u) {
              var prev = this.__minis || {};
              this.__minis = { m: (m || 'GET').toUpperCase(), u: String(u), qh: prev.qh || null, qb: null };
              return oo.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function(b) {
              var x = this; var st = Date.now();
              try { if (x.__minis) x.__minis.qb = capBody(b, 2000); } catch (_) {}
              state.pending++; touch();
              var id = ++seq;
              x.addEventListener('loadend', function() {
                state.pending--; touch();
                try {
                  var body = null;
                  try { body = x.responseText; } catch (_) {}
                  var stored = storeFull(id, body);
                  push({t:'xhr', id:id, m:(x.__minis && x.__minis.m) || 'GET', u:(x.__minis && x.__minis.u) || '', s:x.status, ms:Date.now()-st, b:preview(body), bl:(body == null ? 0 : String(body).length), tb:stored, qh:(x.__minis && x.__minis.qh) || null, qb:(x.__minis && x.__minis.qb) || null, rh:rawHeaders(x.getAllResponseHeaders())});
                } catch (_) {}
              });
              return os.apply(this, arguments);
            };
          } catch (_) {}
          try {
            window.addEventListener('error', function(ev) {
              try { console.error('MINIS-UNCAUGHT', (ev && ev.message) || '', (ev && ev.filename) || '', (ev && ev.lineno) || 0); } catch (_) {}
            });
            window.addEventListener('unhandledrejection', function(ev) {
              try { console.error('MINIS-REJECTION', String((ev && ev.reason) || '')); } catch (_) {}
            });
          } catch (_) {}
        })();
    """.trimIndent()

    /**
     * [T-android-browser-smart-selectors-2026-10] Installed alongside the net
     * instrumentation (document start; page-start fallback). Defines
     * `window.__MQF` / `window.__MQA` — a selector engine that understands
     * `text=…`, `aria-label=…` and `role=button[name=…]` prefixes besides
     * plain CSS, and searches through Shadow DOM roots and same-origin
     * iframes. Action snippets call these when present and fall back to
     * document.querySelector otherwise. Cross-origin iframes remain out of
     * reach (same-origin policy); targets inside them can still be hit with
     * coordinate clicks.
     */
    val QUERY_HELPERS_JS: String = """
        (function(){
          if (window.__MQF) return;
          function isVisible(el) {
            try {
              if (!el || !el.getBoundingClientRect) return false;
              var r = el.getBoundingClientRect();
              if (r.width <= 0 || r.height <= 0) return false;
              var st = window.getComputedStyle(el);
              return !(st.display === 'none' || st.visibility === 'hidden' || st.opacity === '0');
            } catch (e) { return false; }
          }
          var SCAN_CAP = 12000;
          function scanAll() {
            var out = [];
            function walk(root) {
              if (!root || !root.querySelectorAll || out.length >= SCAN_CAP) return;
              var all;
              try { all = root.querySelectorAll('*'); } catch (e) { return; }
              for (var i = 0; i < all.length; i++) {
                if (out.length >= SCAN_CAP) return;
                var el = all[i];
                out.push(el);
                try {
                  if (el.shadowRoot) walk(el.shadowRoot);
                  if (el.tagName === 'IFRAME') { var d = el.contentDocument; if (d) walk(d); }
                } catch (e) {}
              }
            }
            walk(document);
            return out;
          }
          function cssFind(sel) { try { return document.querySelector(sel); } catch (e) { return null; } }
          function cssFindAll(sel) { try { return Array.prototype.slice.call(document.querySelectorAll(sel)); } catch (e) { return []; } }
          function implicitRole(el) {
            var t = el.tagName;
            var ty = String((el.getAttribute && el.getAttribute('type')) || '').toLowerCase();
            if (t === 'BUTTON') return 'button';
            if (t === 'A') return (el.getAttribute && el.getAttribute('href')) ? 'link' : null;
            if (t === 'SELECT') return 'combobox';
            if (t === 'TEXTAREA') return 'textbox';
            if (t === 'INPUT') {
              if (ty === 'submit' || ty === 'button' || ty === 'reset') return 'button';
              if (ty === 'checkbox') return 'checkbox';
              if (ty === 'radio') return 'radio';
              if (ty === 'search') return 'searchbox';
              return 'textbox';
            }
            if (t === 'IMG') return 'img';
            return null;
          }
          function accName(el) {
            var v = '';
            try { v = el.getAttribute('aria-label') || el.getAttribute('title') || el.getAttribute('placeholder') || ''; } catch (e) {}
            if (v) return String(v);
            return String((el.innerText || el.textContent || el.value || ''));
          }
          function clickable(el) {
            var t = el.tagName;
            if (t === 'BUTTON' || t === 'A' || t === 'INPUT' || t === 'SELECT' || t === 'TEXTAREA' || t === 'LABEL') return true;
            var r = '';
            try { r = String(el.getAttribute('role') || '').toLowerCase(); } catch (e) {}
            if (r === 'button' || r === 'link' || r === 'tab' || r === 'menuitem' || r === 'checkbox' || r === 'radio' || r === 'option') return true;
            try { if (el.onclick) return true; } catch (e) {}
            try { if (window.getComputedStyle(el).cursor === 'pointer') return true; } catch (e) {}
            return false;
          }
          function pickBest(list) {
            if (!list || !list.length) return null;
            var best = null; var bestScore = -1; var bestArea = 1e18;
            for (var i = 0; i < list.length && i < 40; i++) {
              var el = list[i];
              var area = 1e17;
              try { var r = el.getBoundingClientRect(); area = r.width * r.height; } catch (e) {}
              var score = 0;
              if (clickable(el)) score += 2;
              if (isVisible(el)) score += 1;
              if (i < 8) score += 1;
              if (score > bestScore || (score === bestScore && area < bestArea)) { best = el; bestScore = score; bestArea = area; }
            }
            return best || list[0];
          }
          function stripQuotes(v) {
            if (v && v.length > 1) {
              var a = v.charAt(0); var b = v.charAt(v.length - 1);
              if ((a === '"' && b === '"') || (a === "'" && b === "'")) return v.slice(1, -1);
            }
            return v || '';
          }
          function matchText(sel) {
            if (sel.toLowerCase().indexOf('text=') !== 0) return null;
            var raw = sel.substring(5).trim();
            var exact = false;
            if (raw.length > 1 && ((raw.charAt(0) === '"' && raw.charAt(raw.length-1) === '"') || (raw.charAt(0) === "'" && raw.charAt(raw.length-1) === "'"))) {
              raw = raw.slice(1, -1); exact = true;
            }
            var want = raw.trim().toLowerCase();
            if (!want) return [];
            var els = scanAll();
            var exactHits = []; var looseHits = [];
            for (var i = 0; i < els.length; i++) {
              var el = els[i];
              var t = String(el.innerText || el.textContent || el.value || '').trim().toLowerCase();
              var ph = '';
              try { ph = String(el.getAttribute('placeholder') || '').toLowerCase(); } catch (e) {}
              var al = '';
              try { al = String(el.getAttribute('aria-label') || '').toLowerCase(); } catch (e) {}
              if (!t && !ph && !al) continue;
              if (t === want || ph === want || al === want) { exactHits.push(el); continue; }
              if (!exact && (t.indexOf(want) >= 0 || ph.indexOf(want) >= 0 || al.indexOf(want) >= 0)) looseHits.push(el);
            }
            var best = pickBest(exactHits);
            if (best) return [best].concat(exactHits, looseHits);
            best = pickBest(looseHits);
            return best ? [best].concat(looseHits) : [];
          }
          function matchAria(sel) {
            if (sel.toLowerCase().indexOf('aria-label=') !== 0) return null;
            var want = stripQuotes(sel.substring(11).trim()).toLowerCase();
            var els = scanAll(); var hits = [];
            for (var i = 0; i < els.length; i++) {
              var v = '';
              try { v = String(els[i].getAttribute('aria-label') || '').toLowerCase(); } catch (e) {}
              if (v && v.indexOf(want) >= 0) hits.push(els[i]);
            }
            var best = pickBest(hits);
            return best ? [best].concat(hits) : [];
          }
          function matchRole(sel) {
            if (sel.toLowerCase().indexOf('role=') !== 0) return null;
            var rest = sel.substring(5).trim();
            var want = null;
            var rb = rest.indexOf('[');
            if (rb >= 0) {
              var rbEnd = rest.lastIndexOf(']');
              if (rbEnd > rb) {
                var inner = rest.substring(rb + 1, rbEnd).trim();
                if (inner.toLowerCase().indexOf('name=') === 0) {
                  want = stripQuotes(inner.substring(5).trim()).toLowerCase();
                }
              }
              rest = rest.substring(0, rb);
            }
            var role = rest.trim().toLowerCase();
            if (!role) return [];
            var els = scanAll(); var hits = [];
            for (var i = 0; i < els.length; i++) {
              var el = els[i];
              var r = '';
              try { r = String(el.getAttribute('role') || '').toLowerCase(); } catch (e) {}
              if (!r) r = implicitRole(el) || '';
              if (r !== role) continue;
              if (want !== null) {
                var nm = accName(el).trim().toLowerCase();
                if (nm.indexOf(want) < 0) continue;
              }
              hits.push(el);
            }
            var best = pickBest(hits);
            return best ? [best].concat(hits) : [];
          }
          function matchPlaceholder(sel) {
            var lower = sel.toLowerCase();
            var raw = null;
            if (lower.indexOf('placeholder*=') === 0) raw = sel.substring(13);
            else if (lower.indexOf('placeholder=') === 0) raw = sel.substring(12);
            else return null;
            var m = raw.trim();
            var exact = false;
            if (m.length > 1 && ((m.charAt(0) === '"' && m.charAt(m.length - 1) === '"') ||
                                 (m.charAt(0) === "'" && m.charAt(m.length - 1) === "'"))) {
              m = m.slice(1, -1); exact = true;
            }
            var want = m.trim().toLowerCase();
            if (!want) return [];
            var els = scanAll();
            var exactHits = []; var looseHits = [];
            for (var i = 0; i < els.length; i++) {
              var el = els[i];
              var ph = '';
              try { ph = String(el.getAttribute('placeholder') || el.getAttribute('aria-placeholder') || '').toLowerCase(); } catch (e) {}
              if (!ph) continue;
              if (ph === want) { exactHits.push(el); continue; }
              if (!exact && ph.indexOf(want) >= 0) looseHits.push(el);
            }
            var best = pickBest(exactHits);
            if (best) return [best].concat(exactHits, looseHits);
            best = pickBest(looseHits);
            return best ? [best].concat(looseHits) : [];
          }
          function limitOnly(arr, limit) { return arr.slice(0, limit || arr.length); }
          function resolveAll(sel, limit) {
            var s = String(sel == null ? '' : sel).trim();
            var pref = matchText(s);
            if (pref !== null) return limitOnly(pref, limit);
            pref = matchAria(s);
            if (pref !== null) return limitOnly(pref, limit);
            pref = matchRole(s);
            if (pref !== null) return limitOnly(pref, limit);
            pref = matchPlaceholder(s);
            if (pref !== null) return limitOnly(pref, limit);
            return cssFindAll(s).slice(0, limit);
          }
          window.__MQA = function(sel, limit) {
            try { return resolveAll(sel, limit || 50); } catch (e) { return []; }
          };
          window.__MQF = function(sel) {
            try {
              var s = String(sel == null ? '' : sel).trim();
              var sl = s.toLowerCase();
              if (sl.indexOf('text=') === 0 || sl.indexOf('aria-label=') === 0 || sl.indexOf('role=') === 0 ||
                  sl.indexOf('placeholder=') === 0 || sl.indexOf('placeholder*=') === 0) {
                var arr = resolveAll(s, 1);
                return arr.length ? arr[0] : null;
              }
              return cssFind(s) || null;
            } catch (e) { return null; }
          };
          window.__MQhint = function(sel) {
            try {
              var s = String(sel == null ? '' : sel).trim();
              var lower = s.toLowerCase();
              if (lower.indexOf('placeholder*=') === 0) s = s.substring(13);
              else if (lower.indexOf('placeholder=') === 0) s = s.substring(12);
              else if (lower.indexOf('text=') === 0) s = s.substring(5);
              else if (lower.indexOf('aria-label=') === 0) s = s.substring(11);
              s = stripQuotes(s).trim();
              if (s.length < 2) return '';
              var needle = s.toLowerCase();
              var hits = [];
              var els = document.querySelectorAll('input,textarea,button,a,[role="button"],[contenteditable="true"]');
              for (var i = 0; i < els.length && hits.length < 3; i++) {
                var el = els[i];
                var ph = '';
                try { ph = String(el.getAttribute('placeholder') || el.getAttribute('aria-placeholder') || ''); } catch (e) {}
                var al = '';
                try { al = String(el.getAttribute('aria-label') || ''); } catch (e) {}
                var tx = String(el.innerText || el.value || '').trim();
                var pl = ph.toLowerCase(); var all = al.toLowerCase(); var tl = tx.toLowerCase();
                if (pl && pl !== needle && (pl.indexOf(needle) >= 0 || needle.indexOf(pl) >= 0)) {
                  hits.push('placeholder*=' + ph);
                } else if (all && all !== needle && (all.indexOf(needle) >= 0 || needle.indexOf(all) >= 0)) {
                  hits.push('aria-label=' + al);
                } else if (tl && tl.length <= 24 && tl !== needle && (tl.indexOf(needle) >= 0 || needle.indexOf(tl) >= 0)) {
                  hits.push('text=' + tx.slice(0, 20));
                }
              }
              return hits.length ? ' — did you mean: ' + hits.join(' | ') : '';
            } catch (e) { return ''; }
          };
        })();
    """.trimIndent()

    /**
     * [T-android-viewport-guard-2026-10] Corrects malformed viewport metas at
     * parse time. GitCode ships `initial-scale=0` (illegal) and WebView clamps
     * the page to its 25% minimum scale with a 4×-stretched layout. Rewrites
     * initial-scale<=0 to 1 and drops zero maximum/minimum-scale clauses, via
     * an early scan plus a MutationObserver for parser-inserted metas. Sets
     * `window.__minis_vp_fixed` so the manager can decide whether a recovery
     * reload is worth it.
     */
    val VIEWPORT_GUARD_JS: String = """
        (function(){
          if (window.__minis_vp_guard) return;
          window.__minis_vp_guard = true;
          window.__minis_vp_fixes = 0;
          function fix(meta) {
            try {
              if (window.__minis_vp_fixes > 200) return;
              var c = String(meta.getAttribute('content') || '');
              if (!c) return;
              var parts = c.split(',');
              var out = [];
              var changed = false;
              for (var i = 0; i < parts.length; i++) {
                var p = parts[i].trim();
                if (!p) continue;
                var eq = p.indexOf('=');
                if (eq < 0) { out.push(p); continue; }
                var k = p.slice(0, eq).trim().toLowerCase();
                var v = p.slice(eq + 1).trim();
                var f = parseFloat(v);
                if ((k === 'initial-scale' || k === 'maximum-scale' || k === 'minimum-scale') && isFinite(f) && f <= 0) {
                  changed = true;
                  if (k === 'initial-scale') out.push('initial-scale=1');
                  continue;
                }
                // [T-android-browser-user-zoom-2026-10] Pages that lock the
                // viewport (user-scalable=no / maximum-scale) block pinch —
                // WebView honours them even when built-in zoom is enabled.
                // Mirror Chrome's accessibility behaviour: drop maximum-scale
                // and un-lock user-scalable; minimum-scale stays respected.
                if (k === 'user-scalable') {
                  var uv = v.toLowerCase();
                  if (uv === 'no' || uv === 'false' || uv === '0') {
                    changed = true;
                    out.push('user-scalable=yes');
                    continue;
                  }
                }
                if (k === 'maximum-scale') {
                  changed = true;
                  continue;
                }
                // Desktop-breakpoint pages (e.g. xiaohongshu creator login,
                // .login-container{min-width:1024px}) are UNREADABLE at phone
                // width unless the user can zoom OUT past minimum-scale=1 to
                // fit. Clamp the floor to 0.2 instead of honouring 1.
                if (k === 'minimum-scale') {
                  var mf = parseFloat(v);
                  if (isFinite(mf) && mf > 0.2) {
                    changed = true;
                    out.push('minimum-scale=0.2');
                    continue;
                  }
                }
                out.push(p);
              }
              if (changed) {
                window.__minis_vp_fixes++;
                meta.setAttribute('content', out.join(', '));
                window.__minis_vp_fixed = true;
                try { console.warn('MINIS-VIEWPORT-FIX', c); } catch (_) {}
              }
            } catch (_) {}
          }
          function scan(root) {
            try {
              var list = (root && root.querySelectorAll) ? root.querySelectorAll('meta') : [];
              for (var i = 0; i < list.length; i++) {
                var n = list[i];
                if (String(n.getAttribute('name') || '').toLowerCase() === 'viewport') fix(n);
              }
            } catch (_) {}
          }
          window.__minis_vp_fix_all = function () { scan(document); };
          // [T-android-browser-user-zoom-2026-10] xiaohongshu and friends set
          // `html,body{touch-action:none}` — that disables the pinch gesture at
          // the hit-test layer, ABOVE the viewport meta and the WebView zoom
          // settings, which is why a page can still refuse pinch after every
          // meta fix. A resident !important rule re-enables panning + pinch;
          // a MutationObserver keeps it alive against page rewrites (our own
          // style element being removed or its sheet mutated).
          function installTouchActionOverride() {
            try {
              // touch-action is an INTERSECTION-cascading property: the hit
              // rules are combined, and `none` intersected with ANYTHING is
              // still blocked — so overriding with a stronger !important rule
              // does nothing while the page's `html,body{touch-action:none}`
              // (xiaohongshu) stays in force. The only fix is to REWRITE that
              // rule's own declaration. Walk every reachable sheet (CORS
              // sheets throw on cssRules and are skipped) and neutralize any
              // touch-action rule that matches html/body. Note Chromium
              // serializes 'pan-x pan-y pinch-zoom' AS 'manipulation' — that
              // display is correct and functional, not a failure.
              for (var si = 0; si < document.styleSheets.length; si++) {
                var sheet = document.styleSheets[si];
                var rules;
                try { rules = sheet.cssRules; } catch (_) { continue; }
                if (!rules) continue;
                for (var ri = 0; ri < rules.length; ri++) {
                  var rule = rules[ri];
                  var sel = '';
                  try { sel = rule.selectorText || ''; } catch (_) { continue; }
                  if (!sel) continue;
                  var matchesRoot = false;
                  try { matchesRoot = document.documentElement.matches(sel) || document.body.matches(sel); } catch (_) { continue; }
                  if (!matchesRoot) continue;
                  try {
                    var cur = rule.style ? rule.style.getPropertyValue('touch-action') : '';
                    if (cur && cur.trim() !== 'auto' &&
                        cur.indexOf('pinch-zoom') < 0 && cur.indexOf('manipulation') < 0) {
                      rule.style.setProperty('touch-action', 'pan-x pan-y pinch-zoom', 'important');
                    }
                  } catch (_) {}
                }
              }
              // Inline declarations: an inline !important beats every sheet, so
              // a page writing style.touchAction='none' must be stripped.
              ['documentElement', 'body'].forEach(function (prop) {
                var el = document[prop];
                if (el && el.style && el.style.touchAction) {
                  try { el.style.removeProperty('touch-action'); } catch (_) { try { el.style.touchAction = ''; } catch (_) {} }
                }
              });
            } catch (_) {}
          }
          function armTouchActionGuard() {
            try {
              var mo2 = new MutationObserver(function () { installTouchActionOverride(); });
              mo2.observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class'] });
              if (document.head) {
                mo2.observe(document.head, { childList: true, subtree: true });
              }
            } catch (_) {}
          }
          function watch() {
            scan(document);
            installTouchActionOverride();
            armTouchActionGuard();
            try {
              var mo = new MutationObserver(function (muts) {
                for (var i = 0; i < muts.length; i++) {
                  var m = muts[i];
                  // In-place content mutation on the meta itself (GitCode's
                  // post-hydration rewrite) arrives as an `attributes` record,
                  // NOT childList — handle both.
                  if (m.target && m.target.nodeType === 1 &&
                      m.target.tagName === 'META' &&
                      String(m.target.getAttribute('name') || '').toLowerCase() === 'viewport') {
                    fix(m.target);
                  }
                  var added = m.addedNodes;
                  for (var j = 0; j < added.length; j++) {
                    var n = added[j];
                    if (!n || n.nodeType !== 1) continue;
                    if (n.tagName === 'META' && String(n.getAttribute('name') || '').toLowerCase() === 'viewport') fix(n);
                    else scan(n);
                  }
                }
              });
              mo.observe(document.documentElement || document, {
                childList: true, subtree: true, attributes: true, attributeFilter: ['content']
              });
            } catch (_) {}
          }
          function boot() {
            if (document.documentElement) watch();
            else setTimeout(boot, 0);
          }
          boot();
          // Belt-and-braces: the poisoning script can run at any hydration
          // moment — poll fast for the first 5s, then slowly for another 15s.
          try {
            var fast = setInterval(function () { scan(document); installTouchActionOverride(); }, 60);
            setTimeout(function () {
              clearInterval(fast);
              var slow = setInterval(function () { scan(document); installTouchActionOverride(); }, 500);
              setTimeout(function () { clearInterval(slow); }, 15000);
            }, 5000);
          } catch (_) {}
        })();
    """.trimIndent()

    /** Scroll the element into view and return its viewport rect (CSS px). */
    fun elementRect(selector: String): String = """
        (function(){
          var __f = (window.__MQF || function(s){ try { return document.querySelector(s); } catch(e) { return null; } });
          var el = __f(${JSONObject.quote(selector)});
          if (!el) return JSON.stringify({error:'selector not found'});
          el.scrollIntoView({block:'center', inline:'center'});
          var r = el.getBoundingClientRect();
          return JSON.stringify({x:r.left, y:r.top, w:r.width, h:r.height, tag:el.tagName});
        })()
    """.trimIndent()

    /** One synchronous poll for [waitFor] — returns JSON `{ok, matched, tag}`. */
    fun waitProbe(selector: String, text: String?): String = """
        (function(){
          var __f = (window.__MQF || function(s){ try { return document.querySelector(s); } catch(e) { return null; } });
          var el = __f(${JSONObject.quote(selector)});
          var bodyText = document.body ? (document.body.innerText || '') : '';
          var t = ${if (text == null) "null" else JSONObject.quote(text)};
          if (el) {
            if (t === null) return JSON.stringify({ok:true, matched:'selector', tag:el.tagName, text:(el.innerText||el.value||'').slice(0,140)});
            if ((el.innerText||'').indexOf(t) >= 0 || bodyText.indexOf(t) >= 0) {
              return JSON.stringify({ok:true, matched:'selector+text', tag:el.tagName});
            }
          }
          if (t !== null && bodyText.indexOf(t) >= 0) return JSON.stringify({ok:true, matched:'text'});
          return JSON.stringify({ok:false});
        })()
    """.trimIndent()

    /** Set a `<select>` by option label or value, dispatching change+input. */
    fun selectOption(selector: String, want: String): String = """
        (function(){
          var __f = (window.__MQF || function(s){ try { return document.querySelector(s); } catch(e) { return null; } });
          var el = __f(${JSONObject.quote(selector)});
          if (!el) return JSON.stringify({error:'selector not found'});
          if (el.tagName !== 'SELECT') return JSON.stringify({error:'not a <select>: ' + el.tagName});
          var want = ${JSONObject.quote(want)};
          var hit = null;
          for (var i = 0; i < el.options.length; i++) {
            var o = el.options[i];
            if (o.value === want || (o.text && o.text.trim() === want) || (o.text && o.text.indexOf(want) >= 0)) { hit = o; break; }
          }
          if (!hit) return JSON.stringify({error:'option not found: ' + want});
          el.value = hit.value;
          el.dispatchEvent(new Event('change', {bubbles:true}));
          el.dispatchEvent(new Event('input', {bubbles:true}));
          return JSON.stringify({selected: hit.text, value: hit.value});
        })()
    """.trimIndent()
    // BEGIN BrowserReadGuard
    /**
     * [T-android-js-error-detail] Wraps an action script (an IIFE expression
     * returning a JSON string) so it never fails silently. Android's
     * `WebView.evaluateJavascript` swallows a thrown exception and hands the
     * callback a bare `null`, so an unguarded `document.body.innerText` on a
     * document without a body - mid-navigation, or not HTML at all (SVG, XML,
     * an image) - reached the tool as the success text "null" with no reason.
     *
     * - no body yet (or still loading with an empty body) → a JSON `error`
     *   saying so and what to do; with `requiresBody = false` the script runs
     *   anyway (get_page_info must work exactly then, and the write actions
     *   only want the try/catch);
     * - anything thrown → `{error: "Script threw: <name>: <message>"}`.
     * The wrapped script's own return value passes through unchanged, so a
     * successful result is byte-identical to the unwrapped one. Same wording
     * as iOS `BrowserUseJS.guardedRead` (BrowserUseJavaScript.swift).
     */
    fun guardedRead(iife: String, requiresBody: Boolean = true): String = """
(function() {
${if (requiresBody) BODY_CHECK_JS else ""}
    try {
        return $iife;
    } catch (e) {
        return JSON.stringify({error: 'Script threw: ' + ((e && e.name) || 'Error') + ': ' + ((e && e.message) || String(e))});
    }
})()
""".trim()

    /** Returns early with a JSON error when the page has no usable body. */
    const val BODY_CHECK_JS = """
    var __b = document.body;
    if (!__b || (document.readyState === 'loading' && !__b.firstElementChild && !(__b.textContent || '').trim())) {
        var __rs = document.readyState, __u = String(location.href).substring(0, 200);
        if (__rs === 'loading' || !__b && __rs !== 'complete') {
            return JSON.stringify({error: 'The page has no body yet (readyState=' + __rs + ', url=' + __u + '). Wait for it to finish loading - e.g. call wait_for_dom_stable or get_page_info first - then retry.'});
        }
        return JSON.stringify({error: 'This document has no <body> (readyState=' + __rs + ', contentType=' + (document.contentType || '?') + ', url=' + __u + ') - it is not an HTML page (e.g. SVG, XML or an image). Use execute_js with document.documentElement instead.'});
    }"""

    /**
     * JS expression describing a caught exception `e` as
     * "Script threw: <name>: <message>" - the read guard's wording, also used
     * by execute_js's wrapper so a user script's throw keeps its type.
     */
    const val SCRIPT_THREW_JS =
        "'Script threw: ' + ((e && e.name) || 'Error') + ': ' + ((e && e.message) || String(e))"

    /**
     * Compiles an execute_js [script] as an async function body WITHOUT
     * running it, and returns the SyntaxError text ("SyntaxError: …") or ''
     * when it compiles. Used only after the wrapped script failed to run:
     * WebView reports a parse failure as a bare null, with no message.
     * Pages whose Content-Security-Policy forbids eval answer with an
     * EvalError instead, which [BrowserJSResult.syntaxErrorMessage] turns
     * into the generic text.
     */
    fun syntaxCheck(script: String): String = """
(function() {
    try {
        new (Object.getPrototypeOf(async function() {}).constructor)(${org.json.JSONObject.quote(script)});
        return '';
    } catch (e) {
        return ((e && e.name) || 'Error') + ': ' + ((e && e.message) || String(e));
    }
})()
""".trim()
    // END BrowserReadGuard

    /** Safely escape a string for embedding in JavaScript source (single-quoted). */
    fun jsQuote(s: String): String = buildString {
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

    // -- Click --

    fun click(selector: String): String = guardedRead("""
        (function() {
            var __f = (window.__MQF || function(s){ try { return document.querySelector(s); } catch(e) { return null; } });
            var el = __f('${jsQuote(selector)}');
            if (!el) return JSON.stringify({error: 'Element not found: ${jsQuote(selector)} - searched CSS / text= / aria-label= / role= / placeholder*= (incl. Shadow DOM + same-origin iframes)' + (window.__MQhint ? window.__MQhint(${JSONObject.quote(selector)}) : '')});
            if (el.disabled) return JSON.stringify({error: 'Element is disabled', tag: el.tagName});
            // [T-android-blank-link-2026-10] A target=_blank link from a
            // scripted click would be silently swallowed by the popup blocker
            // (no tab, no notice — observed on GitCode). Hand the href back so
            // the manager can open a real tab instead of clicking blindly.
            var blankAnchor = null;
            try {
                var a = (el.tagName === 'A') ? el : (el.closest ? el.closest('a') : null);
                if (a && a.getAttribute && String(a.getAttribute('target') || '').toLowerCase() === '_blank' &&
                    a.href && String(a.href).indexOf('javascript:') !== 0) {
                    blankAnchor = a;
                }
            } catch (e) {}
            if (blankAnchor) {
                return JSON.stringify({needsNewTab: true, url: String(blankAnchor.href), text: (blankAnchor.innerText || '').substring(0, 100)});
            }
            var info = {tag: el.tagName, text: (el.innerText || el.value || '').substring(0, 100)};
            try { el.scrollIntoView({block: 'center'}); } catch (e) {}
            var bOpts = {bubbles: true, cancelable: true, view: window};
            var nbOpts = {bubbles: false, cancelable: true, view: window};
            el.dispatchEvent(new MouseEvent('mouseover', bOpts));
            el.dispatchEvent(new MouseEvent('mouseenter', nbOpts));
            el.dispatchEvent(new MouseEvent('mousemove', bOpts));
            el.dispatchEvent(new MouseEvent('mousedown', bOpts));
            el.dispatchEvent(new MouseEvent('mouseup', bOpts));
            el.click();
            el.dispatchEvent(new MouseEvent('mouseleave', nbOpts));
            el.dispatchEvent(new MouseEvent('mouseout', bOpts));
            return JSON.stringify({clicked: true, tag: info.tag, text: info.text});
        })()
    """.trimIndent(), requiresBody = false)

    fun clickCoordinate(x: Int, y: Int): String = guardedRead("""
        (function() {
            var el = document.elementFromPoint($x, $y);
            if (!el) return JSON.stringify({error: 'No element at ($x, $y)'});
            if (el.disabled) return JSON.stringify({error: 'Element is disabled', tag: el.tagName, x: $x, y: $y});
            var bOpts = {bubbles: true, cancelable: true, view: window};
            var nbOpts = {bubbles: false, cancelable: true, view: window};
            el.dispatchEvent(new MouseEvent('mouseover', bOpts));
            el.dispatchEvent(new MouseEvent('mouseenter', nbOpts));
            el.dispatchEvent(new MouseEvent('mousemove', bOpts));
            el.dispatchEvent(new MouseEvent('mousedown', bOpts));
            el.dispatchEvent(new MouseEvent('mouseup', bOpts));
            el.click();
            el.dispatchEvent(new MouseEvent('mouseleave', nbOpts));
            el.dispatchEvent(new MouseEvent('mouseout', bOpts));
            return JSON.stringify({clicked: true, tag: el.tagName, x: $x, y: $y, text: (el.innerText || '').substring(0, 100)});
        })()
    """.trimIndent(), requiresBody = false)

    // -- Type --

    fun type(selector: String, text: String): String = guardedRead("""
        (function() {
            var __f = (window.__MQF || function(s){ try { return document.querySelector(s); } catch(e) { return null; } });
            var el = __f('${jsQuote(selector)}');
            if (!el) return JSON.stringify({error: 'Element not found: ${jsQuote(selector)}' + (window.__MQhint ? window.__MQhint(${JSONObject.quote(selector)}) : '')});
            el.focus();
            if (el.isContentEditable) {
                // [T-android-rich-editor-type-2026-10] Rich editors (CodeMirror 6,
                // TinyMCE body, Slate/Lexical…) only sync their framework model
                // from the REAL beforeinput/input pipeline that
                // execCommand('insertText') produces; assigning innerText leaves
                // the model empty (counter 0/65535, submit button stays disabled).
                var via = 'fallback';
                var ok = false;
                try {
                    var sel = window.getSelection();
                    var range = document.createRange();
                    range.selectNodeContents(el);
                    sel.removeAllRanges();
                    sel.addRange(range);
                    var lines = '${jsQuote(text)}'.split('\n');
                    for (var li = 0; li < lines.length; li++) {
                        if (li > 0) { try { document.execCommand('insertLineBreak'); } catch (e) {} }
                        if (lines[li].length > 0) {
                            ok = document.execCommand('insertText', false, lines[li]) || ok;
                        }
                    }
                    if (ok) via = 'execCommand-insertText';
                } catch (e) {}
                if (!ok) {
                    try {
                        el.textContent = '${jsQuote(text)}';
                        el.dispatchEvent(new InputEvent('input', {bubbles: true, inputType: 'insertText', data: '${jsQuote(text)}'}));
                    } catch (e) {}
                }
                try { el.dispatchEvent(new Event('change', {bubbles: true})); } catch (e) {}
                try {
                    el.dispatchEvent(new FocusEvent('blur', {bubbles: true, relatedTarget: null}));
                    el.dispatchEvent(new FocusEvent('focusout', {bubbles: true, relatedTarget: null}));
                } catch (e) {}
                return JSON.stringify({typed: true, selector: '${jsQuote(selector)}', length: '${jsQuote(text)}'.length, via: via});
            }
            if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
                var nativeSetter = Object.getOwnPropertyDescriptor(
                    el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype, 'value'
                );
                if (nativeSetter && nativeSetter.set) {
                    nativeSetter.set.call(el, '${jsQuote(text)}');
                } else {
                    el.value = '${jsQuote(text)}';
                }
            } else {
                el.innerText = '${jsQuote(text)}';
            }
            var chars = '${jsQuote(text)}';
            for (var i = 0; i < chars.length; i++) {
                var c = chars[i];
                el.dispatchEvent(new KeyboardEvent('keydown', {key: c, bubbles: true}));
                el.dispatchEvent(new KeyboardEvent('keypress', {key: c, bubbles: true}));
                el.dispatchEvent(new InputEvent('input', {data: c, inputType: 'insertText', bubbles: true}));
                el.dispatchEvent(new KeyboardEvent('keyup', {key: c, bubbles: true}));
            }
            el.dispatchEvent(new Event('change', {bubbles: true}));
            try {
                if (window.angular) {
                    var ngEl = window.angular.element(el);
                    var scope = ngEl.scope() || (ngEl.injector && ngEl.injector().get('${'$'}rootScope'));
                    if (scope && !scope.${'$'}${'$'}phase) scope.${'$'}apply();
                }
            } catch(e) {}
            try {
                if (el.__vue__) el.__vue__.${'$'}forceUpdate();
                if (el._vei || el.__vueParentComponent) el.dispatchEvent(new Event('input', {bubbles: true}));
            } catch(e) {}
            if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable) {
                el.dispatchEvent(new FocusEvent('blur', {bubbles: true, relatedTarget: null}));
                el.dispatchEvent(new FocusEvent('focusout', {bubbles: true, relatedTarget: null}));
            }
            return JSON.stringify({typed: true, selector: '${jsQuote(selector)}', length: chars.length});
        })()
    """.trimIndent(), requiresBody = false)

    // -- Get Text --

    fun getText(selector: String?): String {
        if (selector != null) {
            val sel = jsQuote(selector)
            return guardedRead("""
                (function() {
                    var __f = (window.__MQF || function(s){ try { return document.querySelector(s); } catch(e) { return null; } });
                    var el = __f('$sel');
                    if (!el) return JSON.stringify({error: 'Element not found: $sel' + (window.__MQhint ? window.__MQhint(${JSONObject.quote(sel)}) : '')});
                    var innerTextVal = el.innerText || '';
                    var textContentVal = el.textContent || '';
                    // [T-android-gettext-form-values-2026-10] Form controls
                    // carry their content in `value`, not innerText — a bare
                    // get_text on an input/textarea/select used to return an
                    // empty string right after a successful type/fill_form.
                    // Report the value for those, plus the selected option's
                    // label for selects, so the agent can verify what it typed.
                    var tag = (el.tagName || '').toUpperCase();
                    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') {
                        var v = (el.value != null) ? String(el.value) : '';
                        var extra = '';
                        if (tag === 'SELECT' && el.selectedOptions && el.selectedOptions.length) {
                            var labels = [];
                            for (var si = 0; si < el.selectedOptions.length; si++) labels.push(el.selectedOptions[si].label || el.selectedOptions[si].value);
                            extra = labels.join(', ');
                        }
                        text = (v + (extra && extra !== v ? ' (' + extra + ')' : '')).substring(0, 10000);
                        return JSON.stringify({text: text, length: text.length, value: v, kind: 'value'});
                    }
                    var text = innerTextVal.substring(0, 10000);
                    return JSON.stringify({text: text, length: text.length});
                })()
            """.trimIndent())
        }
        return guardedRead("""
            (function() {
                var innerTextVal = document.body.innerText || '';
                var text = innerTextVal.substring(0, 10000);
                return JSON.stringify({text: text, length: text.length, url: location.href, title: document.title});
            })()
        """.trimIndent())
    }

    // -- Get Readable --

    fun getReadable(): String = guardedRead("""
        (function() {
            var candidateSelectors = [
                'article', '[role="main"]', 'main', '.post-content',
                '.article-body', '.entry-content', '#content', '.content'
            ];
            var el = null;
            var matchedSelector = null;
            for (var i = 0; i < candidateSelectors.length; i++) {
                var found = document.querySelector(candidateSelectors[i]);
                if (found && window.getComputedStyle(found).display !== 'none' && (found.innerText || '').length > 0) {
                    el = found; matchedSelector = candidateSelectors[i]; break;
                }
            }
            if (!el) { el = document.body; matchedSelector = 'document.body (fallback)'; }
            var title = document.title || '';
            var innerTextVal = el.innerText || '';
            var text = innerTextVal.replace(/\s+/g, ' ').trim().substring(0, 15000);
            return JSON.stringify({title: title, text: text, length: text.length, source: matchedSelector});
        })()
    """.trimIndent())

    // -- Scroll --

    fun scroll(direction: ScrollDirection, amount: Int, selector: String?): String {
        val pixels = if (direction == ScrollDirection.DOWN) amount else -amount
        val dir = direction.value
        val selectorJS = if (selector != null) "'${jsQuote(selector)}'" else "null"
        return guardedRead("""
            (function() {
                var targetSelector = $selectorJS;
                var target = null;
                var scrolledElement = 'window';
                if (targetSelector) {
                    target = (window.__MQF || function(s){ try { return document.querySelector(s); } catch(e) { return null; } })(targetSelector);
                    if (!target) return JSON.stringify({error: 'Element not found: ' + targetSelector + (window.__MQhint ? window.__MQhint(targetSelector) : '')});
                } else {
                    var beforeY = window.scrollY;
                    window.scrollBy(0, $pixels);
                    if (window.scrollY !== beforeY) {
                        var sh = document.documentElement.scrollHeight || document.body.scrollHeight;
                        return JSON.stringify({scrolled: true, element: 'window', direction: '$dir', amount: $amount, scrollY: window.scrollY, scrollHeight: sh, viewportHeight: window.innerHeight});
                    }
                    var best = null;
                    var bestArea = 0;
                    function walk(el, depth) {
                        if (depth > 10) return;
                        var children = el.children;
                        for (var i = 0; i < children.length; i++) {
                            var child = children[i];
                            var st = window.getComputedStyle(child);
                            var oy = st.overflowY;
                            if ((oy === 'auto' || oy === 'scroll') && child.scrollHeight > child.clientHeight + 5) {
                                var area = child.clientWidth * child.clientHeight;
                                if (area > bestArea) { best = child; bestArea = area; }
                            }
                            walk(child, depth + 1);
                        }
                    }
                    walk(document.body, 0);
                    if (best) { target = best; scrolledElement = best.tagName.toLowerCase(); }
                    else {
                        document.documentElement.scrollTop += $pixels;
                        return JSON.stringify({scrolled: true, element: 'document.documentElement', direction: '$dir', amount: $amount, scrollY: document.documentElement.scrollTop});
                    }
                }
                if (target) {
                    target.scrollBy(0, $pixels);
                    return JSON.stringify({scrolled: true, element: scrolledElement, direction: '$dir', amount: $amount, scrollTop: target.scrollTop, scrollHeight: target.scrollHeight, clientHeight: target.clientHeight});
                }
                return JSON.stringify({scrolled: false, error: 'No scrollable target found'});
            })()
        """.trimIndent(), requiresBody = false)
    }

    // -- Hover --

    fun hover(selector: String): String = guardedRead("""
        (function() {
            var __f = (window.__MQF || function(s){ try { return document.querySelector(s); } catch(e) { return null; } });
            var el = __f('${jsQuote(selector)}');
            if (!el) return JSON.stringify({error: 'Element not found: ${jsQuote(selector)}' + (window.__MQhint ? window.__MQhint(${JSONObject.quote(selector)}) : '')});
            el.dispatchEvent(new MouseEvent('mouseenter', {bubbles: true}));
            el.dispatchEvent(new MouseEvent('mouseover', {bubbles: true}));
            return JSON.stringify({hovered: true, tag: el.tagName, text: (el.innerText || '').substring(0, 100)});
        })()
    """.trimIndent(), requiresBody = false)

    // -- Find Elements --

    fun findElements(selector: String): String = guardedRead("""
        (function() {
            var __fa = (window.__MQA || function(s, n){ try { return Array.prototype.slice.call(document.querySelectorAll(s)).slice(0, n || 20); } catch(e) { return []; } });
            var els = __fa('${jsQuote(selector)}', 20);
            var scrollX = window.scrollX || window.pageXOffset || 0;
            var scrollY = window.scrollY || window.pageYOffset || 0;
            function shortSel(el) {
              try {
                if (el.id) return '#' + el.id;
                var path = [];
                var cur = el;
                while (cur && cur !== document.body && cur !== document.documentElement && path.length < 4) {
                  if (cur.id) { path.unshift('#' + cur.id); break; }
                  var seg = cur.tagName.toLowerCase();
                  var parent = cur.parentElement;
                  if (parent) {
                    var sameTag = 0, idx = 0;
                    for (var i = 0; i < parent.children.length; i++) {
                      if (parent.children[i].tagName === cur.tagName) { sameTag++; if (parent.children[i] === cur) idx = sameTag; }
                    }
                    if (sameTag > 1) seg += ':nth-of-type(' + idx + ')';
                  }
                  path.unshift(seg);
                  cur = parent;
                }
                return path.slice(-3).join(' > ');
              } catch (e) { return null; }
            }
            function roleOf(el) {
              var t = el.tagName;
              var ty = String((el.getAttribute && el.getAttribute('type')) || '').toLowerCase();
              if (t === 'BUTTON') return 'button';
              if (t === 'A') return (el.getAttribute && el.getAttribute('href')) ? 'link' : null;
              if (t === 'SELECT') return 'combobox';
              if (t === 'TEXTAREA') return 'textbox';
              if (t === 'INPUT') {
                if (ty === 'submit' || ty === 'button' || ty === 'reset') return 'button';
                if (ty === 'checkbox') return 'checkbox';
                if (ty === 'radio') return 'radio';
                if (ty === 'search') return 'searchbox';
                return 'textbox';
              }
              if (t === 'IMG') return 'img';
              return null;
            }
            var results = [];
            for (var i = 0; i < els.length && results.length < 20; i++) {
                var el = els[i];
                var rect = el.getBoundingClientRect();
                var role = '';
                try { role = String(el.getAttribute('role') || '').toLowerCase(); } catch (e) {}
                if (!role) role = roleOf(el) || '';
                var aria = '';
                try { aria = String(el.getAttribute('aria-label') || ''); } catch (e) {}
                var ph = '';
                try { ph = String(el.getAttribute('placeholder') || ''); } catch (e) {}
                var val = null;
                try {
                  if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT') {
                    val = (String(el.type || '').toLowerCase() === 'password') ? '[redacted]' : String(el.value || '').substring(0, 60);
                  }
                } catch (e) {}
                results.push({
                    index: i, tag: el.tagName, id: el.id || null,
                    role: role || null,
                    ariaLabel: aria || null,
                    placeholder: ph || null,
                    value: val,
                    className: (typeof el.className === 'string' ? el.className : '') || null,
                    text: (el.innerText || '').substring(0, 80) || null,
                    href: (el.tagName === 'A' && el.href) ? String(el.href).substring(0, 120) : null,
                    suggestedSelector: shortSel(el),
                    rect: {x: Math.round(rect.x), y: Math.round(rect.y), width: Math.round(rect.width), height: Math.round(rect.height), pageX: Math.round(rect.x + scrollX), pageY: Math.round(rect.y + scrollY), visible: rect.width > 0 && rect.height > 0 && rect.top < window.innerHeight && rect.bottom > 0}
                });
            }
            var out = {count: els.length, shown: results.length, elements: results};
            if (els.length === 0) {
              out.hint = "0 matches. Selector accepts CSS, or text=<visible label>, aria-label=<label>, role=button[name=<label>] (searches Shadow DOM + same-origin iframes).";
            }
            return JSON.stringify(out);
        })()
    """.trimIndent(), requiresBody = false)

    // -- Get Page Info --

    // [T-android-js-error-detail] Must work on a page without a body: the
    // no-body error of the other read actions points the model here.
    fun getPageInfo(): String = guardedRead("""
        (function() {
            return JSON.stringify({
                url: window.location.href,
                title: document.title,
                scrollY: window.scrollY,
                scrollHeight: document.body ? document.body.scrollHeight : (document.documentElement ? document.documentElement.scrollHeight : 0),
                viewportWidth: window.innerWidth,
                viewportHeight: window.innerHeight,
                readyState: document.readyState,
                forms: document.forms.length,
                links: document.links.length,
                images: document.images.length
            });
        })()
    """.trimIndent(), requiresBody = false)

    // -- Get Backbone --

    fun getBackbone(maxDepth: Int): String = guardedRead("""
        (function() {
            var MAX_DEPTH = $maxDepth;
            var MERGE_THRESHOLD = 10;
            var MIN_SIZE = 8;
            function isVisible(el) {
                if (!el.getBoundingClientRect) return false;
                var st = window.getComputedStyle(el);
                if (st.display === 'none' || st.visibility === 'hidden' || st.opacity === '0') return false;
                var r = el.getBoundingClientRect();
                return r.width >= MIN_SIZE && r.height >= MIN_SIZE;
            }
            var SEMANTIC_TAGS = new Set(['IMG','SVG','VIDEO','CANVAS','INPUT','TEXTAREA','BUTTON','A','SELECT','IFRAME']);
            var SKIP_TAGS = new Set(['SCRIPT','STYLE','NOSCRIPT','BR','HR','META','LINK','TEMPLATE']);
            function isLeaf(el) {
                if (SKIP_TAGS.has(el.tagName)) return false;
                if (SEMANTIC_TAGS.has(el.tagName)) return true;
                var children = el.children;
                for (var i = 0; i < children.length; i++) {
                    if (!SKIP_TAGS.has(children[i].tagName) && isVisible(children[i])) return false;
                }
                return true;
            }
            function rectsClose(a, b) {
                return Math.abs(a.left - b.left) <= MERGE_THRESHOLD && Math.abs(a.top - b.top) <= MERGE_THRESHOLD &&
                       Math.abs(a.right - b.right) <= MERGE_THRESHOLD && Math.abs(a.bottom - b.bottom) <= MERGE_THRESHOLD;
            }
            function shortSelector(el) {
                if (el.id) return '#' + el.id;
                var path = [];
                var cur = el;
                while (cur && cur !== document.body && cur !== document.documentElement) {
                    if (cur.id) { path.unshift('#' + cur.id); break; }
                    var seg = cur.tagName.toLowerCase();
                    var parent = cur.parentElement;
                    if (parent) {
                        var siblings = parent.children;
                        var sameTag = 0, idx = 0;
                        for (var i = 0; i < siblings.length; i++) {
                            if (siblings[i].tagName === cur.tagName) { sameTag++; if (siblings[i] === cur) idx = sameTag; }
                        }
                        if (sameTag > 1) seg += ':nth-of-type(' + idx + ')';
                    }
                    path.unshift(seg);
                    cur = parent;
                }
                if (path.length > 3 && path[0].charAt(0) === '#') path = [path[0]].concat(path.slice(-2));
                else if (path.length > 2) path = path.slice(-2);
                return path.join(' > ');
            }
            function getText(el) { return ((el.innerText || el.textContent || '').trim()).substring(0, 120); }
            function hasMeaningfulContent(el, rect) {
                if (rect.width < 12 || rect.height < 12) return false;
                if (getText(el).length > 1) return true;
                if (['INPUT','TEXTAREA','SELECT','BUTTON'].indexOf(el.tagName) >= 0) return true;
                if (el.tagName === 'IMG' && rect.width >= 24 && rect.height >= 24) return true;
                if (['VIDEO','CANVAS','IFRAME'].indexOf(el.tagName) >= 0) return true;
                if (el.tagName === 'A' && el.href && el.href.indexOf('javascript:') !== 0) return true;
                return false;
            }
            var allEls = document.body.querySelectorAll('*');
            var leaves = [];
            for (var i = 0; i < allEls.length; i++) {
                var el = allEls[i];
                if (isVisible(el) && isLeaf(el)) {
                    var r = el.getBoundingClientRect();
                    if (hasMeaningfulContent(el, r)) leaves.push(el);
                }
            }
            var seen = new Set();
            var representatives = [];
            var mergedCount = 0;
            for (var i = 0; i < leaves.length; i++) {
                var rep = leaves[i];
                var cur = rep.parentElement;
                while (cur && cur !== document.body && cur !== document.documentElement) {
                    if (!isVisible(cur)) break;
                    if (rectsClose(rep.getBoundingClientRect(), cur.getBoundingClientRect())) { rep = cur; mergedCount++; }
                    else break;
                    cur = cur.parentElement;
                }
                if (!seen.has(rep)) { seen.add(rep); representatives.push(rep); }
            }
            var ancestorMap = new Map();
            for (var i = 0; i < representatives.length; i++) {
                var cur = representatives[i].parentElement;
                var depth = 0;
                while (cur && cur !== document.body && cur !== document.documentElement && depth < 6) {
                    if (!ancestorMap.has(cur)) ancestorMap.set(cur, 0);
                    ancestorMap.set(cur, ancestorMap.get(cur) + 1);
                    cur = cur.parentElement; depth++;
                }
            }
            var groupNodes = [];
            ancestorMap.forEach(function(count, el) {
                if (count >= 2 && !seen.has(el) && isVisible(el)) { seen.add(el); groupNodes.push(el); }
            });
            var allReps = representatives.concat(groupNodes);
            var nodes = allReps.map(function(el) {
                var r = el.getBoundingClientRect();
                var info = {el: el, tag: el.tagName, rect: {x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height)}, area: r.width * r.height, children: [], isGroup: groupNodes.indexOf(el) >= 0};
                if (el.id) info.id = el.id;
                var cn = el.className && typeof el.className === 'string' ? el.className.trim().split(/\s+/).slice(0, 2).join(' ') : '';
                if (cn) info.className = cn;
                info.selector = shortSelector(el);
                if (!info.isGroup) { var txt = getText(el); if (txt) info.text = txt; }
                if (el.tagName === 'IMG') { info.src = (el.src || '').substring(0, 120); info.imgSize = el.naturalWidth + 'x' + el.naturalHeight; }
                if (el.tagName === 'A' && el.href && el.href.indexOf('javascript:') !== 0) {
                    try { var u = new URL(el.href); info.href = u.pathname + (u.search ? u.search.substring(0, 40) : ''); } catch(e) { info.href = el.href.substring(0, 80); }
                }
                // [T-android-browser-password-redact] A password field's value is
                // redacted at capture, so the plaintext never enters the node tree
                // (and never reaches the model). Whether it is filled is still
                // reported: the agent needs that to drive a login form. iOS parity
                // (BrowserUseJavaScript.swift).
                if (['INPUT','TEXTAREA','SELECT'].indexOf(el.tagName) >= 0) { info.inputType = el.type || null; if (el.value) info.value = (el.type === 'password') ? '[redacted]' : el.value.substring(0, 60); if (el.placeholder) info.placeholder = el.placeholder.substring(0, 60); }
                var role = el.getAttribute('role'); if (role) info.role = role;
                return info;
            });
            nodes.sort(function(a, b) { return b.area - a.area; });
            function domContains(a, b) { return a.el.contains(b.el); }
            var roots = [];
            for (var i = 0; i < nodes.length; i++) {
                var node = nodes[i]; var bestParent = null; var bestArea = Infinity;
                for (var j = 0; j < i; j++) {
                    if (nodes[j].el !== node.el && domContains(nodes[j], node) && nodes[j].area < bestArea) { bestParent = nodes[j]; bestArea = nodes[j].area; }
                }
                if (bestParent) bestParent.children.push(node); else roots.push(node);
            }
            function prune(node) {
                for (var i = 0; i < node.children.length; i++) prune(node.children[i]);
                if (node.isGroup && node.children.length === 1 && !node.text) {
                    var child = node.children[0];
                    node.children = child.children;
                    if (child.text && !node.text) node.text = child.text;
                    if (child.href && !node.href) node.href = child.href;
                    if (child.src) { node.src = child.src; node.imgSize = child.imgSize; }
                    if (!node.id && child.id) node.id = child.id;
                    node.tag = child.tag; node.selector = child.selector; node.isGroup = false;
                }
                node.children = node.children.filter(function(c) { return !(c.isGroup && c.children.length === 0 && !c.text); });
            }
            for (var i = 0; i < roots.length; i++) prune(roots[i]);
            roots = roots.filter(function(r) { return !(r.isGroup && r.children.length === 0 && !r.text); });
            function trimDepth(node, depth) { if (depth >= MAX_DEPTH) { node.children = []; return; } for (var i = 0; i < node.children.length; i++) trimDepth(node.children[i], depth + 1); }
            for (var i = 0; i < roots.length; i++) trimDepth(roots[i], 1);
            var totalNodes = 0, maxD = 0;
            var sX = window.scrollX || window.pageXOffset || 0;
            var sY = window.scrollY || window.pageYOffset || 0;
            function ser(node, depth) {
                totalNodes++; if (depth > maxD) maxD = depth;
                var o = {tag: node.tag};
                if (node.id) o.id = node.id;
                if (node.className) o.cls = node.className;
                o.sel = node.selector;
                if (node.role) o.role = node.role;
                if (node.text) o.text = node.text;
                if (node.href) o.href = node.href;
                if (node.src) o.img = node.imgSize + ' ' + node.src;
                if (node.inputType !== undefined) o.input = node.inputType + (node.value ? ' val=' + node.value : '') + (node.placeholder ? ' ph=' + node.placeholder : '');
                o.rect = node.rect.x + ',' + node.rect.y + ' ' + node.rect.w + 'x' + node.rect.h;
                o.pageXY = (node.rect.x + sX) + ',' + (node.rect.y + sY);
                if (node.children.length > 0) o.children = node.children.map(function(c) { return ser(c, depth + 1); });
                return o;
            }
            var tree = roots.map(function(r) { return ser(r, 1); });
            return JSON.stringify({backbone: tree, nodeCount: totalNodes, depth: maxD, merged: mergedCount});
        })()
    """.trimIndent())

    /** Focus the element matching [selector] (smart selectors understood). */
    fun focusElement(selector: String): String = """
        (function(){
          var __f = (window.__MQF || function(s){ try { return document.querySelector(s); } catch(e) { return null; } });
          var el = __f('${jsQuote(selector)}');
          if (!el) return JSON.stringify({error: 'Element not found: ${jsQuote(selector)}'});
          try { el.scrollIntoView({block: 'center'}); } catch (e) {}
          try { el.focus(); } catch (e) {}
          var ae = document.activeElement;
          return JSON.stringify({focused: true, tag: el.tagName, activeTag: ae ? ae.tagName : null});
        })()
    """.trimIndent()

    /** [T-android-browser-qr] Locate QR-plausible elements (square-ish, >=96px). */
    fun qrCandidates(): String = """
        (function(){
          var out = [];
          var els = document.querySelectorAll('img,canvas,svg,video');
          for (var i = 0; i < els.length; i++) {
            var el = els[i];
            var r = el.getBoundingClientRect();
            if (r.width < 96 || r.height < 96) continue;
            var ratio = r.width / r.height;
            if (ratio < 0.6 || ratio > 1.7) continue;
            var st = window.getComputedStyle(el);
            if (st.display === 'none' || st.visibility === 'hidden') continue;
            var src = '';
            try { src = String(el.currentSrc || el.src || '').substring(0, 200); } catch (e) {}
            var alt = '';
            try { alt = String(el.getAttribute('alt') || ''); } catch (e) {}
            out.push({tag: el.tagName, x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height), src: src, alt: alt});
          }
          out.sort(function(a, b){ return (b.w * b.h) - (a.w * a.h); });
          out = out.slice(0, 6);
          return JSON.stringify({count: out.length, candidates: out});
        })()
    """.trimIndent()

    /** [T-android-browser-network-idle] Page-side network/activity probe. */
    fun netIdleProbe(): String = """
        (function(){
          var st = window.__minis_net_state || null;
          var textLen = 0; var hasMedia = false;
          try { textLen = document.body ? String(document.body.innerText || '').length : 0; } catch (e) {}
          try { hasMedia = (document.images && document.images.length > 0) || !!document.querySelector('canvas,video,svg'); } catch (e) {}
          return JSON.stringify({
            pending: st ? st.pending : -1,
            lastAgo: st ? (Date.now() - st.last) : -1,
            readyState: document.readyState,
            textLen: textLen,
            hasMedia: hasMedia
          });
        })()
    """.trimIndent()

    /**
     * [T-android-net-capacity-2026-10] Page-side network-log query with paging
     * and optional full bodies. Newest-first paging: offset=0 starts at the
     * most recent matching entry.
     */
    fun networkLogQuery(filter: String?, offset: Int, limit: Int, full: Boolean): String = """
        (function(){
          var ring = window.__minis_net__ || [];
          var f = ${JSONObject.quote(filter?.trim()?.lowercase() ?: "")};
          var items = [];
          for (var i = 0; i < ring.length; i++) {
            var e = ring[i];
            if (f && String(e.u).toLowerCase().indexOf(f) < 0) continue;
            items.push(e);
          }
          var total = items.length;
          var off = ${offset.coerceAtLeast(0)};
          var lim = ${limit.coerceIn(1, 100)};
          var end = total - off; if (end < 0) end = 0;
          var start = end - lim; if (start < 0) start = 0;
          var fullMap = window.__minis_net_full || {};
          var useFull = ${if (full) "true" else "false"};
          var out = [];
          for (var j = start; j < end; j++) {
            var e2 = items[j];
            var body = e2.b || '';
            if (useFull && e2.id != null && fullMap[e2.id] != null) {
              body = String(fullMap[e2.id]);
              if (body.length > 20000) body = body.slice(0, 20000) + '...[' + body.length + ' chars total]';
            }
            out.push({id:e2.id, t:e2.t, tf:Date.now() - e2.t, m:e2.m, u:e2.u, s:e2.s, ms:e2.ms, b:body, bl:e2.bl || 0, tb:!!e2.tb, qh:e2.qh || null, qb:e2.qb || null, rh:e2.rh || null});
          }
          return JSON.stringify({total: total, offset: off, limit: lim, full: useFull, entries: out});
        })()
    """.trimIndent()

    /**
     * [T-android-request-replay-2026-10] Re-send a captured fetch/XHR request
     * from the page context (carries the page's cookies; no CORS guessing).
     * Async IIFE — call through BrowserUseManager.awaitPromiseJs.
     */
    fun replayRequest(index: Int?, filter: String?, method: String?, body: String?, headersJson: String?): String = """
        (async function(){
          var ring = window.__minis_net__ || [];
          var f = ${JSONObject.quote(filter?.trim()?.lowercase() ?: "")};
          var cands = [];
          for (var i = 0; i < ring.length; i++) {
            var e = ring[i];
            if (f && String(e.u).toLowerCase().indexOf(f) < 0) continue;
            cands.push(e);
          }
          if (!cands.length) return {error: 'no captured fetch/XHR request matches' + (f ? (' filter=' + f) : ' (ring: ' + ring.length + ' entries)') + '. Note: only in-page fetch/XHR is captured — the main document navigation is not (it has no request body to replay); navigate first if the request you want was fired by the page before capture.'};
          var idx = ${(index ?: 1).coerceAtLeast(1)};
          var e2 = cands[cands.length - idx];
          if (!e2) return {error: 'index ' + idx + ' is out of range (' + cands.length + ' matching requests)'};
          var m = ${JSONObject.quote(method?.trim()?.uppercase() ?: "")} || e2.m || 'GET';
          var hdrs = {};
          if (e2.qh) { for (var k in e2.qh) { var lk = String(k).toLowerCase(); if (lk !== 'cookie' && lk !== 'host' && lk !== 'content-length') hdrs[k] = e2.qh[k]; } }
          var extra = ${JSONObject.quote(headersJson?.trim() ?: "")};
          if (extra) { try { var o = JSON.parse(extra); for (var k2 in o) { hdrs[k2] = String(o[k2]); } } catch (_) {} }
          var overrideBody = ${JSONObject.quote(body ?: "")};
          var payload = null;
          if (overrideBody) payload = overrideBody;
          else if (e2.qb != null) { var qbStr = String(e2.qb); if (qbStr.length > 0 && qbStr.charAt(0) !== '[') payload = qbStr; }
          if (payload != null && (m === 'GET' || m === 'HEAD')) payload = null;
          var opts = {method: m, headers: hdrs, credentials: 'include'};
          if (payload != null) opts.body = payload;
          var t0 = Date.now();
          try {
            var r = await fetch(e2.u, opts);
            var txt = '';
            try { txt = await r.text(); } catch (_) {}
            return {replayed: true, id: e2.id, url: e2.u, method: m, status: r.status, ms: Date.now() - t0, size: txt.length, body: txt.length > 4000 ? txt.slice(0, 4000) : txt, truncated: txt.length > 4000};
          } catch (err) {
            return {replayed: false, url: e2.u, method: m, error: String((err && err.message) || err)};
          }
        })()
    """.trimIndent()

    /**
     * [T-android-page-diff-2026-10] Visible-text + structure fingerprint for
     * before/after comparisons. Lines are whitespace-collapsed and empty lines
     * dropped so a diff is stable across reflows; capped at 30k chars.
     */
    fun pageFingerprint(): String = """
        (function(){
          var out = [];
          try {
            var body = document.body;
            var raw = body ? String(body.innerText || body.textContent || '') : '';
            var lines = raw.split('\n');
            for (var i = 0; i < lines.length; i++) {
              var l = lines[i].replace(/\s+/g, ' ').trim();
              if (l) out.push(l);
            }
          } catch (e) {}
          var text = out.join('\n');
          if (text.length > 30000) text = text.slice(0, 30000);
          var a = 0; var b = 0; var im = 0;
          try { a = document.querySelectorAll('a').length; } catch (e) {}
          try { b = document.querySelectorAll('button,input[type=submit]').length; } catch (e) {}
          try { im = document.images.length; } catch (e) {}
          var h = 0;
          for (var k = 0; k < text.length; k += 7) { h = (h * 31 + text.charCodeAt(k)) | 0; }
          return JSON.stringify({len: text.length, h: h, a: a, b: b, i: im, text: text});
        })()
    """.trimIndent()

    // -- Fetch (async IIFE) --

    fun fetch(url: String): String {
        val escaped = url.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """
            (async function() {
                try {
                    const resp = await fetch("$escaped");
                    const buf = await resp.arrayBuffer();
                    const bytes = new Uint8Array(buf);
                    let binary = '';
                    for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
                    const b64 = btoa(binary);
                    return JSON.stringify({
                        base64: b64,
                        contentType: resp.headers.get('content-type') || '',
                        contentDisposition: resp.headers.get('content-disposition') || '',
                        status: resp.status,
                        url: resp.url,
                        size: bytes.length
                    });
                } catch(e) {
                    return JSON.stringify({error: e.message});
                }
            })()
        """.trimIndent()
    }

    /**
     * [T-android-selection-cleanup-2026-10] Collapse any active text
     * selection and drop selection handles. Idempotent, cheap, safe to run
     * after every action. Also blurs an actively focused contenteditable is
     * NOT done here — typing flows need focus; this only clears selection.
     */
    val CLEAR_SELECTION_JS: String = """
        (function() {
            try {
                var sel = window.getSelection();
                if (sel) {
                    if (sel.rangeCount > 0 && sel.isCollapsed === false) sel.removeAllRanges();
                    if (typeof sel.collapseToEnd === 'function') sel.collapseToEnd();
                }
                if (document.activeElement &&
                    document.activeElement !== document.body &&
                    document.activeElement.tagName !== 'INPUT' &&
                    document.activeElement.tagName !== 'TEXTAREA' &&
                    document.activeElement.isContentEditable) {
                    document.activeElement.blur();
                }
                return 'ok';
            } catch (e) { return 'err'; }
        })()
    """.trimIndent()
}

// BEGIN BrowserJSResult
/**
 * [T-android-js-error-detail] One reading of a `WebView.evaluateJavascript`
 * result for the action scripts in [BrowserUseJS], which always return a
 * JSON string. Android hands the callback a bare `null` both when the script
 * evaluated to null/undefined and when it THREW - the exception never
 * reaches Kotlin - so for these scripts `null` means "failed, reason
 * unknown". It used to be reported as the success text "null" (get_text,
 * get_readable, get_page_info, scroll) or as org.json's misleading
 * "Value null ... cannot be converted to JSONObject" (get_backbone).
 *
 * Only for scripts that must return. execute_js does not come through here:
 * a user script may legitimately return null, and its value arrives over the
 * `__minis__` bridge, not this callback.
 */
object BrowserJSResult {

    /** The tool-facing error for a must-return script that produced nothing. */
    const val NO_RESULT_ERROR =
        "JavaScript error: the page script stopped without returning a result. " +
            "Android WebView does not report the exception, so the reason is unknown - " +
            "often the page was mid-navigation or is not an HTML page. Call get_page_info, then retry."

    /**
     * Decodes the raw callback value: a JSON string literal is unquoted, any
     * other JSON value (number, object) is returned as written, and JS
     * null/undefined - or a thrown exception - is `null`.
     */
    fun decode(callbackValue: String?): String? {
        if (callbackValue == null || callbackValue == "null") return null
        if (callbackValue.length >= 2 && callbackValue.startsWith("\"") && callbackValue.endsWith("\"")) {
            return try {
                org.json.JSONObject("{\"v\":$callbackValue}").getString("v")
            } catch (_: Exception) {
                callbackValue
            }
        }
        return callbackValue
    }

    sealed interface Outcome {
        /** The script produced nothing: it threw, or did not parse. */
        data object NoResult : Outcome
        /** The script returned `{error: …}`. */
        data class ScriptError(val message: String) : Outcome
        data class Json(val json: org.json.JSONObject) : Outcome
        /** Not a JSON object; passed through as text, as before. */
        data class Text(val text: String) : Outcome
    }

    fun classify(decoded: String?): Outcome {
        if (decoded == null) return Outcome.NoResult
        val json = try { org.json.JSONObject(decoded) } catch (_: Exception) { null }
            ?: return Outcome.Text(decoded)
        return if (json.has("error")) Outcome.ScriptError(json.getString("error")) else Outcome.Json(json)
    }

    /**
     * execute_js's error when its wrapped script never ran. [detail] is the
     * [BrowserUseJS.syntaxCheck] result:
     * - "SyntaxError: …" → shown as is;
     * - '' (it compiles on its own) → not a plain syntax error: the page
     *   navigated away during injection, or the script breaks out of the
     *   async wrapper (e.g. a stray `})`);
     * - anything else (CSP refused the check with an EvalError, or the check
     *   itself failed) → the generic text.
     */
    fun syntaxErrorMessage(detail: String?): String = when {
        detail != null && detail.startsWith("SyntaxError") ->
            "JavaScript error: the script does not parse, so it never ran - $detail"
        detail == "" ->
            "JavaScript error: the script did not run. It compiles on its own, so either the page navigated " +
                "away while it was injected, or the script breaks out of execute_js's async wrapper " +
                "(e.g. a stray `})`). Retry, or simplify the script."
        else ->
            "JavaScript error: the script does not parse, so it never ran (a syntax error; this page " +
                "does not report the details). Check for unbalanced braces, parentheses or quotes."
    }
}
// END BrowserJSResult
