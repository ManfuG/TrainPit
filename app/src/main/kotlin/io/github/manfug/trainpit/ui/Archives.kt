package io.github.manfug.trainpit.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.manfug.trainpit.TrainPitController
import io.github.manfug.trainpit.model.ArchivePreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.LocalDate

internal sealed interface ArchiveRequest {
    data class Export(val subjectId: String, val sessionRoots: Set<String>? = null) : ArchiveRequest
    data object Import : ArchiveRequest
}
private const val MAX_ARCHIVE_BYTES = 32 * 1024 * 1024

/** Memory-only operation state; survives credential relocking and configuration changes. */
internal class ArchiveDraft {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var password by mutableStateOf("")
    var confirmation by mutableStateOf("")
    var visible by mutableStateOf(false)
    var processing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
    var file by mutableStateOf<Uri?>(null)
    var bytesToWrite by mutableStateOf<ByteArray?>(null)
    var preview by mutableStateOf<ArchivePreview?>(null)
    fun close() {
        scope.cancel()
        password = ""; confirmation = ""; preview = null; file = null
        bytesToWrite?.fill(0); bytesToWrite = null
    }
}

private fun readBounded(resolver: ContentResolver, uri: Uri): ByteArray {
    return resolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_ARCHIVE_BYTES - total + 1))
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= MAX_ARCHIVE_BYTES) { "Archive exceeds the 32 MiB limit. Select a smaller archive." }
            output.write(buffer, 0, count)
        }
        require(total > 0) { "The selected file is empty." }
        output.toByteArray()
    } ?: throw IllegalArgumentException("The selected file could not be opened.")
}

@Composable
internal fun ArchiveWorkflow(request: ArchiveRequest?, draft: ArchiveDraft, controller: TrainPitController, busy: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = draft.scope
    var password by draft::password
    var confirmation by draft::confirmation
    var visible by draft::visible
    var processing by draft::processing
    var error by draft::error
    var status by draft::status
    var file by draft::file
    var bytesToWrite by draft::bytesToWrite
    var preview by draft::preview
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val data = bytesToWrite
        bytesToWrite = null
        if (uri == null || data == null) { processing = false; status = "Export cancelled. No archive was written." }
        else scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalArgumentException("The destination could not be opened.")
                    output.use { it.write(data); it.flush() }
                }
                password = ""; confirmation = ""; status = "Encrypted archive saved. Keep its password safe and share it separately."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message ?: "Could not save the archive. The destination may contain an incomplete file; delete it and retry."
            } finally { processing = false }
        }
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> file = uri; error = null }
    if (request == null) return
    Dialog(onDismissRequest = { if (!processing) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.94f), shape = MaterialTheme.shapes.large) {
            ScrollPage {
                SectionTitle(if (request is ArchiveRequest.Export) "Export encrypted history" else "Import encrypted archive")
                if (request is ArchiveRequest.Export) {
                    Text("Client UID: ${request.subjectId}")
                    Text(request.sessionRoots?.let { "${it.size} selected session(s), their dependencies and revision ancestry. Unrelated sessions and body weight are excluded." }
                        ?: "Full authorized client history, including accessible body-weight records, signed revisions and deletion evidence.")
                    Text("Anyone with the file and its password can decrypt every included record.", style = MaterialTheme.typography.bodySmall)
                }
                if (preview == null && status == null) {
                    if (request == ArchiveRequest.Import) {
                        OutlinedButton(onClick = { openDocument.launch(arrayOf("*/*")) }, enabled = !processing) { Text(if (file == null) "Choose encrypted archive" else "Choose a different file") }
                        if (file != null) Text("File selected. Maximum encoded size: 32 MiB.", style = MaterialTheme.typography.bodySmall)
                    }
                    PasswordInput(password, { password = it }, "Archive password (12+ characters)", visible, { visible = !visible }, enabled = !processing)
                    if (request is ArchiveRequest.Export) PasswordInput(confirmation, { confirmation = it }, "Confirm archive password", visible, { visible = !visible }, enabled = !processing)
                    Text("Passwords are used only for this operation. They are not saved or logged.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        error = when {
                            password.length < 12 -> "Use an archive password with at least 12 characters."
                            request is ArchiveRequest.Export && password != confirmation -> "The two passwords do not match."
                            request == ArchiveRequest.Import && file == null -> "Choose an encrypted archive first."
                            else -> null
                        }
                        if (error == null) {
                            processing = true
                            scope.launch {
                                try {
                                    val secret = password
                                    when (request) {
                                        is ArchiveRequest.Export -> {
                                            bytesToWrite = withContext(Dispatchers.IO) { controller.exportArchive(request.subjectId, request.sessionRoots, secret).also { require(it.size <= MAX_ARCHIVE_BYTES) { "Export exceeds the 32 MiB file limit. Select fewer sessions." } } }
                                            createDocument.launch("trainpit-${LocalDate.now()}.trainpit")
                                        }
                                        ArchiveRequest.Import -> {
                                            val selected = requireNotNull(file)
                                            preview = withContext(Dispatchers.IO) { controller.previewArchive(readBounded(context.contentResolver, selected), secret) }
                                            password = ""; confirmation = ""; processing = false
                                        }
                                    }
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    error = e.message ?: "Archive operation failed. Check the password, account access and trusted device keys."
                                    bytesToWrite = null; processing = false
                                }
                            }
                        }
                    }, enabled = !processing && !busy) { Text(if (request is ArchiveRequest.Export) "Encrypt & choose destination" else "Decrypt & preview") }
                }
                preview?.let { checked ->
                    val manifest = checked.manifest
                    InfoCard("Review before import") {
                        Text("Client UID: ${manifest.subjectId}")
                        Text("Declared exporter UID: ${manifest.exporterId}")
                        Text(if (manifest.selectedSessions) "Selected-session archive" else "Full-history archive")
                        Text("${manifest.entries.size} signed revisions across ${manifest.entries.map { it.rootId }.distinct().size} records")
                        Text("Plan revisions: ${checked.plans}\nSession revisions: ${checked.sessions}\nBody-weight revisions: ${checked.weights}\nDeletion revisions: ${manifest.entries.count { it.deleted }}")
                        Text("Record signatures and current access verified. The declared exporter is not separately signed. Import preserves branches; owners resolve conflicts in History.")
                    }
                    Button(onClick = { controller.importArchive(checked); password = ""; preview = null; onClose() }, enabled = !busy) { Text("Confirm import") }
                }
                if (processing) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Processing archive…") }
                ErrorText(error)
                status?.let { Text(it) }
                TextButton(onClick = { password = ""; confirmation = ""; preview = null; onClose() }, enabled = !processing) { Text(if (status != null) "Done" else "Cancel") }
            }
        }
    }
}
