package com.a07.catpanel.voice

/**
 * Each recognition run is one editable segment.  A later run must never replace
 * text that the user has already reviewed or typed.
 */
internal fun appendRecognizedText(existing: String, segment: String): String {
  val stable = existing.trim()
  val incoming = segment.trim()
  return when {
    incoming.isEmpty() -> stable
    stable.isEmpty() -> incoming
    stable.last().isWhitespace() -> stable + incoming
    else -> "$stable $incoming"
  }
}
