package com.xterm.ssh

/**
 * 三类会话（SSH / 本机 shell / 本机 Linux-proot）对终端页暴露的统一接口。
 * 对齐 Aurora `terminal_page.dart` 里 `_session` 的动态鸭子类型。
 */
interface TerminalConnection {
    fun connect(cols: Int, rows: Int)
    fun write(data: ByteArray)
    fun resize(cols: Int, rows: Int)
    fun close()
}
