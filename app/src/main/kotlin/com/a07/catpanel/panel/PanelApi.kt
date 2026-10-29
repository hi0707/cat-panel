package com.a07.catpanel.panel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class PanelApi {
  suspend fun send(config: PanelConfig, text: String): String = withContext(Dispatchers.IO) {
    val base = validateEndpoint(config.endpoint)
    val connection = (URL("$base/api/text").openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = CONNECT_TIMEOUT_MS
      readTimeout = READ_TIMEOUT_MS
      doOutput = true
      useCaches = false
      setRequestProperty("Content-Type", "application/json; charset=utf-8")
      setRequestProperty("Accept", "application/json")
      if (config.accessKey.isNotBlank()) setRequestProperty("X-Panel-Key", config.accessKey)
    }

    try {
      val payload = JSONObject().put("text", text).toString().toByteArray(Charsets.UTF_8)
      connection.outputStream.use { it.write(payload) }
      val code = connection.responseCode
      val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
        ?.bufferedReader(Charsets.UTF_8)
        ?.use { it.readText() }
        .orEmpty()
      val response = runCatching { JSONObject(body) }.getOrNull()
      if (code !in 200..299) {
        throw IOException(
          if (code == 502) {
            errorMessageForHttp(code)
          } else {
            response?.optString("error")?.ifBlank { null }
              ?: errorMessageForHttp(code)
          },
        )
      }
      response?.optString("reply")?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw IOException(response?.optString("error")?.ifBlank { null } ?: "没有拿到回复")
    } finally {
      connection.disconnect()
    }
  }

  private fun validateEndpoint(raw: String): String {
    val normalized = raw.trim().removeSuffix("/")
    val uri = runCatching { URI(normalized) }.getOrNull()
      ?: throw IllegalArgumentException("面板网址格式不对")
    require(uri.scheme == "https" || (uri.scheme == "http" && PanelEndpoint.isPrivateHost(uri.host))) {
      "面板网址要 https:// 开头；家里直连可以用 http://192.168.x.x"
    }
    require(uri.query.isNullOrBlank()) { "请把网址后的 ?k=… 口令填进下方口令栏，不要粘在网址里" }
    require(uri.host?.isNotBlank() == true) { "面板网址缺少域名" }
    return normalized
  }

  private fun errorMessageForHttp(code: Int): String = when (code) {
    502 -> "连接暂时断开，文字已保留；恢复后可直接重发。"
    else -> "服务返回 HTTP $code"
  }

  private companion object {
    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 180_000
  }
}
