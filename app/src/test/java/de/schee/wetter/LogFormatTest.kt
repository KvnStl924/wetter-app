package de.schee.wetter

import de.schee.wetter.log.LogFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class LogFormatTest {
    @Test fun lineHasTimeCategoryAndMessage() {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.GERMANY).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val line = LogFormat.line(3_723_456L, "TIPP", "Kachel\nRadar", fmt)
        assertEquals("01:02:03.456  TIPP    Kachel Radar", line)
    }

    @Test fun deletesOldestUntilUnderLimit() {
        val files = listOf(
            LogFormat.FileInfo("neu", 5, 300),
            LogFormat.FileInfo("alt", 5, 100),
            LogFormat.FileInfo("mittel", 5, 200),
        )
        assertEquals(listOf("alt", "mittel"), LogFormat.filesToDelete(files, 6))
        assertTrue(LogFormat.filesToDelete(files, 15).isEmpty())
    }
}
