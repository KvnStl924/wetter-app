package de.schee.wetter.log

import java.text.SimpleDateFormat
import java.util.Date


/** Reine Hilfsfunktionen ohne Android, damit sie per Unit-Test geprüft werden können. */
object LogFormat {
    data class FileInfo(val path: String, val bytes: Long, val modified: Long)

    /** "21:14:03.120  TIPP    Kachel „Regenradar“ → Vollbild geöffnet" */
    fun line(epochMs: Long, category: String, message: String, fmt: SimpleDateFormat): String =
        fmt.format(Date(epochMs)) + "  " + category.padEnd(7) + " " + message.replace('\n', ' ')

    /** Älteste zuerst löschen, bis die Summe unter [limit] liegt. */
    fun filesToDelete(files: List<FileInfo>, limit: Long): List<String> {
        var total = files.sumOf { it.bytes }
        if (total <= limit) return emptyList()
        val out = mutableListOf<String>()
        for (f in files.sortedBy { it.modified }) {
            if (total <= limit) break
            out += f.path
            total -= f.bytes
        }
        return out
    }
}
