package com.rx.geminipro.utils.network

import org.json.JSONObject

fun blobDownloadScript(template: String, url: String, token: String): String =
    template + "\ndownloadGeminiBlob(${JSONObject.quote(url)}, ${JSONObject.quote(token)}, ${DownloadTransfer.MAX_BYTES});"
