package com.goatwebview.sdk

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.net.URI

/**
 * 웹뷰 생성 시 적용하는 설정.
 *
 * [allowedMessageOrigins]에는 경로가 없는 정확한 HTTP(S) 출처만 지정한다.
 * 빈 집합이면 JavaScript 메시지 수신 객체를 노출하지 않는다.
 * [requestInterceptor]는 WebView의 작업 스레드에서 호출되므로 UI를 조작하지 않는다.
 * [documentStartScripts]는 허용 출처의 문서 시작 시 실행한다. iframe 제외는 스크립트에서도 검사해야 한다.
 * 스크립트를 요청했지만 System WebView가 지원하지 않으면 메시지 브리지 전체를 비활성화한다.
 */
class WebViewConfig(
    allowedMessageOrigins: Set<String> = emptySet(),
    val requestInterceptor: ((WebResourceRequest) -> WebResourceResponse?)? = null,
    documentStartScripts: List<String> = emptyList(),
) {
    val allowedMessageOrigins: Set<String> = allowedMessageOrigins.toSet()
    val documentStartScripts: List<String> = documentStartScripts.toList()

    init {
        require(this.documentStartScripts.isEmpty() || this.allowedMessageOrigins.isNotEmpty()) {
            "문서 시작 스크립트에는 허용 출처가 필요합니다."
        }
        require(this.documentStartScripts.all { it.isNotBlank() }) { "빈 스크립트는 등록할 수 없습니다." }
        requireValidOrigins(this.allowedMessageOrigins)
    }

    internal companion object {
        /** 경로 없는 정확한 HTTP(S) 출처인지 검사한다. 아니면 IllegalArgumentException을 던진다. */
        fun requireValidOrigins(origins: Set<String>) = origins.forEach { origin ->
            val uri = runCatching { URI(origin) }.getOrNull()
            require(
                uri != null &&
                    uri.scheme in setOf("https", "http") &&
                    !uri.host.isNullOrEmpty() &&
                    uri.rawUserInfo == null &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null &&
                    uri.rawPath.isNullOrEmpty() &&
                    (uri.port == -1 || uri.port in 1..65535) &&
                    '*' !in origin,
            ) { "메시지 출처에는 경로 없는 정확한 HTTP(S) 출처가 필요합니다: " + origin }
        }
    }
}
