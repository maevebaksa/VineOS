package com.hexadecinull.vineos.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hexadecinull.vineos.data.models.ROMImage
import com.hexadecinull.vineos.data.models.VMInstance
import com.hexadecinull.vineos.data.models.VMStatus
import com.hexadecinull.vineos.data.repository.AppPreferences
import com.hexadecinull.vineos.data.repository.InstanceRepository
import com.hexadecinull.vineos.data.repository.ROMRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class CreateInstanceState {
    data object Idle : CreateInstanceState()
    data object Creating : CreateInstanceState()
    data class Success(val instanceId: String) : CreateInstanceState()
    data class Error(val message: String) : CreateInstanceState()
}

@HiltViewModel
class CreateInstanceViewModel @Inject constructor(
    private val instanceRepo: InstanceRepository,
    private val romRepo: ROMRepository,
    private val prefs: AppPreferences,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val stateFlow = MutableStateFlow<CreateInstanceState>(CreateInstanceState.Idle)
    val state: StateFlow<CreateInstanceState> = stateFlow.asStateFlow()

    private val romFlow = MutableStateFlow<ROMImage?>(null)
    val rom: StateFlow<ROMImage?> = romFlow.asStateFlow()

    val instanceName = MutableStateFlow("")
    val selectedRamMb = MutableStateFlow(AppPreferences.DEFAULT_RAM_MB)
    val selectedStorageMb = MutableStateFlow(AppPreferences.DEFAULT_STORAGE_MB)
    val selectedCpuCores = MutableStateFlow(AppPreferences.DEFAULT_CPU_CORES)
    val selectedEmoji = MutableStateFlow("\uD83D\uDFE2")
    val selectedRoot = MutableStateFlow(false)

    val allowRootInstances: StateFlow<Boolean> = prefs.allowRootInstances
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isFormValid: StateFlow<Boolean> = instanceName
        .map { it.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            selectedRamMb.value = prefs.defaultRamMb.first()
            selectedStorageMb.value = prefs.defaultStorageMb.first()
            selectedCpuCores.value = prefs.defaultCpuCores.first()
        }
    }

    fun loadRom(romId: String) {
        romFlow.value = romRepo.getRom(romId)
        if (instanceName.value.isBlank()) instanceName.value = romFlow.value?.displayName.orEmpty()
    }

    fun createInstance() {
        val selectedRom = romFlow.value ?: return
        if (stateFlow.value is CreateInstanceState.Creating) return
        stateFlow.value = CreateInstanceState.Creating

        viewModelScope.launch {
            val name = instanceName.value.trim()
            val ram = selectedRamMb.value
            val storage = selectedStorageMb.value
            val cpuCores = selectedCpuCores.value
            val isRooted = allowRootInstances.value && selectedRoot.value

            // Generate a real per-instance ID here. The old native path was called with
            // an empty ID, which collapsed every instance onto filesDir/instances/.
            // Rootless QEMU needs no privileged setup at creation time: it only needs
            // an app-private directory for its qcow2 overlays.
            val instanceId = UUID.randomUUID().toString()
            val instanceDir = File(context.filesDir, "instances/$instanceId")
            if (!instanceDir.mkdirs() && !instanceDir.isDirectory) {
                stateFlow.value = CreateInstanceState.Error(
                    "Failed to create instance storage. Check available disk space.",
                )
                return@launch
            }

            val instance = VMInstance(
                id = instanceId,
                name = name,
                romId = selectedRom.id,
                romVersion = selectedRom.androidVersion,
                storagePath = instanceDir.absolutePath,
                status = VMStatus.STOPPED,
                ramMb = ram,
                cpuCores = cpuCores,
                storageMb = storage,
                androidVersionDisplay = selectedRom.displayName,
                isRooted = isRooted,
                iconEmoji = selectedEmoji.value,
            )
            instanceRepo.save(instance)
            stateFlow.value = CreateInstanceState.Success(instanceId)
        }
    }

    fun resetState() {
        stateFlow.value = CreateInstanceState.Idle
    }
}
