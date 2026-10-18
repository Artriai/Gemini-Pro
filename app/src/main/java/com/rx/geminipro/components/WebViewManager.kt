package com.rx.geminipro.components

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Bitmap
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.rx.geminipro.R
import com.rx.geminipro.utils.network.WebOriginPolicy
import com.rx.geminipro.utils.network.blobDownloadScript
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rx.geminipro.utils.network.BlobDownloaderInterface
import java.io.File
import java.lang.ref.WeakReference


private const val TAG = "WebViewManager"


class WebViewManager(
    private val context: Context,
    private val activity: WeakReference<ComponentActivity>,
) {
    var onShowFileChooser: (ValueCallback<Array<Uri>>, Intent, Intent?) -> Unit = { callback, _, _ -> callback.onReceiveValue(null) }

    var onShowToast: (message: String) -> Unit = {}

    var onStartActivity: (intent: Intent) -> Unit = {}

    var onPageFinished: (view: WebView, url: String) -> Unit = { _, _ -> }

    var onPermissionRequest: (request: PermissionRequest) -> Unit = { it.deny() }
    var onPermissionCanceled: (PermissionRequest) -> Unit = {}
    var onNavigationStarted: () -> Unit = {}
    var withDownloadPermission: ((() -> Unit) -> Unit) = { it() }
    private val blobDownloader = BlobDownloaderInterface(context)
    private val downloadScript = context.assets.open("blob_download.js").bufferedReader().use { it.readText() }
    private val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()

    var onCameraTmpFileCreated: (Uri) -> Unit = {}
    var onProgressChanged: (Int) -> Unit = {}

    // --- Private State ---

    private var lastUrl: String? = null
    private var lastFailedUrl: String? = null
    private val errorUrl = "https://appassets.androidplatform.net/assets/webview_error.html"
    private val spoofHeaders = mapOf("X-Requested-With" to "")

    // --- Core WebView Clients and Listeners ---

    val webViewClient: WebViewClient = object : WebViewClient() {
        @SuppressLint("QueryPermissionsNeeded")
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (!request.isForMainFrame) return false
            return when {
                url == "geminipro://retry" -> {
                    if (view.url == errorUrl) view.loadUrl(retryUrl(), spoofHeaders)
                    true
                }
                url.startsWith("https://") -> {
                    if (WebOriginPolicy.isInternalNavigation(url)) false
                    else handleExternalAppUrl(url)
                }
                url.startsWith("http://") -> handleExternalAppUrl(url)
                url.startsWith("intent://") -> handleIntentUrl(url, view)
                url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("sms:") || url.startsWith("market:") -> handleExternalAppUrl(url)
                else -> true
            }
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            assetLoader.shouldInterceptRequest(request.url)

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            blobDownloader.cancel()
            onNavigationStarted()
            super.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            if (url != errorUrl) lastUrl = url
            onPageFinished.invoke(view, if (url == errorUrl) retryUrl() else url)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            if (WebOriginPolicy.isInternalNavigation(url)) {
                lastUrl = url
                onPageFinished.invoke(view, url)
            }
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true && request.url.toString() != errorUrl) {
                val failingUrl = request.url.toString()
                Log.e(TAG, "WebView Error: ${error?.errorCode} - ${error?.description} for $failingUrl")
                lastFailedUrl = failingUrl
                view?.loadUrl(errorUrl)
            }
        }
    }

    val webChromeClient: WebChromeClient = object : WebChromeClient() {
        private var customView: View? = null
        private var customViewCallback: CustomViewCallback? = null
        private var originalOrientation: Int = 0
        private var fullscreenContainer: FrameLayout? = null
        private var onBackPressedCallback: OnBackPressedCallback? = null

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            var photoUri: Uri? = null
            var captureIntent: Intent? = null

            try {
                val cameraDir = File(context.cacheDir, "camera").apply { mkdirs() }
                cameraDir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
                val photoFile = File.createTempFile("gemini_cam_", ".jpg", cameraDir)

                photoUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    photoFile
                )

                photoUri?.let { onCameraTmpFileCreated(it) }

                captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)

            } catch (e: Exception) {
                Log.e(TAG, "Unable to create camera temp file", e)
            }

            val galleryIntent = fileChooserParams.createIntent()

            val chooserIntent = Intent(Intent.ACTION_CHOOSER)
            chooserIntent.putExtra(Intent.EXTRA_INTENT, galleryIntent)
            chooserIntent.putExtra(Intent.EXTRA_TITLE, context.getString(R.string.choose_file))

            val photoIntent = captureIntent?.takeIf {
                fileChooserParams.acceptTypes.any { type -> type.startsWith("image/") || type == "*/*" || type.isBlank() }
            }?.apply {
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newRawUri("camera", photoUri)
            }
            onShowFileChooser(filePathCallback, chooserIntent, photoIntent)
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            onPermissionRequest.invoke(request)
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            onPermissionCanceled(request)
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            val currentActivity = activity.get() ?: run {
                callback?.onCustomViewHidden()
                return
            }

            if (customView != null) {
                callback?.onCustomViewHidden()
                return
            }

            customView = view
            customViewCallback = callback
            originalOrientation = currentActivity.requestedOrientation

            val decorView = currentActivity.window.decorView as ViewGroup
            fullscreenContainer = FrameLayout(currentActivity).apply {
                setBackgroundColor(Color.BLACK)
                addView(customView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }

            decorView.addView(fullscreenContainer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setFullscreen(enabled = true, activity = currentActivity)

            onBackPressedCallback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { onHideCustomView() }
            }
            currentActivity.onBackPressedDispatcher.addCallback(currentActivity, onBackPressedCallback!!)
        }

        override fun onHideCustomView() {
            val currentActivity = activity.get() ?: return
            if (customView == null) return

            setFullscreen(enabled = false, activity = currentActivity)
            (fullscreenContainer?.parent as? ViewGroup)?.removeView(fullscreenContainer)

            fullscreenContainer = null
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null

            onBackPressedCallback?.remove()
            onBackPressedCallback = null
        }

        private fun setFullscreen(enabled: Boolean, activity: Activity) {
            val window = activity.window
            val container = fullscreenContainer ?: return
            WindowInsetsControllerCompat(window, container).let { controller ->
                if (enabled) {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            val currentUrl = view?.url

            onProgressChanged(newProgress)

            if (newProgress == 100 && view != null && currentUrl != null) {
                onPageFinished.invoke(view, if (currentUrl == errorUrl) retryUrl() else currentUrl)
            }
        }
    }

    fun retryUrl(): String = lastFailedUrl ?: lastUrl ?: "https://aistudio.google.com/u/0/prompts/new_chat"

    fun reload(view: WebView) {
        if (view.url == errorUrl) view.loadUrl(retryUrl(), spoofHeaders) else view.reload()
    }

    fun installDownloadBridge(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(webView, "GeminiDownload", WebOriginPolicy.bridgeOrigins) {
                view, message, sourceOrigin, isMainFrame, reply ->
            if (isMainFrame && message.type == WebMessageCompat.TYPE_STRING && WebOriginPolicy.isBridgeOrigin(sourceOrigin.toString()) &&
                WebOriginPolicy.origin(view.url) == WebOriginPolicy.origin(sourceOrigin.toString())) {
                message.data?.let { blobDownloader.onMessage(it) { response -> reply.postMessage(response) } }
            }
        }
    }

    fun getDownloadListener(webView: WebView): DownloadListener =
        DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (!WebOriginPolicy.canDownload(url, webView.url)) {
                onShowToast(context.getString(R.string.download_failed))
                return@DownloadListener
            }
            val pageUrl = webView.url
            withDownloadPermission {
                // Do not download a blob after the user has navigated to a different origin.
                if (WebOriginPolicy.origin(pageUrl) != WebOriginPolicy.origin(webView.url)) return@withDownloadPermission
                if (url.startsWith("blob:") || url.startsWith("data:")) {
                    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                        onShowToast(context.getString(R.string.update_webview))
                    } else if (url.length > BlobDownloaderInterface.MAX_DOWNLOAD_BYTES * 4 / 3) {
                        onShowToast(context.getString(R.string.download_too_large))
                    } else if (blobDownloader.isBusy()) {
                        onShowToast(context.getString(R.string.download_in_progress))
                    } else {
                        val token = blobDownloader.prepare()
                        webView.evaluateJavascript(blobDownloadScript(downloadScript, url, token), null)
                        onShowToast(context.getString(R.string.download_starting))
                    }
                } else handleStandardDownload(url, userAgent, contentDisposition, mimeType)
            }
        }

    fun close() {
        webChromeClient.onHideCustomView()
        blobDownloader.close()
    }

    // --- Private Helper Functions ---

    private fun handleIntentUrl(url: String, webView: WebView): Boolean {
        try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            val fallbackUrl = intent.getStringExtra("browser_fallback_url")
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            intent.removeExtra("browser_fallback_url")

            if (intent.resolveActivity(context.packageManager) != null) {
                onStartActivity(intent)
            } else if (fallbackUrl != null && WebOriginPolicy.isInternalNavigation(fallbackUrl)) {
                webView.loadUrl(fallbackUrl, spoofHeaders)
            } else {
                onShowToast(context.getString(R.string.cannot_open_link))
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing intent URL: $url", e)
            onShowToast(context.getString(R.string.cannot_open_link))
            return true
        }
    }

    private fun handleExternalAppUrl(url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            onStartActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start activity for URL: $url", e)
            onShowToast(context.getString(R.string.cannot_open_link))
            true
        }
    }

    private fun handleStandardDownload(url: String, userAgent: String, contentDisposition: String, mimeType: String) {
        try {
            val request = DownloadManager.Request(url.toUri()).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                setTitle(filename)
                setDescription(context.getString(R.string.download_starting))
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            onShowToast(context.getString(R.string.download_starting))
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for URL: $url", e)
            onShowToast(context.getString(R.string.download_failed))
        }
    }
}
