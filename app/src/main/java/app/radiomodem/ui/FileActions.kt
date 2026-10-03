package app.radiomodem.ui

/** Activity-owned actions that need the system file picker or a permission dialog. */
class FileActions(
    val playWav: () -> Unit = {},
    val useMic: () -> Unit = {},
    val requestMic: () -> Unit = {},
    val importMemories: () -> Unit = {},
    val exportMemoriesJson: () -> Unit = {},
    val exportMemoriesCsv: () -> Unit = {},
)
