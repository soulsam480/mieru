package cx.miruro.app.data.embed

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** A playlist the embed page asked for, plus the headers it sent to get it. */
data class ResolvedEmbed(val url: String, val headers: Map<String, String>)

/**
 * Turns an embed page into a playable playlist by loading it in an offscreen WebView and
 * watching its traffic, so tokenised POSTs, cookies and CDN redirects happen exactly as the site
 * intends. The WebView never renders: it is created for one resolve, then destroyed.
 */
class EmbedResolver(private val context: Context) {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolve(embedUrl: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ResolvedEmbed? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                var finished = false

                val web =
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString = DESKTOP_USER_AGENT
                    }

                fun finish(result: ResolvedEmbed?) {
                    if (finished) return
                    finished = true
                    runCatching {
                        web.stopLoading()
                        web.destroy()
                    }
                    if (continuation.isActive) continuation.resumeWith(Result.success(result))
                }

                web.webViewClient =
                    object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            val url = request.url.toString()
                            if (url.contains(".m3u8", ignoreCase = true) ||
                                url.contains(".mp4", ignoreCase = true)
                            ) {
                                finish(ResolvedEmbed(url = url, headers = headersFor(request, url)))
                            }
                            return null
                        }
                    }

                Handler(Looper.getMainLooper()).postDelayed({ finish(null) }, timeoutMs)
                continuation.invokeOnCancellation { finish(null) }
                web.loadUrl(embedUrl)
            }
        }

    /**
     * Only the headers a CDN actually checks survive: the rest are the WebView's own noise. The
     * cookie jar is read separately so ExoPlayer gets the session the page earned.
     */
    private fun headersFor(request: WebResourceRequest, url: String): Map<String, String> =
        buildMap {
            request.requestHeaders.forEach { (name, value) ->
                when (name.lowercase()) {
                    "referer",
                    "origin",
                    "user-agent" -> put(name, value)
                }
            }
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                put("Cookie", it)
            }
        }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/156.0.0.0 Mobile Safari/537.36"
    }
}
