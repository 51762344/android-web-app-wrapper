package com.example.webwrapper

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.example.webwrapper.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val cookieManager: CookieManager = CookieManager.getInstance()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var loadTimeoutRunnable: Runnable? = null
    private var hasMainFrameLoadError = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureUi()
        configureSystemInsets()
        configureWebView()

        onBackPressedDispatcher.addCallback(this) {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            } else {
                finish()
            }
        }

        val restoredState = savedInstanceState?.getBundle(WEB_VIEW_STATE_KEY)
        if (restoredState != null && binding.webView.restoreState(restoredState) != null) {
            hideErrorState()
        } else {
            loadBaseUrl()
        }
    }

    override fun onPause() {
        clearLoadTimeout()
        cookieManager.flush()
        binding.webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        if (isPageLoadInProgress()) {
            scheduleLoadTimeout()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val webViewBundle = Bundle()
        binding.webView.saveState(webViewBundle)
        outState.putBundle(WEB_VIEW_STATE_KEY, webViewBundle)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        clearLoadTimeout()
        cookieManager.flush()
        binding.webView.apply {
            stopLoading()
            webChromeClient = null
            destroy()
        }
        super.onDestroy()
    }

    private fun configureUi() {
        if (BuildConfig.ENABLE_PULL_TO_REFRESH) {
            val refreshTriggerPx = (REFRESH_TRIGGER_DP * resources.displayMetrics.density).toInt()

            binding.swipeRefresh.isEnabled = true
            binding.swipeRefresh.setDistanceToTriggerSync(refreshTriggerPx)
            binding.swipeRefresh.setSlingshotDistance(refreshTriggerPx)
            binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
                binding.webView.canScrollVertically(-1)
            }
            binding.swipeRefresh.setOnRefreshListener {
                reloadCurrentPageOrBaseUrl()
            }
        } else {
            binding.swipeRefresh.isEnabled = false
        }

        binding.retryButton.setOnClickListener {
            reloadCurrentPageOrBaseUrl()
        }
    }

    private fun configureSystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val statusBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navigationBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.swipeRefresh.updatePadding(top = statusBarInsets.top)
            binding.errorGroup.updatePadding(
                left = binding.errorGroup.paddingLeft,
                top = statusBarInsets.top,
                right = binding.errorGroup.paddingRight,
                bottom = navigationBarInsets.bottom,
            )
            binding.loadingIndicator.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
                topMargin = statusBarInsets.top
            }

            windowInsets
        }
    }

    private fun configureWebView() {
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            safeBrowsingEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.loadingIndicator.isVisible = newProgress in 0..99
                binding.loadingIndicator.setProgressCompat(newProgress, true)
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val uri = request.url
                return if (uri.scheme == "http" || uri.scheme == "https") {
                    false
                } else {
                    openExternalUri(uri)
                    true
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                hasMainFrameLoadError = false
                binding.loadingIndicator.isVisible = true
                hideErrorState()
                scheduleLoadTimeout()
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                super.onPageCommitVisible(view, url)
                clearLoadTimeout()
                hideErrorState()
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                clearLoadTimeout()
                binding.swipeRefresh.isRefreshing = false
                if (!hasMainFrameLoadError) {
                    binding.loadingIndicator.isVisible = false
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    hasMainFrameLoadError = true
                    showErrorState(
                        title = getString(R.string.error_title_network),
                        message = error.description?.toString()?.ifBlank {
                            getString(R.string.error_message_network)
                        } ?: getString(R.string.error_message_network),
                    )
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError,
            ) {
                handler.cancel()
                hasMainFrameLoadError = true
                showErrorState(
                    title = getString(R.string.error_title_ssl),
                    message = getString(R.string.error_message_ssl),
                )
            }
        }
    }

    private fun loadBaseUrl() {
        val configuredUrl = getConfiguredBaseUrl()

        if (!isHttpUrl(configuredUrl)) {
            showErrorState(
                title = getString(R.string.error_title_configuration),
                message = getString(R.string.error_message_configuration),
            )
            return
        }

        hideErrorState()
        binding.loadingIndicator.isVisible = true
        binding.webView.loadUrl(configuredUrl)
    }

    private fun reloadCurrentPageOrBaseUrl() {
        if (hasMainFrameLoadError || binding.webView.url.isNullOrBlank()) {
            loadBaseUrl()
        } else {
            binding.swipeRefresh.isRefreshing = true
            binding.webView.reload()
        }
    }

    private fun openExternalUri(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showErrorState(title: String, message: String) {
        clearLoadTimeout()
        binding.swipeRefresh.isRefreshing = false
        binding.loadingIndicator.isVisible = false
        binding.errorTitle.text = title
        binding.errorMessage.text = message
        binding.errorGroup.isVisible = true
    }

    private fun hideErrorState() {
        binding.errorGroup.isVisible = false
    }

    private fun scheduleLoadTimeout() {
        clearLoadTimeout()
        loadTimeoutRunnable = Runnable {
            hasMainFrameLoadError = true
            binding.webView.stopLoading()
            showErrorState(
                title = getString(R.string.error_title_timeout),
                message = getString(R.string.error_message_timeout),
            )
        }.also {
            mainHandler.postDelayed(it, LOAD_TIMEOUT_MS)
        }
    }

    private fun clearLoadTimeout() {
        loadTimeoutRunnable?.let(mainHandler::removeCallbacks)
        loadTimeoutRunnable = null
    }

    private fun isPageLoadInProgress(): Boolean {
        return binding.loadingIndicator.isVisible && !hasMainFrameLoadError
    }

    private fun getConfiguredBaseUrl(): String {
        return BuildConfig.DEFAULT_BASE_URL.trim()
    }

    private fun isHttpUrl(url: String): Boolean {
        val parsedUri = Uri.parse(url)
        return !parsedUri.scheme.isNullOrBlank() &&
            !parsedUri.host.isNullOrBlank() &&
            (parsedUri.scheme == "http" || parsedUri.scheme == "https")
    }

    companion object {
        private const val WEB_VIEW_STATE_KEY = "web_view_state"
        private const val LOAD_TIMEOUT_MS = 20_000L
        private const val REFRESH_TRIGGER_DP = 144
    }
}
