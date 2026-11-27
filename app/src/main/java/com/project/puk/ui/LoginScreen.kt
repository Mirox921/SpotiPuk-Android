package com.project.puk.ui

import com.project.puk.core.Lang

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.project.puk.core.Auth
import com.project.puk.core.LoginTap
import com.project.puk.core.Log
import kotlinx.coroutines.delay

private const val ACCOUNTS_ORIGIN = "https://accounts.spotify.com"

// Spotify can replace its absolutely positioned layout without reloading the WebView.
private val accountLayoutScript = """
    (() => {
        if (location.origin !== '$ACCOUNTS_ORIGIN') return;

        const fixLayout = () => {
            const main = document.querySelector('#__next > main');
            if (!main || getComputedStyle(main).position !== 'absolute') return;
            main.style.position = 'relative';
            main.style.inset = 'auto';
            main.style.minHeight = '100vh';
            main.style.overflow = 'visible';
        };

        const observeLayout = () => {
            fixLayout();
            new MutationObserver(fixLayout).observe(document.documentElement, {
                childList: true,
                subtree: true
            });
        };
        if (document.documentElement) observeLayout();
        else document.addEventListener('DOMContentLoaded', observeLayout, { once: true });
    })();
""".trimIndent()

/**
 * One-time login. The WebView loads Spotify's own pages with the harvest tap
 * installed; as soon as the sp_dc cookie exists we snapshot the session,
 * hand control to the native UI.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var status by remember { mutableStateOf(Lang.t(Lang.Key.LOGIN_SIGN_IN_SPOTIFY)) }

    var loadFailed by remember { mutableStateOf(false) }

    // Poll for the harvested session.
    LaunchedEffect(webView) {
        while (true) {
            val session = webView?.let { Auth.captureSession(context) }
            if (session != null) {
                Auth.saveSession(context, session)
                try {
                    val profile = com.project.puk.api.Spotify.profile(context)
                        ?: com.project.puk.api.Spotify.refreshProfile(context)
                    if (profile != null && profile.id.isNotBlank()) {
                        Auth.saveAccount(context, profile)
                        Auth.finishAddingAccount(context)
                        Log.i("Login", "Spotify session saved")
                        onLoggedIn()
                        break
                    }
                    status = Lang.t(Lang.Key.LOGIN_CHECKING_ACCOUNT)
                    Auth.invalidateToken(context)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { status = Lang.t(Lang.Key.LOGIN_CHECK_RETRYING) }
                delay(2_000L)
            }
            delay(700L)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (loadFailed) Lang.t(Lang.Key.LOGIN_PAGE_LOAD_FAILED) else status,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(16.dp),
        )
        if (loadFailed) {
            TextButton(onClick = { webView?.reload() }) { Text(Lang.t(Lang.Key.LIB_RETRY)) }
        }
        AndroidView(
            factory = { ctx ->
                Auth.applySession(ctx) // reuse a saved session if there is one
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        allowFileAccess = false
                        allowContentAccess = false
                        mediaPlaybackRequiresUserGesture = false
                        setGeolocationEnabled(false)
                    }
                    setBackgroundColor(0xFF121212.toInt())
                    LoginTap.install(this, ctx)
                    val supportsDocumentStart =
                        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                    if (supportsDocumentStart) {
                        WebViewCompat.addDocumentStartJavaScript(this, accountLayoutScript, setOf(ACCOUNTS_ORIGIN))
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            loadFailed = false
                            status = Lang.t(Lang.Key.LOGIN_LOADING_SPOTIFY)
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            Log.w("Login", "WebView error ${error.errorCode} on ${request.url.host}")
                            if (request.isForMainFrame) loadFailed = true
                        }

                        override fun onReceivedHttpError(
                            view: WebView,
                            request: WebResourceRequest,
                            response: WebResourceResponse,
                        ) {
                            Log.w("Login", "HTTP ${response.statusCode} on ${request.url.host}")
                            if (request.isForMainFrame) loadFailed = true
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            if (loadFailed) return
                            status = if (url?.let { Uri.parse(it).host } == "accounts.spotify.com") {
                                if (!supportsDocumentStart) {
                                    view.evaluateJavascript(accountLayoutScript, null)
                                }
                                Lang.t(Lang.Key.LOGIN_SIGN_IN_PROMPT)
                            } else {
                                Lang.t(Lang.Key.LOGIN_LOADING_SPOTIFY)
                            }
                        }
                    }
                    loadUrl("$ACCOUNTS_ORIGIN/login")
                    webView = this
                }
            },
            modifier = Modifier.weight(1f).fillMaxSize(),
            onRelease = { it.destroy() },
        )
    }
}
