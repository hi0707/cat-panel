package com.a07.catpanel.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.a07.catpanel.MainActivity
import com.a07.catpanel.panel.PanelEndpoint
import com.a07.catpanel.panel.PanelPreferences
import com.a07.catpanel.ui.theme.CatTheme

class VoiceCaptureActivity : ComponentActivity() {
  private val viewModel: VoiceCaptureViewModel by viewModels()
  private var asr: StreamingAsr? = null

  private val microphonePermission = registerForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    if (granted) {
      startListening()
    } else {
      viewModel.showError("需要麦克风权限才能把语音转成文字；你也可以在下方直接输入。")
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (!android.provider.Settings.canDrawOverlays(this)) {
      android.app.AlertDialog.Builder(this)
        .setTitle("需要一次授权")
        .setMessage(
          "要让浮层常驻桌面、又不挡住你的手势，需要「显示在其他应用上层」权限。\n\n" +
            "点「去授权」→ 找到 Cat → 打开开关 → 再点一次「Cat 说一句」。",
        )
        .setPositiveButton("去授权") { _, _ ->
          startActivity(
            Intent(
              android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
              android.net.Uri.parse("package:$packageName"),
            ),
          )
          finish()
        }
        .setNegativeButton("取消") { _, _ -> finish() }
        .show()
      return
    }
    // 没配过地址就先进引导页，否则用户点开只看到"还没连接好"的报错，无从下手
    val config = PanelPreferences(this).loadConfig()
    if (!PanelEndpoint.isUsable(config.endpoint) || config.accessKey.length < 16) {
      startActivity(Intent(this, com.a07.catpanel.ui.setup.SetupActivity::class.java))
      finish()
      return
    }
    VoiceOverlayService.start(this)
    finish()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    if (viewModel.uiState.captureState == CaptureState.Idle) {
      requestMicrophoneAndListen()
    }
  }

  override fun onDestroy() {
    asr?.cancel()
    asr = null
    super.onDestroy()
  }

  private fun toggleListening() {
    when (viewModel.uiState.captureState) {
      CaptureState.Idle -> requestMicrophoneAndListen()
      CaptureState.Listening -> {
        // 先本地收尾：文字立刻进草稿、发送按钮立刻可用；服务端最终结果回来再校正
        viewModel.finalizeNow()
        asr?.stop()
      }
      CaptureState.Finalizing -> Unit
    }
  }

  private fun requestMicrophoneAndListen() {
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
      startListening()
    } else {
      microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  /**
   * 自己录音 + 走云端实时识别（小米没有系统识别引擎，SpeechRecognizer 用不了）。
   */
  private fun startListening() {
    val config = PanelPreferences(this).loadConfig()
    if (!PanelEndpoint.isUsable(config.endpoint)) {
      viewModel.showError("还没连接好，点右上角「展开」进去填网址和口令。")
      return
    }
    asr?.cancel()
    viewModel.beginCapture()
    asr = StreamingAsr(config.endpoint, config.accessKey).also { engine ->
      engine.start(object : StreamingAsr.Listener {
        override fun onText(text: String) {
          runOnUiThread { viewModel.updatePartial(text) }
        }

        override fun onError(message: String) {
          runOnUiThread { viewModel.finishWithPartial(message) }
        }

        override fun onFinished(text: String) {
          runOnUiThread { viewModel.completeCapture(text) }
        }
      })
    }
  }

  private fun openFullPanel() {
    // 浮层和面板是两个界面，草稿存一下再走，否则点「展开」文字就没了
    val pending = viewModel.uiState.draft.trim()
    if (pending.isNotEmpty()) PanelPreferences(this).stashDraft(pending)
    startActivity(Intent(this, MainActivity::class.java))
    finish()
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceCaptureTray(
  state: VoiceCaptureUiState,
  onToggleCapture: () -> Unit,
  onDraftChanged: (String) -> Unit,
  onSend: () -> Unit,
  onDismiss: () -> Unit,
  onExpand: () -> Unit,
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .wrapContentHeight()
      .navigationBarsPadding()
      .imePadding()
      .padding(horizontal = 12.dp, vertical = 10.dp)
      .shadow(14.dp, MaterialTheme.shapes.extraLarge),
    shape = MaterialTheme.shapes.extraLarge,
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor = MaterialTheme.colorScheme.onSurface,
  ) {
    Column(
      modifier = Modifier.padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Cat", style = MaterialTheme.typography.titleLarge)
          Text(
            text = trayStatus(state),
            color = if (state.error == null) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.error
            },
            style = MaterialTheme.typography.labelMedium,
          )
        }
        TextButton(onClick = onExpand, contentPadding = PaddingValues(horizontal = 8.dp)) {
          Text("展开")
        }
        IconButton(onClick = onDismiss) {
          Icon(Icons.Outlined.Close, contentDescription = "关闭语音条")
        }
      }

      state.error?.let { error ->
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      }

      state.reply?.let { reply ->
        Surface(
          color = MaterialTheme.colorScheme.secondaryContainer,
          shape = MaterialTheme.shapes.medium,
        ) {
          Text(
            text = "Cat：$reply",
            modifier = Modifier.padding(12.dp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }

      if (state.captureState == CaptureState.Idle) {
        OutlinedTextField(
          value = state.draft,
          onValueChange = onDraftChanged,
          enabled = !state.sending,
          placeholder = { Text("说完可以修改，也可以直接输入") },
          minLines = 2,
          maxLines = 5,
          modifier = Modifier.fillMaxWidth(),
        )
      } else {
        Surface(
          color = MaterialTheme.colorScheme.primaryContainer,
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          shape = MaterialTheme.shapes.medium,
        ) {
          Text(
            text = state.shownText.ifBlank { "正在听…文字会显示在这里" },
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyLarge,
          )
        }
      }

      Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.captureState == CaptureState.Listening) {
          OutlinedButton(
            onClick = onToggleCapture,
            enabled = !state.sending,
            modifier = Modifier.weight(1f),
          ) {
            Text("暂停并编辑")
          }
        } else {
          Button(
            onClick = onToggleCapture,
            enabled = !state.sending && state.captureState == CaptureState.Idle,
            modifier = Modifier.weight(1f),
          ) {
            Icon(Icons.Outlined.Mic, contentDescription = null)
            Spacer(Modifier.width(7.dp))
            Text(if (state.draft.isBlank()) "开始说话" else "继续补充")
          }
        }

        Button(
          onClick = onSend,
          enabled = !state.sending && state.draft.isNotBlank(),
          contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
          if (state.sending) {
            CircularProgressIndicator(
              color = MaterialTheme.colorScheme.onPrimary,
              strokeWidth = 2.dp,
              modifier = Modifier.width(20.dp),
            )
          } else {
            Icon(Icons.Outlined.Send, contentDescription = "发送")
          }
        }
      }
    }
  }
}

private fun trayStatus(state: VoiceCaptureUiState): String = when {
  state.sending -> "正在等待 Cat 回复"
  state.captureState == CaptureState.Listening -> "正在听，点一次即可暂停修改"
  state.captureState == CaptureState.Finalizing -> "正在整理刚才的语音"
  state.draft.isNotBlank() -> "可编辑，也可继续补充"
  else -> "点“开始说话”"
}
