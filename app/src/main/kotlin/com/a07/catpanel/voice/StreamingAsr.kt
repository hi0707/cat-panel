package com.a07.catpanel.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 边说边出字：自己录音，每 800ms 把一段 16k PCM 推给面板服务，服务端持有到阿里云
 * 实时识别的长连接，把累计文字返回。
 *
 * 为什么不用系统 SpeechRecognizer：小米国内版没有可用识别引擎（实测报
 * "没有可用的语音识别服务"），只能退回输入框。
 */
class StreamingAsr(private val endpoint: String, private val accessKey: String) {

  interface Listener {
    /** 中间结果，可能反复覆盖同一句 */
    fun onText(text: String)

    /** 出错（连不上、没识别到内容） */
    fun onError(message: String)

    /** 正常结束，给出最终文字 */
    fun onFinished(text: String)
  }

  private val base = endpoint.trim().removeSuffix("/")
  private val queue = LinkedBlockingQueue<ByteArray>()

  @Volatile private var running = false
  @Volatile private var latest = ""
  @Volatile private var lastSent = ""

  private var sessionId: String? = null
  private var recorder: AudioRecord? = null
  private var captureThread: Thread? = null
  private var uploadThread: Thread? = null
  private var listener: Listener? = null

  fun start(listener: Listener) {
    this.listener = listener
    latest = ""
    lastSent = ""
    running = true
    Thread {
      try {
        val id = post("/api/asr/start", ByteArray(0), null).optString("session_id")
        if (id.isBlank()) throw IOException("服务端没有返回会话号")
        sessionId = id
        startUpload()
        startCapture()
      } catch (e: Exception) {
        running = false
        listener.onError(e.message ?: "实时识别没启动起来")
        release()
      }
    }.start()
  }

  /** 停止录音并取回最终文字；内部起线程，调用方可直接在主线程调。 */
  fun stop() {
    if (!running) return
    running = false
    Thread {
      val text = runCatching { finish() }.getOrElse { latest }
      if (text.isNotBlank()) listener?.onFinished(text) else listener?.onError("没有识别到内容，再说一次？")
    }.start()
  }

  /** 直接丢弃（页面被关掉时调用） */
  fun cancel() {
    running = false
    runCatching { finish() }
  }

  // ---------------- 内部 ----------------

  @SuppressLint("MissingPermission")
  private fun startCapture() {
    val minBuf = AudioRecord.getMinBufferSize(
      SAMPLE_RATE,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
    )
    val bufSize = maxOf(minBuf, CHUNK_BYTES * 2)
    val record = AudioRecord(
      MediaRecorder.AudioSource.VOICE_RECOGNITION,
      SAMPLE_RATE,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
      bufSize,
    )
    if (record.state != AudioRecord.STATE_INITIALIZED) {
      record.release()
      throw IOException("麦克风打不开，可能被别的应用占用了")
    }
    recorder = record
    record.startRecording()

    captureThread = Thread {
      val chunk = ByteArray(CHUNK_BYTES)
      var filled = 0
      while (running) {
        val read = record.read(chunk, filled, chunk.size - filled)
        if (read <= 0) continue
        filled += read
        if (filled == chunk.size) {
          queue.offer(chunk.copyOf())
          filled = 0
        }
      }
      // 把最后不足 800ms 的一点也发出去，别丢结尾
      if (filled > 0) queue.offer(chunk.copyOf(filled))
    }.also { it.start() }
  }

  private fun startUpload() {
    uploadThread = Thread {
      while (true) {
        val chunk = if (queue.isNotEmpty()) {
          queue.poll()
        } else {
          if (!running) break
          queue.poll(150, TimeUnit.MILLISECONDS)
        } ?: continue
        if (chunk.isEmpty()) continue
        val sid = sessionId ?: break
        val result = runCatching { post("/api/asr/chunk", chunk, sid) }.getOrNull() ?: continue
        val text = result.optString("text")
        if (text.isNotBlank() && text != lastSent) {
          lastSent = text
          latest = text
          listener?.onText(text)
        }
      }
    }.also { it.start() }
  }

  private fun finish(): String {
    val capture = captureThread
    captureThread = null
    recorder?.let { rec ->
      runCatching { rec.stop() }
      runCatching { rec.release() }
    }
    recorder = null
    runCatching { capture?.join(1500) }

    uploadThread?.let { runCatching { it.join(5000) } }
    uploadThread = null

    val sid = sessionId
    sessionId = null
    val finalText = if (sid != null) {
      runCatching { post("/api/asr/stop", ByteArray(0), sid).optString("text") }
        .getOrDefault(latest)
    } else {
      latest
    }
    release()
    return finalText.ifBlank { latest }
  }

  private fun release() {
    queue.clear()
    runCatching { recorder?.release() }
    recorder = null
  }

  private fun post(path: String, body: ByteArray, session: String?): JSONObject {
    val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = 12_000
      readTimeout = 30_000
      doOutput = true
      useCaches = false
      setRequestProperty("Content-Type", "application/octet-stream")
      setRequestProperty("Accept", "application/json")
      if (accessKey.isNotBlank()) setRequestProperty("X-Panel-Key", accessKey)
      if (session != null) setRequestProperty("X-Session", session)
    }
    try {
      connection.outputStream.use { if (body.isNotEmpty()) it.write(body) }
      val code = connection.responseCode
      val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)
        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
      val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
      if (code !in 200..299) {
        throw IOException(json.optString("error").ifBlank { "服务返回 HTTP $code" })
      }
      return json
    } finally {
      connection.disconnect()
    }
  }

  private companion object {
    const val SAMPLE_RATE = 16000
    // 400ms 的 16bit 单声道 PCM = 16000 * 2 * 0.4（片小一点，出字更跟手）
    const val CHUNK_BYTES = 12_800
  }
}
