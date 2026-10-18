package com.rx.geminipro.utils.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.webkit.PermissionRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.rx.geminipro.R
import com.rx.geminipro.utils.network.WebOriginPolicy

data class WebPermissionHandler(
    val request: (PermissionRequest) -> Unit,
    val canceled: (PermissionRequest) -> Unit,
    val clear: () -> Unit,
)

/** A website prompt is required even when Android permissions were already granted. */
@Composable
fun rememberWebPermissionHandler(currentPageUrl: () -> String?): WebPermissionHandler {
    val context = LocalContext.current
    val pageUrl by rememberUpdatedState(currentPageUrl)
    val cameraText = stringResource(R.string.camera)
    val microphoneText = stringResource(R.string.microphone)
    var pending by remember { mutableStateOf<PermissionRequest?>(null) }
    var awaitingRuntime by remember { mutableStateOf(false) }

    fun resources(request: PermissionRequest) = request.resources.filter {
        it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
    }
    fun permission(resource: String) = if (resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        Manifest.permission.CAMERA else Manifest.permission.RECORD_AUDIO
    fun trusted(request: PermissionRequest) = WebOriginPolicy.isBridgeOrigin(request.origin.toString()) &&
        WebOriginPolicy.origin(request.origin.toString()) == WebOriginPolicy.origin(pageUrl())
    fun complete() {
        val request = pending ?: return
        pending = null
        val allowed = resources(request).filter {
            ContextCompat.checkSelfPermission(context, permission(it)) == PackageManager.PERMISSION_GRANTED
        }
        if (trusted(request) && allowed.isNotEmpty()) request.grant(allowed.toTypedArray())
        else request.deny()
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        awaitingRuntime = false
        complete()
    }
    val shown = pending
    if (shown != null && !awaitingRuntime) {
        AlertDialog(
            onDismissRequest = { pending?.deny(); pending = null },
            title = { Text(stringResource(R.string.site_permission_title)) },
            text = { Text(stringResource(R.string.site_permission_message, shown.origin.host.orEmpty(),
                resources(shown).joinToString(", ") {
                    if (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE) cameraText else microphoneText
                })) },
            confirmButton = {
                TextButton(onClick = {
                    if (!trusted(shown)) { pending?.deny(); pending = null }
                    else {
                        val missing = resources(shown).map(::permission).distinct().filter {
                            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                        }
                        if (missing.isEmpty()) complete()
                        else { awaitingRuntime = true; launcher.launch(missing.toTypedArray()) }
                    }
                }) { Text(stringResource(R.string.allow)) }
            },
            dismissButton = { TextButton(onClick = { pending?.deny(); pending = null }) {
                Text(stringResource(R.string.deny))
            } },
        )
    }
    DisposableEffect(Unit) {
        onDispose { pending?.deny(); pending = null }
    }
    return WebPermissionHandler(
        request = {
            if (pending != null || awaitingRuntime || !trusted(it) || resources(it).isEmpty()) it.deny()
            else pending = it
        },
        canceled = { if (pending === it) pending = null },
        clear = { pending?.deny(); pending = null },
    )
}
