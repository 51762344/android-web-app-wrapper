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
import android.webkit.ValueCallback
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
                injectPromoPopupBlocker(view)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                clearLoadTimeout()
                binding.swipeRefresh.isRefreshing = false
                injectPromoPopupBlocker(view)
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

    private fun injectPromoPopupBlocker(
        view: WebView,
        callback: ValueCallback<String>? = null,
    ) {
        if (!BuildConfig.ENABLE_PROMO_POPUP_BLOCKING) {
            callback?.onReceiveValue("\"disabled\"")
            return
        }

        view.evaluateJavascript(PROMO_POPUP_BLOCKER_SCRIPT, callback)
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
        private const val PROMO_POPUP_BLOCKER_SCRIPT = """
            (function() {
                if (!document || !document.documentElement) {
                    return "no-document";
                }

                var blocker = window.__webWrapperPromoBlocker;
                if (blocker && typeof blocker.scan === "function") {
                    blocker.scan();
                    return "rescanned";
                }

                var PROMO_TEXT_PATTERNS = [
                    /open[\s-]*in[\s-]*app/i,
                    /open[\s-]*app/i,
                    /download[\s-]*app/i,
                    /install[\s-]*app/i,
                    /use[\s-]*app/i,
                    /in[\s-]*the[\s-]*app/i,
                    /app[\s-]*exclusive/i,
                    /\u6253\u5f00\s*APP/i,
                    /\u6253\u5f00\s*\u5ba2\u6237\u7aef/i,
                    /\u4e0b\u8f7d\s*APP/i,
                    /\u4e0b\u8f7d\s*\u5ba2\u6237\u7aef/i,
                    /\u5ba2\u6237\u7aef\u5185\u6253\u5f00/i,
                    /APP\u5185/i
                ];

                var PROMO_SELECTOR = [
                    '[class*="open-app" i]',
                    '[id*="open-app" i]',
                    '[class*="download-app" i]',
                    '[id*="download-app" i]',
                    '[class*="app-banner" i]',
                    '[id*="app-banner" i]',
                    '[class*="smartbanner" i]',
                    '[id*="smartbanner" i]',
                    '[class*="app-promo" i]',
                    '[id*="app-promo" i]',
                    '[class*="open-in-app" i]',
                    '[id*="open-in-app" i]',
                    '[class*="callapp" i]',
                    '[id*="callapp" i]',
                    '[data-testid*="open-app" i]',
                    '[data-testid*="download-app" i]'
                ].join(',');

                function ensureStyle() {
                    if (document.getElementById('webwrapper-promo-popup-style')) {
                        return;
                    }

                    var style = document.createElement('style');
                    style.id = 'webwrapper-promo-popup-style';
                    style.textContent =
                        '.webwrapper-hidden-promo-popup {' +
                        'display: none !important;' +
                        'visibility: hidden !important;' +
                        'opacity: 0 !important;' +
                        'pointer-events: none !important;' +
                        '}';
                    (document.head || document.documentElement).appendChild(style);
                }

                function textLooksPromotional(text) {
                    if (!text) {
                        return false;
                    }

                    var normalized = text.replace(/\s+/g, ' ').trim().slice(0, 160);
                    return PROMO_TEXT_PATTERNS.some(function(pattern) {
                        return pattern.test(normalized);
                    });
                }

                function hrefLooksExternalApp(href) {
                    if (!href) {
                        return false;
                    }

                    return /^(intent|market|itmss?|taobao|tmall|alipays|weixin|zhihu|snssdk|baiduboxapp):/i.test(href);
                }

                function shouldHideElement(node) {
                    if (!(node instanceof HTMLElement)) {
                        return false;
                    }

                    if (node.classList.contains('webwrapper-hidden-promo-popup')) {
                        return false;
                    }

                    var style = window.getComputedStyle(node);
                    if (!style || style.display === 'none' || style.visibility === 'hidden') {
                        return false;
                    }

                    var rect = node.getBoundingClientRect();
                    if (rect.width <= 0 || rect.height <= 0) {
                        return false;
                    }

                    var viewportWidth = window.innerWidth || document.documentElement.clientWidth || 0;
                    var viewportHeight = window.innerHeight || document.documentElement.clientHeight || 0;
                    var position = style.position;
                    var zIndex = Number(style.zIndex || '0');
                    var nearBottom = rect.bottom >= viewportHeight - 24 && rect.top >= viewportHeight * 0.45;
                    var bannerLike = rect.width >= viewportWidth * 0.6 && rect.height >= 36 && rect.height <= Math.max(220, viewportHeight * 0.35);
                    var overlayLike = rect.width >= viewportWidth * 0.75 && rect.height >= viewportHeight * 0.2;
                    var hasPromoSelector = node.matches(PROMO_SELECTOR);
                    var hasPromoText = textLooksPromotional(node.innerText || '');
                    var appLink = node.querySelector('a[href], button[data-href], [data-url], [data-href]');
                    var linkedHref = appLink && (appLink.getAttribute('href') || appLink.getAttribute('data-url') || appLink.getAttribute('data-href') || '');
                    var opensExternalApp = hrefLooksExternalApp(linkedHref || '');

                    if (hasPromoSelector) {
                        return true;
                    }

                    if ((position === 'fixed' || position === 'sticky') && zIndex >= 10 && nearBottom && bannerLike && (hasPromoText || opensExternalApp)) {
                        return true;
                    }

                    if (position === 'fixed' && overlayLike && (hasPromoText || opensExternalApp)) {
                        return true;
                    }

                    return false;
                }

                function hideNode(node) {
                    if (!(node instanceof HTMLElement)) {
                        return false;
                    }

                    if (!shouldHideElement(node)) {
                        return false;
                    }

                    node.classList.add('webwrapper-hidden-promo-popup');
                    return true;
                }

                function findHideTarget(node) {
                    var current = node instanceof HTMLElement ? node : null;
                    while (current && current !== document.body) {
                        if (shouldHideElement(current)) {
                            return current;
                        }
                        current = current.parentElement;
                    }
                    return null;
                }

                function scan() {
                    ensureStyle();

                    var hiddenCount = 0;
                    document.querySelectorAll(PROMO_SELECTOR).forEach(function(node) {
                        if (hideNode(node)) {
                            hiddenCount += 1;
                        }
                    });

                    document.querySelectorAll('body *').forEach(function(node) {
                        if (hideNode(node)) {
                            hiddenCount += 1;
                        }
                    });

                    return hiddenCount;
                }

                document.addEventListener('click', function(event) {
                    var target = event.target;
                    if (!(target instanceof Element)) {
                        return;
                    }

                    var trigger = target.closest('a[href], [data-url], [data-href]');
                    if (!trigger) {
                        return;
                    }

                    var href = trigger.getAttribute('href') || trigger.getAttribute('data-url') || trigger.getAttribute('data-href') || '';
                    if (!hrefLooksExternalApp(href)) {
                        return;
                    }

                    var container = findHideTarget(trigger);
                    if (container) {
                        event.preventDefault();
                        event.stopPropagation();
                        event.stopImmediatePropagation();
                        hideNode(container);
                    }
                }, true);

                var scanQueued = false;
                var observer = new MutationObserver(function() {
                    if (scanQueued) {
                        return;
                    }

                    scanQueued = true;
                    window.requestAnimationFrame(function() {
                        scanQueued = false;
                        scan();
                    });
                });

                observer.observe(document.documentElement, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ['class', 'style', 'id']
                });

                blocker = {
                    scan: scan,
                    observer: observer
                };
                window.__webWrapperPromoBlocker = blocker;

                scan();
                window.setTimeout(scan, 400);
                window.setTimeout(scan, 1200);
                window.setTimeout(scan, 2500);

                return "installed";
            })();
        """
    }
}
