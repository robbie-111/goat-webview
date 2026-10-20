package com.goatwebview.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import kotlin.math.roundToInt

/**
 * 웹뷰가 로드되기 전과 실패했을 때 웹뷰 아래에 보이는 화면. 닫기 버튼은 항상 보인다(iOS FallbackView와 같음).
 * 풀스크린으로 연 웹뷰가 로드에 실패해도 사용자가 닫을 수 있게 한다. 리소스 파일 없이 플랫폼 기본 drawable을 쓴다.
 * 색은 호스트 테마와 무관하게 [theme]으로 정하며 iOS 시스템 색(systemBackground·label·secondaryLabel)과 같은 값을 쓴다.
 */
@SuppressLint("ViewConstructor")
internal class FallbackView(context: Context, onClose: () -> Unit) : FrameLayout(context) {
    enum class State { LOADING, LOADED, FAILED }

    var state = State.LOADING
        set(value) {
            field = value
            updateState()
        }

    var theme = FallbackTheme.LIGHT
        set(value) {
            field = value
            applyTheme()
        }

    private val progress = ProgressBar(context).apply { isIndeterminate = true }
    private val failure = ImageView(context).apply { setImageResource(android.R.drawable.ic_dialog_alert) }
    val closeButton = ImageButton(context).apply {
        setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        setBackgroundColor(Color.TRANSPARENT)
        contentDescription = "Close"
        setOnClickListener { onClose() }
    }

    init {
        addView(progress, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(failure, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(closeButton, LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
        })
        applyTheme()
        updateState()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (theme == FallbackTheme.SYSTEM) applyTheme()
    }

    private fun applyTheme() {
        val dark = when (theme) {
            FallbackTheme.LIGHT -> false
            FallbackTheme.DARK -> true
            FallbackTheme.SYSTEM ->
                resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val secondary = ColorStateList.valueOf(if (dark) SECONDARY_DARK else SECONDARY_LIGHT)
        setBackgroundColor(if (dark) Color.BLACK else Color.WHITE)
        closeButton.imageTintList = ColorStateList.valueOf(if (dark) Color.WHITE else Color.BLACK)
        failure.imageTintList = secondary
        progress.indeterminateTintList = secondary
    }

    private fun updateState() {
        progress.visibility = if (state == State.LOADING) VISIBLE else GONE
        failure.visibility = if (state == State.FAILED) VISIBLE else GONE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        // iOS secondaryLabel (라이트 #3C3C43, 다크 #EBEBF5, 알파 0.6)
        const val SECONDARY_LIGHT = 0x993C3C43.toInt()
        const val SECONDARY_DARK = 0x99EBEBF5.toInt()
    }
}
