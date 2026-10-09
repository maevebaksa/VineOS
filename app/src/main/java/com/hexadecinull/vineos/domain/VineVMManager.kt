package com.hexadecinull.vineos.domain

import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.Surface
import androidx.core.content.ContextCompat
import com.hexadecinull.vineos.data.models.AbiCompat
import com.hexadecinull.vineos.data.models.ROMImage
import com.hexadecinull.vineos.data.models.VMInstance
import com.hexadecinull.vineos.data.models.VMStatus
import com.hexadecinull.vineos.rootless.RootlessQemuBackend
import com.hexadecinull.vineos.service.VineService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * VM facade used by the UI.
 *
 * On the rootless-backend branch all guest lifecycle operations intentionally use
 * RootlessQemuBackend. The legacy native namespace container remains in-tree for
 * rooted devices/upstream work, but it is never invoked from this manager.
 */
@Singleton
class VineVMManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rootless = RootlessQemuBackend(context)
    private val handleCounter = AtomicLong(1L)

    // Existing UI code treats a non-null handle as "this instance is running".
    // Rootless QEMU is process-backed rather than native-handle-backed, so these are
    // stable synthetic handles used only as UI identity tokens.
    private val handles = mutableMapOf<String, Long>()
    private val statusFlows = mutableMapOf<String, MutableStateFlow<VMStatus>>()

    val hostAbis: List<String> = Build.SUPPORTED_ABIS.toList()

    // The physical phone may be arm64-only, but the rootless guest is deliberately
    // multiarch and exposes armeabi-v7a/armeabi as secondary ABIs.
    val hostSupports32bit: Boolean = true
    val isRootlessBackend: Boolean = true

    fun isRomCompatible(rom: ROMImage): Boolean = rom.supportedAbis.any {
        it == AbiCompat.ARM64_V8A || it == AbiCompat.ARMEABI_V7A || it == AbiCompat.ARMEABI
    }

    fun statusFlow(instanceId: String): StateFlow<VMStatus> =
        statusFlows.getOrPut(instanceId) { MutableStateFlow(VMStatus.STOPPED) }.asStateFlow()

    fun getHandle(instanceId: String): Long? = handles[instanceId]

    fun startInstance(instance: VMInstance) {
        scope.launch {
            val flow = statusFlows.getOrPut(instance.id) { MutableStateFlow(VMStatus.STOPPED) }
            if (flow.value == VMStatus.BOOTING || flow.value == VMStatus.RUNNING) return@launch
            flow.value = VMStatus.BOOTING

            ContextCompat.startForegroundService(
                context,
                Intent(context, VineService::class.java).apply { action = VineService.ACTION_START },
            )

            val ok = rootless.start(
                instanceId = instance.id,
                instancePath = instance.storagePath,
                ramMb = instance.ramMb,
                cpuCores = instance.cpuCores,
            )
            if (!ok) {
                flow.value = VMStatus.ERROR
                stopServiceIfIdle()
                return@launch
            }

            handles[instance.id] = handleCounter.getAndIncrement()
            flow.value = VMStatus.RUNNING
            monitorInstance(instance.id, flow)
        }
    }

    fun stopInstance(instance: VMInstance) {
        scope.launch {
            rootless.stop(instance.id)
            handles.remove(instance.id)
            statusFlows[instance.id]?.value = VMStatus.STOPPED
            stopServiceIfIdle()
        }
    }

    fun killInstance(instanceId: String) {
        scope.launch {
            rootless.kill(instanceId)
            handles.remove(instanceId)
            statusFlows[instanceId]?.value = VMStatus.STOPPED
            stopServiceIfIdle()
        }
    }

    /** Kept for compatibility with callers on main; rootless rendering does not use an fd. */
    fun getFramebufferFd(instanceId: String): Int = if (handles.containsKey(instanceId)) 0 else -1

    fun attachSurface(instanceId: String, surface: Surface): Boolean = rootless.attachSurface(instanceId, surface)

    fun detachSurface(instanceId: String) = rootless.detachSurface(instanceId)

    fun sendTouch(instanceId: String, action: Int, x: Float, y: Float) {
        rootless.sendTouch(instanceId, action, x, y)
    }

    fun sendKey(instanceId: String, keycode: Int, down: Boolean) {
        rootless.sendKey(instanceId, keycode, down)
    }

    fun getDiagnostics(instanceId: String): String = rootless.diagnostics(instanceId)

    fun rootlessRuntimeDiagnostics(): String {
        val info = rootless.runtimeInfo()
        return buildString {
            appendLine("Rootless QEMU backend")
            appendLine("QEMU packaged: ${info.qemuPresent}")
            appendLine("qemu-img packaged: ${info.qemuImgPresent}")
            appendLine("UEFI firmware packaged: ${info.firmwarePresent}")
            appendLine("Guest image downloaded: ${info.guestReady}")
            appendLine("Native library dir: ${info.nativeLibDir}")
        }
    }

    private fun monitorInstance(instanceId: String, flow: MutableStateFlow<VMStatus>) = scope.launch {
        while (handles.containsKey(instanceId)) {
            delay(1500L)
            val newStatus = when (rootless.status(instanceId)) {
                RootlessQemuBackend.Status.STOPPED -> VMStatus.STOPPED
                RootlessQemuBackend.Status.BOOTING -> VMStatus.BOOTING
                RootlessQemuBackend.Status.RUNNING -> VMStatus.RUNNING
                RootlessQemuBackend.Status.ERROR -> VMStatus.ERROR
            }
            if (newStatus != flow.value) flow.value = newStatus
            if (newStatus == VMStatus.STOPPED || newStatus == VMStatus.ERROR) {
                handles.remove(instanceId)
                stopServiceIfIdle()
                break
            }
        }
    }

    private fun stopServiceIfIdle() {
        if (handles.isEmpty()) context.stopService(Intent(context, VineService::class.java))
    }

    fun cleanup() {
        scope.cancel()
        rootless.shutdown()
    }
}
