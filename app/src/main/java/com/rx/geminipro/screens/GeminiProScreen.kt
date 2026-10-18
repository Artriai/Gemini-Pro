package com.rx.geminipro.screens

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.rx.geminipro.R
import com.rx.geminipro.components.*
import com.rx.geminipro.utils.system.extractPreviewContent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rx.geminipro.utils.session.HomeBrowserController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.rx.geminipro.viewmodels.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext


@Composable
fun GeminiProScreen(
    modifier: Modifier = Modifier,
    viewModel: GeminiViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    var webView by remember { mutableStateOf<WebView?>(null) }
    val activity = LocalActivity.current
    var mountBrowser by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val homeScript = remember { context.assets.open("home_bridge.js").bufferedReader().use { it.readText() } }
    var preparationJob by remember { mutableStateOf<Job?>(null) }
    var reloadPage by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ioScope = rememberCoroutineScope()

    var showAdditionalMenu by remember { mutableStateOf(value = false) }
    var showDiagram by remember { mutableStateOf(value = false) }
    var showHtmlPreview by remember { mutableStateOf(value = false) }
    var clipboardText by remember { mutableStateOf(value = "") }
    var pendingSaveText by remember { mutableStateOf("") }
    var isHighlightingMenu by remember { mutableStateOf(value = false) }

    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }


    val isKeyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(isKeyboardVisible) {
        viewModel.onEvent(GeminiUiEvent.KeyboardVisibilityChanged(isKeyboardVisible))
    }

    // --- File Picker Setup ---
    val filePathCallbackState = remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (filePathCallbackState.value == null) return@rememberLauncherForActivityResult

        val uris: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data

            if (data?.data != null) {
                arrayOf(data.data!!)
            }
            else if (data?.clipData != null) {
                val clip = data.clipData!!
                List(clip.itemCount) { clip.getItemAt(it).uri }.toTypedArray()
            }
            else if (tempCameraUri != null && runCatching {
                    context.contentResolver.openAssetFileDescriptor(tempCameraUri!!, "r")?.use { it.length > 0 } == true
                }.getOrDefault(false)) {
                arrayOf(tempCameraUri!!)
            }
            else null
        } else null

        filePathCallbackState.value?.onReceiveValue(uris)
        filePathCallbackState.value = null
        tempCameraUri = null
    }

    // --- Document Launcher for "Save to File" ---
    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val textToSave = pendingSaveText
        pendingSaveText = ""
        uri?.let { target ->
            ioScope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        checkNotNull(context.contentResolver.openOutputStream(target)).use {
                            it.write(textToSave.toByteArray(Charsets.UTF_8))
                        }
                    }.isSuccess
                }
                Toast.makeText(context, if (saved) R.string.file_saved else R.string.save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- Side Effect Handler ---
    LaunchedEffect(Unit) {
        viewModel.sideEffectFlow.collect { effect ->
            when (effect) {
                is GeminiSideEffect.LaunchIntent -> context.startActivity(effect.intent)
                is GeminiSideEffect.ShowToast -> Toast.makeText(
                    context,
                    effect.message,
                    Toast.LENGTH_SHORT,
                ).show()

                is GeminiSideEffect.LaunchSaveToFile -> {
                    pendingSaveText = clipboardText
                    documentLauncher.launch("gemini-note.txt")
                }
                is GeminiSideEffect.WebViewGoBack -> webView?.goBack()
                is GeminiSideEffect.WebViewReload -> reloadPage?.invoke()
                is GeminiSideEffect.WebViewGoForward -> webView?.goForward()
                is GeminiSideEffect.LoadUrl -> { homeViewModel.openWeb(); webView?.loadUrl(effect.url) }
            }
        }
    }

    // Register once per screen and always remove the listener when leaving it.
    DisposableEffect(clipboardManager) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            val text = clipboardManager.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            clipboardText = text
            viewModel.onClipboardTextChanged(text)
        }
        clipboardManager.addPrimaryClipChangedListener(listener)
        onDispose { clipboardManager.removePrimaryClipChangedListener(listener) }
    }
    val previewCode = remember(clipboardText) { extractPreviewContent(clipboardText).code }

    // --- Back Handler Logic ---
    BackHandler {
        if (isKeyboardVisible) { keyboard?.hide(); focusManager.clearFocus() }
        else if (!homeState.visible) homeViewModel.home()
        else activity?.moveTaskToBack(true)
    }


    // Draw the local homepage before initializing the relatively expensive WebView.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        mountBrowser = true
    }
    LaunchedEffect(webView, homeState.visible) {
        webView?.importantForAccessibility = if (homeState.visible)
            android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if (!homeState.visible && homeState.problem == HomeProblem.UNCERTAIN) {
            webView?.let { view -> runCatching { HomeBrowserController(view, homeScript).call("close") } }
        }
    }
    LaunchedEffect(homeState.request?.id) {
        if (homeState.request != null) { focusManager.clearFocus(); keyboard?.hide() }
    }
    LaunchedEffect(webView, homeState.visible, homeState.revision, homeState.request?.id) {
        val view = webView ?: return@LaunchedEffect
        if (!homeState.visible || (homeState.problem != null && homeState.request == null)) return@LaunchedEffect
        preparationJob = currentCoroutineContext()[Job]
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            HomeBrowserController(view, homeScript).run(homeViewModel.state.value, homeViewModel)
        }
    }
    fun revealWeb(history: Boolean = false, login: Boolean = false) {
        ioScope.launch {
            preparationJob?.cancelAndJoin()
            webView?.let { view ->
                runCatching { HomeBrowserController(view, homeScript).call(if (history) "history" else "close") }
            }
            homeViewModel.openWeb(login)
        }
    }

    // --- Menu Highlight Animation Trigger ---
    LaunchedEffect(uiState.isMenuLeft) {
        isHighlightingMenu = true
        delay(2000)
        isHighlightingMenu = false
    }

    // --- UI Layer ---

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (mountBrowser) GeminiWebViewer(
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (homeState.visible) 0f else 1f },
            filePathCallbackState = filePathCallbackState,
            filePickerLauncher = filePickerLauncher,
            isVideoSelectionMode = uiState.isVideoSelectionMode,
            onWebViewCreated = { createdWebView ->
                webView = createdWebView
            },
            onProgressChanged = { progress ->
                viewModel.onEvent(GeminiUiEvent.LoadingProgressChanged(progress))
            },
            onPageFinished = { webView, url ->
                viewModel.onEvent(
                    GeminiUiEvent.WebViewNavigated(
                        canGoBack = webView.canGoBack(),
                        url = url,
                    )
                )
                viewModel.onEvent(GeminiUiEvent.ApplicationReady)
                if (homeViewModel.state.value.loginFlow && HomeBrowserController.isAiStudio(url)) homeViewModel.home()
            },
            onCameraTmpFileCreated = { tempCameraUri = it },
            onReloadActionCreated = { reloadPage = it },
        )

        if (!homeState.visible) {
            BrowserProgressBar(progress = uiState.loadingProgress)

            ReloadIndicator(isLoading = uiState.isReloading)

            VideoModeIndicator(uiState.isVideoSelectionMode)

            PreviewButtons(
                clipboardContentType = uiState.clipboardContentType,
                onShowDiagram = { showDiagram = true },
                onShowHtml = { showHtmlPreview = true }
            )

            if (!uiState.isKeyboardVisible) {
                MenuHandles(
                    isMenuLeft = uiState.isMenuLeft,
                    isHighlighting = isHighlightingMenu,
                    onReload = { viewModel.onEvent(GeminiUiEvent.ReloadPageClicked) },
                    onOpenMenu = { showAdditionalMenu = true }
                )
            }

        }

        if (homeState.visible) {
            NativeHomeScreen(state = homeState, home = homeViewModel,
                onOpenWeb = { revealWeb() }, onHistory = { revealWeb(history = true) },
                onTools = { showAdditionalMenu = true }, onLogin = { revealWeb(login = true) })
        }

        if (showAdditionalMenu) {
            AdditionalMenu(
                onClose = { showAdditionalMenu = false },
                items = listOf(MenuItemData(R.drawable.home_house, stringResource(R.string.home_return)) {
                    homeViewModel.home()
                }) + rememberMenuItems(viewModel::onEvent, uiState.isMenuLeft)
            )
        }

        DiagramScreen(
            isDiagramVisible = showDiagram,
            clipboardText = previewCode,
            onBack = { showDiagram = false },
            onClose = { showDiagram = false }
        )

        HtmlPreviewScreen(
            isHtmlPreviewVisible = showHtmlPreview,
            clipboardText = previewCode,
            onBack = { showHtmlPreview = false },
            onClose = { showHtmlPreview = false }
        )

        if (uiState.updateInfo != null) {
            UpdateAvailableDialog(
                newVersion = uiState.updateInfo!!.version,
                changelog = uiState.updateInfo!!.changelog,
                onDismiss = { viewModel.onEvent(GeminiUiEvent.DismissUpdateDialog) },
                onDownload = { viewModel.onEvent(GeminiUiEvent.UpdateClicked) },
                onSkipVersion = { viewModel.onEvent(GeminiUiEvent.SkipUpdateClicked) }
            )
        }
    }
}

@Composable
fun BoxScope.BrowserProgressBar(progress: Int) {
    val isVisible = ((progress in 1..99))

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(4.dp)
            .align(Alignment.TopCenter)
    ) {
        LinearProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.Transparent,
        )
    }
}

@Composable
private fun BoxScope.VideoModeIndicator(isVisible: Boolean){
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut(),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .statusBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
                .background(Color(0xFF2196F3).copy(alpha = 0.8f))
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.tap_and_hold_video),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun BoxScope.PreviewButtons(
    clipboardContentType: ClipboardContentType,
    onShowDiagram: () -> Unit,
    onShowHtml: () -> Unit
) {
    val isVisible = clipboardContentType != ClipboardContentType.NONE
    val buttonText = when (clipboardContentType) {
        ClipboardContentType.DIAGRAM -> stringResource(R.string.show_diagram)
        ClipboardContentType.HTML -> stringResource(R.string.preview_html)
        else -> ""
    }
    val onClick = when (clipboardContentType) {
        ClipboardContentType.DIAGRAM -> onShowDiagram
        ClipboardContentType.HTML -> onShowHtml
        else -> ({})
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 72.dp)
    ) {
        DialogButton(text = buttonText, onClick = onClick)
    }
}

@Composable
private fun BoxScope.MenuHandles(
    isMenuLeft: Boolean,
    isHighlighting: Boolean,
    onReload: () -> Unit,
    onOpenMenu: () -> Unit
) {
    Box(
        modifier = Modifier
            .statusBarsPadding()
            .align(Alignment.TopCenter)
            .width(80.dp)
            .height(60.dp)
            .pointerInput(Unit) {
                var distance = 0f
                detectVerticalDragGestures(
                    onDragStart = { distance = 0f },
                    onDragEnd = { if (distance > 64.dp.toPx()) onReload() },
                    onVerticalDrag = { change, amount -> distance += amount; change.consume() },
                )
            }
    )

    val animatedColor by animateColorAsState(
        targetValue = if (isHighlighting) MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f) else Color.Transparent,
        animationSpec = tween(durationMillis = 500),
        label = "menuHighlightColor"
    )
    val alignment = if (isMenuLeft) Alignment.CenterStart else Alignment.CenterEnd

    Box(
        modifier = Modifier
            .align(alignment)
            .width(30.dp)
            .fillMaxHeight(0.4f)
            .background(animatedColor)
            .pointerInput(isMenuLeft) {
                var distance = 0f
                detectHorizontalDragGestures(
                    onDragStart = { distance = 0f },
                    onDragEnd = { if (kotlin.math.abs(distance) > 32.dp.toPx()) onOpenMenu() },
                    onHorizontalDrag = { change, amount -> distance += amount; change.consume() },
                )
            }
    )
    FilledTonalIconButton(
        onClick = onOpenMenu,
        modifier = Modifier.align(if (isMenuLeft) Alignment.BottomStart else Alignment.BottomEnd)
            .navigationBarsPadding().padding(12.dp),
    ) {
        Icon(painterResource(R.drawable.outline_arrow_menu_open_24),
            contentDescription = stringResource(R.string.open_menu))
    }
}

@Composable
private fun rememberMenuItems(onEvent: (GeminiUiEvent) -> Unit, isMenuLeft: Boolean): List<MenuItemData> {
    return listOf(
        MenuItemData(R.drawable.google_docs, stringResource(R.string.open_docs)) { onEvent(GeminiUiEvent.OpenDocsClicked) },
        MenuItemData(R.drawable.coffee_cup, stringResource(R.string.caffeine)) { onEvent(GeminiUiEvent.KeepScreenOnToggled) },
        MenuItemData(R.drawable.google_flow_icon, "Google Flow") { onEvent(GeminiUiEvent.OpenFlowClicked) },
        MenuItemData(R.drawable.note_text, stringResource(R.string.save_to_file)) { onEvent(GeminiUiEvent.SaveToFileClicked) },
        MenuItemData(R.drawable.baseline_save_alt_24, stringResource(R.string.download_video)) { onEvent(GeminiUiEvent.ToggleVideoSelectionMode) },
        MenuItemData(R.drawable.baseline_share_24, stringResource(R.string.share_page)) { onEvent(GeminiUiEvent.SharePageClicked) },
        MenuItemData(R.drawable.baseline_link_24, stringResource(R.string.copy_link)) { onEvent(GeminiUiEvent.CopyLinkClicked) },
        MenuItemData(R.drawable.baseline_replay_circle_filled_24, stringResource(R.string.reload_page)) { onEvent(GeminiUiEvent.ReloadPageClicked) },
        MenuItemData(R.drawable.baseline_arrow_circle_right_24, stringResource(R.string.go_forward)) { onEvent(GeminiUiEvent.GoForwardClicked) },
        MenuItemData(R.drawable.baseline_open_in_browser_24, stringResource(R.string.open_in_browser)) { onEvent(GeminiUiEvent.OpenInBrowserClicked) },
        MenuItemData(if (isMenuLeft) R.drawable.outline_arrow_menu_close_24 else R.drawable.outline_arrow_menu_open_24,
            stringResource(R.string.menu_side)) { onEvent(GeminiUiEvent.MenuPositionChanged(!isMenuLeft)) },
    )
}

@Composable
private fun DialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.padding(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.secondary
        )
    ) {
        Text(text)
    }
}

@Composable
private fun BoxScope.ReloadIndicator(isLoading: Boolean) {
    AnimatedVisibility(
        visible = isLoading,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding(),
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Surface(
            modifier = Modifier.padding(top = 16.dp),
            shape = CircleShape,
            shadowElevation = 8.dp
        ) {
            CircularProgressIndicator(
                modifier = Modifier.padding(8.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
