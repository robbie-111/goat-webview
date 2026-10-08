package com.goatwebview.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import kotlin.math.roundToInt

/**
 * 웹뷰가 로드되기 전과 실패했을 때 웹뷰 아래에 보이는 화면. 닫기 버튼은 항상 보인다(iOS FallbackView와 같음).
 * 풀스크린으로 연 웹뷰가 로드에 실패해도 사용자가 닫을 수 있게 한다. 리소스 파일 없이 플랫폼 기본 drawable을 쓴다.
 */
@SuppressLint("ViewConstructor")
internal class FallbackView(context: Context, onClose: () -> Unit) : FrameLayout(context) {
    enum class State { LOADING, LOADED, FAILED }

    var state = State.LOADING
        set(value) {
            field = value
            updateState()
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
        setBackgroundColor(themeColor(context, android.R.attr.colorBackground, Color.WHITE))
        addView(progress, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(failure, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(closeButton, LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
        })
        updateState()
    }

    private fun updateState() {
        progress.visibility = if (state == State.LOADING) VISIBLE else GONE
        failure.visibility = if (state == State.FAILED) VISIBLE else GONE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        fun themeColor(context: Context, attr: Int, fallback: Int): Int {
            val value = TypedValue()
            return if (context.theme.resolveAttribute(attr, value, true) &&
                value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT
            ) value.data else fallback
        }
    }
}
