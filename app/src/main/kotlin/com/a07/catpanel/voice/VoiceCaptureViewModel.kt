package com.a07.catpanel.voice

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.a07.catpanel.panel.ChatMessage
import com.a07.catpanel.panel.PanelApi
import com.a07.catpanel.panel.PanelEndpoint
import com.a07.catpanel.panel.PanelPreferences
import com.a07.catpanel.widget.CatPanelWidgetProvider
import kotlinx.coroutines.launch

enum class CaptureState {
  Idle,
  Listening,
  Finalizing,
}

data class VoiceCaptureUiState(
  val draft: String = "",
  val livePartial: String = "",
  val captureState: CaptureState = CaptureState.Idle,
  val sending: Boolean = false,
  val reply: String? = null,
  val error: String? = null,
) {
  val shownText: String
    get() = appendRecognizedText(draft, livePartial)
}

class VoiceCaptureViewModel(application: Application) : AndroidViewModel(application) {
  private val preferences = PanelPreferences(application)
  private val api = PanelApi()

  var uiState by mutableStateOf(VoiceCaptureUiState())
    private set

  /** 开始录音前的草稿；结束时要拼回它，不能覆盖用户原来的内容 */
  private var draftBeforeCapture = ""
  /** 已经本地收尾、还在等服务端最终文字 */
  private var awaitingFinal = false

  fun beginCapture() {
    if (uiState.sending) return
    // 上一轮已答完：这次开口算全新一轮，不要带着上一条回答出来
    if (uiState.reply != null) {
      uiState = VoiceCaptureUiState()
    }
    draftBeforeCapture = uiState.draft.trim()
    awaitingFinal = false
    uiState = uiState.copy(
      livePartial = "",
      captureState = CaptureState.Listening,
      error = null,
    )
  }

  fun markFinalizing() {
    if (uiState.captureState == CaptureState.Listening) {
      uiState = uiState.copy(captureState = CaptureState.Finalizing)
    }
  }

  fun updatePartial(text: String) {
    if (uiState.captureState != CaptureState.Idle) {
      uiState = uiState.copy(livePartial = text.trim(), error = null)
    }
  }

  /** 已本地收尾时写进草稿的那份文本，用来判断用户后来有没有改过 */
  private var draftAtFinalize = ""

  /** 点「暂停并编辑」：立刻把已经识别到的字并进草稿，不等服务端（否则按钮会灰着等十几秒） */
  fun finalizeNow() {
    if (uiState.captureState == CaptureState.Idle) return
    awaitingFinal = true
    val merged = appendRecognizedText(draftBeforeCapture, uiState.livePartial.trim())
    draftAtFinalize = merged
    uiState = uiState.copy(
      draft = merged,
      livePartial = "",
      captureState = CaptureState.Idle,
      error = null,
    )
  }

  fun completeCapture(text: String?) {
    val recognized = text?.trim().takeUnless { it.isNullOrEmpty() } ?: uiState.livePartial
    if (awaitingFinal) {
      awaitingFinal = false
      val untouched = uiState.draft == draftAtFinalize
      val merged = appendRecognizedText(draftBeforeCapture, recognized)
      uiState = uiState.copy(
        draft = if (untouched && recognized.isNotBlank()) merged else uiState.draft,
        livePartial = "",
        captureState = CaptureState.Idle,
        error = null,
      )
      return
    }
    uiState = uiState.copy(
      draft = appendRecognizedText(uiState.draft, recognized),
      livePartial = "",
      captureState = CaptureState.Idle,
      error = null,
    )
  }

  fun finishWithPartial(error: String) {
    val hadText = uiState.livePartial.isNotBlank()
    completeCapture(null)
    if (!hadText) {
      uiState = uiState.copy(error = error)
    }
  }

  /** 手动编辑接管：停掉听写语义，内容直接落草稿（服务端那份不再回补） */
  fun editDraft(value: String) {
    if (uiState.sending) return
    awaitingFinal = false
    draftBeforeCapture = value
    uiState = uiState.copy(
      draft = value,
      livePartial = "",
      captureState = CaptureState.Idle,
      error = null,
    )
  }

  /** 收起浮层 = 结束这一轮：清空草稿/回复，下次开口是全新输入框 */
  fun resetSession() {
    awaitingFinal = false
    draftBeforeCapture = ""
    draftAtFinalize = ""
    uiState = VoiceCaptureUiState()
  }

  fun updateDraft(value: String) {
    if (!uiState.sending && uiState.captureState == CaptureState.Idle) {
      uiState = uiState.copy(draft = value, error = null)
    }
  }

  fun showError(message: String) {
    uiState = uiState.copy(
      captureState = CaptureState.Idle,
      livePartial = "",
      error = message,
    )
  }

  fun send() {
    val text = uiState.draft.trim()
    if (text.isEmpty() || uiState.sending || uiState.captureState != CaptureState.Idle) return

    val config = preferences.loadConfig()
    if (!PanelEndpoint.isUsable(config.endpoint)) {
      showError("还没有完成连接设置，请先展开完整面板。")
      return
    }

    uiState = uiState.copy(sending = true, error = null, reply = null)
    viewModelScope.launch {
      runCatching { api.send(config, text) }
        .onSuccess { reply ->
          val sent = ChatMessage(ChatMessage.Role.User, text, System.currentTimeMillis())
          val received = ChatMessage(ChatMessage.Role.Cat, reply, System.currentTimeMillis())
          val updated = (preferences.loadMessages() + sent + received).takeLast(MAX_HISTORY)
          preferences.saveMessages(updated)
          CatPanelWidgetProvider.updateAll(getApplication())
          uiState = uiState.copy(
            draft = "",
            sending = false,
            reply = reply,
          )
        }
        .onFailure { error ->
          uiState = uiState.copy(
            sending = false,
            error = error.message ?: "发送失败，文字已保留，可稍后直接重发。",
          )
        }
    }
  }

  private companion object {
    const val MAX_HISTORY = 60
  }
}
