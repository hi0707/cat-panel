package com.a07.catpanel.panel

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.a07.catpanel.widget.CatPanelWidgetProvider
import com.a07.catpanel.voice.VoiceCaptureActivity
import com.a07.catpanel.voice.VoiceOverlayService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatPanelApp(
  startVoiceOnLaunch: Boolean,
  viewModel: PanelViewModel = viewModel(),
) {
  val state = viewModel.uiState
  val context = LocalContext.current
  var showSettings by rememberSaveable { mutableStateOf(!state.isConfigured) }
  var voiceLaunchConsumed by rememberSaveable { mutableStateOf(false) }

  fun startSpeech() {
    context.startActivity(
      Intent(context, VoiceCaptureActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      },
    )
  }

  LaunchedEffect(startVoiceOnLaunch, state.isConfigured) {
    if (startVoiceOnLaunch && state.isConfigured && !voiceLaunchConsumed) {
      voiceLaunchConsumed = true
      startSpeech()
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text("Cat", style = MaterialTheme.typography.titleLarge)
            Text(
              text = statusLabel(state),
              style = MaterialTheme.typography.labelMedium,
              color = statusColor(state),
            )
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.background,
          titleContentColor = MaterialTheme.colorScheme.onBackground,
        ),
        actions = {
          // 收起：回到桌面 + 打开悬浮浮条（07 2026-10-29 要求：面板里要能收起）
          IconButton(onClick = {
            VoiceOverlayService.start(context)
            (context as? Activity)?.finish()
          }) {
            Icon(Icons.Outlined.ExpandMore, contentDescription = "收起")
          }
          IconButton(onClick = { showSettings = true }) {
            Icon(Icons.Outlined.Settings, contentDescription = "连接设置")
          }
        },
      )
    },
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .imePadding(),
    ) {
      Conversation(
        messages = state.messages,
        modifier = Modifier.weight(1f),
      )
      if (state.requestState is RequestState.Error) {
        Text(
          text = state.requestState.message,
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
      }
      Composer(
        draft = state.draft,
        sending = state.requestState == RequestState.Sending,
        enabled = state.isConfigured,
        onDraftChanged = viewModel::updateDraft,
        onMic = ::startSpeech,
        onSend = viewModel::sendDraft,
      )
    }
  }

  if (showSettings) {
    ConnectionSettings(
      config = state.config,
      onDismiss = { showSettings = false },
      onSave = { endpoint, accessKey ->
        viewModel.saveConfig(endpoint, accessKey)
        showSettings = false
      },
      onClearHistory = viewModel::clearHistory,
    )
  }
}

@Composable
private fun Conversation(messages: List<ChatMessage>, modifier: Modifier = Modifier) {
  if (messages.isEmpty()) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(36.dp)) {
        Text("随手说一条", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(
          "点下方麦克风，把语音先转成可修改的文字；确认后发送给 Cat。",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }
    return
  }

  val listState = rememberLazyListState()
  // 进面板/来新消息时，直接停在最新一条，不停在半截旧消息上
  LaunchedEffect(messages.size) {
    if (messages.isNotEmpty()) {
      listState.scrollToItem(messages.lastIndex)
    }
  }

  LazyColumn(
    modifier = modifier.fillMaxWidth(),
    state = listState,
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    items(messages) { message -> MessageBubble(message) }
  }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
  val isUser = message.role == ChatMessage.Role.User
  val background = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
  val foreground = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
  val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
  Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
    Surface(
      color = background,
      contentColor = foreground,
      shape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (isUser) 16.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 16.dp,
      ),
      tonalElevation = if (isUser) 0.dp else 1.dp,
    ) {
      Text(
        text = message.text,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        style = MaterialTheme.typography.bodyLarge,
      )
    }
  }
}

@Composable
private fun Composer(
  draft: String,
  sending: Boolean,
  enabled: Boolean,
  onDraftChanged: (String) -> Unit,
  onMic: () -> Unit,
  onSend: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surfaceContainerLow)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Button(
      onClick = onMic,
      enabled = enabled && !sending,
      shape = RoundedCornerShape(18.dp),
      contentPadding = PaddingValues(vertical = 14.dp),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Icon(Icons.Outlined.Mic, contentDescription = null)
      Spacer(Modifier.width(9.dp))
      Text("点这里说话")
    }
    Row(verticalAlignment = Alignment.Bottom) {
      OutlinedTextField(
        value = draft,
        onValueChange = onDraftChanged,
        enabled = enabled && !sending,
        placeholder = { Text(if (enabled) "说完可修改，再发送" else "先在右上角完成连接设置") },
        minLines = 1,
        maxLines = 4,
        modifier = Modifier.weight(1f),
      )
      Spacer(Modifier.width(8.dp))
      Button(
        onClick = onSend,
        enabled = enabled && !sending && draft.isNotBlank(),
        modifier = Modifier.height(56.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
      ) {
        if (sending) {
          CircularProgressIndicator(
            color = MaterialTheme.colorScheme.onPrimary,
            strokeWidth = 2.dp,
            modifier = Modifier.size(20.dp),
          )
        } else {
          Icon(Icons.Outlined.Send, contentDescription = "发送")
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionSettings(
  config: PanelConfig,
  onDismiss: () -> Unit,
  onSave: (String, String) -> Unit,
  onClearHistory: () -> Unit,
) {
  val context = LocalContext.current
  val widgetManager = remember(context) { AppWidgetManager.getInstance(context) }
  var endpoint by remember(config.endpoint) { mutableStateOf(config.endpoint) }
  var accessKey by remember(config.accessKey) { mutableStateOf(config.accessKey) }
  var widgetHint by rememberSaveable { mutableStateOf<String?>(null) }
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 34.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Text("连接 Cat", style = MaterialTheme.typography.headlineSmall)
      Text(
        "这里只保存你的私有面板地址和口令。网址必须是 https://，口令单独填写，不要把 ?k=… 粘进网址。",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
      )
      OutlinedTextField(
        value = endpoint,
        onValueChange = { endpoint = it },
        label = { Text("面板网址") },
        placeholder = { Text("https://…") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = accessKey,
        onValueChange = { accessKey = it },
        label = { Text("访问口令") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      Button(
        onClick = { onSave(endpoint, accessKey) },
        modifier = Modifier.fillMaxWidth(),
      ) { Text("保存连接") }
      OutlinedButton(
        onClick = { widgetHint = requestWidgetPin(widgetManager, context) },
        modifier = Modifier.fillMaxWidth(),
      ) { Text("添加到桌面") }
      widgetHint?.let { hint ->
        AlertDialog(
          onDismissRequest = { widgetHint = null },
          confirmButton = {
            TextButton(onClick = { widgetHint = null }) { Text("知道了") }
          },
          title = { Text("添加到桌面") },
          text = { Text(hint) },
        )
      }
      HorizontalDivider()
      TextButton(onClick = onClearHistory, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("仅清除手机上的对话记录")
      }
    }
  }
}

private fun requestWidgetPin(
  appWidgetManager: AppWidgetManager,
  context: Context,
): String {
  val manual = "\n\n如果没弹窗，用手动方式：\n长按桌面空白处 → 添加小部件（或「小部件」）→ 搜索「Cat」→ 拖到桌面。\n\n如果搜不到「Cat」，说明系统没认这个小部件，回来告诉我。"
  if (!appWidgetManager.isRequestPinAppWidgetSupported) {
    return "你的桌面不支持一键添加（小米常见）。" + manual
  }
  val provider = ComponentName(context, CatPanelWidgetProvider::class.java)
  return runCatching {
    val ok = appWidgetManager.requestPinAppWidget(provider, null, null)
    if (ok) {
      "已经向桌面发出添加请求，请在系统弹窗里点「添加」。不会的话看下面的手动方式。$manual"
    } else {
      "系统拒绝了这次添加请求。" + manual
    }
  }.getOrElse {
    "系统拒绝了这次添加请求。" + manual
  }
}

private fun statusLabel(state: PanelUiState): String = when (state.requestState) {
  RequestState.Sending -> "正在等待回复"
  is RequestState.Error -> "需要处理"
  RequestState.Idle -> if (state.isConfigured) "已准备好" else "尚未连接"
}

@Composable
private fun statusColor(state: PanelUiState): Color = when (state.requestState) {
  is RequestState.Error -> MaterialTheme.colorScheme.error
  RequestState.Sending -> MaterialTheme.colorScheme.tertiary
  RequestState.Idle -> if (state.isConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
}
