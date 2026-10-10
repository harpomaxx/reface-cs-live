package com.refacecslive

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.refacecslive.midi.RefaceMidiClient
import com.refacecslive.model.LibraryState
import com.refacecslive.model.Patch
import com.refacecslive.model.PerformanceSet
import com.refacecslive.protocol.RefaceProtocol
import com.refacecslive.storage.LibraryStore
import java.io.IOException

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LibraryStore(application)
    private val initialState = store.load()

    val patches = mutableStateListOf<Patch>().apply { addAll(initialState.patches) }
    val performanceSets = mutableStateListOf<PerformanceSet>().apply {
        addAll(initialState.performanceSets.ifEmpty { listOf(PerformanceSet(name = "Live Set")) })
    }

    var currentSetId by mutableStateOf(
        initialState.currentSetId?.takeIf { id -> performanceSets.any { it.id == id } }
            ?: performanceSets.first().id
    )
        private set

    var midiStatus by mutableStateOf(
        RefaceMidiClient.Status(false, "Looking for MIDI devices…")
    )
        private set

    val midiDevices = mutableStateListOf<RefaceMidiClient.DeviceOption>()

    var selectedMidiDeviceId by mutableStateOf<Int?>(null)
        private set

    var isBusy by mutableStateOf(false)
        private set

    var notice by mutableStateOf<String?>(null)
        private set

    var loadedPatchId by mutableStateOf<String?>(null)
        private set

    var loadedEntryIndex by mutableIntStateOf(-1)
        private set

    var editorPatchId by mutableStateOf<String?>(null)
        private set

    var editorToneData by mutableStateOf<ByteArray?>(null)
        private set

    private var editorOriginalToneData: ByteArray? = null

    var editorLiveSend by mutableStateOf(false)
        private set

    private val midiClient = RefaceMidiClient(
        context = application,
        onStatus = { status ->
            midiStatus = status
            if (!status.connected) editorLiveSend = false
        },
        onDevicesChanged = { devices, selectedId ->
            midiDevices.clear()
            midiDevices.addAll(devices)
            selectedMidiDeviceId = selectedId
        }
    )

    val currentSet: PerformanceSet
        get() = performanceSets.firstOrNull { it.id == currentSetId } ?: performanceSets.first()

    val currentSetPatches: List<Patch?>
        get() = currentSet.patchIds.map { id -> patches.firstOrNull { it.id == id } }

    val editorPatch: Patch?
        get() = editorPatchId?.let { id -> patches.firstOrNull { it.id == id } }

    val editorHasUnsavedChanges: Boolean
        get() {
            val current = editorToneData ?: return false
            val original = editorOriginalToneData ?: return false
            return !current.contentEquals(original)
        }

    fun scanForDevice() {
        midiClient.scanAndConnect()
    }

    fun selectMidiDevice(deviceId: Int) {
        midiClient.selectDevice(deviceId)
    }

    fun openEditor(patch: Patch) {
        val data = RefaceProtocol.parsePatchDump(patch.sysex).toneData
        editorPatchId = patch.id
        editorOriginalToneData = data.copyOf()
        editorToneData = data.copyOf()
        editorLiveSend = false
    }

    fun updateEditorLiveSend(enabled: Boolean) {
        editorLiveSend = enabled
    }

    fun editorParameterChanged(toneOffset: Int): Boolean {
        val current = editorToneData ?: return false
        val original = editorOriginalToneData ?: return false
        return toneOffset in current.indices && current[toneOffset] != original[toneOffset]
    }

    fun setEditorParameter(toneOffset: Int, value: Int, finished: Boolean = false) {
        val current = editorToneData ?: return
        if (toneOffset !in current.indices || value !in 0..127) return
        if ((current[toneOffset].toInt() and 0xFF) == value && !finished) return
        val updated = current.copyOf()
        updated[toneOffset] = value.toByte()
        editorToneData = updated

        if (editorLiveSend) {
            midiClient.sendToneParameter(
                toneOffset = toneOffset,
                value = value,
                immediate = finished,
                onError = { error -> notice = error.message ?: "Could not send parameter" }
            )
        }
    }

    fun auditionEditor() {
        val data = editorToneData ?: return
        val patch = editorPatch ?: return
        val dump = try {
            RefaceProtocol.buildPatchDump(data)
        } catch (error: Exception) {
            notice = error.message ?: "Invalid patch values"
            return
        }
        if (isBusy) return
        isBusy = true
        notice = "Auditioning ${patch.name}…"
        midiClient.sendPatch(dump.sysex) { result ->
            isBusy = false
            result.onSuccess {
                loadedPatchId = patch.id
                loadedEntryIndex = -1
                notice = "Auditioning ${patch.name}"
            }.onFailure { error ->
                notice = error.message ?: "Could not audition patch"
            }
        }
    }

    fun saveEditorChanges() {
        val patchId = editorPatchId ?: return
        val data = editorToneData ?: return
        val index = patches.indexOfFirst { it.id == patchId }
        if (index < 0) return
        try {
            val dump = RefaceProtocol.buildPatchDump(data)
            patches[index] = patches[index].copy(sysex = dump.sysex)
            editorOriginalToneData = data.copyOf()
            editorToneData = data.copyOf()
            persist()
            notice = "Saved ${patches[index].name}"
        } catch (error: Exception) {
            notice = error.message ?: "Could not save patch"
        }
    }

    fun saveEditorCopy(name: String) {
        val data = editorToneData ?: return
        try {
            val dump = RefaceProtocol.buildPatchDump(data)
            val patch = Patch(
                name = name.trim().ifEmpty { "${editorPatch?.name ?: "Patch"} Copy" },
                sysex = dump.sysex
            )
            patches.add(patch)
            persist()
            openEditor(patch)
            notice = "Saved ${patch.name}"
        } catch (error: Exception) {
            notice = error.message ?: "Could not save patch copy"
        }
    }

    fun revertEditor() {
        val original = editorOriginalToneData ?: return
        editorToneData = original.copyOf()
        notice = "Reverted unsaved changes"
    }

    fun updatePatchFromSynth(patch: Patch) {
        if (isBusy) return
        isBusy = true
        notice = "Updating ${patch.name} from reface CS…"
        midiClient.captureCurrentPatch { result ->
            isBusy = false
            result.onSuccess { dump ->
                val index = patches.indexOfFirst { it.id == patch.id }
                if (index >= 0) {
                    patches[index] = patches[index].copy(sysex = dump.sysex)
                    if (editorPatchId == patch.id) {
                        editorOriginalToneData = dump.toneData.copyOf()
                        editorToneData = dump.toneData.copyOf()
                    }
                    loadedPatchId = patch.id
                    loadedEntryIndex = -1
                    persist()
                    notice = "Updated ${patch.name} from reface CS"
                }
            }.onFailure { error ->
                notice = error.message ?: "Could not update patch"
            }
        }
    }

    fun capturePatch(name: String) {
        if (isBusy) return
        isBusy = true
        notice = "Requesting current sound…"
        midiClient.captureCurrentPatch { result ->
            isBusy = false
            result.onSuccess { dump ->
                val patch = Patch(
                    name = name.trim().ifEmpty { nextPatchName() },
                    sysex = dump.sysex
                )
                patches.add(patch)
                addPatchToCurrentSet(patch.id)
                persist()
                notice = "Captured ${patch.name}"
            }.onFailure { error ->
                notice = error.message ?: "Could not capture patch"
            }
        }
    }

    fun loadPatch(patch: Patch, entryIndex: Int = -1) {
        if (isBusy) return
        isBusy = true
        notice = "Loading ${patch.name}…"
        midiClient.sendPatch(patch.sysex) { result ->
            isBusy = false
            result.onSuccess {
                loadedPatchId = patch.id
                loadedEntryIndex = entryIndex
                notice = "Loaded ${patch.name}"
            }.onFailure { error ->
                notice = error.message ?: "Could not load patch"
            }
        }
    }

    fun loadPrevious() {
        val entries = currentSetPatches
        if (entries.isEmpty()) {
            notice = "This setlist is empty"
            return
        }
        val index = if (loadedEntryIndex <= 0) 0 else loadedEntryIndex - 1
        entries[index]?.let { loadPatch(it, index) }
    }

    fun loadNext() {
        val entries = currentSetPatches
        if (entries.isEmpty()) {
            notice = "This setlist is empty"
            return
        }
        val index = if (loadedEntryIndex < 0) 0 else (loadedEntryIndex + 1).coerceAtMost(entries.lastIndex)
        entries[index]?.let { loadPatch(it, index) }
    }

    fun importSysex(uri: Uri) {
        try {
            val resolver = getApplication<Application>().contentResolver
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IOException("Could not read file")
            val dump = RefaceProtocol.parsePatchDump(bytes)
            val patch = Patch(
                name = displayName(resolver, uri).substringBeforeLast('.').ifBlank { nextPatchName() },
                sysex = dump.sysex
            )
            patches.add(patch)
            persist()
            notice = "Imported ${patch.name}"
        } catch (error: Exception) {
            notice = "Import failed: ${error.message ?: "invalid SysEx"}"
        }
    }

    fun exportPatch(uri: Uri, patch: Patch) {
        try {
            getApplication<Application>().contentResolver.openOutputStream(uri, "w")?.use {
                it.write(patch.sysex)
            } ?: throw IOException("Could not open destination")
            notice = "Exported ${patch.name}"
        } catch (error: Exception) {
            notice = "Export failed: ${error.message}"
        }
    }

    fun exportLibrary(uri: Uri) {
        try {
            val json = store.encode(snapshot())
            getApplication<Application>().contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use {
                it.write(json)
            } ?: throw IOException("Could not open destination")
            notice = "Library backup saved"
        } catch (error: Exception) {
            notice = "Backup failed: ${error.message}"
        }
    }

    fun restoreLibrary(uri: Uri) {
        try {
            val raw = getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                it.readText()
            } ?: throw IOException("Could not read backup")
            val restored = store.decode(raw)
            patches.clear()
            patches.addAll(restored.patches)
            performanceSets.clear()
            performanceSets.addAll(restored.performanceSets)
            currentSetId = restored.currentSetId ?: performanceSets.first().id
            loadedPatchId = null
            loadedEntryIndex = -1
            editorPatchId = null
            editorToneData = null
            editorOriginalToneData = null
            persist()
            notice = "Library restored"
        } catch (error: Exception) {
            notice = "Restore failed: ${error.message}"
        }
    }

    fun renamePatch(patchId: String, newName: String) {
        val index = patches.indexOfFirst { it.id == patchId }
        val name = newName.trim()
        if (index >= 0 && name.isNotEmpty()) {
            patches[index] = patches[index].copy(name = name)
            persist()
        }
    }

    fun duplicatePatch(patchId: String) {
        patches.firstOrNull { it.id == patchId }?.let { source ->
            patches.add(Patch(name = "${source.name} Copy", sysex = source.sysex.copyOf()))
            persist()
        }
    }

    fun deletePatch(patchId: String) {
        patches.removeAll { it.id == patchId }
        for (index in performanceSets.indices) {
            val set = performanceSets[index]
            performanceSets[index] = set.copy(patchIds = set.patchIds.filterNot { it == patchId })
        }
        if (loadedPatchId == patchId) {
            loadedPatchId = null
            loadedEntryIndex = -1
        }
        if (editorPatchId == patchId) {
            editorPatchId = null
            editorToneData = null
            editorOriginalToneData = null
        }
        persist()
    }

    fun createSet(name: String) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        val set = PerformanceSet(name = cleanName)
        performanceSets.add(set)
        selectSet(set.id)
        persist()
    }

    fun deleteCurrentSet() {
        if (performanceSets.size == 1) {
            notice = "At least one setlist is required"
            return
        }
        val index = performanceSets.indexOfFirst { it.id == currentSetId }
        if (index >= 0) performanceSets.removeAt(index)
        currentSetId = performanceSets.first().id
        loadedEntryIndex = -1
        persist()
    }

    fun selectSet(id: String) {
        if (performanceSets.any { it.id == id }) {
            currentSetId = id
            loadedEntryIndex = -1
            persist()
        }
    }

    fun selectNextSet(direction: Int) {
        val index = performanceSets.indexOfFirst { it.id == currentSetId }.coerceAtLeast(0)
        val next = (index + direction).floorMod(performanceSets.size)
        selectSet(performanceSets[next].id)
    }

    fun addPatchToCurrentSet(patchId: String) {
        if (patches.none { it.id == patchId }) return
        updateCurrentSet { it.copy(patchIds = it.patchIds + patchId) }
        notice = "Added to ${currentSet.name}"
    }

    fun removeSetEntry(index: Int) {
        updateCurrentSet { set ->
            if (index !in set.patchIds.indices) set
            else set.copy(patchIds = set.patchIds.toMutableList().also { it.removeAt(index) })
        }
        if (loadedEntryIndex == index) loadedEntryIndex = -1
    }

    fun moveSetEntry(index: Int, delta: Int) {
        val destination = index + delta
        updateCurrentSet { set ->
            if (index !in set.patchIds.indices || destination !in set.patchIds.indices) return@updateCurrentSet set
            val ids = set.patchIds.toMutableList()
            val item = ids.removeAt(index)
            ids.add(destination, item)
            set.copy(patchIds = ids)
        }
        if (loadedEntryIndex == index) loadedEntryIndex = destination
    }

    fun clearNotice() {
        notice = null
    }

    private fun updateCurrentSet(transform: (PerformanceSet) -> PerformanceSet) {
        val index = performanceSets.indexOfFirst { it.id == currentSetId }
        if (index >= 0) {
            performanceSets[index] = transform(performanceSets[index])
            persist()
        }
    }

    private fun persist() = store.save(snapshot())

    private fun snapshot() = LibraryState(
        patches = patches.toList(),
        performanceSets = performanceSets.toList(),
        currentSetId = currentSetId
    )

    private fun nextPatchName(): String = "Patch ${(patches.size + 1).toString().padStart(2, '0')}"

    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: nextPatchName()
        }
        return uri.lastPathSegment ?: nextPatchName()
    }

    private fun Int.floorMod(divisor: Int): Int = ((this % divisor) + divisor) % divisor

    override fun onCleared() {
        midiClient.close()
        super.onCleared()
    }
}
