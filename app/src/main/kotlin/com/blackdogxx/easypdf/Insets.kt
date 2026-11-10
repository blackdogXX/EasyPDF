package com.blackdogxx.easypdf

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding

/**
 * Экран рисуется «от края до края»: отступ под строку состояния даём отдельной
 * полоской, боковые вырезы — отступами панели, низ (панель навигации и клавиатура)
 * отдаём вызывающему.
 */
fun applyBarInsets(root: View, statusSpacer: View, toolbar: View, onBottom: (bottom: Int) -> Unit = {}) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        statusSpacer.updateLayoutParams { height = bars.top }
        toolbar.updatePadding(left = bars.left, right = bars.right)
        onBottom(maxOf(bars.bottom, ime.bottom))
        insets
    }
}
