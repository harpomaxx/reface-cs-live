package com.refacecslive.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.refacecslive.AppViewModel
import kotlin.math.roundToInt

private data class FaderSpec(
    val offset: Int,
    val label: String,
    val value: Int,
    val maximum: Int = 127,
    val displayValue: String = value.toString()
)

@Composable
internal fun EditorScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val patch = viewModel.editorPatch
    val toneData = viewModel.editorToneData
    var showSaveCopy by remember { mutableStateOf(false) }
    var showUpdateConfirmation by remember { mutableStateOf(false) }

    if (patch == null || toneData == null) {
        Column(
            modifier = modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ConnectionCard(viewModel)
            Text("PATCH EDITOR", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Select EDIT on a saved patch in Manage mode.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
        return
    }

    fun value(offset: Int): Int = toneData[offset].toInt() and 0xFF
    val lfoNames = listOf("Off", "Amp", "Filter", "Pitch", "Osc")
    val oscillatorNames = listOf("Multi-saw", "Pulse", "Sync", "Ring", "FM")
    val effectNames = listOf("Dist", "Cho/Fla", "Phaser", "Delay", "Off")
    val effectRateLabel = when (value(16)) {
        0 -> "Tone"
        1, 2 -> "Rate"
        3 -> "Delay time"
        else -> "Rate"
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { ConnectionCard(viewModel) }
        item { NoticeBanner(viewModel) }
        item {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(
                    if (viewModel.editorHasUnsavedChanges) 2.dp else 1.dp,
                    if (viewModel.editorHasUnsavedChanges) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                patch.name,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                if (viewModel.editorHasUnsavedChanges) "EDITED · unsaved changes" else "Saved patch",
                                color = if (viewModel.editorHasUnsavedChanges) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = viewModel::auditionEditor,
                            enabled = viewModel.midiStatus.connected && !viewModel.isBusy
                        ) { Text("AUDITION") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("LIVE SEND", fontWeight = FontWeight.Bold)
                            Text(
                                "Send fader movement to the selected MIDI device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = viewModel.editorLiveSend,
                            onCheckedChange = viewModel::updateEditorLiveSend,
                            enabled = viewModel.midiStatus.connected
                        )
                    }
                }
            }
        }

        item {
            FaderSection(
                title = "LFO & PORTAMENTO",
                specs = listOf(
                    FaderSpec(2, "Assign", value(2), 4, lfoNames[value(2)]),
                    FaderSpec(3, "Depth", value(3)),
                    FaderSpec(4, "Speed", value(4)),
                    FaderSpec(5, "Portamento", value(5), displayValue = if (value(5) == 0) "Poly" else "Mono ${value(5)}")
                ),
                viewModel = viewModel
            )
        }
        item {
            FaderSection(
                title = "OSCILLATOR",
                specs = listOf(
                    FaderSpec(6, "Type", value(6), 4, oscillatorNames[value(6)]),
                    FaderSpec(7, "Texture", value(7)),
                    FaderSpec(8, "Mod", value(8))
                ),
                viewModel = viewModel
            )
        }
        item {
            FaderSection(
                title = "FILTER",
                specs = listOf(
                    FaderSpec(9, "Cutoff", value(9)),
                    FaderSpec(10, "Resonance", value(10))
                ),
                viewModel = viewModel
            )
        }
        item {
            FaderSection(
                title = "ENVELOPE",
                specs = listOf(
                    FaderSpec(11, "FEG / AEG", value(11)),
                    FaderSpec(12, "Attack", value(12)),
                    FaderSpec(13, "Decay", value(13)),
                    FaderSpec(14, "Sustain", value(14)),
                    FaderSpec(15, "Release", value(15))
                ),
                viewModel = viewModel
            )
        }
        item {
            FaderSection(
                title = "EFFECT",
                specs = listOf(
                    FaderSpec(16, "Type", value(16), 4, effectNames[value(16)]),
                    FaderSpec(17, "Depth", value(17)),
                    FaderSpec(18, effectRateLabel, value(18))
                ),
                viewModel = viewModel
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::revertEditor,
                    enabled = viewModel.editorHasUnsavedChanges,
                    modifier = Modifier.weight(1f)
                ) { Text("REVERT") }
                OutlinedButton(
                    onClick = { showSaveCopy = true },
                    modifier = Modifier.weight(1f)
                ) { Text("SAVE COPY") }
            }
        }
        item {
            Button(
                onClick = viewModel::saveEditorChanges,
                enabled = viewModel.editorHasUnsavedChanges,
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) { Text("SAVE CHANGES", fontWeight = FontWeight.Bold) }
        }
        item {
            TextButton(
                onClick = { showUpdateConfirmation = true },
                enabled = viewModel.midiStatus.connected && !viewModel.isBusy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("UPDATE THIS PATCH FROM REFACE CS") }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }

    if (showSaveCopy) {
        TextInputDialog(
            title = "Save edited patch as copy",
            initialValue = "${patch.name} Copy",
            confirmLabel = "Save copy",
            onDismiss = { showSaveCopy = false },
            onConfirm = {
                viewModel.saveEditorCopy(it)
                showSaveCopy = false
            }
        )
    }
    if (showUpdateConfirmation) {
        AlertDialog(
            onDismissRequest = { showUpdateConfirmation = false },
            title = { Text("Replace edited values?") },
            text = { Text("The current sound from the reface CS will replace this patch and any unsaved edits.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.updatePatchFromSynth(patch)
                    showUpdateConfirmation = false
                }) { Text("UPDATE FROM CS") }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateConfirmation = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun FaderSection(
    title: String,
    specs: List<FaderSpec>,
    viewModel: AppViewModel
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp)) {
            Text(
                title,
                modifier = Modifier.padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                specs.forEach { spec ->
                    VerticalFader(
                        spec = spec,
                        changed = viewModel.editorParameterChanged(spec.offset),
                        modifier = Modifier.weight(1f),
                        onValueChange = { newValue, finished ->
                            viewModel.setEditorParameter(spec.offset, newValue, finished)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun VerticalFader(
    spec: FaderSpec,
    changed: Boolean,
    modifier: Modifier = Modifier,
    onValueChange: (Int, Boolean) -> Unit
) {
    var localValue by remember(spec.value) { mutableFloatStateOf(spec.value.toFloat()) }
    val accent = if (changed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface

    Column(
        modifier = modifier.padding(horizontal = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.Center) {
            Text(
                spec.label,
                color = accent,
                fontSize = 11.sp,
                lineHeight = 12.sp,
                fontWeight = if (changed) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
        Box(
            modifier = Modifier.fillMaxWidth().height(142.dp),
            contentAlignment = Alignment.Center
        ) {
            Slider(
                value = localValue,
                onValueChange = { newValue ->
                    localValue = newValue
                    onValueChange(newValue.roundToInt(), false)
                },
                onValueChangeFinished = {
                    onValueChange(localValue.roundToInt(), true)
                },
                valueRange = 0f..spec.maximum.toFloat(),
                steps = if (spec.maximum <= 4) spec.maximum - 1 else 0,
                modifier = Modifier
                    .requiredWidth(132.dp)
                    .graphicsLayer { rotationZ = -90f }
            )
        }
        Text(
            spec.displayValue,
            color = accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().height(22.dp)
        )
    }
}
