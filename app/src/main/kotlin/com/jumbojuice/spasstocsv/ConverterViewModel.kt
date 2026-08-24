package com.jumbojuice.spasstocsv

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jumbojuice.spasstocsv.core.ConversionWarning
import com.jumbojuice.spasstocsv.core.SpassConverter
import com.jumbojuice.spasstocsv.core.SpassDocument
import com.jumbojuice.spasstocsv.core.SpassException
import com.jumbojuice.spasstocsv.core.SpassTable
import com.jumbojuice.spasstocsv.history.HistoryEntry
import com.jumbojuice.spasstocsv.history.HistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which of the five screen states the UI is in. */
enum class Stage { NO_FILE, LOADING, READY, ERROR }

/** A failure, split so the UI can show a short headline plus the detail. */
data class ErrorInfo(val headline: String, val detail: String)

/** Shown while the app waits for the export password. */
data class PasswordPrompt(val fileName: String, val previousAttemptFailed: Boolean = false)

data class ConverterUiState(
    val stage: Stage = Stage.NO_FILE,
    val sourceFileName: String? = null,
    val document: SpassDocument? = null,
    val selectedTableIndex: Int = 0,
    val passwordPrompt: PasswordPrompt? = null,
    val error: ErrorInfo? = null,
    /** Transient confirmation, e.g. "Saved passwords.csv". */
    val notice: String? = null,
    val revealSecrets: Boolean = false,
    val history: List<HistoryEntry> = emptyList(),
    /** Set when a CSV has been staged and the share sheet should open. */
    val pendingShare: Uri? = null,
) {
    val tables: List<SpassTable> get() = document?.tables.orEmpty()

    val selectedTable: SpassTable?
        get() = tables.getOrNull(selectedTableIndex)

    val warnings: List<ConversionWarning> get() = document?.warnings.orEmpty()
}

/**
 * Owns the conversion. Every step -- decrypt, parse, generate CSV -- runs here on a
 * background dispatcher, entirely on the device.
 */
class ConverterViewModel(application: Application) : AndroidViewModel(application) {

    private val historyStore = HistoryStore(application)

    private val _state = MutableStateFlow(ConverterUiState())
    val state: StateFlow<ConverterUiState> = _state.asStateFlow()

    /** Encrypted bytes held only while waiting for the user to type the password. */
    private var pendingBytes: ByteArray? = null

    init {
        viewModelScope.launch {
            val history = withContext(Dispatchers.IO) { historyStore.load() }
            _state.update { it.copy(history = history) }
        }
    }

    fun onFileSelected(uri: Uri) {
        val context = getApplication<Application>()
        clearPendingBytes()

        _state.update {
            it.copy(
                stage = Stage.LOADING,
                document = null,
                selectedTableIndex = 0,
                error = null,
                notice = null,
                passwordPrompt = null,
                revealSecrets = false,
            )
        }

        viewModelScope.launch {
            try {
                val (name, bytes) = withContext(Dispatchers.IO) {
                    val displayName = DocumentIo.queryDisplayName(context, uri)
                    displayName to DocumentIo.readDocument(context, uri)
                }
                val fileName = name ?: "selected file"
                _state.update { it.copy(sourceFileName = fileName) }

                if (withContext(Dispatchers.Default) { SpassConverter.requiresPassword(bytes) }) {
                    pendingBytes = bytes
                    _state.update {
                        it.copy(stage = Stage.NO_FILE, passwordPrompt = PasswordPrompt(fileName))
                    }
                } else {
                    parse(bytes, password = null)
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun submitPassword(password: CharArray) {
        val bytes = pendingBytes
        if (bytes == null) {
            password.fill(' ')
            return
        }
        _state.update { it.copy(stage = Stage.LOADING, passwordPrompt = null) }
        viewModelScope.launch { parse(bytes, password) }
    }

    fun dismissPasswordPrompt() {
        clearPendingBytes()
        _state.update { it.copy(passwordPrompt = null, stage = Stage.NO_FILE) }
    }

    private suspend fun parse(bytes: ByteArray, password: CharArray?) {
        try {
            val document = withContext(Dispatchers.Default) {
                try {
                    SpassConverter.convert(bytes, password)
                } finally {
                    password?.fill(' ')
                }
            }

            // Show a table that actually has rows, rather than an empty one.
            val firstUseful = document.tables.indexOfFirst { !it.isEmpty }.coerceAtLeast(0)

            clearPendingBytes()
            _state.update {
                it.copy(
                    stage = Stage.READY,
                    document = document,
                    selectedTableIndex = firstUseful,
                    error = null,
                    passwordPrompt = null,
                )
            }
        } catch (e: SpassException.WrongPasswordOrCorrupt) {
            // Keep the bytes so the user can simply try another password.
            val fileName = _state.value.sourceFileName ?: "selected file"
            _state.update {
                it.copy(
                    stage = Stage.NO_FILE,
                    passwordPrompt = PasswordPrompt(fileName, previousAttemptFailed = true),
                    error = null,
                )
            }
        } catch (e: Exception) {
            clearPendingBytes()
            fail(e)
        }
    }

    fun selectTable(index: Int) {
        _state.update { it.copy(selectedTableIndex = index, revealSecrets = false, notice = null) }
    }

    fun toggleReveal() {
        _state.update { it.copy(revealSecrets = !it.revealSecrets) }
    }

    /** Name to pre-fill in the system save dialog. */
    fun suggestedFileName(): String = SpassConverter.suggestCsvName(
        _state.value.sourceFileName,
        _state.value.selectedTable?.name ?: "export",
    )

    fun saveCsv(uri: Uri) {
        val table = _state.value.selectedTable ?: return
        val context = getApplication<Application>()

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { DocumentIo.writeCsv(context, uri, table) }

                val outputName = DocumentIo.queryDisplayName(context, uri) ?: suggestedFileName()
                recordHistory(table, outputName)
                _state.update { it.copy(notice = "Saved $outputName (${table.rowCount} rows).") }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun shareCsv() {
        val table = _state.value.selectedTable ?: return
        val context = getApplication<Application>()
        val fileName = suggestedFileName()

        viewModelScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    DocumentIo.stageForSharing(context, table, fileName)
                }
                recordHistory(table, fileName)
                _state.update { it.copy(pendingShare = uri) }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** Called once the share sheet has been shown. */
    fun onShareHandled() {
        _state.update { it.copy(pendingShare = null) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun clearHistory() {
        viewModelScope.launch {
            val cleared = withContext(Dispatchers.IO) { historyStore.clear() }
            _state.update { it.copy(history = cleared, notice = "History cleared.") }
        }
    }

    /** Drops the loaded vault from memory and returns to the empty state. */
    fun reset() {
        clearPendingBytes()
        DocumentIo.clearShareCache(getApplication())
        _state.update { ConverterUiState(history = it.history) }
    }

    private suspend fun recordHistory(table: SpassTable, outputName: String) {
        val entry = HistoryEntry(
            sourceName = _state.value.sourceFileName ?: "unknown",
            outputName = outputName,
            tableName = table.name,
            rowCount = table.rowCount,
            columnCount = table.columnCount,
            timestampMillis = System.currentTimeMillis(),
        )
        val updated = withContext(Dispatchers.IO) { historyStore.add(entry) }
        _state.update { it.copy(history = updated) }
    }

    private fun fail(e: Throwable) {
        _state.update {
            it.copy(stage = Stage.ERROR, error = describe(e), passwordPrompt = null)
        }
    }

    /** Turns an exception into a headline plus a detail line the user can act on. */
    private fun describe(e: Throwable): ErrorInfo {
        val detail = e.message ?: e.javaClass.simpleName
        return when (e) {
            is SpassException.UnsupportedFileFormat -> ErrorInfo("Unsupported file format", detail)
            is SpassException.UnableToRead -> ErrorInfo("Unable to read file", detail)
            is SpassException.WrongPasswordOrCorrupt -> ErrorInfo("Could not decrypt", detail)
            is SpassException.InvalidStructure -> ErrorInfo("Invalid SPASS structure", detail)
            is SpassException.CsvGenerationFailure -> ErrorInfo("CSV generation failed", detail)
            is OutOfMemoryError -> ErrorInfo(
                "File too large",
                "The device ran out of memory reading this export.",
            )
            else -> ErrorInfo("Something went wrong", detail)
        }
    }

    private fun clearPendingBytes() {
        pendingBytes?.fill(0)
        pendingBytes = null
    }

    override fun onCleared() {
        clearPendingBytes()
        DocumentIo.clearShareCache(getApplication())
        super.onCleared()
    }
}
