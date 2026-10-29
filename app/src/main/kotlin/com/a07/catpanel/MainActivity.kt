package com.a07.catpanel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.a07.catpanel.panel.CatPanelApp
import com.a07.catpanel.ui.theme.CatTheme
import com.a07.catpanel.widget.CatPanelWidgetProvider

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    enableEdgeToEdge()
    val startVoice = intent?.getBooleanExtra(EXTRA_START_VOICE, false) == true
    setContent {
      CatTheme(darkTheme = true) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          CatPanelApp(startVoiceOnLaunch = startVoice)
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    // Existing widgets retain old PendingIntents across a package replacement
    // until the provider is refreshed. Do that whenever the panel returns.
    CatPanelWidgetProvider.updateAll(this)
  }

  companion object {
    const val EXTRA_START_VOICE = "com.a07.catpanel.START_VOICE"
  }
}
