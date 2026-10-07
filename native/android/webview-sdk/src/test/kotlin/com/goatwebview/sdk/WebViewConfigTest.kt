package com.goatwebview.sdk

import org.junit.Assert.*
import org.junit.Test

class WebViewConfigTest {
    @Test fun onlyExactHttpOriginsAreAllowed() {
        listOf("*", "https://*.example.com", "https://example.com/", "https://user@example.com",
            "https://example.com?key=value", "https://example.com#fragment", "file://example.com",
            "https://example.com:0", "https://example.com:65536").forEach { origin ->
            assertThrows(origin, IllegalArgumentException::class.java) { WebViewConfig(setOf(origin)) }
        }
        WebViewConfig(setOf("http://localhost:8080", "https://example.com"))
    }

    @Test fun scriptsRequireAnOriginAndInputsAreCopied() {
        assertThrows(IllegalArgumentException::class.java) { WebViewConfig(documentStartScripts = listOf("1")) }
        assertThrows(IllegalArgumentException::class.java) { WebViewConfig(setOf("https://example.com"), documentStartScripts = listOf(" ")) }
        val origins = mutableSetOf("https://example.com")
        val scripts = mutableListOf("window.ready = true")
        val config = WebViewConfig(origins, documentStartScripts = scripts)
        origins.clear()
        scripts.clear()
        assertEquals(setOf("https://example.com"), config.allowedMessageOrigins)
        assertEquals(listOf("window.ready = true"), config.documentStartScripts)
    }
}
