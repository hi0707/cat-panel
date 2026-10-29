package com.a07.catpanel.panel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.a07.catpanel.widget.CatPanelWidgetProvider
import kotlinx.coroutines.launch

class PanelViewModel(application: Application) : AndroidViewModel(application) {
  private val preferences = PanelPreferences(application)
  private val api = PanelApi()

  var uiState by mutableStateOf(
    PanelUiState(
      config = preferences.loadConfig(),
      messages = preferences.loadMessages(),
      draft = preferences.takePendingDraft(),
    ),
  )
    private set

  fun updateDraft(value: String) {
    uiState = uiState.copy(draft = value, requestState = RequestState.Idle)
  }

  fun useSpeechResult(value: String) {
    uiState = uiState.copy(draft = value.trim(), requestState = RequestState.Idle)
  }

  fun showError(message: String) {
    uiState = uiState.copy(requestState = RequestState.Error(message))
  }

  fun saveConfig(endpoint: String, accessKey: String) {
    val config = PanelConfig(endpoint = endpoint.trim().removeSuffix("/"), accessKey = accessKey.trim())
    preferences.saveConfig(config)
    uiState = uiState.copy(config = config, requestState = RequestState.Idle)
    CatPanelWidgetProvider.updateAll(getApplication())
  }

  fun sendDraft() {
    val text = uiState.draft.trim()
    if (text.isEmpty() || uiState.requestState == RequestState.Sending) return
    if (!uiState.isConfigured) {
      uiState = uiState.copy(requestState = RequestState.Error("先在设置里填入面板地址（家里可填 http://电脑IP:8899）"))
      return
    }

    val config = uiState.config
    val messagesBeforeSend = uiState.messages
    val sent = ChatMessage(ChatMessage.Role.User, text, System.currentTimeMillis())
    val messagesAfterSend = (uiState.messages + sent).takeLast(MAX_VISIBLE_HISTORY)
    uiState = uiState.copy(messages = messagesAfterSend, draft = "", requestState = RequestState.Sending)
    preferences.saveMessages(messagesAfterSend)

    viewModelScope.launch {
      runCatching { api.send(config, text) }
        .onSuccess { reply ->
          val received = ChatMessage(ChatMessage.Role.Cat, reply, System.currentTimeMillis())
          val updated = (uiState.messages + received).takeLast(MAX_VISIBLE_HISTORY)
          uiState = uiState.copy(messages = updated, requestState = RequestState.Idle)
          preferences.saveMessages(updated)
          CatPanelWidgetProvider.updateAll(getApplication())
        }
        .onFailure { error ->
          uiState = uiState.copy(
            messages = messagesBeforeSend,
            draft = text,
            requestState = RequestState.Error(error.message ?: "发送失败，请检查网络或面板状态"),
          )
          preferences.saveMessages(messagesBeforeSend)
        }
    }
  }

  fun clearHistory() {
    preferences.saveMessages(emptyList())
    uiState = uiState.copy(messages = emptyList(), requestState = RequestState.Idle)
    CatPanelWidgetProvider.updateAll(getApplication())
  }

  companion object {
    private const val MAX_VISIBLE_HISTORY = 60
  }
}
