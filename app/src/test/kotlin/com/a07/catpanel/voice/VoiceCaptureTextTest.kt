package com.a07.catpanel.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCaptureTextTest {
  @Test
  fun `new speech segment appends instead of replacing reviewed draft`() {
    assertEquals("第一段 第二段", appendRecognizedText("第一段", "第二段"))
  }

  @Test
  fun `blank recognition result leaves existing draft intact`() {
    assertEquals("已经修改过的文字", appendRecognizedText("已经修改过的文字", "   "))
  }
}
