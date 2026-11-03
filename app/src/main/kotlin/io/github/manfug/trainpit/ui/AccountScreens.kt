package io.github.manfug.trainpit.ui

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.manfug.trainpit.TrainPitController
import io.github.manfug.trainpit.model.*
import kotlinx.coroutines.launch

@Composable
internal fun AuthScreen(controller: TrainPitController, busy: Boolean) {
    var registering by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(Role.CLIENT) }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ScrollPage {
        Spacer(Modifier.height(20.dp))
        Text("TRAINPIT", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        SectionTitle(if (registering) "Create your account" else "Sign in", "Sign in once online. Your encrypted local records remain available offline.")
        if (registering) {
            Input(name, { name = it }, "Your display name")
            Choice("Account role", if (role == Role.CLIENT) "Client" else "Trainer", listOf("Client", "Trainer")) { role = if (it == 0) Role.CLIENT else Role.TRAINER }
            Text("Your role cannot be changed after registration. Trainers write plans; clients record their own training and control sharing.", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email address") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        PasswordInput(password, { password = it }, if (registering) "Password (10+ characters)" else "Password", visible, { visible = !visible })
        ErrorText(error)
        Button(onClick = {
            error = when {
                registering && (name.trim().isEmpty() || name.trim().length > 100) -> "Enter a display name of 1–100 characters."
                !email.trim().contains('@') || email.trim().substringAfter('@').isBlank() -> "Enter a valid email address."
                registering && password.length < 10 -> "Create a password with at least 10 characters."
                password.isEmpty() -> "Enter your account password."
                else -> null
            }
            if (error == null) {
                if (registering) controller.register(name.trim(), email.trim(), password, role) else controller.signIn(email.trim(), password)
            }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Please wait…" else if (registering) "Create account" else "Sign in") }
        TextButton(onClick = { registering = !registering; password = ""; error = null }, enabled = !busy) { Text(if (registering) "Already registered? Sign in" else "New here? Create account") }
        if (!registering) TextButton(onClick = {
            if (!email.trim().contains('@') || email.trim().substringAfter('@').isBlank()) error = "Enter your account email above to request a password reset."
            else { error = null; controller.resetPassword(email.trim()) }
        }, enabled = !busy) { Text("Send password reset email") }
        Text("Cloud sync uses Firebase encryption in transit and at rest, not end-to-end encryption. Share only with people you trust.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PasswordInput(value: String, onChange: (String) -> Unit, label: String, visible: Boolean, toggle: () -> Unit, enabled: Boolean = true) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { TextButton(onClick = toggle) { Text(if (visible) "Hide" else "Show") } })
}

@Composable
internal fun ConnectionsScreen(profile: Profile, state: LocalState, controller: TrainPitController, busy: Boolean, openClient: (String) -> Unit, newPlan: (String) -> Unit) {
    var clientId by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var ending by remember { mutableStateOf<Relationship?>(null) }
    var accepting by remember { mutableStateOf<Relationship?>(null) }
    var sharing by remember { mutableStateOf<Relationship?>(null) }
    var rejecting by remember { mutableStateOf<Relationship?>(null) }
    val connections = remember(state.relationships, profile.uid) { state.relationships.filter { it.includes(profile.uid) }.sortedBy { it.otherName(profile.uid).lowercase() } }
    ScrollPage {
        SectionTitle(if (profile.role == Role.TRAINER) "Your clients" else "Your trainers")
        if (profile.role == Role.TRAINER) InfoCard("Invite a client") {
            Input(clientId, { clientId = it }, "Client account UID")
            ErrorText(error)
            Button(onClick = {
                error = if (clientId.trim().isBlank() || clientId.trim().length > 128 || clientId.trim() == profile.uid) "Enter another client's valid account UID." else null
                if (error == null) controller.requestClient(clientId.trim())
            }, enabled = !busy) { Text("Send connection request") }
        }
        if (connections.isEmpty()) InfoCard(if (profile.role == Role.CLIENT) "No trainer yet" else "No clients yet") {}
        connections.forEach { relationship ->
            InfoCard(relationship.otherName(profile.uid)) {
                Text("Account UID: ${relationship.otherId(profile.uid)}", style = MaterialTheme.typography.bodySmall)
                Text(when (relationship.status) { RelationshipStatus.PENDING -> "Awaiting client consent"; RelationshipStatus.ACTIVE -> "Connected"; RelationshipStatus.ENDED -> "Connection ended" }, color = MaterialTheme.colorScheme.primary)
                if (relationship.status == RelationshipStatus.PENDING) {
                    if (profile.role == Role.CLIENT) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { accepting = relationship }, enabled = !busy) { Text("Review & accept") }
                            OutlinedButton(onClick = { rejecting = relationship }, enabled = !busy) { Text("Decline") }
                        }
                    }
                }
                if (relationship.status == RelationshipStatus.ACTIVE) {
                    if (profile.role == Role.CLIENT) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Share body-weight history", Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge)
                            Switch(relationship.shareBodyWeight, onCheckedChange = { enabled -> if (enabled) sharing = relationship else controller.setWeightSharing(relationship.id, false) }, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Share body-weight history with ${relationship.trainerName}" })
                        }
                    } else {
                        Text(if (relationship.shareBodyWeight) "Body weight shared" else "Body weight private", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { openClient(relationship.clientId) }) { Text("Open client") }
                            OutlinedButton(onClick = { newPlan(relationship.clientId) }, enabled = !busy) { Text("Create plan") }
                        }
                    }
                    TextButton(onClick = { ending = relationship }, enabled = !busy) { Text("End connection") }
                }
            }
        }
    }
    accepting?.let { r -> ConfirmDialog("Share training with ${r.trainerName}?", "This grants access to your complete training-session history and future sessions while connected. Body weight stays private until you enable its separate consent.", "Accept connection", { accepting = null }, { controller.respondRelationship(r.id, true); accepting = null }) }
    rejecting?.let { r -> ConfirmDialog("Decline connection?", "${r.trainerName} will not gain access to your training records.", "Decline", { rejecting = null }, { controller.respondRelationship(r.id, false); rejecting = null }) }
    sharing?.let { r -> ConfirmDialog("Share body weight?", "${r.trainerName} will be able to read your complete body-weight history and future measurements while this consent is enabled. Previously received copies cannot be recalled.", "Share body weight", { sharing = null }, { controller.setWeightSharing(r.id, true); sharing = null }) }
    ending?.let { r -> ConfirmDialog("End connection?", "New access to plans and client records will stop. This does not erase records already received or exported.", "End connection", { ending = null }, { controller.endRelationship(r.id); ending = null }) }
}

@Composable
internal fun SettingsScreen(profile: Profile, state: LocalState, syncLabel: String, busy: Boolean, controller: TrainPitController, export: (String) -> Unit, onImport: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    ScrollPage {
        SectionTitle("Settings", "${profile.displayName} · ${if (profile.role == Role.TRAINER) "Trainer" else "Client"}")
        InfoCard("Account") {
            Text("Account UID", style = MaterialTheme.typography.labelMedium)
            Text(profile.uid, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("TrainPit account UID", profile.uid))); copied = true } }) { Text(if (copied) "UID copied" else "Copy account UID") }
        }
        InfoCard("Sync") {
            Text(syncLabel)
            Text("${state.pendingCount} pending upload(s)")
            Button(onClick = controller::sync, enabled = !busy) { Text("Sync now") }
        }
        InfoCard("Encrypted archives") {
            if (profile.role == Role.CLIENT) OutlinedButton(onClick = { export(profile.uid) }, enabled = !busy) { Text("Export full client history") }
            else state.activeRelationships.filter { it.trainerId == profile.uid }.forEach { relationship ->
                OutlinedButton(onClick = { export(relationship.clientId) }, enabled = !busy) { Text("Export ${relationship.clientName}'s history") }
            }
            OutlinedButton(onClick = onImport, enabled = !busy) { Text("Import encrypted archive") }
        }
        InfoCard("Privacy & local storage") {
            OutlinedButton(onClick = { confirm = "signout" }, enabled = !busy) { Text("Sign out") }
            OutlinedButton(onClick = { confirm = "local" }, enabled = !busy) { Text("Erase this account's local data") }
            TextButton(onClick = { confirm = "records" }, enabled = !busy) { Text("Delete all records I own") }
        }
    }
    when (confirm) {
        "signout" -> ConfirmDialog("Sign out?", "Your encrypted local records remain on this device. Unsynced revisions will upload when you sign in again. Signing in again may require internet.", "Sign out", { confirm = null }, { confirm = null; controller.signOut() })
        "local" -> ConfirmDialog("Erase local data?", "This removes this account's encrypted database and local keys from this device and signs you out. ${state.pendingCount} pending revision(s) will be lost. Cloud records and other devices are not erased. Export important records first.", "Erase local data", { confirm = null }, { confirm = null; controller.eraseLocalData() })
        "records" -> ConfirmDialog("Delete all records you own?", "This hides your ${if (profile.role == Role.TRAINER) "training plans" else "sessions and body-weight records"} using signed deletion markers that synchronize to authorized devices. Historical revisions and cloud backups are not securely erased. Previously shared or exported copies cannot be recalled. Records owned by other people are unchanged.", "Delete owned records", { confirm = null }, { confirm = null; controller.deleteAllOwnedRecords() })
    }
}
