package com.blackdogxx.easypdf

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding

/**
 * Экран рисуется «от края до края»: отступ под строку состояния даём отдельной
 * полоской, боковые вырезы — отступами панели. Сами отступы не поглощаем, чтобы
 * вложенные элементы (просмотрщик, поиск, клавиатура) получили их тоже.
 */
fun applyBarInsets(root: View, statusSpacer: View, toolbar: View, onInsets: (bottom: Int) -> Unit = {}) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        statusSpacer.updateLayoutParams { height = bars.top }
        toolbar.updatePadding(left = bars.left, right = bars.right)
        onInsets(bars.bottom)
        insets
    }
}
