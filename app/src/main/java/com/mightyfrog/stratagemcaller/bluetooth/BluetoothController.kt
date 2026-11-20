package com.mightyfrog.stratagemcaller.bluetooth

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object BluetoothCapability {
    fun supported(context: Context): Boolean =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter != null

    fun permissionsGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun gamepadDescriptor(): ByteArray = byteArrayOf(
        0x05.toByte(), 0x01.toByte(), 0x09.toByte(), 0x05.toByte(), 0xA1.toByte(), 0x01.toByte(),
        0x05.toByte(), 0x09.toByte(), 0x19.toByte(), 0x01.toByte(), 0x29.toByte(), 0x10.toByte(), 0x15.toByte(), 0x00.toByte(),
        0x25.toByte(), 0x01.toByte(), 0x75.toByte(), 0x01.toByte(), 0x95.toByte(), 0x10.toByte(), 0x81.toByte(), 0x02.toByte(),
        0x05.toByte(), 0x01.toByte(), 0x09.toByte(), 0x39.toByte(), 0x15.toByte(), 0x00.toByte(), 0x25.toByte(), 0x07.toByte(),
        0x35.toByte(), 0x00.toByte(), 0x46.toByte(), 0x3B.toByte(), 0x01.toByte(), 0x65.toByte(), 0x14.toByte(), 0x75.toByte(),
        0x04.toByte(), 0x95.toByte(), 0x01.toByte(), 0x81.toByte(), 0x42.toByte(), 0xC0.toByte()
    )
}
