package com.comic.xenvyx.id.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.comic.xenvyx.id.extension.ExtensionManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
private fun createReaderWebView(context: Context): WebView = WebView(context).apply {
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    settings.javaScriptEnabled = true // Required for the public site's reader UI.
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(false)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    webChromeClient = WebChromeClient()
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri: Uri = request.url
            // Browser-style navigation: allow HTTPS sites only. Local files and cleartext HTTP stay blocked.
            return uri.scheme != "https"
        }
        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            val pageUrl = url ?: return
            // Scripts are installed locally, start disabled, and run only for their declared HTTPS URL matches.
            val scripts = runCatching { ExtensionManager(view.context).scriptsFor(pageUrl) }.getOrDefault(emptyList())
            scripts.forEach { script ->
                val quoted = org.json.JSONObject.quote(script)
                view.evaluateJavascript("(function(){try{(new Function($quoted))();}catch(e){console.error('Komiku extension error',e);}})();", null)
            }
        }
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { super.onPageStarted(view, url, favicon) }
    }
}

@Composable
fun KomikuWebView(url: String, modifier: Modifier = Modifier, onCreated: (WebView) -> Unit = {}) {
    AndroidView(
        modifier = modifier,
        factory = { context -> createReaderWebView(context).also(onCreated) },
        update = { view -> if (view.url != url) view.loadUrl(url) }
    )
}
