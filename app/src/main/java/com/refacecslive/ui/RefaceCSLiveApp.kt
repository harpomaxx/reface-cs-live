package com.refacecslive.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.refacecslive.AppViewModel
import com.refacecslive.model.Patch

private enum class Screen { PERFORMANCE, LIBRARY, EDITOR }

@Composable
fun RefaceCSLiveApp(viewModel: AppViewModel) {
    var screenName by rememberSaveable { mutableStateOf(Screen.PERFORMANCE.name) }
    var pendingScreen by remember { mutableStateOf<Screen?>(null) }
    val screen = Screen.valueOf(screenName)

    fun navigate(target: Screen) {
        if (screen == Screen.EDITOR && target != Screen.EDITOR && viewModel.editorHasUnsavedChanges) {
            pendingScreen = target
        } else {
            screenName = target.name
        }
    }

    BackHandler(enabled = screen == Screen.EDITOR) {
        navigate(Screen.LIBRARY)
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = screen == Screen.PERFORMANCE,
                    onClick = { navigate(Screen.PERFORMANCE) },
                    icon = { Text("▶", fontSize = 19.sp) },
                    label = { Text("Perform") }
                )
                NavigationBarItem(
                    selected = screen == Screen.LIBRARY,
                    onClick = { navigate(Screen.LIBRARY) },
                    icon = { Text("▦", fontSize = 19.sp) },
                    label = { Text("Manage") }
                )
                NavigationBarItem(
                    selected = screen == Screen.EDITOR,
                    onClick = { navigate(Screen.EDITOR) },
                    icon = { Text("≡", fontSize = 19.sp) },
                    label = { Text("Editor") }
                )
            }
        }
    ) { padding ->
        when (screen) {
            Screen.PERFORMANCE -> PerformanceScreen(
                viewModel = viewModel,
                modifier = Modifier.padding(padding)
            )

            Screen.LIBRARY -> LibraryScreen(
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
                onEdit = { patch ->
                    viewModel.openEditor(patch)
                    screenName = Screen.EDITOR.name
                }
            )

            Screen.EDITOR -> EditorScreen(
                viewModel = viewModel,
                modifier = Modifier.padding(padding)
            )
        }
    }

    pendingScreen?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingScreen = null },
            title = { Text("Discard unsaved changes?") },
            text = { Text("The edited fader values have not been saved.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.revertEditor()
                    screenName = target.name
                    pendingScreen = null
                }) { Text("DISCARD", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingScreen = null }) { Text("KEEP EDITING") }
            }
        )
    }
}

@Composable
private fun PerformanceScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val set = viewModel.currentSet
    val entries = viewModel.currentSetPatches

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ConnectionCard(viewModel)
        NoticeBanner(viewModel)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(onClick = { viewModel.selectNextSet(-1) }) { Text("‹") }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SETLIST", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                Text(
                    set.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(onClick = { viewModel.selectNextSet(1) }) { Text("›") }
        }

        if (entries.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("This setlist is empty.\nAdd patches in Manage mode.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(entries, key = { index, patch -> "$index-${patch?.id}" }) { index, patch ->
                    if (patch != null) {
                        PerformancePatch(
                            number = index + 1,
                            patch = patch,
                            selected = viewModel.loadedEntryIndex == index,
                            enabled = !viewModel.isBusy && viewModel.midiStatus.connected,
                            onClick = { viewModel.loadPatch(patch, index) }
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = viewModel::loadPrevious,
                modifier = Modifier.weight(1f).height(68.dp),
                enabled = !viewModel.isBusy && viewModel.midiStatus.connected
            ) {
                Text("◀  PREVIOUS", fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = viewModel::loadNext,
                modifier = Modifier.weight(1f).height(68.dp),
                enabled = !viewModel.isBusy && viewModel.midiStatus.connected
            ) {
                Text("NEXT  ▶", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PerformancePatch(
    number: Int,
    patch: Patch,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        border = border ?: BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                number.toString().padStart(2, '0'),
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(patch.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    patch.parameters.summary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) Text("LOADED", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LibraryScreen(
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
    onEdit: (Patch) -> Unit
) {
    var showCaptureDialog by remember { mutableStateOf(false) }
    var showNewSetDialog by remember { mutableStateOf(false) }
    var renamePatch by remember { mutableStateOf<Patch?>(null) }
    var deletePatch by remember { mutableStateOf<Patch?>(null) }
    var updatePatch by remember { mutableStateOf<Patch?>(null) }
    var pendingExport by remember { mutableStateOf<Patch?>(null) }

    val importSysex = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importSysex)
    }
    val exportSysex = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val patch = pendingExport
        if (uri != null && patch != null) viewModel.exportPatch(uri, patch)
        pendingExport = null
    }
    val backupLibrary = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(viewModel::exportLibrary) }
    val restoreLibrary = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::restoreLibrary)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { ConnectionCard(viewModel) }
        item { NoticeBanner(viewModel) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { showCaptureDialog = true },
                    modifier = Modifier.weight(1f),
                    enabled = viewModel.midiStatus.connected && !viewModel.isBusy
                ) { Text("Capture") }
                OutlinedButton(
                    onClick = { importSysex.launch(arrayOf("application/octet-stream", "audio/midi", "*/*")) },
                    modifier = Modifier.weight(1f)
                ) { Text("Import .syx") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { backupLibrary.launch("reface-cs-live-backup.json") },
                    modifier = Modifier.weight(1f)
                ) { Text("Backup library") }
                TextButton(
                    onClick = { restoreLibrary.launch(arrayOf("application/json", "text/json", "*/*")) },
                    modifier = Modifier.weight(1f)
                ) { Text("Restore library") }
            }
        }

        item {
            Text("PATCH LIBRARY", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        }
        if (viewModel.patches.isEmpty()) {
            item { Text("No patches yet. Connect the reface and capture its current sound.") }
        }
        itemsIndexed(viewModel.patches, key = { _, patch -> patch.id }) { _, patch ->
            LibraryPatchCard(
                patch = patch,
                isLoaded = patch.id == viewModel.loadedPatchId,
                canLoad = viewModel.midiStatus.connected && !viewModel.isBusy,
                onLoad = { viewModel.loadPatch(patch) },
                onEdit = { onEdit(patch) },
                onUpdate = { updatePatch = patch },
                onAdd = { viewModel.addPatchToCurrentSet(patch.id) },
                onRename = { renamePatch = patch },
                onDuplicate = { viewModel.duplicatePatch(patch.id) },
                onExport = {
                    pendingExport = patch
                    exportSysex.launch("${safeFilename(patch.name)}.syx")
                },
                onDelete = { deletePatch = patch }
            )
        }

        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(onClick = { viewModel.selectNextSet(-1) }) { Text("‹") }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text("EDIT SETLIST", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    Text(viewModel.currentSet.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = { viewModel.selectNextSet(1) }) { Text("›") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showNewSetDialog = true }, modifier = Modifier.weight(1f)) {
                    Text("New setlist")
                }
                TextButton(onClick = viewModel::deleteCurrentSet, modifier = Modifier.weight(1f)) {
                    Text("Delete setlist", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (viewModel.currentSetPatches.isEmpty()) {
            item { Text("Use Add above to place patches in this setlist.") }
        }
        itemsIndexed(viewModel.currentSetPatches, key = { index, patch -> "set-$index-${patch?.id}" }) { index, patch ->
            if (patch != null) {
                SetEntryEditor(
                    index = index,
                    patch = patch,
                    onUp = { viewModel.moveSetEntry(index, -1) },
                    onDown = { viewModel.moveSetEntry(index, 1) },
                    onRemove = { viewModel.removeSetEntry(index) }
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showCaptureDialog) {
        TextInputDialog(
            title = "Capture current sound",
            initialValue = "Patch ${(viewModel.patches.size + 1).toString().padStart(2, '0')}",
            confirmLabel = "Capture",
            onDismiss = { showCaptureDialog = false },
            onConfirm = {
                showCaptureDialog = false
                viewModel.capturePatch(it)
            }
        )
    }
    if (showNewSetDialog) {
        TextInputDialog(
            title = "New setlist",
            initialValue = "",
            confirmLabel = "Create",
            onDismiss = { showNewSetDialog = false },
            onConfirm = {
                showNewSetDialog = false
                viewModel.createSet(it)
            }
        )
    }
    renamePatch?.let { patch ->
        TextInputDialog(
            title = "Rename patch",
            initialValue = patch.name,
            confirmLabel = "Rename",
            onDismiss = { renamePatch = null },
            onConfirm = {
                viewModel.renamePatch(patch.id, it)
                renamePatch = null
            }
        )
    }
    updatePatch?.let { patch ->
        AlertDialog(
            onDismissRequest = { updatePatch = null },
            title = { Text("Update ${patch.name}?") },
            text = { Text("The current sound from the reface CS will replace this saved patch. Its name and setlist positions will be preserved.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.updatePatchFromSynth(patch)
                    updatePatch = null
                }) { Text("UPDATE FROM CS") }
            },
            dismissButton = { TextButton(onClick = { updatePatch = null }) { Text("Cancel") } }
        )
    }
    deletePatch?.let { patch ->
        AlertDialog(
            onDismissRequest = { deletePatch = null },
            title = { Text("Delete ${patch.name}?") },
            text = { Text("It will also be removed from every setlist.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePatch(patch.id)
                    deletePatch = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletePatch = null }) { Text("Cancel") } }
        )
    }
}

@Composable
internal fun ConnectionCard(viewModel: AppViewModel) {
    var showDeviceDialog by remember { mutableStateOf(false) }
    val status = viewModel.midiStatus
    val statusColor = if (status.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Card(
        colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(12.dp),
                contentAlignment = Alignment.Center
            ) { Text(if (status.connected) "●" else "○", color = statusColor) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(status.title, fontWeight = FontWeight.Bold, color = statusColor)
                if (status.detail.isNotBlank()) {
                    Text(status.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (viewModel.isBusy) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            TextButton(
                onClick = {
                    viewModel.scanForDevice()
                    showDeviceDialog = true
                }
            ) { Text("DEVICE") }
        }
    }

    if (showDeviceDialog) {
        AlertDialog(
            onDismissRequest = { showDeviceDialog = false },
            title = { Text("Select MIDI device") },
            text = {
                if (viewModel.midiDevices.isEmpty()) {
                    Text("No bidirectional MIDI devices found. Connect the reface CS and select Scan.")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(
                            viewModel.midiDevices,
                            key = { _, option -> option.id }
                        ) { _, option ->
                            val selected = option.id == viewModel.selectedMidiDeviceId
                            OutlinedCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.selectMidiDevice(option.id)
                                        showDeviceDialog = false
                                    },
                                border = BorderStroke(
                                    if (selected) 2.dp else 1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        if (selected) "●" else "○",
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(option.name, fontWeight = FontWeight.Bold)
                                        Text(
                                            option.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.scanForDevice()
                    showDeviceDialog = false
                }) { Text("SCAN") }
            },
            dismissButton = {
                TextButton(onClick = { showDeviceDialog = false }) { Text("CANCEL") }
            }
        )
    }
}

@Composable
internal fun NoticeBanner(viewModel: AppViewModel) {
    val notice = viewModel.notice ?: return
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(notice, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = viewModel::clearNotice) { Text("×") }
        }
    }
}

@Composable
private fun LibraryPatchCard(
    patch: Patch,
    isLoaded: Boolean,
    canLoad: Boolean,
    onLoad: () -> Unit,
    onEdit: () -> Unit,
    onUpdate: () -> Unit,
    onAdd: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(
            if (isLoaded) 2.dp else 1.dp,
            if (isLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(patch.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(patch.parameters.summary(), style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onLoad, enabled = canLoad) { Text("LOAD") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("EDIT") }
                OutlinedButton(onClick = onUpdate, enabled = canLoad, modifier = Modifier.weight(1f)) {
                    Text("UPDATE CS")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onAdd, modifier = Modifier.weight(1f)) { Text("Add") }
                TextButton(onClick = onRename, modifier = Modifier.weight(1f)) { Text("Rename") }
                TextButton(onClick = onDuplicate, modifier = Modifier.weight(1f)) { Text("Copy") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onExport, modifier = Modifier.weight(1f)) { Text("Export") }
                TextButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun SetEntryEditor(
    index: Int,
    patch: Patch,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text((index + 1).toString().padStart(2, '0'), color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(12.dp))
            Text(patch.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onUp) { Text("↑") }
            TextButton(onClick = onDown) { Text("↓") }
            TextButton(onClick = onRemove) { Text("×", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
internal fun TextInputDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("Name") }
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun safeFilename(name: String): String = name
    .lowercase()
    .replace(Regex("[^a-z0-9._-]+"), "-")
    .trim('-')
    .ifBlank { "reface-cs-patch" }
