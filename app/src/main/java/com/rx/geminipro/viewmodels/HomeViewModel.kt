package com.rx.geminipro.viewmodels

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class HomeChoice(val id: String, val label: String)
enum class HomeProblem { CONNECTION, LOGIN, CONFIGURATION, UNCERTAIN }
data class HomeRequest(
    val id: String = UUID.randomUUID().toString(),
    val draft: String,
    val model: String,
    val thinking: String,
    val temporary: Boolean,
    val attachment: Boolean = false,
)
data class HomeState(
    val visible: Boolean = true,
    val draft: String = "",
    val models: List<HomeChoice> = emptyList(),
    val model: String = "",
    val modelLabel: String = "",
    val thinkingOptions: List<HomeChoice> = emptyList(),
    val thinking: String = "",
    val temporary: Boolean = false,
    val preparing: Boolean = true,
    val problem: HomeProblem? = null,
    val request: HomeRequest? = null,
    val mayHaveSent: Boolean = false,
    val loginFlow: Boolean = false,
    val revision: Int = 0,
) {
    val busy: Boolean get() = request != null
}

@HiltViewModel
class HomeViewModel @Inject constructor(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("native_home", 0)
    private val _state = MutableStateFlow(HomeState(
        draft = if (prefs.getBoolean("temporary", false)) "" else prefs.getString("draft", "").orEmpty(),
        models = readChoices(prefs.getString("models", "[]")),
        model = prefs.getString("model", "").orEmpty(),
        modelLabel = prefs.getString("model_label", "").orEmpty(),
        thinking = prefs.getString("thinking", "").orEmpty(),
        temporary = prefs.getBoolean("temporary", false),
        mayHaveSent = prefs.getBoolean("unconfirmed_send", false),
        problem = if (prefs.getBoolean("unconfirmed_send", false)) HomeProblem.UNCERTAIN else null,
    ))
    val state = _state.asStateFlow()

    fun editDraft(value: String) {
        if (_state.value.busy) return
        _state.update { it.copy(draft = value.take(100_000)) }
        saveDraft()
    }

    fun selectModel(choice: HomeChoice) {
        if (_state.value.busy) return
        _state.update { it.copy(model = choice.id, modelLabel = choice.label, thinking = "",
            thinkingOptions = emptyList(), problem = null, revision = it.revision + 1) }
        saveConfiguration()
    }

    fun selectThinking(choice: HomeChoice) {
        if (_state.value.busy) return
        _state.update { it.copy(thinking = choice.id, problem = null, revision = it.revision + 1) }
        saveConfiguration()
    }

    fun setTemporary(value: Boolean) {
        if (_state.value.busy) return
        _state.update { it.copy(temporary = value, problem = null, revision = it.revision + 1) }
        saveConfiguration()
        saveDraft()
    }

    fun preparing() { _state.update { it.copy(preparing = true, problem = if (it.mayHaveSent) HomeProblem.UNCERTAIN else null) } }

    fun acceptModels(models: List<HomeChoice>) {
        if (models.isEmpty()) return
        _state.update { it.copy(models = models) }
        prefs.edit { putString("models", choicesJson(models)) }
    }

    fun acceptCatalog(models: List<HomeChoice>, current: HomeChoice?, thinking: List<HomeChoice>, actualThinking: String) {
        _state.update { old ->
            val available = models.ifEmpty { old.models.ifEmpty { listOfNotNull(current) } }
            old.copy(models = available, model = old.model.ifEmpty { current?.id.orEmpty() },
                modelLabel = available.find { it.id == old.model }?.label
                    ?: old.modelLabel.ifEmpty { current?.label.orEmpty() },
                thinkingOptions = thinking, thinking = old.thinking.ifEmpty { actualThinking },
                preparing = false, problem = if (old.mayHaveSent) HomeProblem.UNCERTAIN else null)
        }
        prefs.edit { putString("models", choicesJson(_state.value.models)) }
        saveConfiguration()
    }

    fun submit(attachment: Boolean = false) {
        val value = _state.value
        if (value.busy || value.mayHaveSent || (!attachment && value.draft.isBlank())) return
        _state.update { it.copy(request = HomeRequest(draft = value.draft, model = value.model,
            thinking = value.thinking, temporary = value.temporary, attachment = attachment), problem = null) }
    }

    /** Durable intent BEFORE calling the website; a killed process must not auto-repeat a send. */
    fun beforeDispatch() {
        check(prefs.edit().putBoolean("unconfirmed_send", true).commit())
        _state.update { it.copy(mayHaveSent = true) }
    }

    fun definitelyNotDispatched() {
        prefs.edit { remove("unconfirmed_send") }
        _state.update { it.copy(mayHaveSent = false) }
    }

    fun sent() {
        prefs.edit { remove("draft"); remove("unconfirmed_send") }
        _state.update { it.copy(draft = "", request = null, mayHaveSent = false, visible = false,
            problem = null, preparing = false) }
    }

    fun failed(problem: HomeProblem) {
        _state.update { it.copy(request = null, preparing = false, problem = problem,
            visible = if (problem == HomeProblem.UNCERTAIN) false else it.visible) }
    }

    fun retry() { _state.update { it.copy(revision = it.revision + 1, problem = null) } }
    fun openWeb(login: Boolean = false) {
        _state.update { it.copy(visible = false, request = null, loginFlow = login) }
    }
    fun home() { _state.update { it.copy(visible = true, loginFlow = false, revision = it.revision + 1) } }
    fun acknowledgeUncertainSend() {
        definitelyNotDispatched()
        _state.update { it.copy(problem = null) }
    }

    private fun saveDraft() {
        val value = _state.value
        prefs.edit { if (value.temporary || value.draft.isEmpty()) remove("draft") else putString("draft", value.draft) }
    }
    private fun saveConfiguration() {
        val value = _state.value
        prefs.edit { putString("model", value.model); putString("model_label", value.modelLabel)
            putString("thinking", value.thinking); putBoolean("temporary", value.temporary) }
    }

    companion object {
        fun readChoices(json: String?): List<HomeChoice> = runCatching {
            val array = JSONArray(json ?: "[]")
            (0 until array.length()).map { index -> array.getJSONObject(index).let {
                HomeChoice(it.getString("id"), it.getString("label"))
            } }.filter { it.id.isNotBlank() && it.label.isNotBlank() }.distinctBy { it.id }.take(100)
        }.getOrDefault(emptyList())
        private fun choicesJson(choices: List<HomeChoice>): String = JSONArray(choices.map {
            JSONObject().put("id", it.id).put("label", it.label)
        }).toString()
    }
}
