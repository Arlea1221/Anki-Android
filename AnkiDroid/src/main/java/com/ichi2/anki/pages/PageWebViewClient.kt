/*
 *  Copyright (c) 2022 Brayan Oliveira <brayandso.dev@gmail.com>
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.anki.pages

import android.graphics.Bitmap
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import com.ichi2.anki.OnPageFinishedCallback
import com.ichi2.anki.workarounds.SafeWebViewClient
import com.ichi2.anki.workarounds.SafeWebViewLayout
import com.ichi2.utils.AssetHelper.guessMimeType
import com.ichi2.utils.toRGBHex
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Base WebViewClient to be used on [PageFragment]
 */
open class PageWebViewClient : SafeWebViewClient() {
    val onPageFinishedCallbacks: MutableList<OnPageFinishedCallback> = mutableListOf()

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val path = request.url.path
        if (request.method != "GET" || path == null) return null
        if (path == "/favicon.png") {
            return WebResourceResponse("image/x-icon", null, ByteArrayInputStream(byteArrayOf()))
        }

        val assetPath =
            if (path.startsWith("/_app/")) {
                "backend/sveltekit/app/${path.substring(6)}"
            } else if (isSvelteKitPage(path.substring(1))) {
                SVELTEKIT_INDEX_ASSET_PATH
            } else {
                return null
            }

        try {
            val response =
                if (assetPath == SVELTEKIT_INDEX_ASSET_PATH) {
                    val html =
                        view.context.assets
                            .open(assetPath)
                            .bufferedReader(Charsets.UTF_8)
                            .use { it.readText() }
                    val patchedHtml = injectLegacyWebViewPolyfills(html)
                    WebResourceResponse(
                        "text/html",
                        "utf-8",
                        ByteArrayInputStream(patchedHtml.toByteArray(Charsets.UTF_8)),
                    )
                } else {
                    val mimeType = guessMimeType(assetPath)
                    val inputStream = view.context.assets.open(assetPath)
                    WebResourceResponse(mimeType, null, inputStream)
                }
            if ("immutable" in path) {
                response.responseHeaders = mapOf("Cache-Control" to "max-age=31536000")
            }
            return response
        } catch (_: IOException) {
            Timber.w("Not found %s", assetPath)
        }
        return null
    }

    override fun onPageStarted(
        view: WebView?,
        url: String?,
        favicon: Bitmap?,
    ) {
        super.onPageStarted(view, url, favicon)
        view?.let { webView ->
            val bgColor = MaterialColors.getColor(webView, android.R.attr.colorBackground).toRGBHex()
            webView.evaluateAfterDOMContentLoaded(
                """document.body.style.setProperty("background-color", "$bgColor", "important");
                    console.log("Background color set");""",
            )
            if (url?.contains("#night") == true) {
                injectDarkModeFormFix(webView)
            }
        }
    }

    /**
     * Legacy WebViews don't support `color-scheme: dark`, so native form controls
     * can become unreadable in night mode without an explicit fallback style.
     */
    private fun injectDarkModeFormFix(webView: WebView) {
        webView.evaluateAfterDOMContentLoaded(
            """
            if (!document.getElementById('anki-dark-form-fix')) {
                var s = document.createElement('style');
                s.id = 'anki-dark-form-fix';
                s.textContent =
                    'input:not([type=checkbox]):not([type=radio]):not([type=range]),' +
                    'select, textarea, .form-control, .form-select {' +
                    '  background-color: #2b3035 !important;' +
                    '  color: #dee2e6 !important;' +
                    '  border-color: #495057 !important;' +
                    '}';
                document.head.appendChild(s);
            }
            """.trimIndent(),
        )
    }

    /**
     * Shows the WebView after the page is loaded
     *
     * This may be overridden if additional 'screen ready' logic is provided by the backend
     * @see DeckOptions
     */
    open fun onShowWebView(webView: WebView) {
        Timber.v("Displaying WebView")
        webView.isVisible = true
        (webView.parent as? SafeWebViewLayout)?.isVisible = true
    }

    override fun onPageFinished(
        view: WebView?,
        url: String?,
    ) {
        super.onPageFinished(view, url)
        if (view == null) return
        onPageFinishedCallbacks.map { callback -> callback.onPageFinished(view) }
        /* webView is invisible by default to avoid flashes while
         * the page is loaded, and can be made visible again after it finishes loading */
        onShowWebView(view)
    }
}

fun isSvelteKitPage(path: String): Boolean {
    val pageName = path.substringBefore("/")
    return when (pageName) {
        "graphs",
        "congrats",
        "card-info",
        "change-notetype",
        "deck-options",
        "import-anki-package",
        "import-csv",
        "import-page",
        "image-occlusion",
        -> true
        else -> false
    }
}

fun WebView.evaluateAfterDOMContentLoaded(
    script: String,
    resultCallback: ValueCallback<String>? = null,
) {
    evaluateJavascript(
        """
        var codeToRun = function() { 
            $script
        }
        
        if (document.readyState === "loading") {
          document.addEventListener("DOMContentLoaded", codeToRun);
        } else {
          codeToRun();
        }
        """.trimIndent(),
        resultCallback,
    )
}

private const val SVELTEKIT_INDEX_ASSET_PATH = "backend/sveltekit/index.html"

private val legacyWebViewPolyfillScript =
    """
    <script>
    (function() {
      if (!String.prototype.replaceAll) {
        String.prototype.replaceAll = function(searchValue, replaceValue) {
          if (searchValue instanceof RegExp) {
            if (!searchValue.global) {
              throw new TypeError("replaceAll called with a non-global RegExp argument");
            }
            return this.replace(searchValue, replaceValue);
          }
          var escaped = String(searchValue).replace(/[.*+?^${'$'}{}()|[\]\\]/g, "\\$&");
          return this.replace(new RegExp(escaped, "g"), replaceValue);
        };
      }
    })();
    </script>
    """.trimIndent()

fun injectLegacyWebViewPolyfills(indexHtml: String): String {
    if (indexHtml.contains("String.prototype.replaceAll")) {
        return indexHtml
    }
    val firstScriptTagIndex = indexHtml.indexOf("<script>")
    if (firstScriptTagIndex < 0) {
        return legacyWebViewPolyfillScript + "\n" + indexHtml
    }
    return buildString(indexHtml.length + legacyWebViewPolyfillScript.length + 1) {
        append(indexHtml, 0, firstScriptTagIndex)
        append(legacyWebViewPolyfillScript)
        append('\n')
        append(indexHtml.substring(firstScriptTagIndex))
    }
}
