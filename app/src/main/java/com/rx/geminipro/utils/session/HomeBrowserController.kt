package com.rx.geminipro.utils.session

import android.net.Uri
import android.os.SystemClock
import android.webkit.WebView
import androidx.core.net.toUri
import com.rx.geminipro.viewmodels.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/** One coroutine owns the adapter. All commands run on the WebView's main thread. */
class HomeBrowserController(private val view: WebView, private val source: String) {
    private class Failure(val problem: HomeProblem) : Exception()

    suspend fun run(initial: HomeState, home: HomeViewModel) {
        home.preparing()
        try {
            // Never replay a submission across Activity/lifecycle recreation after a possible click.
            if (initial.mayHaveSent) { home.failed(HomeProblem.UNCERTAIN); return }
            val currentUrl = view.url.orEmpty()
            if (currentUrl.startsWith("https://appassets.androidplatform.net/") ||
                com.rx.geminipro.utils.network.WebOriginPolicy.origin(currentUrl) == "https://labs.google") {
                val previous = view.context.getSharedPreferences("browser_session", 0).getString("last_page", null)
                view.loadUrl(newChatUrl(previous, initial.model, initial.temporary), mapOf("X-Requested-With" to ""))
            }
            awaitWorkspace()
            untilReady("settings")
            var snapshot = call("status")
            val request = initial.request
            val model = request?.model ?: initial.model
            val temporary = request?.temporary ?: initial.temporary
            val thinking = request?.thinking ?: initial.thinking
            val needsNew = request != null && (snapshot.optInt("turns") > 0 || snapshot.optBoolean("hasDraft"))
            val differentModel = model.isNotBlank() && !matches(snapshot.optJSONObject("model"), model)
            if (needsNew || differentModel || snapshot.opt("temporary") != temporary) {
                val previousDocument = snapshot.optString("documentId")
                view.loadUrl(newChatUrl(view.url, model, temporary), mapOf("X-Requested-With" to ""))
                awaitWorkspace(previousDocument)
                untilReady("settings")
            }
            val models = optionalCatalog("models", "models")
            home.acceptModels(models)
            if (model.isNotBlank()) untilReady("selectModel", JSONObject().put("model", model))
            untilReady("selectTemporary", JSONObject().put("temporary", temporary))
            untilReady("settings")
            val actual = call("status").optJSONObject("model")?.let { HomeChoice(it.getString("id"), it.getString("label")) }
                ?: throw Failure(HomeProblem.CONFIGURATION)
            // Discovery may fail after a website layout change; it must never invent a model list.
            val levels = optionalCatalog("thinkingOptions", "options")
            // Publish new valid choices even if an older saved thinking level is no longer supported.
            home.acceptCatalog(models, actual, levels, call("status").optString("thinking"))
            if (thinking.isNotBlank()) untilReady("selectThinking", JSONObject().put("thinking", thinking))
            snapshot = call("status")
            home.acceptCatalog(models, actual, levels, snapshot.optString("thinking"))
            if (request == null) return
            val resolved = JSONObject().put("id", request.id).put("draft", request.draft)
                .put("model", model.ifEmpty { actual.id }).put("thinking", thinking)
                .put("temporary", temporary)
            untilReady("fill", resolved)
            if (request.attachment) {
                call("close")
                home.openWeb()
                call("attach")
                return
            }
            // Wait for Angular to observe the input event and enable Run before the commit.
            untilReady("canCommit")
            home.beforeDispatch()
            val commit = call("commit", JSONObject().put("id", request.id))
            when (commit.optString("status")) {
                "dispatched", "confirmed" -> Unit
                "waiting" -> { home.definitelyNotDispatched(); throw Failure(HomeProblem.CONNECTION) }
                "error" -> {
                    if (commit.optString("reason") != "layout_changed") home.definitelyNotDispatched()
                    throw Failure(if (home.state.value.mayHaveSent) HomeProblem.UNCERTAIN else HomeProblem.CONFIGURATION)
                }
                else -> throw Failure(HomeProblem.UNCERTAIN)
            }
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val receipt = call("receipt", JSONObject().put("id", request.id))
                if (receipt.optString("status") == "confirmed") { home.sent(); return }
                if (receipt.optString("status") == "unknown") break
                delay(180)
            }
            throw Failure(HomeProblem.UNCERTAIN)
        } catch (cancel: CancellationException) {
            // Preserve the pending request only if no website click could have happened.
            if (home.state.value.mayHaveSent) home.failed(HomeProblem.UNCERTAIN)
            throw cancel
        } catch (failure: Exception) {
            if (home.state.value.mayHaveSent) runCatching { call("close") }
            home.failed(if (home.state.value.mayHaveSent) HomeProblem.UNCERTAIN
                else (failure as? Failure)?.problem ?: HomeProblem.CONNECTION)
        }
    }

    suspend fun call(action: String, arguments: JSONObject = JSONObject()): JSONObject {
        val url = view.url
        if (url.orEmpty().toUri().host in setOf("accounts.google.com", "consent.google.com")) throw Failure(HomeProblem.LOGIN)
        if (!isAiStudio(url)) throw Failure(HomeProblem.CONNECTION)
        val json = arguments.put("action", action).toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        return withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine { continuation ->
                view.evaluateJavascript(source.replace("__HOME_REQUEST__", json)) { result ->
                    if (continuation.isActive) {
                        val decoded = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
                        continuation.resume(runCatching { JSONObject(decoded ?: "{}") }.getOrDefault(JSONObject()))
                    }
                }
            }
        } ?: throw Failure(HomeProblem.CONNECTION)
    }

    private suspend fun awaitWorkspace(previousDocument: String? = null): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + 25_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val url = view.url.orEmpty()
            if (url.toUri().host in setOf("accounts.google.com", "consent.google.com")) throw Failure(HomeProblem.LOGIN)
            if (url.startsWith("https://appassets.androidplatform.net/")) throw Failure(HomeProblem.CONNECTION)
            if (isAiStudio(url)) {
                val result = call("status")
                if (result.optString("status") == "ready" && result.optString("documentId") != previousDocument) return result
            }
            delay(200)
        }
        throw Failure(HomeProblem.CONNECTION)
    }

    private suspend fun untilReady(action: String, args: JSONObject = JSONObject(), timeout: Long = 8_000): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            val result = call(action, args)
            when (result.optString("status")) {
                "ready" -> return result
                "error", "unsupported" -> throw Failure(HomeProblem.CONFIGURATION)
            }
            delay(200)
        }
        throw Failure(HomeProblem.CONFIGURATION)
    }

    private suspend fun optionalCatalog(action: String, field: String): List<HomeChoice> = try {
        HomeViewModel.readChoices(untilReady(action, timeout = 5_000).optJSONArray(field)?.toString())
    } catch (cancel: CancellationException) { throw cancel }
      catch (_: Failure) { call("close"); untilReady("settings"); emptyList() }

    companion object {
        fun isAiStudio(url: String?): Boolean = com.rx.geminipro.utils.network.WebOriginPolicy.origin(url) == "https://aistudio.google.com"
        private fun matches(actual: JSONObject?, model: String): Boolean {
            fun normalized(value: String) = value.removePrefix("models/").lowercase().replace(' ', '-')
            return actual != null && (normalized(actual.optString("id")) == normalized(model) ||
                normalized(actual.optString("label")) == normalized(model))
        }
        private fun newChatUrl(current: String?, model: String, temporary: Boolean): String {
            val account = Regex("/u/\\d+").find(current.orEmpty().toUri().path.orEmpty())?.value.orEmpty()
            return Uri.Builder().scheme("https").authority("aistudio.google.com").path("$account/prompts/new_chat")
                .apply {
                    if (model.matches(Regex("gemini-[a-zA-Z0-9._-]+"))) appendQueryParameter("model", model)
                    if (temporary) appendQueryParameter("temporary", "true")
                }.build().toString()
        }
    }
}
