package com.cinemora.app.core

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Generic Cloudflare / bot-wall bypass: loads [url] in a hidden WebView,
 * lets the JavaScript challenge run, then copies the resulting cookies into
 * [Http]'s cookie jar so normal requests start working. Used by providers
 * whose sites sit behind Cloudflare (AnimeWorldIndia, RareAnimesIndia).
 *
 * Runs on the main thread (WebView requirement). Returns true when the page
 * loaded and at least one cookie was captured.
 */
object CfBypass {
    private const val TIMEOUT_MS = 60_000L

    fun isChallengePage(body: String): Boolean {
        val b = body.lowercase()
        return b.contains("just a moment") ||
            b.contains("attention required") ||
            b.contains("cf-chl") ||
            b.contains("checking your browser") ||
            b.contains("cloudflare") && b.contains("challenge")
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolve(url: String, context: Context): Boolean {
        return withTimeoutOrNull(TIMEOUT_MS) {
            val done = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {
                try {
                    val webView = WebView(context.applicationContext)
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.settings.userAgentString = Http.USER_AGENT
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                    var finished = false
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url2: String) {
                            if (finished) return
                            // Give the challenge JS a moment to set cookies.
                            view.postDelayed({
                                if (finished) return@postDelayed
                                finished = true
                                val cookies =
                                    CookieManager.getInstance().getCookie(url2) ?: ""
                                var ok = false
                                if (cookies.isNotBlank()) {
                                    try {
                                        val host =
                                            java.net.URL(url2).host
                                        Http.addCookies(host, cookies)
                                        ok = true
                                    } catch (_: Exception) {
                                    }
                                }
                                runCatching { view.destroy() }
                                if (!done.isCompleted) done.complete(ok)
                            }, 4000)
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean = false
                    }
                    webView.loadUrl(url)
                } catch (_: Exception) {
                    if (!done.isCompleted) done.complete(false)
                }
            }
            done.await()
        } ?: false
    }
}
