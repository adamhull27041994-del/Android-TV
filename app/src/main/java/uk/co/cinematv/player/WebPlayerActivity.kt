package uk.co.cinematv.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity

/** An opt-in fallback for the third-party VidLink HTML embed, not a native HLS stream. */
class WebPlayerActivity : ComponentActivity() {
    private var web: WebView? = null
    private lateinit var root: FrameLayout
    private var fullscreen: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host != "vidlink.pro" || !isValidPath(uri.path.orEmpty())) {
            Toast.makeText(this, "Unsupported web player URL", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        root = FrameLayout(this)
        val w = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                    // Don't allow the external site to navigate the top-level WebView
                    // to advertisements, unrelated websites or non-HTTPS schemes.
                    if (!request.isForMainFrame) return false
                    val next = request.url
                    return next.scheme != "https" || next.host != "vidlink.pro"
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (fullscreen != null) { callback.onCustomViewHidden(); return }
                    fullscreen = view
                    fullscreenCallback = callback
                    root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    this@WebPlayerActivity.web?.visibility = View.GONE
                }
                override fun onHideCustomView() {
                    fullscreen?.let { root.removeView(it) }
                    fullscreen = null
                    fullscreenCallback?.onCustomViewHidden()
                    fullscreenCallback = null
                    this@WebPlayerActivity.web?.visibility = View.VISIBLE
                }
            }
            loadUrl(url)
        }
        web = w
        root.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    override fun onDestroy() {
        if (!::root.isInitialized) { super.onDestroy(); return }
        fullscreen?.let { root.removeView(it) }
        web?.let {
            root.removeView(it)
            it.stopLoading()
            it.loadUrl("about:blank")
            it.destroy()
        }
        web = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "embed_url"
        private fun isValidPath(path: String): Boolean =
            Regex("^/(movie/[0-9]+|tv/[0-9]+/[0-9]+/[0-9]+)$").matches(path)

        fun launch(context: Context, url: String) {
            context.startActivity(Intent(context, WebPlayerActivity::class.java).putExtra(EXTRA_URL, url))
        }
    }
}
