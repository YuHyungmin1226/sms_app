package com.example.sms_app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val SEND_PROGRESS_PREFS = "send_progress"
private const val KEY_MESSAGE_TYPE = "message_type"
private const val KEY_TOTAL_RECIPIENTS = "total_recipients"
private const val KEY_COMPLETED_RECIPIENTS = "completed_recipients"
private const val KEY_SUCCESSFUL_RECIPIENTS = "successful_recipients"
private const val KEY_CURRENT_RECIPIENT = "current_recipient"
private const val KEY_STATUS_TEXT = "status_text"
private const val KEY_IS_ACTIVE = "is_active"
private const val KEY_IS_ERROR = "is_error"

data class SendProgressState(
    val messageType: MessageType,
    val totalRecipients: Int,
    val completedRecipients: Int,
    val successfulRecipients: Int,
    val currentRecipient: String? = null,
    val statusText: String,
    val isActive: Boolean,
    val isError: Boolean = false
) {
    val progressFraction: Float
        get() = when {
            totalRecipients <= 0 -> 0f
            completedRecipients >= totalRecipients -> 1f
            else -> completedRecipients.toFloat() / totalRecipients.toFloat()
        }
}

object SendProgressTracker {
    private val _state = MutableStateFlow<SendProgressState?>(null)
    val state: StateFlow<SendProgressState?> = _state.asStateFlow()

    fun isRunning(): Boolean = _state.value?.isActive == true

    fun refresh(context: Context) {
        _state.value = readState(prefs(context))
    }

    fun registerListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun start(
        context: Context,
        messageType: MessageType,
        totalRecipients: Int,
        currentRecipient: String?,
        statusText: String
    ) {
        persist(
            context = context,
            messageType = messageType,
            totalRecipients = totalRecipients,
            completedRecipients = 0,
            successfulRecipients = 0,
            currentRecipient = currentRecipient,
            statusText = statusText,
            isActive = true,
            isError = false
        )
    }

    fun update(
        context: Context,
        messageType: MessageType,
        totalRecipients: Int,
        completedRecipients: Int,
        successfulRecipients: Int,
        currentRecipient: String?,
        statusText: String
    ) {
        persist(
            context = context,
            messageType = messageType,
            totalRecipients = totalRecipients,
            completedRecipients = completedRecipients,
            successfulRecipients = successfulRecipients,
            currentRecipient = currentRecipient,
            statusText = statusText,
            isActive = true,
            isError = false
        )
    }

    fun complete(
        context: Context,
        messageType: MessageType,
        totalRecipients: Int,
        completedRecipients: Int,
        successfulRecipients: Int,
        currentRecipient: String?,
        statusText: String
    ) {
        persist(
            context = context,
            messageType = messageType,
            totalRecipients = totalRecipients,
            completedRecipients = completedRecipients,
            successfulRecipients = successfulRecipients,
            currentRecipient = currentRecipient,
            statusText = statusText,
            isActive = false,
            isError = false
        )
    }

    fun fail(
        context: Context,
        messageType: MessageType,
        totalRecipients: Int,
        completedRecipients: Int,
        successfulRecipients: Int,
        currentRecipient: String?,
        statusText: String
    ) {
        persist(
            context = context,
            messageType = messageType,
            totalRecipients = totalRecipients,
            completedRecipients = completedRecipients,
            successfulRecipients = successfulRecipients,
            currentRecipient = currentRecipient,
            statusText = statusText,
            isActive = false,
            isError = true
        )
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        _state.value = null
    }

    private fun persist(
        context: Context,
        messageType: MessageType,
        totalRecipients: Int,
        completedRecipients: Int,
        successfulRecipients: Int,
        currentRecipient: String?,
        statusText: String,
        isActive: Boolean,
        isError: Boolean
    ) {
        prefs(context).edit()
            .putString(KEY_MESSAGE_TYPE, messageType.name)
            .putInt(KEY_TOTAL_RECIPIENTS, totalRecipients)
            .putInt(KEY_COMPLETED_RECIPIENTS, completedRecipients.coerceAtMost(totalRecipients))
            .putInt(KEY_SUCCESSFUL_RECIPIENTS, successfulRecipients.coerceAtMost(totalRecipients))
            .putString(KEY_CURRENT_RECIPIENT, currentRecipient)
            .putString(KEY_STATUS_TEXT, statusText)
            .putBoolean(KEY_IS_ACTIVE, isActive)
            .putBoolean(KEY_IS_ERROR, isError)
            .apply()
        refresh(context)
    }

    private fun readState(prefs: SharedPreferences): SendProgressState? {
        val typeName = prefs.getString(KEY_MESSAGE_TYPE, null) ?: return null
        val messageType = runCatching { MessageType.valueOf(typeName) }.getOrNull() ?: return null
        return SendProgressState(
            messageType = messageType,
            totalRecipients = prefs.getInt(KEY_TOTAL_RECIPIENTS, 0),
            completedRecipients = prefs.getInt(KEY_COMPLETED_RECIPIENTS, 0),
            successfulRecipients = prefs.getInt(KEY_SUCCESSFUL_RECIPIENTS, 0),
            currentRecipient = prefs.getString(KEY_CURRENT_RECIPIENT, null),
            statusText = prefs.getString(KEY_STATUS_TEXT, "").orEmpty(),
            isActive = prefs.getBoolean(KEY_IS_ACTIVE, false),
            isError = prefs.getBoolean(KEY_IS_ERROR, false)
        )
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(SEND_PROGRESS_PREFS, Context.MODE_PRIVATE)
}
