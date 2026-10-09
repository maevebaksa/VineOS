package com.hexadecinull.vineos.rootless

import android.content.Context
import android.os.StatFs
import android.util.Log
import android.view.Surface
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Rootless backend that runs a complete Android guest in QEMU TCG.
 *
 * This deliberately does not call the namespace/loop-device backend. QEMU is a
 * normal child process of VineOS and therefore works inside Android's ordinary app
 * sandbox without CAP_SYS_ADMIN, root, Shizuku, mount(), mknod(), or binfmt_misc.
 *
 * The pinned LineageOS virtio image is multi-arch: arm64-v8a primary userspace plus
 * armeabi-v7a/armeabi secondary userspace. QEMU system-emulates an ARM CPU with
 * AArch32 support, so an arm64-only physical phone can execute 32-bit ARM apps
 * inside the guest.
 */
class RootlessQemuBackend(private val context: Context) {
    enum class Status { STOPPED, BOOTING, RUNNING, ERROR }

    data class RuntimeInfo(
        val qemuPresent: Boolean,
        val qemuImgPresent: Boolean,
        val firmwarePresent: Boolean,
        val guestReady: Boolean,
        val nativeLibDir: String,
    )

    private data class RunningVm(
        val id: String,
        val process: Process,
        val vncPort: Int,
        val adbPort: Int,
        @Volatile var status: Status,
        @Volatile var rfb: RfbClient? = null,
        val logFile: File,
    )

    private val tag = "RootlessQemu"
    private val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
    private val rootlessDir = File(context.filesDir, "rootless")
    private val guestDir = File(rootlessDir, GUEST_VERSION)
    private val running = ConcurrentHashMap<String, RunningVm>()

    private val qemu: File get() = File(nativeLibDir, "libqemu.so")
    private val qemuImg: File get() = File(nativeLibDir, "libqemu_img.so")
    private val firmwareCode: File get() = File(rootlessDir, "efi_code.fd")
    private val baseVda: File get() = File(guestDir, "base_vda.qcow2")
    private val baseVdb: File get() = File(guestDir, "base_vdb.qcow2")
    private val baseEfiVars: File get() = File(guestDir, "efi_vars.fd")

    fun runtimeInfo(): RuntimeInfo = RuntimeInfo(
        qemuPresent = qemu.exists(),
        qemuImgPresent = qemuImg.exists(),
        firmwarePresent = firmwareCode.exists() || assetExists(FIRMWARE_ASSET),
        guestReady = guestReady(),
        nativeLibDir = nativeLibDir.absolutePath,
    )

    /** Start and wait until QEMU's VNC server is accepting connections. */
    fun start(instanceId: String, instancePath: String, ramMb: Int, cpuCores: Int): Boolean {
        stop(instanceId)
        return try {
            requireRuntime()
            ensureFirmware()
            ensureGuestBundle()

            val instanceDir = File(instancePath, "rootless-qemu").apply { mkdirs() }
            val vda = File(instanceDir, "vda.qcow2")
            val vdb = File(instanceDir, "vdb.qcow2")
            val vars = File(instanceDir, "efi_vars.fd")
            if (!vda.exists()) createOverlay(vda, baseVda)
            if (!vdb.exists()) createOverlay(vdb, baseVdb)
            if (!vars.exists()) baseEfiVars.copyTo(vars, overwrite = false)

            val vncPort = findFreeVncPort()
            val adbPort = findEphemeralPort(setOf(vncPort))
            val vncDisplay = vncPort - 5900
            if (vncDisplay < 0) throw IllegalStateException("Unable to allocate a VNC display port")

            val logFile = File(instanceDir, "qemu.log")
            val command = buildCommand(
                vda = vda,
                vdb = vdb,
                vars = vars,
                vncDisplay = vncDisplay,
                adbPort = adbPort,
                ramMb = ramMb.coerceIn(768, 8192),
                cpuCores = cpuCores.coerceIn(1, 8),
            )
            Log.i(tag, "Starting rootless QEMU: ${command.joinToString(" ")}")

            val process = ProcessBuilder(command).apply {
                directory(instanceDir)
                environment()["LD_LIBRARY_PATH"] = nativeLibDir.absolutePath
                environment()["TMPDIR"] = context.cacheDir.absolutePath
                redirectErrorStream(true)
                redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            }.start()

            val vm = RunningVm(instanceId, process, vncPort, adbPort, Status.BOOTING, logFile = logFile)
            running[instanceId] = vm

            Thread({
                val exit = runCatching { process.waitFor() }.getOrDefault(-1)
                val current = running[instanceId]
                if (current === vm) {
                    current.status = if (exit == 0) Status.STOPPED else Status.ERROR
                    current.rfb?.stop()
                    Log.i(tag, "QEMU $instanceId exited with $exit")
                }
            }, "vine-qemu-watch-${instanceId.take(8)}").apply { isDaemon = true; start() }

            if (!waitForTcp(vncPort, VNC_BOOT_TIMEOUT_MS, process)) {
                val tail = logFile.takeIf { it.exists() }?.readLines()?.takeLast(30)?.joinToString("\n").orEmpty()
                process.destroyForcibly()
                vm.status = Status.ERROR
                Log.e(tag, "QEMU VNC did not come up. Log tail:\n$tail")
                false
            } else {
                vm.status = Status.RUNNING
                true
            }
        } catch (t: Throwable) {
            Log.e(tag, "Rootless start failed: ${t.message}", t)
            running[instanceId]?.status = Status.ERROR
            false
        }
    }

    fun stop(instanceId: String) {
        val vm = running.remove(instanceId) ?: return
        vm.rfb?.stop()
        vm.rfb = null
        if (vm.process.isAlive) {
            vm.process.destroy()
            if (!vm.process.waitFor(5, TimeUnit.SECONDS)) vm.process.destroyForcibly()
        }
        vm.status = Status.STOPPED
    }

    fun kill(instanceId: String) {
        val vm = running.remove(instanceId) ?: return
        vm.rfb?.stop()
        vm.process.destroyForcibly()
        vm.status = Status.STOPPED
    }

    fun status(instanceId: String): Status {
        val vm = running[instanceId] ?: return Status.STOPPED
        if (!vm.process.isAlive && vm.status != Status.ERROR) vm.status = Status.STOPPED
        return vm.status
    }

    fun attachSurface(instanceId: String, surface: Surface): Boolean {
        val vm = running[instanceId] ?: return false
        if (vm.status != Status.RUNNING && vm.status != Status.BOOTING) return false
        val rfb = vm.rfb ?: RfbClient("127.0.0.1", vm.vncPort).also {
            vm.rfb = it
            it.start()
        }
        rfb.attachSurface(surface)
        return true
    }

    fun detachSurface(instanceId: String) {
        running[instanceId]?.rfb?.detachSurface()
    }

    fun sendTouch(instanceId: String, action: Int, x: Float, y: Float) {
        running[instanceId]?.rfb?.sendPointer(action, x, y)
    }

    fun sendKey(instanceId: String, keyCode: Int, down: Boolean) {
        running[instanceId]?.rfb?.sendAndroidKey(keyCode, down)
    }

    fun diagnostics(instanceId: String): String {
        val info = runtimeInfo()
        val vm = running[instanceId]
        val logTail = vm?.logFile?.takeIf { it.exists() }?.runCatching {
            readLines().takeLast(40).joinToString("\n")
        }?.getOrNull().orEmpty()
        return buildString {
            appendLine("Backend: rootless QEMU TCG")
            appendLine("QEMU: ${info.qemuPresent} (${qemu.absolutePath})")
            appendLine("qemu-img: ${info.qemuImgPresent}")
            appendLine("firmware: ${info.firmwarePresent}")
            appendLine("guest bundle: ${info.guestReady} ($GUEST_VERSION)")
            appendLine("status: ${vm?.status ?: Status.STOPPED}")
            appendLine("pid alive: ${vm?.process?.isAlive ?: false}")
            appendLine("VNC: ${vm?.vncPort ?: -1}")
            appendLine("ADB forward: 127.0.0.1:${vm?.adbPort ?: -1} -> guest:5555")
            if (logTail.isNotBlank()) {
                appendLine("--- qemu.log tail ---")
                append(logTail)
            }
        }
    }

    fun shutdown() {
        running.keys.toList().forEach(::stop)
    }

    private fun requireRuntime() {
        if (!qemu.exists()) {
            throw IllegalStateException(
                "Rootless QEMU runtime missing: ${qemu.absolutePath}. " +
                    "Build with scripts/fetch_rootless_qemu.py or the rootless APK workflow."
            )
        }
        if (!qemuImg.exists()) throw IllegalStateException("qemu-img runtime missing: ${qemuImg.absolutePath}")
    }

    private fun ensureFirmware() {
        if (firmwareCode.exists()) return
        rootlessDir.mkdirs()
        context.assets.open(FIRMWARE_ASSET).use { input ->
            FileOutputStream(firmwareCode).use { output -> input.copyTo(output) }
        }
    }

    private fun guestReady(): Boolean =
        File(guestDir, ".complete").exists() && baseVda.exists() && baseVdb.exists() && baseEfiVars.exists()

    /**
     * Downloads only once. The 2026-07-08 release is intentionally pinned because
     * that release introduced the multiarch arm64 + armeabi-v7a guest configuration.
     */
    private fun ensureGuestBundle() {
        if (guestReady()) return
        guestDir.mkdirs()
        ensureFreeSpace(3L * 1024 * 1024 * 1024)

        val zip = File(rootlessDir, "$GUEST_VERSION.zip")
        val part = File(rootlessDir, "$GUEST_VERSION.zip.part")
        if (!zip.exists()) {
            download(URL(GUEST_URL), part)
            val digest = sha256(part)
            if (!digest.equals(GUEST_SHA256, ignoreCase = true)) {
                part.delete()
                throw IllegalStateException("Guest image checksum mismatch: $digest")
            }
            if (!part.renameTo(zip)) {
                part.copyTo(zip, overwrite = true)
                part.delete()
            }
        }

        var foundVda = false
        var foundVdb = false
        var foundVars = false
        ZipInputStream(BufferedInputStream(zip.inputStream(), 1024 * 1024)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (entry.isDirectory) continue
                val lower = entry.name.lowercase()
                val dest = when {
                    lower.endsWith("/data/vda.qcow2") || lower == "vda.qcow2" -> baseVda.also { foundVda = true }
                    lower.endsWith("/data/vdb.qcow2") || lower == "vdb.qcow2" -> baseVdb.also { foundVdb = true }
                    lower.endsWith("/data/efi_vars.fd") || lower == "efi_vars.fd" -> baseEfiVars.also { foundVars = true }
                    else -> null
                }
                if (dest != null) {
                    FileOutputStream(dest).use { out -> zin.copyTo(out, 1024 * 1024) }
                }
                zin.closeEntry()
            }
        }
        zip.delete()
        if (!foundVda || !foundVdb || !foundVars) {
            baseVda.delete(); baseVdb.delete(); baseEfiVars.delete()
            throw IllegalStateException("Lineage QEMU archive did not contain expected vda/vdb/efi_vars files")
        }
        File(guestDir, ".complete").writeText("$GUEST_VERSION\n")
    }

    private fun createOverlay(dest: File, backing: File) {
        dest.parentFile?.mkdirs()
        val process = ProcessBuilder(
            qemuImg.absolutePath,
            "create", "-f", "qcow2",
            "-F", "qcow2",
            "-b", backing.absolutePath,
            dest.absolutePath,
        ).apply {
            environment()["LD_LIBRARY_PATH"] = nativeLibDir.absolutePath
            redirectErrorStream(true)
        }.start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) throw IllegalStateException("qemu-img overlay creation failed ($exit): $output")
    }

    private fun buildCommand(
        vda: File,
        vdb: File,
        vars: File,
        vncDisplay: Int,
        adbPort: Int,
        ramMb: Int,
        cpuCores: Int,
    ): List<String> = buildList {
        add(qemu.absolutePath)
        addAll(listOf("-machine", "virt,gic-version=3,highmem=off"))
        // Match the LineageOS virtio project's documented TCG configuration. The
        // max CPU model includes the AArch32 execution state needed by the guest's
        // secondary armeabi-v7a userspace.
        addAll(listOf("-cpu", "max,pauth-impdef=on"))
        addAll(listOf("-accel", "tcg,thread=multi"))
        addAll(listOf("-smp", cpuCores.toString()))
        addAll(listOf("-m", ramMb.toString()))

        addAll(listOf("-drive", "if=pflash,unit=0,file=${firmwareCode.absolutePath},format=raw,readonly=on"))
        addAll(listOf("-drive", "if=pflash,unit=1,file=${vars.absolutePath},format=raw"))
        addAll(listOf("-drive", "file=${vda.absolutePath},if=none,id=vda,format=qcow2,discard=unmap,detect-zeroes=unmap"))
        addAll(listOf("-device", "virtio-blk-pci,drive=vda,bootindex=0"))
        addAll(listOf("-drive", "file=${vdb.absolutePath},if=none,id=vdb,format=qcow2,discard=unmap,detect-zeroes=unmap"))
        addAll(listOf("-device", "virtio-blk-pci,drive=vdb,bootindex=1"))

        addAll(listOf("-device", "virtio-gpu-pci"))
        addAll(listOf("-device", "virtio-serial"))
        addAll(listOf("-vnc", "127.0.0.1:$vncDisplay"))
        addAll(listOf("-device", "qemu-xhci,id=usb-bus"))
        addAll(listOf("-device", "usb-tablet,bus=usb-bus.0"))
        addAll(listOf("-device", "usb-kbd,bus=usb-bus.0"))

        addAll(listOf("-netdev", "user,id=net0,hostfwd=tcp:127.0.0.1:$adbPort-:5555"))
        addAll(listOf("-device", "virtio-net-pci,netdev=net0,romfile="))
        addAll(listOf("-device", "virtio-rng-pci"))
        addAll(listOf("-serial", "stdio"))
        addAll(listOf("-monitor", "none"))
        add("-no-reboot")
    }

    private fun findFreeVncPort(): Int {
        // QEMU's -vnc :N convention binds TCP 5900 + N.
        for (display in 20..99) {
            val port = 5900 + display
            try {
                ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1")).use { return port }
            } catch (_: Throwable) { }
        }
        throw IllegalStateException("No free loopback VNC port in 5920..5999")
    }

    private fun findEphemeralPort(excluded: Set<Int> = emptySet()): Int {
        repeat(16) {
            ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { socket ->
                val port = socket.localPort
                if (port !in excluded) return port
            }
        }
        throw IllegalStateException("Unable to allocate a unique loopback TCP port")
    }

    private fun waitForTcp(port: Int, timeoutMs: Long, process: Process): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && process.isAlive) {
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("127.0.0.1", port), 500)
                    return true
                }
            } catch (_: Throwable) {
                Thread.sleep(250)
            }
        }
        return false
    }

    private fun download(url: URL, dest: File) {
        dest.parentFile?.mkdirs()
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("User-Agent", "VineOS-rootless/1")
        connection.connect()
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("Guest download HTTP ${connection.responseCode}")
        }
        connection.inputStream.use { input ->
            FileOutputStream(dest).use { out -> input.copyTo(out, 1024 * 1024) }
        }
        connection.disconnect()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1024 * 1024).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ensureFreeSpace(requiredBytes: Long) {
        val stat = StatFs(context.filesDir.absolutePath)
        if (stat.availableBytes < requiredBytes) {
            throw IllegalStateException(
                "Rootless guest needs about 3 GB free; only ${stat.availableBytes / (1024 * 1024)} MB is available"
            )
        }
    }

    private fun assetExists(path: String): Boolean = runCatching {
        context.assets.open(path).close(); true
    }.getOrDefault(false)

    companion object {
        private const val GUEST_VERSION = "lineage-23.2-20260708-multiarch"
        private const val GUEST_URL =
            "https://github.com/jqssun/android-lineage-qemu/releases/download/v2026.07.08/UTM-VM-lineage-23.2-20260708-jqssun-virtio_arm64only.zip"
        private const val GUEST_SHA256 = "ec1ee6d630248060d531b13b3f3e607288d1a93a21f103522ef6bd342c878394"
        private const val FIRMWARE_ASSET = "rootless/efi_code.fd"
        private const val VNC_BOOT_TIMEOUT_MS = 120_000L
    }
}
