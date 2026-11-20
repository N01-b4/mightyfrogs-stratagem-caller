package com.mightyfrog.stratagemcaller.connection

enum class ConnectionMethod { BLUETOOTH, USB, NONE }
enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class ConnectionState(
    val method: ConnectionMethod = ConnectionMethod.NONE,
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val deviceName: String = "None",
    val error: String? = null
)
