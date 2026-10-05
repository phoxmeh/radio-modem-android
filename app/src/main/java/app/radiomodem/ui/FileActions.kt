package app.radiomodem.ui

/** Activity-owned actions that need the system file picker or a permission dialog. */
class FileActions(
    val playWav: () -> Unit = {},
    val useMic: () -> Unit = {},
    val requestMic: () -> Unit = {},
    /** Leave the screen but keep listening (foreground service). */
    val keepRunning: () -> Unit = {},
    /** Stop listening and close the app. */
    val quit: () -> Unit = {},
    val importMemories: () -> Unit = {},
    val exportMemoriesJson: () -> Unit = {},
    val exportMemoriesCsv: () -> Unit = {},
)
