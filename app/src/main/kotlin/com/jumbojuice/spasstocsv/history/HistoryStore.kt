package com.jumbojuice.spasstocsv.history

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * One past conversion.
 *
 * Metadata only. No credential, no file content and no file path is ever recorded --
 * only what is needed to show "you converted this, then".
 */
data class HistoryEntry(
    val sourceName: String,
    val outputName: String,
    val tableName: String,
    val rowCount: Int,
    val columnCount: Int,
    val timestampMillis: Long,
) {
    fun formattedTime(): String = DateFormat
        .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(timestampMillis))
}

/**
 * A tiny append-only log of recent conversions, kept in the app's private storage as
 * JSON. Nothing is uploaded or synchronised; uninstalling the app removes it, and
 * backup is disabled in the manifest so it never leaves the device.
 */
class HistoryStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()

    fun load(): List<HistoryEntry> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.toEntry()
            }
        } catch (e: Exception) {
            // A corrupt history file must never stop the app from converting.
            emptyList()
        }
    }

    fun add(entry: HistoryEntry): List<HistoryEntry> = synchronized(lock) {
        val updated = (listOf(entry) + load()).take(MAX_ENTRIES)
        persist(updated)
        updated
    }

    fun clear(): List<HistoryEntry> = synchronized(lock) {
        runCatching { file.delete() }
        emptyList()
    }

    private fun persist(entries: List<HistoryEntry>) {
        val array = JSONArray()
        for (entry in entries) array.put(entry.toJson())
        runCatching { file.writeText(array.toString()) }
    }

    private fun HistoryEntry.toJson(): JSONObject = JSONObject().apply {
        put(KEY_SOURCE, sourceName)
        put(KEY_OUTPUT, outputName)
        put(KEY_TABLE, tableName)
        put(KEY_ROWS, rowCount)
        put(KEY_COLUMNS, columnCount)
        put(KEY_TIME, timestampMillis)
    }

    private fun JSONObject.toEntry(): HistoryEntry? {
        val source = optString(KEY_SOURCE).takeIf { it.isNotEmpty() } ?: return null
        return HistoryEntry(
            sourceName = source,
            outputName = optString(KEY_OUTPUT),
            tableName = optString(KEY_TABLE),
            rowCount = optInt(KEY_ROWS),
            columnCount = optInt(KEY_COLUMNS),
            timestampMillis = optLong(KEY_TIME),
        )
    }

    private companion object {
        const val FILE_NAME = "conversion-history.json"
        const val MAX_ENTRIES = 50

        const val KEY_SOURCE = "source"
        const val KEY_OUTPUT = "output"
        const val KEY_TABLE = "table"
        const val KEY_ROWS = "rows"
        const val KEY_COLUMNS = "columns"
        const val KEY_TIME = "time"
    }
}
