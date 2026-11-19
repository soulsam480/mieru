package cx.miruro.app.data.embed

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
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
 * Turns an embed page into a playable playlist by loading it in a WebView that is attached to the
 * window but two pixels square, so the page behaves like a visible player without showing
 * anything. It watches the traffic for the first playlist request.
 */
class EmbedResolver {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolve(
        context: Context,
        embedUrl: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ResolvedEmbed? = withContext(Dispatchers.Main) {
        val activity = context.findActivity()
        Log.d(TAG, "resolve start host=${embedUrl.substringAfter("//").substringBefore("/")}")

        if (activity == null) {
            Log.w(TAG, "no activity: an unattached WebView never autoplays")
            return@withContext null
        }

        suspendCancellableCoroutine { continuation ->
            var finished = false
            val root = activity.window?.decorView as? ViewGroup

            val web =
                WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.userAgentString = USER_AGENT
                }

            fun finish(result: ResolvedEmbed?) {
                if (finished) return
                finished = true
                Log.d(
                    TAG,
                    if (result == null) {
                        "resolve timeout"
                    } else {
                        "resolve ok host=" +
                            result.url.substringAfter("//").substringBefore("/")
                    }
                )
                runCatching {
                    root?.removeView(web)
                    web.stopLoading()
                    web.destroy()
                }
                if (continuation.isActive) continuation.resumeWith(Result.success(result))
            }

            web.webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        Log.d(TAG, "page finished ${url?.take(80)}")
                        view?.evaluateJavascript(START_PLAYBACK_JS, null)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val url = request.url.toString()
                        if (isPlaylist(url)) {
                            finish(ResolvedEmbed(url = url, headers = headersFor(request, url)))
                        }
                        return null
                    }
                }

            // Two pixels on screen is the difference between a player that autoplays and one that
            // sits waiting for a gesture it will never get.
            runCatching { root?.addView(web, ViewGroup.LayoutParams(2, 2)) }
                .onFailure { Log.w(TAG, "attach failed: ${it.message}") }

            Handler(Looper.getMainLooper()).postDelayed({ finish(null) }, timeoutMs)
            continuation.invokeOnCancellation { finish(null) }
            web.loadUrl(embedUrl)
        }
    }

    private fun isPlaylist(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") ||
            lower.contains(".mp4") ||
            (lower.contains("master") && lower.contains("hls"))
    }

    /** Only headers a CDN checks survive; the cookie jar is read separately. */
    private fun headersFor(request: WebResourceRequest, url: String): Map<String, String> =
        buildMap {
            request.requestHeaders.forEach { (name, value) ->
                when (name.lowercase()) {
                    "referer", "origin", "user-agent" -> put(name, value)
                }
            }
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                put("Cookie", it)
            }
        }

    private companion object {
        const val TAG = "EmbedResolver"
        const val DEFAULT_TIMEOUT_MS = 12_000L
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/156.0.0.0 Mobile Safari/537.36"

        /** Mute first, then nudge the player: an unmuted offscreen page is heard but never seen. */
        const val START_PLAYBACK_JS =
            "(function(){" +
                "var v=document.querySelector('video');" +
                "if(v){v.muted=true;v.volume=0;if(v.play){v.play().catch(function(){})}}" +
                "var b=document.querySelector('[class*=play],[aria-label*=lay],button');" +
                "if(b){b.click()}" +
                "})()"
    }
}

private fun Context.findActivity(): Activity? {
    var candidate = this
    while (candidate is ContextWrapper) {
        if (candidate is Activity) return candidate
        candidate = candidate.baseContext
    }
    return null
}
