package com.a07.catpanel.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.a07.catpanel.MainActivity
import com.a07.catpanel.panel.PanelEndpoint
import com.a07.catpanel.panel.PanelPreferences
import com.a07.catpanel.ui.theme.CatTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * 「常驻浮层」：系统级悬浮窗（TYPE_APPLICATION_OVERLAY）。
 *
 * 为什么不是 Activity：Activity 窗口天生吃满屏并独占手势，
 * 所以"浮层开着还能滑桌面"必须用悬浮窗。
 *
 * 两种形态：
 *  - 收起：只剩一粒「🎤 说一句」小按钮，常驻不挡事
 *  - 展开：工具条（说话 / 状态 / 面板 / 发送 / 收起 / 关闭）+ 文字区
 * 文字区点一下即进入编辑状态（临时放开窗口焦点，弹键盘）。
 */
class VoiceOverlayService : Service() {

  private lateinit var windowManager: WindowManager
  private lateinit var owner: OverlayOwner
  private var overlayView: View? = null
  private var asr: StreamingAsr? = null
  private var viewModel: VoiceCaptureViewModel? = null
  private var params: WindowManager.LayoutParams? = null
  private var prefs: android.content.SharedPreferences? = null

  override fun onCreate() {
    super.onCreate()
    windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    prefs = getSharedPreferences("cat_overlay", Context.MODE_PRIVATE)
    owner = OverlayOwner()
    owner.onCreate()
    startForegroundNotification()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stopSelf()
      return START_NOT_STICKY
    }
    if (overlayView == null) showOverlay()
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    rememberPosition()
    asr?.cancel()
    asr = null
    overlayView?.let { runCatching { windowManager.removeView(it) } }
    overlayView = null
    owner.onDestroy()
    super.onDestroy()
  }

  private fun showOverlay() {
    val vm = VoiceCaptureViewModel(application)
    viewModel = vm
    val view = ComposeView(this).apply {
      setViewTreeLifecycleOwner(owner)
      setViewTreeSavedStateRegistryOwner(owner)
      setViewTreeViewModelStoreOwner(owner)
      setContent {
        CatTheme(darkTheme = true) {
          OverlayTray(
            state = vm.uiState,
            onToggleCapture = { toggleListening(vm) },
            onSend = vm::send,
            onExpand = ::openPanel,
            onDismiss = ::stopSelf,
            onDrag = ::dragBy,
            onEditFocus = ::setWindowFocusable,
            onDraftChange = { value ->
              asr?.cancel()
              asr = null
              vm.editDraft(value)
            },
            onCollapse = {
              asr?.cancel()
              asr = null
              vm.resetSession()
              rememberPosition()
              // 等这一帧把内容画小，再把窗口收成一粒；顺序反了会夹住内容
              overlayView?.post { lockWindowToMic() }
            },
            onBeforeExpand = ::unlockWindow,
          )
        }
      }
    }
    val metrics = resources.displayMetrics
    val defaultX = (metrics.widthPixels * 0.06f).roundToInt()
    val defaultY = (metrics.heightPixels * 0.66f).roundToInt()
    val lp = WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
      PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      x = prefs?.getInt(KEY_X, defaultX) ?: defaultX
      y = prefs?.getInt(KEY_Y, defaultY) ?: defaultY
    }
    params = lp
    view.viewTreeObserver.addOnGlobalLayoutListener {
      val frame = android.graphics.Rect()
      view.getWindowVisibleDisplayFrame(frame)
      val screenH = resources.displayMetrics.heightPixels
      val bottomInset = screenH - frame.bottom
      handleIme(bottomInset)
    }
    runCatching { windowManager.addView(view, lp) }
      .onFailure {
        android.util.Log.e("CatOverlay", "addView failed", it)
        stopSelf()
      }
    overlayView = view
  }

  /**
   * 编辑态需要窗口可获焦点，键盘才弹得出来；退出编辑立刻收回，
   * 否则浮层会开始吃按键、点外面也穿不过去。
   */
  private fun setWindowFocusable(focusable: Boolean) {
    val p = params ?: return
    val view = overlayView ?: return
    p.flags = if (focusable) {
      p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
    } else {
      p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    }
    p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
    val screenH = resources.displayMetrics.heightPixels
    editingMode = focusable
    if (focusable) {
      // 键盘高度不可靠（MIUI 常不给 overlay 报 IME inset）→ 直接抬到上半屏
      editYBefore = p.y
      p.y = (screenH * 0.33f).toInt().coerceAtLeast(0)
    } else {
      p.y = editYBefore.coerceIn(0, (screenH - view.height).coerceAtLeast(0))
    }
    runCatching { windowManager.updateViewLayout(view, p) }
  }

  private var editYBefore = 0
  private var editingMode = false
  private var imeActive = false
  private var yBeforeIme = 0

  /** 键盘弹出时把浮条整体抬到键盘上方，收起后回到原位 */
  private fun handleIme(bottomInset: Int) {
    val p = params ?: return
    val view = overlayView ?: return
    val screenH = resources.displayMetrics.heightPixels
    if (bottomInset < 120) {
      if (imeActive) {
        imeActive = false
        p.y = yBeforeIme
        runCatching { windowManager.updateViewLayout(view, p) }
      }
      return
    }
    if (!imeActive) {
      yBeforeIme = p.y
      imeActive = true
    }
    val target = (screenH - bottomInset - view.height - (8 * resources.displayMetrics.density)).toInt()
    p.y = target.coerceIn(0, (screenH - view.height).coerceAtLeast(0))
    runCatching { windowManager.updateViewLayout(view, p) }
  }

  /** 拖动：窗口跟着手指走，越界夹在屏幕内 */
  private fun dragBy(dx: Float, dy: Float) {
    val p = params ?: return
    val view = overlayView ?: return
    val metrics = resources.displayMetrics
    val maxX = (metrics.widthPixels - view.width).coerceAtLeast(0)
    val maxY = (metrics.heightPixels - view.height).coerceAtLeast(0)
    p.x = (p.x + dx).roundToInt().coerceIn(0, maxX)
    p.y = (p.y + dy).roundToInt().coerceIn(0, maxY)
    runCatching { windowManager.updateViewLayout(view, p) }
  }

  /**
   * 收起后把窗口钉成「一粒麦克风」的固定尺寸。
   *
   * 为什么不能只靠 WRAP_CONTENT：MIUI 上内容变小时窗口不重算，会留一条长框。
   * 反过来也踩过坑（11:50 事故）：把窗口长期钉在内容尺寸上，内容被窗口夹住长不大，
   * 点麦克风后永远是个小圆圈 —— 所以只允许「收起态」钉死，展开前必须先解锁。
   */
  private fun lockWindowToMic() {
    val p = params ?: return
    val view = overlayView ?: return
    val side = (60 * resources.displayMetrics.density).roundToInt() // 48dp 麦克风 + 6dp*2 外边距
    if (p.width == side && p.height == side) return
    p.width = side
    p.height = side
    p.x = p.x.coerceIn(0, (resources.displayMetrics.widthPixels - side).coerceAtLeast(0))
    p.y = p.y.coerceIn(0, (resources.displayMetrics.heightPixels - side).coerceAtLeast(0))
    runCatching { windowManager.updateViewLayout(view, p) }
  }

  /** 展开前解锁：恢复 WRAP_CONTENT，内容才有空间长大 */
  private fun unlockWindow() {
    val p = params ?: return
    val view = overlayView ?: return
    if (p.width == WindowManager.LayoutParams.WRAP_CONTENT &&
      p.height == WindowManager.LayoutParams.WRAP_CONTENT
    ) return
    p.width = WindowManager.LayoutParams.WRAP_CONTENT
    p.height = WindowManager.LayoutParams.WRAP_CONTENT
    runCatching { windowManager.updateViewLayout(view, p) }
  }

  private fun rememberPosition() {
    val p = params ?: return
    val restY = if (editingMode) editYBefore else p.y
    prefs?.edit()?.putInt(KEY_X, p.x)?.putInt(KEY_Y, restY)?.apply()
  }

  private fun toggleListening(vm: VoiceCaptureViewModel) {
    when (vm.uiState.captureState) {
      CaptureState.Idle -> startListening(vm)
      CaptureState.Listening -> {
        vm.finalizeNow()
        asr?.stop()
        rememberPosition()
      }
      CaptureState.Finalizing -> Unit
    }
  }

  private fun startListening(vm: VoiceCaptureViewModel) {
    val config = PanelPreferences(this).loadConfig()
    if (!PanelEndpoint.isUsable(config.endpoint)) {
      vm.showError("还没连接好，点「面板」填网址和口令。")
      return
    }
    asr?.cancel()
    vm.beginCapture()
    asr = StreamingAsr(config.endpoint, config.accessKey).also { engine ->
      engine.start(object : StreamingAsr.Listener {
        override fun onText(text: String) {
          vm.updatePartial(text)
        }

        override fun onError(message: String) {
          vm.finishWithPartial(message)
        }

        override fun onFinished(text: String) {
          vm.completeCapture(text)
        }
      })
    }
  }

  private fun openPanel() {
    val pending = viewModel?.uiState?.draft?.trim().orEmpty()
    if (pending.isNotEmpty()) PanelPreferences(this).stashDraft(pending)
    rememberPosition()
    startActivity(
      Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    stopSelf()
  }

  private fun startForegroundNotification() {
    val manager = getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "Cat 浮层",
        NotificationManager.IMPORTANCE_MIN,
      ).apply { description = "语音浮层保持可用" }
      manager.createNotificationChannel(channel)
    }
    val stopIntent = PendingIntent.getService(
      this,
      1,
      Intent(this, VoiceOverlayService::class.java).setAction(ACTION_STOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val notification: Notification = Notification.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setContentTitle("Cat 浮层已开启")
      .setContentText("点麦克风说话；长按可拖动")
      .setOngoing(true)
      .addAction(Notification.Action.Builder(null, "关闭浮层", stopIntent).build())
      .build()
    startForeground(NOTIFICATION_ID, notification)
  }

  companion object {
    private const val CHANNEL_ID = "cat_voice_overlay"
    private const val NOTIFICATION_ID = 4101
    private const val KEY_X = "overlay_x"
    private const val KEY_Y = "overlay_y"
    const val ACTION_STOP = "com.a07.catpanel.action.STOP_OVERLAY"

    fun start(context: Context) {
      val intent = Intent(context, VoiceOverlayService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      context.startService(
        Intent(context, VoiceOverlayService::class.java).setAction(ACTION_STOP),
      )
    }
  }
}

/** ComposeView 在 Service 里需要这三个 owner，否则一渲染就崩。 */
private class OverlayOwner :
  LifecycleOwner,
  SavedStateRegistryOwner,
  ViewModelStoreOwner {

  private val registry = LifecycleRegistry(this)
  private val savedStateController = SavedStateRegistryController.create(this)

  override val lifecycle: Lifecycle get() = registry
  override val savedStateRegistry: SavedStateRegistry
    get() = savedStateController.savedStateRegistry
  override val viewModelStore = ViewModelStore()

  fun onCreate() {
    savedStateController.performRestore(null)
    registry.currentState = Lifecycle.State.RESUMED
  }

  fun onDestroy() {
    registry.currentState = Lifecycle.State.DESTROYED
    viewModelStore.clear()
  }
}

@Composable
private fun OverlayTray(
  state: VoiceCaptureUiState,
  onToggleCapture: () -> Unit,
  onSend: () -> Unit,
  onExpand: () -> Unit,
  onDismiss: () -> Unit,
  onDrag: (Float, Float) -> Unit,
  onEditFocus: (Boolean) -> Unit,
  onDraftChange: (String) -> Unit,
  onCollapse: () -> Unit,
  onBeforeExpand: () -> Unit,
) {
  // 收起 = 只剩一粒麦克风；展开 = 整条工具条
  var barOpen by remember { mutableStateOf(false) }
  // 点文字 → 可编辑（弹键盘）
  var editing by remember { mutableStateOf(false) }
  val focusRequester = remember { FocusRequester() }
  val keyboard = LocalSoftwareKeyboardController.current

  LaunchedEffect(editing) {
    onEditFocus(editing)
    if (editing) {
      delay(140)
      focusRequester.requestFocus()
      keyboard?.show()
    } else {
      keyboard?.hide()
    }
  }

  // 一开口就展开工具条
  LaunchedEffect(state.captureState) {
    if (state.captureState != CaptureState.Idle && !barOpen) {
      onBeforeExpand()   // 从小组件/通知栏进来的录音，也要先解锁窗口
      barOpen = true
    }
  }

  val shown = state.shownText
  val hasContent = shown.isNotBlank() || state.reply != null

  var elapsed by remember { mutableIntStateOf(0) }
  LaunchedEffect(state.sending) {
    if (state.sending) {
      elapsed = 0
      while (true) {
        delay(1000)
        elapsed += 1
      }
    } else {
      elapsed = 0
    }
  }

  Surface(
    modifier = Modifier
      .widthIn(max = 360.dp)
      .wrapContentWidth()
      .padding(6.dp)
      // 自己判「点」还是「拖」：系统 drag 检测会吃掉轻点，导致麦克风点了没反应（07 2026-10-29）
      .pointerInput(barOpen) {
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          var dragged = false
          var total = Offset.Zero
          while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val delta = change.position - change.previousPosition
            if (delta != Offset.Zero) {
              total += delta
              if (total.getDistance() > viewConfiguration.touchSlop) {
                dragged = true
                change.consume()
                onDrag(delta.x, delta.y)
              }
            }
          }
          if (!dragged && !barOpen) {
            onBeforeExpand()
            barOpen = true
            onToggleCapture()
          }
        }
      },
    shape = MaterialTheme.shapes.extraLarge,
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor = MaterialTheme.colorScheme.onSurface,
  ) {
    if (!barOpen) {
      Box(
        modifier = Modifier
          .size(48.dp)
          .padding(10.dp),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          Icons.Outlined.Mic,
          contentDescription = "说一句",
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp),
        )
      }
      return@Surface
    }

    Column(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (state.captureState == CaptureState.Listening) {
          Button(onClick = onToggleCapture, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text("说完了", fontSize = 13.sp)
          }
        } else {
          Button(
            onClick = onToggleCapture,
            enabled = !state.sending && state.captureState == CaptureState.Idle,
            contentPadding = PaddingValues(horizontal = 12.dp),
          ) {
            Icon(Icons.Outlined.Mic, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(if (state.draft.isBlank()) "说一句" else "补充", fontSize = 13.sp)
          }
        }

        Text(
          text = overlayStatus(state, elapsed, hasContent),
          color = if (state.error == null) {
            MaterialTheme.colorScheme.onSurfaceVariant
          } else {
            MaterialTheme.colorScheme.error
          },
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.labelSmall,
          modifier = Modifier.weight(1f),
        )

        TextButton(onClick = onExpand, contentPadding = PaddingValues(horizontal = 4.dp)) {
          Text("面板", fontSize = 12.sp)
        }

        IconButton(
          onClick = {
            editing = false
            if (state.captureState == CaptureState.Listening) onToggleCapture()
            barOpen = false
            onCollapse()
          },
          modifier = Modifier.width(30.dp),
        ) {
          Icon(Icons.Outlined.ExpandLess, contentDescription = "收起")
        }

        IconButton(onClick = onDismiss, modifier = Modifier.width(30.dp)) {
          Icon(Icons.Outlined.Close, contentDescription = "关闭浮层")
        }
      }

      // 发送按钮贴内容区右上角：有字才出现，省出的位置留给状态文字
      Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth()) {
          if (editing) {
            Surface(
              color = MaterialTheme.colorScheme.primaryContainer,
              shape = MaterialTheme.shapes.medium,
            ) {
              BasicTextField(
                value = state.shownText,
                onValueChange = onDraftChange,
                modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(min = 44.dp, max = 120.dp)
                  .padding(start = 10.dp, top = 10.dp, end = 44.dp, bottom = 10.dp)
                  .focusRequester(focusRequester),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                  color = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
              )
            }
          } else if (hasContent) {
            BubbleContent(state = state, shown = shown, onTapText = { editing = true })
          }

          val canSend = state.shownText.isNotBlank() && state.captureState == CaptureState.Idle
          if (canSend || state.sending) {
            Surface(
              modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp),
              shape = MaterialTheme.shapes.small,
              color = MaterialTheme.colorScheme.primary,
              contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
              IconButton(
                onClick = {
                  editing = false
                  onSend()
                },
                enabled = canSend && !state.sending,
                modifier = Modifier.width(34.dp).height(34.dp),
              ) {
                if (state.sending) {
                  CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.width(16.dp),
                  )
                } else {
                  Icon(
                    Icons.Outlined.Send,
                    contentDescription = "发送",
                    modifier = Modifier.width(18.dp),
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

/** 气泡：限高 + 可上下滑看全文；点文字进入编辑 */
@Composable
private fun BubbleContent(
  state: VoiceCaptureUiState,
  shown: String,
  onTapText: () -> Unit,
) {
  val scroll = rememberScrollState()
  val fullReply = state.reply.orEmpty()
  var revealed by remember(fullReply) {
    mutableIntStateOf(if (fullReply.isEmpty()) 0 else fullReply.length)
  }

  LaunchedEffect(fullReply) {
    if (fullReply.isNotEmpty()) {
      revealed = 0
      while (revealed < fullReply.length) {
        delay(18)
        revealed = (revealed + 2).coerceAtMost(fullReply.length)
        scroll.scrollTo(scroll.maxValue)
      }
    }
  }

  Column(
    modifier = Modifier
      .heightIn(max = 220.dp)
      .verticalScroll(scroll),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    // 「你说」和「Cat」分成两块，各自标题 + 各自底色，不糊成一条（07 2026-10-29）
    if (shown.isNotBlank()) {
      Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
      ) {
        Column(modifier = Modifier.padding(10.dp)) {
          Text(
            text = "你说",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = shown,
            modifier = Modifier
              .padding(top = 3.dp)
              .clickable(onClick = onTapText),
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }
    if (fullReply.isNotEmpty()) {
      Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
      ) {
        Column(modifier = Modifier.padding(10.dp)) {
          Text(
            text = "Cat",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
          )
          Text(
            text = fullReply.take(revealed),
            modifier = Modifier.padding(top = 3.dp),
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }
  }
}

private fun overlayStatus(
  state: VoiceCaptureUiState,
  elapsed: Int,
  hasContent: Boolean,
): String = when {
  state.error != null -> state.error
  state.sending -> "思考中 ${elapsed}s"
  state.captureState == CaptureState.Listening -> "正在听…"
  state.captureState == CaptureState.Finalizing -> "收尾中…"
  state.reply != null -> "已回复"
  hasContent -> "可点文字修改"
  else -> "按住可拖动"
}
