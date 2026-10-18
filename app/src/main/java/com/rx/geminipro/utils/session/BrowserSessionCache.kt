package com.rx.geminipro.utils.session

import android.content.Context
import android.annotation.SuppressLint
import android.content.MutableContextWrapper
import android.os.Bundle
import android.os.Parcel
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import com.rx.geminipro.components.WebViewManager
import com.rx.geminipro.utils.network.WebOriginPolicy
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/** One live page per app process. Never retain a destroyed Activity or Compose callbacks. */
object BrowserSessionCache {
    const val STATE_KEY = "browser_navigation"
    // The Activity is released on detach; the wrapper then points only to applicationContext.
    @SuppressLint("StaticFieldLeak")
    private var cached: BrowserSession? = null
    private var pendingState: Bundle? = null
    private val cookieWorker = Executors.newSingleThreadExecutor()

    fun prepareRestore(state: Bundle?) {
        if (cached == null) pendingState = state
    }

    fun acquire(context: Context): BrowserSession = cached ?: run {
        val app = context.applicationContext
        val wrapper = MutableContextWrapper(app)
        BrowserSession(wrapper, WebView(wrapper), WebViewManager(app, WeakReference(null)), pendingState)
            .also { cached = it; pendingState = null }
    }

    fun flushCookies() {
        // CookieManager owns the credentials. Do not export or re-inject cookie strings.
        cookieWorker.execute { runCatching { CookieManager.getInstance().flush() } }
    }

    fun saveNavigationState(): Bundle? {
        val view = cached?.view ?: return null
        if (!WebOriginPolicy.isBridgeOrigin(view.url)) return null
        return runCatching {
            val state = Bundle()
            if (view.saveState(state) == null) return null
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(state)
                // Leave room in savedInstanceState for the Activity and the rest of Compose.
                state.takeIf { parcel.dataSize() <= 200 * 1024 }
            } finally { parcel.recycle() }
        }.getOrNull()
    }
}

class BrowserSession(
    private val contextWrapper: MutableContextWrapper,
    val view: WebView,
    val manager: WebViewManager,
    var savedState: Bundle?,
) {
    var started = false

    fun attach(activity: ComponentActivity?) {
        contextWrapper.baseContext = activity ?: contextWrapper.applicationContext
        manager.attachActivity(activity)
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.onResume()
    }

    @SuppressLint("ClickableViewAccessibility") // Remove the listener; this does not handle clicks.
    fun detach() {
        manager.detach()
        view.setOnTouchListener(null)
        view.setOnCreateContextMenuListener(null)
        view.setDownloadListener(null)
        view.onPause()
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        contextWrapper.baseContext = contextWrapper.applicationContext
        BrowserSessionCache.flushCookies()
    }
}
