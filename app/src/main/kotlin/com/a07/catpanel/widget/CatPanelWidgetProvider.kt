package com.a07.catpanel.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.a07.catpanel.MainActivity
import com.a07.catpanel.R
import com.a07.catpanel.panel.PanelEndpoint
import com.a07.catpanel.panel.PanelPreferences
import com.a07.catpanel.voice.VoiceCaptureActivity

class CatPanelWidgetProvider : AppWidgetProvider() {
  override fun onUpdate(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray,
  ) {
    appWidgetIds.forEach { appWidgetId ->
      appWidgetManager.updateAppWidget(appWidgetId, buildRemoteViews(context))
    }
  }

  companion object {
    fun updateAll(context: Context) {
      val manager = AppWidgetManager.getInstance(context)
      val component = ComponentName(context, CatPanelWidgetProvider::class.java)
      manager.getAppWidgetIds(component).forEach { widgetId ->
        manager.updateAppWidget(widgetId, buildRemoteViews(context))
      }
    }

    private fun buildRemoteViews(context: Context): RemoteViews {
      val preferences = PanelPreferences(context)
      val isConnected = PanelEndpoint.isUsable(preferences.loadConfig().endpoint)
      val lastReply = preferences.latestCatReply()
        .replace(Regex("\\s+"), " ")
        .take(MAX_PREVIEW_LENGTH)
        .ifBlank { if (isConnected) "点“说话”开始一条新消息" else "先点“展开”完成连接设置" }

      return RemoteViews(context.packageName, R.layout.cat_panel_widget).apply {
        setTextViewText(R.id.widget_status, if (isConnected) "已准备好" else "尚未连接")
        setTextViewText(R.id.widget_reply, lastReply)
        setOnClickPendingIntent(
          R.id.widget_mic,
          if (isConnected) voiceIntent(context) else activityIntent(context, startVoice = false, requestCode = 10),
        )
        setOnClickPendingIntent(R.id.widget_expand, activityIntent(context, startVoice = false, requestCode = 11))
        setOnClickPendingIntent(R.id.widget_root, activityIntent(context, startVoice = false, requestCode = 12))
      }
    }

    private fun activityIntent(context: Context, startVoice: Boolean, requestCode: Int): PendingIntent {
      val intent = Intent(context, MainActivity::class.java).apply {
        putExtra(MainActivity.EXTRA_START_VOICE, startVoice)
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      }
      return PendingIntent.getActivity(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    }

    private fun voiceIntent(context: Context): PendingIntent {
      val intent = Intent(context, VoiceCaptureActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      }
      return PendingIntent.getActivity(
        context,
        10,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    }

    private const val MAX_PREVIEW_LENGTH = 88
  }
}
