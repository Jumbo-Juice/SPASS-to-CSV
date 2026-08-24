package com.jumbojuice.spasstocsv

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.jumbojuice.spasstocsv.core.CsvOptions
import com.jumbojuice.spasstocsv.core.CsvWriter
import com.jumbojuice.spasstocsv.core.SpassConverter
import com.jumbojuice.spasstocsv.core.SpassException
import com.jumbojuice.spasstocsv.core.SpassTable
import java.io.File

/**
 * All filesystem access, done through the Storage Access Framework.
 *
 * Every read and write targets a `content://` URI the user picked, so the app needs no
 * storage permission and never hardcodes a path.
 */
object DocumentIo {

    /** Reads a user-picked document into memory, with a size guard. */
    @Throws(SpassException::class)
    fun readDocument(context: Context, uri: Uri): ByteArray {
        val declaredSize = querySize(context, uri)
        if (declaredSize != null && declaredSize > SpassConverter.MAX_INPUT_BYTES) {
            throw SpassException.UnableToRead(
                "That file is ${declaredSize / (1024 * 1024)} MB. The limit is " +
                    "${SpassConverter.MAX_INPUT_BYTES / (1024 * 1024)} MB."
            )
        }

        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes()
            } ?: throw SpassException.UnableToRead(
                "Android did not grant access to the selected file. Try picking it again."
            )
        } catch (e: SpassException) {
            throw e
        } catch (e: Exception) {
            throw SpassException.UnableToRead(
                "Could not read the selected file: ${e.message ?: e.javaClass.simpleName}", e,
            )
        }
    }

    /** Writes a table as CSV to a location the user chose with ACTION_CREATE_DOCUMENT. */
    @Throws(SpassException::class)
    fun writeCsv(context: Context, uri: Uri, table: SpassTable, options: CsvOptions = CsvOptions()) {
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                CsvWriter.writeTo(table, output, options)
            } ?: throw SpassException.CsvGenerationFailure(
                "Android did not grant write access to the chosen location."
            )
        } catch (e: SpassException) {
            throw e
        } catch (e: Exception) {
            throw SpassException.CsvGenerationFailure(
                "Could not save the CSV: ${e.message ?: e.javaClass.simpleName}", e,
            )
        }
    }

    /**
     * Writes the CSV into the app's cache and returns a `content://` URI for the share
     * sheet. The cache folder is emptied first so an old export is never shared by
     * mistake.
     */
    @Throws(SpassException::class)
    fun stageForSharing(
        context: Context,
        table: SpassTable,
        fileName: String,
        options: CsvOptions = CsvOptions(),
    ): Uri {
        return try {
            val directory = File(context.cacheDir, SHARE_DIRECTORY)
            directory.deleteRecursively()
            directory.mkdirs()

            val file = File(directory, fileName)
            file.outputStream().use { output -> CsvWriter.writeTo(table, output, options) }

            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: SpassException) {
            throw e
        } catch (e: Exception) {
            throw SpassException.CsvGenerationFailure(
                "Could not prepare the CSV for sharing: ${e.message ?: e.javaClass.simpleName}", e,
            )
        }
    }

    /** Removes any CSV left staged for sharing. */
    fun clearShareCache(context: Context) {
        runCatching { File(context.cacheDir, SHARE_DIRECTORY).deleteRecursively() }
    }

    /** The human-readable name of a picked document, if the provider supplies one. */
    fun queryDisplayName(context: Context, uri: Uri): String? = queryColumn(
        context, uri, OpenableColumns.DISPLAY_NAME,
    ) { cursor, index -> cursor.getString(index) }

    private fun querySize(context: Context, uri: Uri): Long? = queryColumn(
        context, uri, OpenableColumns.SIZE,
    ) { cursor, index -> if (cursor.isNull(index)) null else cursor.getLong(index) }

    private fun <T> queryColumn(
        context: Context,
        uri: Uri,
        column: String,
        read: (Cursor, Int) -> T?,
    ): T? = runCatching {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(column)
            if (index < 0) null else read(cursor, index)
        }
    }.getOrNull()

    private const val SHARE_DIRECTORY = "shared"
}
