package com.detoxin.app.service.detector

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/**
 * До API 34 каждый полученный [AccessibilityNodeInfo] берётся из системного пула и
 * удерживает Binder-ресурсы до явного `recycle()`. Начиная с API 34 метод устарел
 * и ничего не делает, поэтому вызывается только на старых версиях.
 */
internal fun AccessibilityNodeInfo.recycleCompat() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        @Suppress("DEPRECATION")
        recycle()
    }
}
