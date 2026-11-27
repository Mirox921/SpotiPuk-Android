package com.project.puk.core

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * Installed on the login WebView. A document-start script wraps fetch/XHR so
 * every call the web player makes is visible: bearer + client-token headers
 * go to Auth, pathfinder operation names + sha256 hashes + variables go to
 * Pathfinder. One login harvest keeps the native client working afterwards.
 */
object LoginTap {

    private const val BRIDGE = "PukTap"
    private val ORIGINS = setOf("https://open.spotify.com", "https://accounts.spotify.com")

    fun install(view: WebView, context: Context) {
        view.addJavascriptInterface(Bridge(context.applicationContext), BRIDGE)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Log.w("Tap", "DOCUMENT_START_SCRIPT unsupported, harvesting degraded")
            return
        }
        runCatching {
            WebViewCompat.addDocumentStartJavaScript(view, tapScript(), ORIGINS)
            Log.i("Tap", "document-start tap installed")
        }.onFailure { Log.e("Tap", "tap install failed", it) }
    }

    class Bridge(private val appContext: Context) {
        private val generation = Auth.generation

        @JavascriptInterface
        fun creds(json: String) = synchronized(Auth) {
            if (generation != Auth.generation) return@synchronized
            val obj = parse(json) ?: return@synchronized
            val bearer = obj.optString("auth").removePrefix("Bearer ").removePrefix("bearer ").trim()
            val clientToken = obj.optString("ct")
            if (bearer.isNotBlank() && bearer != Auth.token(appContext)) {
                Log.i("Tap", "bearer captured (${bearer.length}B)")
                Auth.saveToken(appContext, bearer)
            }
            if (clientToken.isNotBlank() && clientToken != Auth.clientToken(appContext)) {
                Log.i("Tap", "client-token captured (${clientToken.length}B)")
                Auth.saveClientToken(appContext, clientToken, 0L)
            }
        }

        @JavascriptInterface
        fun op(json: String) {
            val obj = parse(json) ?: return
            val name = obj.optString("op")
            val hash = obj.optString("hash")
            val vars = obj.optString("vars")
            if (name.isNotBlank() && hash.isNotBlank()) {
                Pathfinder.record(appContext, name, hash, vars)
            }
        }
    }

    private fun parse(json: String): JSONObject? =
        runCatching { JSONObject(json) }.getOrNull()

    private fun tapScript(): String = """
        (function(){
            if (window.__pukTap) return;
            window.__pukTap = 1;
            var bridge = window.$BRIDGE;
            if (!bridge) return;

            function toBridge(kind, payload) {
                try { bridge[kind](JSON.stringify(payload)); } catch (e) {}
            }

            function normHeaders(h) {
                var out = {};
                if (!h) return out;
                try {
                    if (typeof h.forEach === 'function' && typeof h.get === 'function') {
                        h.forEach(function(v, k){ out[String(k).toLowerCase()] = String(v); });
                    } else if (Array.isArray(h)) {
                        h.forEach(function(p){ if (p && p.length === 2) out[String(p[0]).toLowerCase()] = String(p[1]); });
                    } else {
                        Object.keys(h).forEach(function(k){ out[String(k).toLowerCase()] = String(h[k]); });
                    }
                } catch (e) {}
                return out;
            }

            function credsFrom(url, h) {
                if (!h) return;
                var auth = h['authorization'] || '';
                var ct = h['client-token'] || '';
                if (!auth && !ct) return;
                toBridge('creds', { url: url, auth: auth, ct: ct });
            }

            function pfFrom(url, body) {
                if (url.indexOf('/pathfinder') < 0) return null;
                var out = { op: '', hash: '', vars: '' };
                try {
                    var q = new URL(url, location.href).searchParams;
                    out.op = q.get('operationName') || '';
                    out.vars = q.get('variables') || '';
                    var ext = q.get('extensions');
                    if (ext) {
                        var j = JSON.parse(ext);
                        out.hash = (j.persistedQuery && j.persistedQuery.sha256Hash) || '';
                    }
                } catch (e) {}
                if (body) {
                    try {
                        var b = JSON.parse(body);
                        out.op = out.op || b.operationName || '';
                        out.vars = out.vars || (b.variables ? JSON.stringify(b.variables) : '');
                        out.hash = out.hash ||
                            (b.extensions && b.extensions.persistedQuery && b.extensions.persistedQuery.sha256Hash) || '';
                    } catch (e) {}
                }
                if (!out.op) return null;
                out.vars = (out.vars || '').substring(0, 600);
                return out;
            }

            var origFetch = window.fetch;
            if (origFetch) {
                window.fetch = function(input, init) {
                    var url = '';
                    try {
                        url = (typeof input === 'string') ? input : ((input && input.url) || '');
                        url = new URL(url, location.href).href;
                        credsFrom(url, normHeaders(init && init.headers));
                        if (input && typeof input === 'object' && input.headers) {
                            credsFrom(url, normHeaders(input.headers));
                        }
                    } catch (e) {}
                    var body = (init && typeof init.body === 'string') ? init.body : '';
                    var pf = pfFrom(url, body);
                    if (pf) toBridge('op', pf);
                    return origFetch.apply(this, arguments);
                };
            }

            var xp = XMLHttpRequest.prototype;
            var origOpen = xp.open, origSend = xp.send, origHeader = xp.setRequestHeader;
            xp.open = function(method, url) {
                try { this.__puk = { url: new URL(url, location.href).href, body: '', headers: {} }; } catch (e) {}
                return origOpen.apply(this, arguments);
            };
            xp.setRequestHeader = function(k, v) {
                try { if (this.__puk) this.__puk.headers[String(k).toLowerCase()] = String(v); } catch (e) {}
                return origHeader.apply(this, arguments);
            };
            xp.send = function(body) {
                var info = this.__puk;
                if (info) {
                    if (typeof body === 'string') info.body = body;
                    try {
                        credsFrom(info.url, info.headers);
                        var pf = pfFrom(info.url, info.body);
                        if (pf) toBridge('op', pf);
                    } catch (e) {}
                }
                return origSend.apply(this, arguments);
            };
        })();
    """.trimIndent().replace("$BRIDGE", BRIDGE)
}
