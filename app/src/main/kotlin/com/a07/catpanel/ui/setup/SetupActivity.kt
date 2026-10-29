package com.a07.catpanel.ui.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.a07.catpanel.panel.PanelConfig
import com.a07.catpanel.panel.PanelEndpoint
import com.a07.catpanel.panel.PanelPreferences
import com.a07.catpanel.ui.theme.CatTheme
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 首次启动引导：填地址 → 测连通 → 给权限 → 开始用。
 *
 * 为什么单独一个 Activity：装完 App 的人没有作者在旁边，界面上必须能把
 * 「连不上」拆成地址错 / 口令错 / 没授权三种，而不是一个转圈。
 */
class SetupActivity : ComponentActivity() {

  private val microphonePermission = registerForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { /* 授权结果会在 onResume 里重新读，不用回调处理 */ }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val prefs = PanelPreferences(this)
    setContent {
      CatTheme {
        SetupScreen(
          initial = prefs.loadConfig(),
          state = ::permissionState,
          onRequestMic = { microphonePermission.launch(Manifest.permission.RECORD_AUDIO) },
          onRequestOverlay = {
            startActivity(
              Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
              ),
            )
          },
          onRequestBattery = { requestIgnoreBatteryOptimizations() },
          onDone = { config ->
            prefs.saveConfig(config)
            finish()
          },
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    // 从系统设置页返回后刷新勾选状态
    permissionTick.value = permissionTick.value + 1
  }

  private fun requestIgnoreBatteryOptimizations() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    runCatching {
      startActivity(
        Intent(
          Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
          Uri.parse("package:$packageName"),
        ),
      )
    }.onFailure {
      runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
  }

  private fun permissionState(): Perms = Perms(
    microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED,
    overlay = Settings.canDrawOverlays(this),
    batteryFree = runCatching {
      (getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(false),
  )
}

/** 用一个全局计数当"权限状态变了"的信号，避免给 Activity 加生命周期监听 */
private val permissionTick = mutableStateOf(0)

data class Perms(val microphone: Boolean, val overlay: Boolean, val batteryFree: Boolean)

private sealed interface ConnState {
  data object Idle : ConnState
  data object Testing : ConnState
  data class Ok(val name: String, val version: String, val caps: String) : ConnState
  data class Fail(val message: String) : ConnState
}

@Composable
private fun SetupScreen(
  initial: PanelConfig,
  state: () -> Perms,
  onRequestMic: () -> Unit,
  onRequestOverlay: () -> Unit,
  onRequestBattery: () -> Unit,
  onDone: (PanelConfig) -> Unit,
) {
  var endpoint by remember { mutableStateOf(initial.endpoint) }
  var token by remember { mutableStateOf(initial.accessKey) }
  var conn by remember { mutableStateOf<ConnState>(ConnState.Idle) }

  val tick = permissionTick.value
  val perms = remember(tick) { state() }
  val addressOk = PanelEndpoint.isUsable(endpoint)

  Scaffold { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(horizontal = 22.dp, vertical = 18.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Text("连接你的 Agent", style = MaterialTheme.typography.headlineSmall)
      Text(
        "这个 App 不自带模型，也不经过任何第三方服务器。它只负责把你说的话，" +
          "发到你自己跑的那个服务上。先填地址，再填口令。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      OutlinedTextField(
        value = endpoint,
        onValueChange = {
          endpoint = it
          conn = ConnState.Idle
        },
        label = { Text("服务地址") },
        placeholder = { Text("http://192.168.x.x:8899") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        supportingText = {
          Text(
            if (endpoint.isBlank()) "局域网用 http://192.168.x.x:8899；外网必须 https"
            else if (!addressOk) "地址格式不对：公网必须 https，局域网可以 http" else " ",
            color = if (!endpoint.isBlank() && !addressOk) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          )
        },
      )

      OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text("口令 token") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
        supportingText = { Text("服务端 config.json 里的 token 字段") },
      )

      Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
          enabled = addressOk && conn != ConnState.Testing,
          onClick = {
            conn = ConnState.Testing
            testConnection(endpoint, token) { conn = it }
          },
        ) { Text("测试连接") }
        if (conn == ConnState.Testing) CircularProgressIndicator(modifier = Modifier.height(20.dp))
      }

      when (val c = conn) {
        is ConnState.Ok -> Text(
          "通了：${c.name}（v${c.version}）支持 ${c.caps}",
          color = MaterialTheme.colorScheme.primary,
          style = MaterialTheme.typography.bodyMedium,
        )
        is ConnState.Fail -> Text(
          c.message,
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodyMedium,
        )
        else -> Unit
      }

      Spacer(Modifier.height(6.dp))
      Text("还差三件事", style = MaterialTheme.typography.titleMedium)

      PermissionRow("麦克风", perms.microphone, "说话要它", onRequestMic)
      PermissionRow("显示在其它应用上层", perms.overlay, "浮条能不能浮在别人上面", onRequestOverlay)
      PermissionRow("关掉省电限制", perms.batteryFree, "否则几分钟后会被系统杀掉", onRequestBattery)

      Spacer(Modifier.height(6.dp))
      Button(
        enabled = addressOk && token.length >= 16,
        onClick = { onDone(PanelConfig(PanelEndpoint.normalize(endpoint), token.trim())) },
        modifier = Modifier.fillMaxWidth(),
      ) { Text("保存并开始使用") }

      if (token.isNotEmpty() && token.length < 16) {
        Text(
          "口令太短了。服务端的 token 一般至少 16 位，检查一下有没有复制全。",
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
        )
      }
      Text(
        "保存后点桌面图标即可唤出浮条。以后要改设置，在浮条的「面板」页里改。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun PermissionRow(
  title: String,
  granted: Boolean,
  hint: String,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        (if (granted) "✓ " else "○ ") + title,
        style = MaterialTheme.typography.bodyLarge,
        color = if (granted) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface,
      )
      Text(hint, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!granted) OutlinedButton(onClick = onClick) { Text("去开启") }
  }
}

/**
 * 打 /api/health 探活。
 *
 * 注意：协议里 health 是免认证的，但社区里的后端**未必**照做（有的实现对 health 也校验口令，
 * 不带就 403）。所以这里**一律带上口令**——服务端忽略也无所谓；不带的后果是"明明能用却报 403"，
 * 这种假阴性比多传一个 header 糟糕得多（07 2026-10-29 实测踩到）。
 */
private fun testConnection(endpoint: String, accessKey: String, done: (ConnState) -> Unit) {
  val main = Handler(Looper.getMainLooper())
  Thread {
    val result = runCatching {
      val url = URL(PanelEndpoint.normalize(endpoint) + "/api/health")
      val conn = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 4000
        readTimeout = 4000
        requestMethod = "GET"
        if (accessKey.isNotBlank()) {
          setRequestProperty("X-Panel-Key", accessKey)
          setRequestProperty("Authorization", "Bearer $accessKey")
        }
      }
      try {
        val code = conn.responseCode
        if (code != 200) return@runCatching ConnState.Fail(
          when (code) {
            401, 403 -> "服务是活的，但没认这个口令（$code）。检查口令是否复制完整（注意别带上空格）。"
            404 -> "这个地址上没有面板服务（404）。检查端口号，或服务是不是还没启动。"
            502, 503, 504 -> "服务在那台机器上，但没起来（$code）。去看服务端日志。"
            else -> "服务返回 $code。看服务端日志。"
          },
        )
        val body = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(body)
        if (!json.optBoolean("ok", false)) {
          ConnState.Fail("服务回应了，但说自己不正常：$body")
        } else {
          val caps = json.optJSONArray("capabilities")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.joinToString("/")
          } ?: "text"
          ConnState.Ok(
            name = json.optString("name", "未命名"),
            version = json.optString("version", "?"),
            caps = caps,
          )
        }
      } finally {
        conn.disconnect()
      }
    }.getOrElse { e ->
      ConnState.Fail(
        when (e) {
          is java.net.SocketTimeoutException -> "超时。地址可能对，但网络不通（不同 WiFi？防火墙？）"
          is java.net.ConnectException -> "连不上这个地址。检查 IP、端口、以及手机是不是同一个网络。"
          is java.net.UnknownHostException -> "解析不了这个域名。域名拼错，或者 DDNS 还没生效。"
          is java.net.MalformedURLException -> "地址格式不对，要带 http:// 或 https://"
          else -> "出错：${e.javaClass.simpleName} ${e.message ?: ""}"
        },
      )
    }
    main.post { done(result) }
  }.start()
}
