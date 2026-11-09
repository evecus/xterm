package com.xterm.ssh

/** 首次连接 / 主机密钥变更时，需要用户在 UI 上确认的指纹信息。 */
data class HostKeyPrompt(
    val host: String,
    val port: Int,
    val algorithm: String,
    val fingerprint: String,
    val changed: Boolean,
)

sealed interface ConnState {
    data object Idle : ConnState
    data object Connecting : ConnState
    data object Connected : ConnState
    data class Failed(val message: String) : ConnState
    data object Disconnected : ConnState
}
