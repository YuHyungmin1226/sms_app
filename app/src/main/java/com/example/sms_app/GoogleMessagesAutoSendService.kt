package com.example.sms_app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray

private const val GOOGLE_MESSAGES_PACKAGE = "com.google.android.apps.messaging"
private const val PREFS_NAME = "mms_auto_send"
private const val KEY_RECIPIENTS = "recipients"
private const val KEY_MESSAGE = "message"
private const val KEY_ATTACHMENT_URI = "attachment_uri"
private const val KEY_MIME_TYPE = "mime_type"
private const val KEY_CURRENT_INDEX = "current_index"
private const val KEY_ARMED_UNTIL = "armed_until"
private const val AUTO_SEND_TIMEOUT_MS = 10 * 60_000L
private const val COMPOSER_READY_DELAY_MS = 1_500L
private const val BUTTON_RETRY_DELAY_MS = 700L
private const val MAX_BUTTON_FIND_ATTEMPTS = 28
private const val NEXT_RECIPIENT_DELAY_MS = 2_000L
private const val TAG = "SmsAppAutoSend"

object MmsAutoSendController {
    fun startQueue(
        context: Context,
        recipients: List<String>,
        message: String,
        attachmentUri: Uri,
        mimeType: String
    ): Boolean {
        if (recipients.isEmpty()) return false

        val recipientJson = JSONArray().apply {
            recipients.forEach(::put)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECIPIENTS, recipientJson.toString())
            .putString(KEY_MESSAGE, message)
            .putString(KEY_ATTACHMENT_URI, attachmentUri.toString())
            .putString(KEY_MIME_TYPE, mimeType)
            .putInt(KEY_CURRENT_INDEX, 0)
            .putLong(KEY_ARMED_UNTIL, System.currentTimeMillis() + AUTO_SEND_TIMEOUT_MS)
            .apply()

        return launchCurrentRecipient(context)
    }

    fun isArmed(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val armedUntil = prefs.getLong(KEY_ARMED_UNTIL, 0L)
        if (armedUntil <= System.currentTimeMillis()) {
            clear(context)
            return false
        }
        return currentRecipient(context) != null
    }

    fun advance(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val nextIndex = prefs.getInt(KEY_CURRENT_INDEX, 0) + 1
        val recipientCount = recipients(prefs.getString(KEY_RECIPIENTS, null)).size
        if (nextIndex >= recipientCount) {
            clear(context)
            return false
        }
        prefs.edit().putInt(KEY_CURRENT_INDEX, nextIndex).apply()
        return true
    }

    fun launchCurrentRecipient(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val recipient = currentRecipient(context) ?: return false
        val attachmentUri = prefs.getString(KEY_ATTACHMENT_URI, null)?.let(Uri::parse) ?: return false
        val message = prefs.getString(KEY_MESSAGE, "").orEmpty()
        val mimeType = prefs.getString(KEY_MIME_TYPE, "image/*") ?: "image/*"

        context.grantUriPermission(
            GOOGLE_MESSAGES_PACKAGE,
            attachmentUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            setPackage(GOOGLE_MESSAGES_PACKAGE)
            putExtra("address", recipient)
            putExtra("sms_body", message)
            putExtra(Intent.EXTRA_TEXT, message)
            putExtra(Intent.EXTRA_STREAM, attachmentUri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse {
            clear(context)
            false
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun currentRecipient(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val allRecipients = recipients(prefs.getString(KEY_RECIPIENTS, null))
        return allRecipients.getOrNull(prefs.getInt(KEY_CURRENT_INDEX, 0))
    }

    private fun recipients(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return List(array.length()) { index -> array.optString(index) }.filter(String::isNotBlank)
    }
}

class GoogleMessagesAutoSendService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var actionInProgress = false
    private var findAttempts = 0

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != GOOGLE_MESSAGES_PACKAGE) return
        if (!MmsAutoSendController.isArmed(this) || actionInProgress) return

        actionInProgress = true
        findAttempts = 0
        Log.i(TAG, "Google Messages composer detected; waiting for send button")
        handler.postDelayed({ clickCurrentSendButton() }, COMPOSER_READY_DELAY_MS)
    }

    private fun clickCurrentSendButton() {
        if (!MmsAutoSendController.isArmed(this)) {
            actionInProgress = false
            return
        }

        val sendButton = rootInActiveWindow?.let(::findSendButton)
        if (sendButton == null) {
            findAttempts++
            if (findAttempts < MAX_BUTTON_FIND_ATTEMPTS) {
                handler.postDelayed({ clickCurrentSendButton() }, BUTTON_RETRY_DELAY_MS)
            } else {
                Log.e(TAG, "Send button not found after $findAttempts attempts")
                actionInProgress = false
            }
            return
        }

        val clicked = sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(
            TAG,
            "Send button click result=$clicked id=${sendButton.viewIdResourceName} " +
                "label=${sendButton.contentDescription ?: sendButton.text}"
        )
        if (!clicked) {
            findAttempts++
            if (findAttempts < MAX_BUTTON_FIND_ATTEMPTS) {
                handler.postDelayed({ clickCurrentSendButton() }, BUTTON_RETRY_DELAY_MS)
            } else {
                actionInProgress = false
            }
            return
        }

        if (!MmsAutoSendController.advance(this)) {
            actionInProgress = false
            return
        }

        handler.postDelayed({
            if (!MmsAutoSendController.launchCurrentRecipient(this)) {
                Log.e(TAG, "Failed to launch the next MMS recipient")
                actionInProgress = false
                return@postDelayed
            }
            findAttempts = 0
            handler.postDelayed({ clickCurrentSendButton() }, COMPOSER_READY_DELAY_MS)
        }, NEXT_RECIPIENT_DELAY_MS)
    }

    override fun onInterrupt() {
        handler.removeCallbacksAndMessages(null)
        actionInProgress = false
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val viewIds = listOf(
            "$GOOGLE_MESSAGES_PACKAGE:id/send_message_button_icon",
            "$GOOGLE_MESSAGES_PACKAGE:id/send_message_button",
            "$GOOGLE_MESSAGES_PACKAGE:id/send_button"
        )
        viewIds.forEach { id ->
            root.findAccessibilityNodeInfosByViewId(id)
                .firstOrNull { it.isVisibleToUser && it.isEnabled }
                ?.let(::clickableNode)
                ?.let { return it }
        }

        return breadthFirst(root).firstOrNull { node ->
            if (!node.isVisibleToUser || !node.isEnabled) return@firstOrNull false
            val resourceId = node.viewIdResourceName.orEmpty().lowercase()
            val label = listOf(node.text, node.contentDescription)
                .joinToString(" ") { it?.toString().orEmpty() }
                .trim()
                .lowercase()
            resourceId.contains("send") ||
                label == "보내기" || label.startsWith("보내기 ") || label.contains(" 보내기") ||
                label == "send" || label.startsWith("send ") || label.contains(" send")
        }?.let(::clickableNode)
    }

    private fun clickableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(4) {
            if (current?.isClickable == true) return current
            current = current?.parent
        }
        return null
    }

    private fun breadthFirst(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            yield(node)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
    }
}
