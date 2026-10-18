package com.rx.geminipro.components

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rx.geminipro.R
import com.rx.geminipro.viewmodels.*

private val HomeBackground = Color(0xFF181818)
private val HomeCard = Color(0xFF242424)
private val HomeOutline = Color(0xFF343434)
private val HomeText = Color(0xFFDADADA)
private val HomeMuted = Color(0xFF929292)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeHomeScreen(
    state: HomeState,
    home: HomeViewModel,
    onOpenWeb: () -> Unit,
    onHistory: () -> Unit,
    onTools: () -> Unit,
    onLogin: () -> Unit,
) {
    var sheet by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                home.editDraft(state.draft + if (state.draft.isBlank()) it else "\n$it")
            }
        }
    }
    Column(Modifier.fillMaxSize().background(HomeBackground).pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Final).changes.forEach { it.consume() }
        }
    }.statusBarsPadding().navigationBarsPadding()
        .imePadding().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeIconButton(R.drawable.home_menu, stringResource(R.string.open_menu), enabled = !state.busy) { sheet = "menu" }
            Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                .clickable(enabled = !state.busy) { sheet = "models" }, verticalAlignment = Alignment.CenterVertically) {
                Text(state.modelLabel.ifEmpty { stringResource(R.string.home_select_model) },
                    Modifier.weight(1f, fill = false), color = HomeText, fontSize = 20.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Icon(painterResource(R.drawable.home_expand), null, Modifier.padding(start = 6.dp).size(20.dp), tint = HomeText)
            }
            HomeIconButton(R.drawable.home_tune, stringResource(R.string.home_settings), enabled = !state.busy) { sheet = "settings" }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Icon(painterResource(R.drawable.google_gemini_icon), null, Modifier.size(52.dp), tint = Color.Unspecified)
                Text(stringResource(R.string.home_greeting), color = HomeText, fontSize = 26.sp, fontWeight = FontWeight.Normal)
            }
        }
        if (state.problem != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(when (state.problem) {
                    HomeProblem.LOGIN -> R.string.home_login_needed
                    HomeProblem.CONNECTION -> R.string.home_connection_failed
                    HomeProblem.CONFIGURATION -> R.string.home_configuration_failed
                    HomeProblem.UNCERTAIN -> R.string.home_send_uncertain
                }), Modifier.weight(1f), color = HomeMuted, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = if (state.problem == HomeProblem.LOGIN) onLogin else onOpenWeb) {
                    Text(stringResource(if (state.problem == HomeProblem.LOGIN) R.string.home_login else R.string.home_view_chat), color = HomeText)
                }
            }
            if (state.mayHaveSent) {
                TextButton(onClick = home::acknowledgeUncertainSend) {
                    Text(stringResource(R.string.home_acknowledge_send), color = HomeMuted)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1.08f).height(48.dp).border(1.dp, HomeOutline, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp)).clickable(enabled = !state.busy) { sheet = "thinking" }
                .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(R.drawable.home_thinking), null, Modifier.size(19.dp), tint = HomeText)
                Text(stringResource(R.string.home_thinking_value, thinkingLabel(state.thinking)), Modifier.weight(1f),
                    fontSize = 13.sp, color = HomeText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(painterResource(R.drawable.home_expand), null, Modifier.size(16.dp), tint = HomeMuted)
            }
            Row(Modifier.weight(1f).height(48.dp).border(1.dp, HomeOutline, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp)).toggleable(value = state.temporary, enabled = !state.busy,
                    role = Role.Switch, onValueChange = home::setTemporary).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(R.drawable.home_temporary), null, Modifier.size(19.dp), tint = HomeText)
                Text(stringResource(R.string.home_temporary), Modifier.weight(1f), fontSize = 13.sp, color = HomeText, maxLines = 1)
                Canvas(Modifier.size(30.dp, 18.dp)) {
                    drawRoundRect(if (state.temporary) Color(0xFF8AB4F8) else HomeOutline,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
                    drawCircle(if (state.temporary) HomeBackground else HomeMuted, radius = size.height * .38f,
                        center = androidx.compose.ui.geometry.Offset(if (state.temporary) size.width - size.height / 2 else size.height / 2, size.height / 2))
                }
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(HomeCard).padding(16.dp)) {
            BasicTextField(value = state.draft, onValueChange = home::editDraft,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp, max = 168.dp), enabled = !state.busy,
                textStyle = TextStyle(color = HomeText, fontSize = 18.sp, lineHeight = 26.sp),
                cursorBrush = SolidColor(Color(0xFFAAC7FF)), maxLines = 6,
                decorationBox = { inner ->
                    Box {
                        if (state.draft.isEmpty()) Text(stringResource(R.string.home_prompt_hint), color = HomeMuted, fontSize = 18.sp)
                        inner()
                    }
                })
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                HomeIconButton(R.drawable.home_add, stringResource(R.string.home_attach), filled = false,
                    enabled = !state.busy && !state.mayHaveSent) { home.submit(attachment = true) }
                Spacer(Modifier.weight(1f))
                HomeIconButton(R.drawable.home_mic, stringResource(R.string.home_voice), filled = false, enabled = !state.busy) {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    runCatching { speech.launch(intent) }.onFailure {
                        android.widget.Toast.makeText(context, R.string.home_voice_unavailable, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                Spacer(Modifier.width(10.dp))
                val special = state.problem == HomeProblem.LOGIN || state.mayHaveSent
                Button(onClick = {
                    when {
                        state.mayHaveSent -> onOpenWeb()
                        state.problem == HomeProblem.LOGIN -> onLogin()
                        else -> home.submit()
                    }
                }, enabled = !state.busy && (state.draft.isNotBlank() || special),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    modifier = Modifier.height(44.dp), shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, HomeOutline),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFAAC7FF), contentColor = HomeBackground,
                        disabledContainerColor = HomeCard, disabledContentColor = HomeMuted)) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = HomeText)
                    else Icon(painterResource(R.drawable.home_send), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(when {
                        state.busy && state.mayHaveSent -> R.string.home_sending
                        state.busy -> R.string.home_preparing
                        state.mayHaveSent -> R.string.home_view_chat
                        state.problem == HomeProblem.LOGIN -> R.string.home_login
                        else -> R.string.home_send
                    }), fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = HomeCard) {
        when (sheet) {
            "models", "thinking" -> {
                val models = sheet == "models"
                val choices = if (models) state.models else listOf(HomeChoice("", stringResource(R.string.home_default))) + state.thinkingOptions
                Text(stringResource(if (models) R.string.home_select_model else R.string.home_thinking),
                    Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = HomeText, style = MaterialTheme.typography.titleLarge)
                if (choices.isEmpty()) {
                    Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (state.preparing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = HomeText)
                        Text(stringResource(if (state.preparing) R.string.home_reading_options
                            else if (models) R.string.home_options_unavailable else R.string.home_model_default), color = HomeMuted)
                    }
                    TextButton(onClick = { home.retry() }, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(stringResource(R.string.home_retry), color = HomeText)
                    }
                } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(choices, key = { it.id }) { choice ->
                        Row(Modifier.fillMaxWidth().clickable {
                            if (models) home.selectModel(choice) else home.selectThinking(choice)
                            sheet = null
                        }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = choice.id == if (models) state.model else state.thinking, onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFAAC7FF), unselectedColor = HomeMuted))
                            Text(if (models) choice.label else thinkingLabel(choice.id), Modifier.padding(start = 12.dp), color = HomeText)
                        }
                    }
                }
            }
            "settings" -> {
                Text(stringResource(R.string.home_settings), Modifier.padding(24.dp), color = HomeText, style = MaterialTheme.typography.titleLarge)
                HomeSheetRow(stringResource(R.string.home_select_model), state.modelLabel) { sheet = "models" }
                HomeSheetRow(stringResource(R.string.home_thinking), thinkingLabel(state.thinking)) { sheet = "thinking" }
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.home_temporary), Modifier.weight(1f), color = HomeText)
                    Switch(checked = state.temporary, onCheckedChange = home::setTemporary)
                }
                TextButton(onClick = { sheet = null; onOpenWeb() }, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.home_more_settings), color = HomeMuted)
                }
            }
            else -> {
                HomeSheetRow(stringResource(R.string.home_history)) { sheet = null; onHistory() }
                HomeSheetRow(stringResource(R.string.home_open_chat)) { sheet = null; onOpenWeb() }
                HomeSheetRow(stringResource(R.string.home_app_tools)) { sheet = null; onTools() }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HomeSheetRow(title: String, value: String = "", onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = HomeText)
        if (value.isNotEmpty()) Text(value, color = HomeMuted)
        Icon(painterResource(R.drawable.home_expand), null, Modifier.padding(start = 8.dp).size(18.dp), tint = HomeMuted)
    }
}

@Composable
private fun HomeIconButton(icon: Int, description: String, enabled: Boolean = true, filled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)
        .clip(RoundedCornerShape(15.dp)).background(if (filled) Color(0xFF303030) else Color.Transparent)
        .then(if (filled) Modifier else Modifier.border(1.dp, HomeOutline, RoundedCornerShape(15.dp)))) {
        Icon(painterResource(icon), description, Modifier.size(23.dp), tint = if (enabled) HomeText else HomeMuted)
    }
}

@Composable
private fun thinkingLabel(value: String): String = stringResource(when (value.lowercase()) {
    "minimal", "最低", "最小" -> R.string.home_thinking_minimal
    "low", "低" -> R.string.home_thinking_low
    "medium", "中等", "中" -> R.string.home_thinking_medium
    "high", "高" -> R.string.home_thinking_high
    "" -> R.string.home_default
    else -> return value
})
