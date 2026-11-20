package com.mightyfrog.stratagemcaller.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

object UsbProtocol {
    const val HANDSHAKE = "SC1:H;"
    const val IDENTIFY = "SC1:I;"
    const val HEARTBEAT = "SC1:HBT;"
    const val CANCEL = "SC1:C;"
    const val RELEASE_ALL = "SC1:R;"
    fun hold(direction: Char) = "SC1:D:$direction:H;"
    fun release(direction: Char) = "SC1:D:$direction:L;"
}

class UsbDeviceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
