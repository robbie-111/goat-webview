package com.goatwebview.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import com.goatwebview.sdk.WebViewController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 샘플은 웹 페이지 디버깅을 위해 웹 콘솔 로그를 logcat(GoatWebView 태그)으로 출력한다.
        WebViewController.setForwardWebConsoleToNativeOutput(true)
        setContent {
            MaterialTheme { DemoScreen() }
        }
    }
}
