package com.hexadecinull.vineos.ui.viewmodel

import android.view.KeyEvent
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hexadecinull.vineos.data.models.VMStatus
import com.hexadecinull.vineos.data.repository.InstanceRepository
import com.hexadecinull.vineos.domain.VineVMManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@HiltViewModel
class VMDisplayViewModel @Inject constructor(
    private val vmManager: VineVMManager,
    private val instanceRepo: InstanceRepository,
) : ViewModel() {

    var isBooting by mutableStateOf(false)
        private set

    private var instanceId: String? = null
    private var statusJob: Job? = null
    private var displaySurface: Surface? = null

    fun attach(id: String) {
        instanceId = id
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            vmManager.statusFlow(id).collect { status ->
                isBooting = status == VMStatus.BOOTING
                if (status == VMStatus.RUNNING) {
                    displaySurface?.takeIf { it.isValid }?.let { vmManager.attachSurface(id, it) }
                }
            }
        }
    }

    fun detach() {
        instanceId?.let { vmManager.detachSurface(it) }
        statusJob?.cancel()
        statusJob = null
        displaySurface = null
        instanceId = null
    }

    fun onSurfaceReady(surface: Surface) {
        displaySurface = surface
        val id = instanceId ?: return
        vmManager.attachSurface(id, surface)
    }

    fun onSurfaceDestroyed() {
        val id = instanceId
        if (id != null) vmManager.detachSurface(id)
        displaySurface = null
    }

    fun sendTouch(action: Int, x: Float, y: Float) {
        val id = instanceId ?: return
        vmManager.sendTouch(id, action, x, y)
    }

    fun sendBack() = sendAndroidKey(KeyEvent.KEYCODE_BACK)

    fun sendHome() = sendAndroidKey(KeyEvent.KEYCODE_HOME)

    fun sendRecents() = sendAndroidKey(KeyEvent.KEYCODE_APP_SWITCH)

    private fun sendAndroidKey(keycode: Int) {
        val id = instanceId ?: return
        vmManager.sendKey(id, keycode, true)
        vmManager.sendKey(id, keycode, false)
    }
}
