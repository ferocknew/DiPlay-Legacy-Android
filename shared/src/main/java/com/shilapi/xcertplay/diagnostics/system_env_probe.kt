package com.shilapi.xcertplay.diagnostics

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Read-only snapshots of system files that identify who occupies the resources wired and wireless
 * bring-up need on 4.4-era head units: USB class drivers, network interfaces, UDP listeners and
 * kernel messages. Every read is best-effort; failures become one report line and never throw.
 *
 * Output lines are prefixed "probe " and stay below the diagnostic redactor's line limits; they
 * must not contain redaction keywords (name=, ssid, hex=, payload=) or whole lines get dropped.
 */
internal object SystemEnvProbe {
    private const val TAG = "xcertplay-diag"
    private const val MAX_LINE_CHARS = 500
    private const val MAX_UDP_LINES = 200
    private const val MAX_USB_DEVICE_LINES = 400
    private const val KERNEL_TAIL_LINES = 200
    private const val KERNEL_READ_HARD_CAP_LINES = 20_000
    private const val MIN_CAPTURE_INTERVAL_MILLIS = 60_000L
    private val lastCaptureAt = AtomicLong(0L)

    /** Captures at most once per interval so retry loops do not flood the rotating log files. */
    fun captureThrottled(): List<String> {
        val now = System.currentTimeMillis()
        val previous = lastCaptureAt.get()
        if (now - previous < MIN_CAPTURE_INTERVAL_MILLIS) return emptyList()
        if (!lastCaptureAt.compareAndSet(previous, now)) return emptyList()
        return try {
            capture()
        } catch (error: Throwable) {
            Log.w(TAG, "environment probe failed", error)
            listOf("probe snapshot unavailable: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun capture(): List<String> {
        val lines = ArrayList<String>()
        lines += listDir("usb-drivers", "/sys/bus/usb/drivers")
        lines += listDir("net-interfaces", "/sys/class/net")
        lines += fileHead("udp", "/proc/net/udp", MAX_UDP_LINES)
        lines += fileHead("udp6", "/proc/net/udp6", MAX_UDP_LINES)
        lines += fileHead("usb-devices", "/proc/bus/usb/devices", MAX_USB_DEVICE_LINES)
        lines += fileTail("last_kmsg", "/proc/last_kmsg", KERNEL_TAIL_LINES)
        lines += fileTail("kmsg", "/dev/kmsg", KERNEL_TAIL_LINES)
        return lines
    }

    private fun listDir(label: String, path: String): List<String> = try {
        val entries = File(path).list()
        if (entries == null) {
            listOf("probe $label unavailable: directory not listable")
        } else {
            listOf("probe $label: ${entries.sorted().joinToString(",")}".clip())
        }
    } catch (error: Throwable) {
        unavailable(label, error)
    }

    private fun fileHead(label: String, path: String, maxLines: Int): List<String> = try {
        readLinesBounded(path, maxLines).let { lines ->
            if (lines.isEmpty()) {
                listOf("probe $label: empty")
            } else {
                listOf("probe $label: ${lines.size} lines") + lines.map { "probe $label: $it".clip() }
            }
        }
    } catch (error: Throwable) {
        unavailable(label, error)
    }

    private fun fileTail(label: String, path: String, tailLines: Int): List<String> = try {
        // A hard cap bounds the scan of very large kernel logs; only the tail is reported.
        val window = ArrayDeque<String>()
        var scanned = 0
        File(path).bufferedReader().use { reader ->
            while (scanned < KERNEL_READ_HARD_CAP_LINES) {
                val line = try {
                    reader.readLine() ?: break
                } catch (error: Throwable) {
                    return@use
                }
                scanned++
                window.addLast(line)
                if (window.size > tailLines) window.removeFirst()
            }
        }
        if (window.isEmpty()) {
            listOf("probe $label: empty")
        } else {
            listOf("probe $label: tail ${window.size} of $scanned lines") +
                window.map { "probe $label: $it".clip() }
        }
    } catch (error: Throwable) {
        unavailable(label, error)
    }

    private fun readLinesBounded(path: String, maxLines: Int): List<String> =
        File(path).bufferedReader().use { reader ->
            val lines = ArrayList<String>()
            while (lines.size < maxLines) {
                val line = try {
                    reader.readLine() ?: break
                } catch (error: Throwable) {
                    break
                }
                lines.add(line)
            }
            lines
        }

    private fun unavailable(label: String, error: Throwable): List<String> =
        listOf("probe $label unavailable: ${error.message ?: error.javaClass.simpleName}".clip())

    private fun String.clip(): String = if (length <= MAX_LINE_CHARS) this else take(MAX_LINE_CHARS)
}
