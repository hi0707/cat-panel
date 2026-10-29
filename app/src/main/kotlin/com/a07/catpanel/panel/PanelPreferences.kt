package com.a07.catpanel.panel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class PanelPreferences(context: Context) {
  private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun loadConfig(): PanelConfig = PanelConfig(
    endpoint = preferences.getString(KEY_ENDPOINT, "").orEmpty(),
    accessKey = preferences.getString(KEY_ACCESS_KEY, "").orEmpty(),
  )

  fun saveConfig(config: PanelConfig) {
    preferences.edit()
      .putString(KEY_ENDPOINT, config.endpoint)
      .putString(KEY_ACCESS_KEY, config.accessKey)
      .apply()
  }

  fun loadMessages(): List<ChatMessage> = runCatching {
    val raw = preferences.getString(KEY_HISTORY, "[]").orEmpty()
    val array = JSONArray(raw)
    buildList {
      for (index in 0 until array.length()) {
        val item = array.getJSONObject(index)
        val role = ChatMessage.Role.valueOf(item.getString("role"))
        val text = item.getString("text")
        val sentAt = item.getLong("sentAt")
        add(ChatMessage(role = role, text = text, sentAt = sentAt))
      }
    }
  }.getOrDefault(emptyList())

  fun saveMessages(messages: List<ChatMessage>) {
    val retained = messages.takeLast(MAX_HISTORY)
    val array = JSONArray()
    retained.forEach { message ->
      array.put(
        JSONObject()
          .put("role", message.role.name)
          .put("text", message.text)
          .put("sentAt", message.sentAt),
      )
    }
    preferences.edit().putString(KEY_HISTORY, array.toString()).apply()
  }

  /** 浮层点「展开」时把没发出去的草稿交过来，避免丢字 */
  fun stashDraft(text: String) {
    preferences.edit().putString(KEY_PENDING_DRAFT, text).apply()
  }

  fun takePendingDraft(): String {
    val text = preferences.getString(KEY_PENDING_DRAFT, "").orEmpty()
    if (text.isNotEmpty()) preferences.edit().remove(KEY_PENDING_DRAFT).apply()
    return text
  }

  fun latestCatReply(): String = loadMessages()
    .lastOrNull { it.role == ChatMessage.Role.Cat }
    ?.text
    .orEmpty()

  companion object {
    private const val KEY_PENDING_DRAFT = "pending_draft"
    private const val PREFS_NAME = "cat_panel"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_ACCESS_KEY = "access_key"
    private const val KEY_HISTORY = "history"
    private const val MAX_HISTORY = 60
  }
}
