package com.a07.catpanel.panel

/**
 * 面板地址规则：公网必须 https；局域网/本机可以用 http（家里直连，不走隧道，延迟几十毫秒）。
 */
object PanelEndpoint {
  private val PRIVATE = Regex("""^(10\.|192\.168\.|127\.|172\.(1[6-9]|2[0-9]|3[01])\.)""")

  fun normalize(raw: String): String = raw.trim().trimEnd('/')

  fun isPrivateHost(host: String?): Boolean =
    host != null && (host == "localhost" || PRIVATE.containsMatchIn(host))

  fun isUsable(raw: String): Boolean {
    val uri = runCatching { java.net.URI(normalize(raw)) }.getOrNull() ?: return false
    val scheme = uri.scheme ?: return false
    val host = uri.host ?: return false
    if (uri.query != null) return false
    return scheme == "https" || (scheme == "http" && isPrivateHost(host))
  }
}

data class PanelConfig(
  val endpoint: String = "",
  val accessKey: String = "",
)

data class ChatMessage(
  val role: Role,
  val text: String,
  val sentAt: Long,
) {
  enum class Role { User, Cat }
}

sealed interface RequestState {
  data object Idle : RequestState
  data object Sending : RequestState
  data class Error(val message: String) : RequestState
}

data class PanelUiState(
  val config: PanelConfig = PanelConfig(),
  val messages: List<ChatMessage> = emptyList(),
  val draft: String = "",
  val requestState: RequestState = RequestState.Idle,
) {
  val isConfigured: Boolean
    get() = PanelEndpoint.isUsable(config.endpoint)
}
