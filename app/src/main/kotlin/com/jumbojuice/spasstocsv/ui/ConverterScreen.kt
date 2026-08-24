@file:OptIn(ExperimentalMaterial3Api::class)

package com.jumbojuice.spasstocsv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jumbojuice.spasstocsv.ConverterUiState
import com.jumbojuice.spasstocsv.Stage
import com.jumbojuice.spasstocsv.core.SpassTable
import com.jumbojuice.spasstocsv.core.SpassTableType
import com.jumbojuice.spasstocsv.history.HistoryEntry

/** How many rows the preview renders. Enough to judge the conversion, cheap to draw. */
private const val PREVIEW_ROW_LIMIT = 50

/** Columns whose values are masked until the user asks to see them. */
private val SENSITIVE_COLUMNS = setOf(
    "password_value",
    "pw_tz_enc",
    "id_tz_enc",
    "otp",
    "card_number_encrypted",
    "card_security_code",
)

@Composable
fun ConverterScreen(
    state: ConverterUiState,
    onSelectFile: () -> Unit,
    onSubmitPassword: (CharArray) -> Unit,
    onDismissPassword: () -> Unit,
    onSelectTable: (Int) -> Unit,
    onToggleReveal: () -> Unit,
    onSaveCsv: () -> Unit,
    onShare: () -> Unit,
    onClearHistory: () -> Unit,
    onReset: () -> Unit,
    onNoticeShown: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Shared by the preview's header and every preview row so they scroll as one grid.
    val previewScroll = rememberScrollState()

    LaunchedEffect(state.notice) {
        val notice = state.notice
        if (notice != null) {
            snackbarHostState.showSnackbar(notice)
            onNoticeShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SPASS to CSV Converter") },
                actions = {
                    if (state.document != null) {
                        TextButton(onClick = onReset) { Text("Start over") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            item { OfflineBanner() }

            item {
                Button(
                    onClick = onSelectFile,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.stage != Stage.LOADING,
                ) {
                    Text("Select SPASS File")
                }
            }

            item { SelectedFileRow(state) }

            when (state.stage) {
                Stage.NO_FILE -> if (state.passwordPrompt == null && state.sourceFileName == null) {
                    item { EmptyStateCard() }
                }

                Stage.LOADING -> item { LoadingCard() }

                Stage.ERROR -> item { ErrorCard(state) }

                Stage.READY -> {
                    val document = state.document
                    if (document != null) {
                        item { SummaryCard(state) }

                        if (document.tables.size > 1) {
                            item { TableSelector(state, onSelectTable) }
                        }

                        state.selectedTable?.let { table ->
                            item { ColumnsCard(table) }
                            item { PreviewHeader(table, state.revealSecrets, onToggleReveal) }
                            previewTable(table, state.revealSecrets, previewScroll)
                            item {
                                ExportButtons(
                                    enabled = table.rowCount > 0,
                                    onSaveCsv = onSaveCsv,
                                    onShare = onShare,
                                )
                            }
                        }

                        if (state.warnings.isNotEmpty()) {
                            item { WarningsCard(state) }
                        }
                    }
                }
            }

            if (state.history.isNotEmpty()) {
                item { HistorySection(state.history, onClearHistory) }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    state.passwordPrompt?.let { prompt ->
        PasswordDialog(
            fileName = prompt.fileName,
            previousAttemptFailed = prompt.previousAttemptFailed,
            onSubmit = onSubmitPassword,
            onDismiss = onDismissPassword,
        )
    }
}

@Composable
private fun OfflineBanner() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Everything happens on this device",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Your Samsung Pass export is decrypted and converted locally. The app has " +
                    "no internet permission, so nothing can be uploaded. It works in " +
                    "airplane mode.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SelectedFileRow(state: ConverterUiState) {
    val name = state.sourceFileName
    Text(
        text = if (name == null) "No file selected" else "Selected: $name",
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun EmptyStateCard() {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("How to use", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "1. In Samsung Pass, choose Settings, then Export data, and set a password.\n" +
                    "2. Tap Select SPASS File above and pick the .spass file.\n" +
                    "3. Enter the same export password.\n" +
                    "4. Check the preview, then save or share the CSV.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun LoadingCard() {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Decrypting and parsing...", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Key stretching uses 70,000 rounds, so this takes a moment.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ErrorCard(state: ConverterUiState) {
    val error = state.error ?: return
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                error.headline,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                error.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun SummaryCard(state: ConverterUiState) {
    val document = state.document ?: return
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Converted successfully",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            val version = document.formatVersion?.toString() ?: "unknown"
            Text(
                "Export format v$version - ${document.tables.size} table(s), " +
                    "${document.totalRows} row(s) in total.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TableSelector(state: ConverterUiState, onSelectTable: (Int) -> Unit) {
    Column {
        Text("Table to export", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.tables.forEachIndexed { index, table ->
                FilterChip(
                    selected = index == state.selectedTableIndex,
                    onClick = { onSelectTable(index) },
                    label = { Text("${table.displayName()} (${table.rowCount})") },
                )
            }
        }
    }
}

@Composable
private fun ColumnsCard(table: SpassTable) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(
                "${table.rowCount} rows - ${table.columnCount} columns",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                table.headers.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun PreviewHeader(table: SpassTable, revealSecrets: Boolean, onToggleReveal: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shown = minOf(table.rowCount, PREVIEW_ROW_LIMIT)
        Text(
            "Preview (first $shown of ${table.rowCount})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        if (table.headers.any { it.lowercase() in SENSITIVE_COLUMNS }) {
            TextButton(onClick = onToggleReveal) {
                Text(if (revealSecrets) "Hide secrets" else "Reveal secrets")
            }
        }
    }
}

/**
 * The preview grid, emitted straight into the screen's LazyColumn.
 *
 * The rows are items of that one list rather than a nested scrolling container: nesting
 * two vertical scrollers fights for the same gestures. Columns scroll horizontally, and
 * every row shares one [ScrollState] so the header stays aligned with the data.
 */
private fun LazyListScope.previewTable(
    table: SpassTable,
    revealSecrets: Boolean,
    horizontalScroll: ScrollState,
) {
    if (table.rowCount == 0) {
        item {
            Card {
                Text(
                    "This table has no rows.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        return
    }

    item {
        Column {
            Row(Modifier.horizontalScroll(horizontalScroll)) {
                table.headers.forEach { header ->
                    Text(
                        text = header,
                        modifier = Modifier.width(150.dp).padding(end = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(top = 6.dp))
        }
    }

    val visibleRows = table.rows.take(PREVIEW_ROW_LIMIT)
    val sensitive = table.headers.map { it.lowercase() in SENSITIVE_COLUMNS }

    itemsIndexed(visibleRows) { index, row ->
        Row(
            Modifier
                .horizontalScroll(horizontalScroll)
                .background(
                    if (index % 2 == 0) MaterialTheme.colorScheme.surface
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(vertical = 4.dp),
        ) {
            row.forEachIndexed { column, value ->
                val masked = sensitive.getOrElse(column) { false } &&
                    !revealSecrets &&
                    value.isNotEmpty()
                Text(
                    text = if (masked) "*".repeat(minOf(value.length, 10)) else value,
                    modifier = Modifier.width(150.dp).padding(end = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    if (table.rowCount > PREVIEW_ROW_LIMIT) {
        item {
            Text(
                "... and ${table.rowCount - PREVIEW_ROW_LIMIT} more rows. " +
                    "All of them are written to the CSV.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ExportButtons(enabled: Boolean, onSaveCsv: () -> Unit, onShare: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = onSaveCsv, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text("Save CSV")
        }
        OutlinedButton(onClick = onShare, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text("Share")
        }
    }
}

@Composable
private fun WarningsCard(state: ConverterUiState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "${state.warnings.size} note(s) while parsing",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Nothing was dropped -- these describe places the file did not match the " +
                    "expected structure.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            state.warnings.take(20).forEach { warning ->
                Text("- $warning", style = MaterialTheme.typography.bodySmall)
            }
            if (state.warnings.size > 20) {
                Text(
                    "... and ${state.warnings.size - 20} more.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun HistorySection(history: List<HistoryEntry>, onClearHistory: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Recent conversions",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            TextButton(onClick = onClearHistory) { Text("Clear") }
        }
        Text(
            "Stored on this device only: file names, counts and times. No vault data.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Card(shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(vertical = 4.dp)) {
                history.take(10).forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            entry.outputName.ifBlank { entry.sourceName },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${entry.sourceName} - ${entry.tableName} - " +
                                "${entry.rowCount} rows - ${entry.formattedTime()}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordDialog(
    fileName: String,
    previousAttemptFailed: Boolean,
    onSubmit: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export password") },
        text = {
            Column {
                Text(
                    "$fileName is encrypted. Enter the password you set in Samsung Pass " +
                        "when you exported it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text("Password") },
                    isError = previousAttemptFailed,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (password.isNotEmpty()) onSubmit(password.toCharArray()) },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (previousAttemptFailed) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "That password did not decrypt the file. Try again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(password.toCharArray()) },
                enabled = password.isNotEmpty(),
            ) {
                Text("Decrypt")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun SpassTable.displayName(): String = when (type) {
    SpassTableType.PASSWORDS -> "Passwords"
    SpassTableType.CARDS -> "Cards"
    SpassTableType.ADDRESSES -> "Addresses"
    SpassTableType.NOTES -> "Notes"
    SpassTableType.UNKNOWN -> name.replaceFirstChar { it.uppercase() }
}
