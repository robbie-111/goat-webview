package com.goatwebview.sample

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.goatwebview.sdk.WebViewCache
import com.goatwebview.sdk.WebViewConfig
import com.goatwebview.sdk.WebViewController
import com.goatwebview.sdk.WebViewEvent

private const val START_URL = "https://example.com"

/** setTopSafeAreaColor에 넘기는 r, g, b, a(각 0..1). Off는 호출하지 않는다(SDK 기본: 상단을 투명하게 비움). */
private enum class SafeArea(val color: FloatArray?) {
    Off(null),
    Black(floatArrayOf(0f, 0f, 0f, 1f)),
    White(floatArrayOf(1f, 1f, 1f, 1f)),
    Red(floatArrayOf(1f, 0f, 0f, 1f)),
}

private fun WebViewController.applySafeArea(preset: SafeArea) {
    preset.color?.let { (r, g, b, a) -> setTopSafeAreaColor(r, g, b, a) }
}

/**
 * webview-sdk 기능을 시험하는 화면. Load는 웹뷰를 숨긴 채 만들어 URL을 로드하고, Show는 그 웹뷰를 표시한다.
 * Fullscreen은 설정 화면을 치우고 전체 화면에, Popup은 설정 화면 위에 마진을 두고 띄운다(바깥 설정 화면은 계속 조작 가능).
 * 표시 중 뒤로가기는 웹 기록이 있으면 웹에서 뒤로 가고, 없으면 웹뷰를 destroy한다.
 */
@Composable
fun DemoScreen() {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var input by rememberSaveable { mutableStateOf(START_URL) }
    var safeArea by rememberSaveable { mutableStateOf(SafeArea.Black) }
    var scrollBounce by rememberSaveable { mutableStateOf(true) }
    // 팝업은 safe area와 겹치지 않을 만큼 마진을 준다. 바깥에는 배경을 두지 않아 창 배경이 그대로 보인다.
    var popup by rememberSaveable { mutableStateOf(false) }
    // null이면 웹뷰가 없다. 회전 후에는 이 URL로 웹뷰를 다시 만든다.
    var loadedUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var showing by rememberSaveable { mutableStateOf(false) }
    var controller by remember { mutableStateOf<WebViewController?>(null) }
    var navigation by remember { mutableStateOf(WebViewEvent.NavigationChanged(null, false, false)) }
    var status by remember { mutableStateOf("웹뷰 없음") }

    fun load() {
        focusManager.clearFocus()
        val target = input.trim().let { if ("://" in it) it else "https://$it" }
        if (target.toUri().scheme?.lowercase() !in setOf("https", "http")) {
            status = "HTTP(S) URL만 사용할 수 있습니다."
            return
        }
        controller?.loadUrl(target)
        loadedUrl = target
    }

    fun show() {
        if (loadedUrl == null) load()
        if (loadedUrl == null) return
        controller?.setVisible(true)
        showing = true
    }

    // loadedUrl이 null이 되면 WebViewHost가 composition에서 빠지고 onRelease에서 destroy된다.
    fun close() {
        showing = false
        loadedUrl = null
        controller = null
        navigation = WebViewEvent.NavigationChanged(null, false, false)
        status = "웹뷰를 닫았습니다."
    }

    fun clearCache() {
        status = try {
            val current = controller
            if (current != null) current.clearCache(true) else WebViewCache.clear(context)
            "캐시를 비웠습니다."
        } catch (e: RuntimeException) {
            "캐시를 비우지 못했습니다: ${e.message}"
        }
    }

    BackHandler(enabled = showing) {
        if (navigation.canGoBack) controller?.goBack() else close()
    }

    Box(Modifier.fillMaxSize()) {
        // 설정 화면이 아래, 웹뷰가 위. Popup이면 Show 중에도 설정 화면을 남겨 팝업 바깥에 보이게 한다.
        if (!showing || popup) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Goat WebView", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Text("Top safe area color")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SafeArea.entries.forEach { preset ->
                        FilterChip(
                            selected = safeArea == preset,
                            onClick = {
                                safeArea = preset
                                controller?.applySafeArea(preset)
                            },
                            label = { Text(preset.name) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Scroll bounce", Modifier.weight(1f))
                    Switch(
                        checked = scrollBounce,
                        onCheckedChange = {
                            scrollBounce = it
                            controller?.setScrollBounceEnabled(it)
                        },
                    )
                }
                Text("Display")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !popup, onClick = { popup = false }, label = { Text("Fullscreen") })
                    FilterChip(selected = popup, onClick = { popup = true }, label = { Text("Popup") })
                }
                OutlinedButton(onClick = ::clearCache, modifier = Modifier.fillMaxWidth()) { Text("Clear Cache") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::load, modifier = Modifier.weight(1f)) { Text("Load") }
                    Button(onClick = ::show, modifier = Modifier.weight(1f)) { Text("Show") }
                }
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }
        // 웹뷰는 inset을 직접 처리한다. Fullscreen이면 padding 없이 전체 화면, Popup이면 safe area와 겹치지 않는 마진을 둔다.
        loadedUrl?.let { url ->
            WebViewHost(
                url = url,
                visible = showing,
                safeArea = safeArea,
                scrollBounce = scrollBounce,
                onCreated = { controller = it },
                onEvent = { event ->
                    when (event) {
                        is WebViewEvent.PageStarted -> status = "Started ${event.url}"
                        is WebViewEvent.PageFinished -> status = "Finished ${event.url} (${event.statusCode})"
                        is WebViewEvent.LoadingErrorReceived -> status = "Error ${event.errorCode}: ${event.errorMessage}"
                        is WebViewEvent.NavigationChanged -> {
                            navigation = event
                            event.url?.let { loadedUrl = it }
                        }
                        else -> Unit
                    }
                },
                modifier = if (popup) {
                    Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 96.dp)
                } else {
                    Modifier.fillMaxSize()
                },
            )
        }
    }
}

/** AndroidView가 컨트롤러를 소유한다. 생성 시에만 설정을 적용하고 로드하며, 이후 변경은 컨트롤러를 직접 호출한다. */
@Composable
private fun WebViewHost(
    url: String,
    visible: Boolean,
    safeArea: SafeArea,
    scrollBounce: Boolean,
    onCreated: (WebViewController) -> Unit,
    onEvent: (WebViewEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnEvent = rememberUpdatedState(onEvent)
    AndroidView(
        factory = { context ->
            WebViewHostView(context, lifecycle) { currentOnEvent.value(it) }.also {
                val controller = it.controller
                controller.applySafeArea(safeArea)
                controller.setScrollBounceEnabled(scrollBounce)
                controller.setVisible(visible)
                onCreated(controller)
                controller.loadUrl(url)
            }
        },
        modifier = modifier,
        onRelease = { it.release() },
        update = {},
    )
}

// XML로 inflate하지 않고 AndroidView.factory에서 필수 의존성을 전달한다.
@SuppressLint("ViewConstructor")
private class WebViewHostView(
    context: Context,
    private val lifecycle: Lifecycle,
    onEvent: (WebViewEvent) -> Unit,
) : FrameLayout(context), DefaultLifecycleObserver {
    val controller = WebViewController(context, WebViewConfig(), onEvent)
    private var released = false

    // 숨김 상태(Load 후 Show 전)의 웹뷰가 아래 설정 화면의 터치를 가로채지 않게 한다.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        !controller.isDestroyed && controller.view.isShown && super.dispatchTouchEvent(event)

    init {
        addView(controller.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        lifecycle.addObserver(this)
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
    }

    override fun onResume(owner: LifecycleOwner) {
        if (!controller.isDestroyed) controller.resume()
    }

    override fun onPause(owner: LifecycleOwner) {
        if (!controller.isDestroyed) controller.pause()
    }

    override fun onDestroy(owner: LifecycleOwner) = release()

    fun release() {
        if (released) return
        released = true
        lifecycle.removeObserver(this)
        controller.destroy()
    }
}
