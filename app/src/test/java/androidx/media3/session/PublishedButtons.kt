package androidx.media3.session

/**
 * The buttons Media3 publishes to the media notification and Android's media controls from a
 * session's media button preferences: the same call both make, so a test sees what a phone would.
 * In Media3's package because the call is package-private.
 */
fun publishedButtons(preferences: List<CommandButton>): List<CommandButton> =
    CommandButton.getCustomLayoutFromMediaButtonPreferences(preferences, true, true, 10)
