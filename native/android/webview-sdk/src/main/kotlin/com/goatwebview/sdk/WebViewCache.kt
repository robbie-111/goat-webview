package com.goatwebview.sdk

import android.content.Context
import android.os.Looper
import android.webkit.WebView
import androidx.annotation.MainThread

/**
 * 앱의 모든 WebView가 공유하는 메모리·디스크 리소스 캐시를 비운다.
 * 웹뷰가 있으면 [WebViewController.clearCache]를 쓰고, 아직 만들기 전에는 이 함수를 쓴다.
 */
object WebViewCache {
    @MainThread
    fun clear(context: Context) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "웹뷰 캐시는 메인 스레드에서 초기화해야 합니다." }

        // 입력 화면에는 WebView가 없으므로 임시 인스턴스로 앱 공용 캐시를 비운다.
        val webView = WebView(context)
        try {
            webView.clearCache(true)
        } finally {
            webView.destroy()
        }
    }
}
