package com.jumbojuice.spasstocsv

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jumbojuice.spasstocsv.ui.ConverterScreen
import com.jumbojuice.spasstocsv.ui.SpassToCsvTheme

/**
 * The whole app: one screen, backed by [ConverterViewModel].
 *
 * File access goes through the Storage Access Framework
 * ([ActivityResultContracts.OpenDocument] to read, [ActivityResultContracts.CreateDocument]
 * to write), so no storage permission is declared or needed.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // Decrypted credentials are on screen, so keep them out of screenshots and the
        // recent-apps thumbnail.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            val viewModel: ConverterViewModel = viewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()

            // Any file type: `.spass` has no registered MIME type, so restricting the
            // picker would hide the very files the user needs.
            val openDocument = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> uri?.let(viewModel::onFileSelected) }

            val createDocument = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument(CSV_MIME_TYPE)
            ) { uri -> uri?.let(viewModel::saveCsv) }

            LaunchedEffect(state.pendingShare) {
                val uri = state.pendingShare
                if (uri != null) {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = CSV_MIME_TYPE
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching {
                        startActivity(Intent.createChooser(share, "Share CSV"))
                    }.onFailure {
                        Toast.makeText(
                            this@MainActivity,
                            "No app available to receive the CSV.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    viewModel.onShareHandled()
                }
            }

            SpassToCsvTheme {
                ConverterScreen(
                    state = state,
                    onSelectFile = { openDocument.launch(arrayOf("*/*")) },
                    onSubmitPassword = viewModel::submitPassword,
                    onDismissPassword = viewModel::dismissPasswordPrompt,
                    onSelectTable = viewModel::selectTable,
                    onToggleReveal = viewModel::toggleReveal,
                    onSaveCsv = { createDocument.launch(viewModel.suggestedFileName()) },
                    onShare = viewModel::shareCsv,
                    onClearHistory = viewModel::clearHistory,
                    onReset = viewModel::reset,
                    onNoticeShown = viewModel::dismissNotice,
                )
            }
        }
    }

    private companion object {
        const val CSV_MIME_TYPE = "text/csv"
    }
}
