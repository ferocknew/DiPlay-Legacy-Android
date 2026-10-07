package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.os.Build
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer

internal fun queueUsbRequest(request: UsbRequest, buffer: ByteBuffer): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) request.queue(buffer)
    else {
        @Suppress("DEPRECATION")
        request.queue(buffer, buffer.remaining())
    }

internal fun waitForUsbRequest(connection: UsbDeviceConnection, timeoutMillis: Long): UsbRequest? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) connection.requestWait(timeoutMillis.coerceAtLeast(1))
    else connection.requestWait()

internal fun selectUsbConfiguration(
    connection: UsbDeviceConnection,
    configuration: CarPlayUsbConfiguration,
): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
    selectUsbConfiguration21(connection, configuration.platformConfiguration)
} else {
    connection.controlTransfer(
        UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD,
        9,
        configuration.id,
        0,
        null,
        0,
        1_000,
    ) >= 0
}

internal fun selectUsbInterface(connection: UsbDeviceConnection, usbInterface: UsbInterface): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) connection.setInterface(usbInterface)
    else setInterfaceControlTransfer(
        connection,
        usbInterface.id,
        IphoneCarPlayConfiguration.alternateSetting(usbInterface),
    ) >= 0

/**
 * Raw SET_INTERFACE control transfer for pre-Lollipop targets that lack setInterface(). Returns
 * the framework transfer result so callers can record it; negative means failure.
 */
internal fun setInterfaceControlTransfer(
    connection: UsbDeviceConnection,
    interfaceId: Int,
    alternateSetting: Int,
): Int = connection.controlTransfer(
    UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD or UsbConstants.USB_RECIP_INTERFACE,
    USB_REQUEST_SET_INTERFACE,
    alternateSetting,
    interfaceId,
    null,
    0,
    USB_CONTROL_TIMEOUT_MILLIS,
)

/** Raw GET_STATUS probe aimed at one interface; [status] receives two bytes when the result >= 0. */
internal fun interfaceGetStatusTransfer(
    connection: UsbDeviceConnection,
    interfaceId: Int,
    status: ByteArray,
): Int = connection.controlTransfer(
    UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD or UsbConstants.USB_RECIP_INTERFACE,
    USB_REQUEST_GET_STATUS,
    0,
    interfaceId,
    status,
    status.size,
    USB_CONTROL_TIMEOUT_MILLIS,
)

private const val USB_REQUEST_GET_STATUS = 0
private const val USB_REQUEST_SET_INTERFACE = 11
private const val USB_CONTROL_TIMEOUT_MILLIS = 1_000

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
private fun selectUsbConfiguration21(connection: UsbDeviceConnection, value: Any?): Boolean =
    connection.setConfiguration(value as UsbConfiguration)
