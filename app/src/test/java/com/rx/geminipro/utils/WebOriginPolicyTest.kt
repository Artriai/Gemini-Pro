package com.rx.geminipro.utils

import com.rx.geminipro.utils.network.WebOriginPolicy
import org.junit.Assert.*
import org.junit.Test

class WebOriginPolicyTest {
    @Test fun trustedOriginsAreExactAndHttpsOnly() {
        assertTrue(WebOriginPolicy.isBridgeOrigin("https://aistudio.google.com/prompts/123"))
        assertTrue(WebOriginPolicy.isBridgeOrigin("https://labs.google:443/fx"))
        listOf("http://aistudio.google.com", "https://aistudio.google.com.evil.test",
            "https://aistudio.google.com@evil.test", "https://aistudio.google.com:8443",
            "https://accounts.google.com", "file:///tmp/test.html", "not a URL").forEach {
            assertFalse(it, WebOriginPolicy.isBridgeOrigin(it))
        }
    }

    @Test fun blobMustBelongToCurrentTrustedOrigin() {
        assertTrue(WebOriginPolicy.canDownload("blob:https://aistudio.google.com/id", "https://aistudio.google.com/prompts/1"))
        assertFalse(WebOriginPolicy.canDownload("blob:https://evil.test/id", "https://aistudio.google.com"))
        assertFalse(WebOriginPolicy.canDownload("blob:https://aistudio.google.com/id", "https://labs.google"))
        assertFalse(WebOriginPolicy.canDownload("data:text/plain;base64,aA==", "https://evil.test"))
        assertFalse(WebOriginPolicy.canDownload("file:///etc/passwd", "https://aistudio.google.com"))
    }
}
