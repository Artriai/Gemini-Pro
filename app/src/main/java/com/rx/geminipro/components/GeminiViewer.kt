package com.rx.geminipro.components

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.CookieManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.rx.geminipro.utils.permissions.rememberWebPermissionHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.compose.runtime.*
import com.rx.geminipro.utils.network.WebOriginPolicy
import androidx.core.content.edit
import com.rx.geminipro.R
import org.json.JSONTokener
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.rx.geminipro.utils.session.BrowserSessionCache
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun GeminiWebViewer(
    modifier: Modifier,
    filePathCallbackState: MutableState<ValueCallback<Array<Uri>>?>,
    filePickerLauncher: ActivityResultLauncher<Intent>?,
    isVideoSelectionMode: Boolean,
    onWebViewCreated: (WebView) -> Unit,
    onProgressChanged: (Int) -> Unit,
    onPageFinished: (WebView, String) -> Unit,
    onCameraTmpFileCreated: (Uri) -> Unit,
    onReloadActionCreated: ((() -> Unit) -> Unit),
) {
    val context = LocalContext.current
    val activity = LocalActivity.current as? ComponentActivity
    val sessionPrefs = remember(context) { context.getSharedPreferences("browser_session", android.content.Context.MODE_PRIVATE) }
    val initialUrl = remember {
        sessionPrefs.getString("last_page", null)?.takeIf { WebOriginPolicy.isBridgeOrigin(it) }
            ?: "https://aistudio.google.com/u/0/prompts/new_chat"
    }
    val spoofHeaders = mapOf("X-Requested-With" to "")
    val session = remember { BrowserSessionCache.acquire(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    var currentWebView by remember { mutableStateOf<WebView?>(null) }
    val permissions = rememberWebPermissionHandler { currentWebView?.url }
    val currentPermissions by rememberUpdatedState(permissions)
    var cameraChoice by remember { mutableStateOf<Intent?>(null) }
    var galleryChoice by remember { mutableStateOf<Intent?>(null) }
    var pendingCameraLaunch by remember { mutableStateOf<Intent?>(null) }
    fun cancelUpload() {
        cameraChoice = null
        galleryChoice = null
        pendingCameraLaunch = null
        filePathCallbackState.value?.onReceiveValue(null)
        filePathCallbackState.value = null
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val intent = pendingCameraLaunch
        pendingCameraLaunch = null
        if (granted && intent != null) {
            runCatching { filePickerLauncher?.launch(intent) }.onFailure { cancelUpload() }
        } else {
            cancelUpload()
            Toast.makeText(context, R.string.camera_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }
    if (cameraChoice != null) {
        AlertDialog(
            onDismissRequest = { cancelUpload() },
            title = { Text(stringResource(R.string.upload_image)) },
            confirmButton = {
                TextButton(onClick = {
                    val intent = cameraChoice
                    cameraChoice = null
                    galleryChoice = null
                    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        runCatching { filePickerLauncher?.launch(intent!!) }.onFailure { cancelUpload() }
                    } else {
                        pendingCameraLaunch = intent
                        cameraLauncher.launch(android.Manifest.permission.CAMERA)
                    }
                }) { Text(stringResource(R.string.take_photo)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    val intent = galleryChoice
                    cameraChoice = null
                    galleryChoice = null
                    runCatching { filePickerLauncher?.launch(intent!!) }.onFailure { cancelUpload() }
                }) { Text(stringResource(R.string.choose_file)) }
            },
        )
    }
    var pendingDownload by remember { mutableStateOf<(() -> Unit)?>(null) }
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingDownload
        pendingDownload = null
        if (granted) action?.invoke()
        else Toast.makeText(context, R.string.download_permission_denied, Toast.LENGTH_SHORT).show()
    }
    val currentVideoMode by rememberUpdatedState(isVideoSelectionMode)
    val currentCameraCallback by rememberUpdatedState(onCameraTmpFileCreated)
    val currentProgressCallback by rememberUpdatedState(onProgressChanged)
    val currentPageFinishedCallback by rememberUpdatedState(onPageFinished)

    val lastTouchX = remember { mutableFloatStateOf(0f) }
    val lastTouchY = remember { mutableFloatStateOf(0f) }

    val noVideoFoundText = stringResource(com.rx.geminipro.R.string.no_video_found)
    val currentNoVideoFoundText by rememberUpdatedState(noVideoFoundText)
    val filePickerFailedText by rememberUpdatedState(stringResource(R.string.file_picker_failed))
    val downloadVideoText by rememberUpdatedState(stringResource(R.string.download_video_here))
    val saveImageText by rememberUpdatedState(stringResource(R.string.save_image))

    val webViewManager = remember(context, activity, session) {
        session.manager.apply {
            this.onShowToast = { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
            this.onStartActivity = { intent -> context.startActivity(intent) }
            this.onShowFileChooser = { callback, intent, cameraIntent ->
                filePathCallbackState.value?.onReceiveValue(null)
                filePathCallbackState.value = callback
                try {
                    if (cameraIntent != null) {
                        cameraChoice = cameraIntent
                        galleryChoice = intent
                    } else filePickerLauncher?.launch(intent)
                } catch (e: Exception) {
                    Log.e("GeminiWebViewer", "Cannot open file picker", e)
                    this.onShowToast(filePickerFailedText)
                    filePathCallbackState.value?.onReceiveValue(null)
                    filePathCallbackState.value = null
                }
            }
            this.onProgressChanged = { currentProgressCallback(it) }
            this.onPageFinished = { view, url ->
                if (WebOriginPolicy.isBridgeOrigin(url)) sessionPrefs.edit { putString("last_page", url) }
                currentPageFinishedCallback(view, url)
                if (WebOriginPolicy.isBridgeOrigin(url)) BrowserSessionCache.flushCookies()
            }
            this.onPermissionRequest = { currentPermissions.request(it) }
            this.onPermissionCanceled = { currentPermissions.canceled(it) }
            this.onNavigationStarted = {
                currentPermissions.clear()
                pendingDownload = null
                cancelUpload()
            }
            this.withDownloadPermission = { action ->
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                    ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    if (pendingDownload == null) {
                        pendingDownload = action
                        storageLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                } else action()
            }
            this.onCameraTmpFileCreated = { uri ->
                currentCameraCallback(uri)
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            android.widget.FrameLayout(ctx).apply {
                fitsSystemWindows = true

                session.attach(activity)
                val webView = session.view.apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        MATCH_PARENT,
                        MATCH_PARENT,
                    )
                    setBackgroundColor(Color.Transparent.toArgb())

                    if (!session.started) webViewManager.installDownloadBridge(this)
                    CookieManager.getInstance().setAcceptCookie(true)

                    webViewManager.let { manager ->
                        webViewClient = manager.webViewClient
                        webChromeClient = manager.webChromeClient
                        setDownloadListener(manager.getDownloadListener(this))
                    }

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        mediaPlaybackRequiresUserGesture = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                        allowFileAccess = false
                        allowContentAccess = false
                        javaScriptCanOpenWindowsAutomatically = true
                        setSupportMultipleWindows(false)

                        val defaultUserAgent = userAgentString ?: ""

                        val chromeVersionToken = try {
                            val pattern = "Chrome/[.0-9]+".toRegex()
                            pattern.find(defaultUserAgent)?.value ?: "Chrome/144.0.0.0"
                        } catch (_: Exception) {
                            "Chrome/144.0.0.0"
                        }
                        userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) $chromeVersionToken Mobile Safari/537.36"                    }

                    setOnTouchListener { _, event ->
                        if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                            lastTouchX.floatValue = event.x
                            lastTouchY.floatValue = event.y
                        }
                        false
                    }

                    setOnCreateContextMenuListener { menu, v, _ ->
                        val hitTestWebView = v as WebView
                        val result = hitTestWebView.hitTestResult

                        if (currentVideoMode) {
                            menu.add(downloadVideoText).setOnMenuItemClickListener {

                                val density = resources.displayMetrics.density
                                val cssX = lastTouchX.floatValue / density
                                val cssY = lastTouchY.floatValue / density

                                val js = """
                             (function() {
                                 var el = document.elementFromPoint($cssX, $cssY);
                                 // Walk up the tree in case we clicked a play button OVER the video
                                 while (el && el.tagName !== 'VIDEO') {
                                     el = el.parentElement;
                                 }
                                 if (el && el.tagName === 'VIDEO') {
                                     return el.currentSrc;
                                 }
                                 return null;
                             })();
                         """.trimIndent()

                                evaluateJavascript(js) { resultJs ->
                                    val url = runCatching { JSONTokener(resultJs).nextValue() as? String }.getOrNull()

                                    if (!url.isNullOrEmpty() && (url != "null")) {
                                        webViewManager.getDownloadListener(this).onDownloadStart(
                                            url,
                                            settings.userAgentString,
                                            "video.mp4",
                                            "video/mp4",
                                            0L
                                        )
                                    } else {
                                        Toast.makeText(
                                            context,
                                            currentNoVideoFoundText,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                                true
                            }
                        }
                        else{
                            if ((result.type == WebView.HitTestResult.IMAGE_TYPE) ||
                                (result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) {

                                val imageUrl = result.extra

                                if (imageUrl != null) {
                                    menu.add(saveImageText).setOnMenuItemClickListener {
                                        webViewManager.getDownloadListener(this).onDownloadStart(
                                            imageUrl,
                                            settings.userAgentString,
                                            "attachment",
                                            "image/png",
                                            0L
                                        )
                                        true
                                    }
                                }
                            }
                        }
                    }

                    if (!session.started) {
                        val restored = session.savedState?.let { restoreState(it) } != null
                        session.savedState = null
                        session.started = true
                        if (!restored) loadUrl(initialUrl, spoofHeaders)
                    }
                }

                addView(webView)
                currentWebView = webView
                onReloadActionCreated { webViewManager.reload(webView) }
                onWebViewCreated(webView)
                currentProgressCallback(webView.progress)
                webView.url?.let { url ->
                    currentPageFinishedCallback(webView,
                        if (url.startsWith("https://appassets.androidplatform.net/")) webViewManager.retryUrl() else url)
                }
            }
        },
        onRelease = { container ->
            pendingDownload = null
            cancelUpload()
            currentPermissions.clear()
            filePathCallbackState.value?.onReceiveValue(null)
            filePathCallbackState.value = null
            session.detach()
            currentWebView = null
            container.removeAllViews()
        },
    )

    DisposableEffect(lifecycleOwner, currentWebView) {
        val view = currentWebView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                Lifecycle.Event.ON_STOP -> BrowserSessionCache.flushCookies()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

}
