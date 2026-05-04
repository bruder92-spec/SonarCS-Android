package com.sonar.android.inject

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import android.accessibilityservice.AccessibilityService

// Port of TextTyper.cs — inserts text via clipboard + ACTION_PASTE.
// Fallback chain: ACTION_PASTE → ACTION_SET_TEXT → Toast.
object TextInjector {

    fun paste(service: AccessibilityService, text: String) {
        val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val prevClip = cm.primaryClip

        cm.setPrimaryClip(ClipData.newPlainText("sonar", text))

        val node = service.rootInActiveWindow
            ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        val inserted = when {
            node == null -> false
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE) -> true
            else -> {
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                    )
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        }

        // Restore previous clipboard after 300 ms
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (prevClip != null) {
                    cm.setPrimaryClip(prevClip)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    cm.clearPrimaryClip()
                }
            } catch (_: Exception) {}
        }, 300)

        if (!inserted) {
            Toast.makeText(service, text, Toast.LENGTH_LONG).show()
        }
    }
}
