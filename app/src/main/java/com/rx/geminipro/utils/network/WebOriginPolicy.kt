package com.rx.geminipro.utils.network

import java.net.URI

/** Exact HTTPS origins only; lookalike suffixes and non-default ports are not trusted. */
object WebOriginPolicy {
    val bridgeOrigins = setOf("https://aistudio.google.com", "https://labs.google")
    private val navigationHosts = setOf(
        "aistudio.google.com", "labs.google", "accounts.google.com",
        "consent.google.com", "docs.google.com", "drive.google.com",
    )

    fun origin(url: String?): String? {
        return runCatching {
            val uri = URI(url ?: return null)
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null ||
                (uri.port != -1 && uri.port != 443)) return null
            "https://${uri.host?.lowercase() ?: return null}"
        }.getOrNull()
    }

    fun isBridgeOrigin(url: String?): Boolean = origin(url) in bridgeOrigins
    fun isInternalNavigation(url: String?): Boolean =
        origin(url)?.removePrefix("https://") in navigationHosts

    fun canDownload(url: String, pageUrl: String?): Boolean = when {
        url.startsWith("blob:") -> isBridgeOrigin(pageUrl) && origin(url.removePrefix("blob:")) == origin(pageUrl)
        url.startsWith("data:") -> isBridgeOrigin(pageUrl)
        else -> origin(url) != null
    }
}
