package com.xterm.ssh

/**
 * 本地 PTY 的 JNI 桥（app/src/main/cpp/pty.c）。
 * 对齐 Aurora 的 flutter_pty：真伪终端、可 resize、交互程序可用。
 */
object PtyBridge {

    val available: Boolean = try {
        System.loadLibrary("xtermpty")
        true
    } catch (t: Throwable) {
        false
    }

    private external fun nativeStart(
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        rows: Int,
        cols: Int,
    ): Int

    private external fun nativeRead(fd: Int, buf: ByteArray, len: Int): Int
    private external fun nativeWrite(fd: Int, buf: ByteArray, len: Int): Int
    private external fun nativeResize(fd: Int, rows: Int, cols: Int)
    private external fun nativeKill(fd: Int, sig: Int)
    private external fun nativeCloseFd(fd: Int)
    private external fun nativePid(fd: Int): Int

    fun start(argv: List<String>, env: Map<String, String>, cwd: String, rows: Int, cols: Int): Int =
        if (!available) -1 else nativeStart(
            argv.toTypedArray(),
            env.map { "${it.key}=${it.value}" }.toTypedArray(),
            cwd,
            rows,
            cols,
        )

    fun read(fd: Int, buf: ByteArray): Int =
        if (!available || fd < 0) -1 else nativeRead(fd, buf, buf.size)

    fun write(fd: Int, data: ByteArray): Int =
        if (!available || fd < 0) -1 else nativeWrite(fd, data, data.size)

    fun resize(fd: Int, rows: Int, cols: Int) {
        if (available && fd >= 0) nativeResize(fd, rows, cols)
    }

    fun kill(fd: Int, sig: Int) {
        if (available && fd >= 0) nativeKill(fd, sig)
    }

    fun close(fd: Int) {
        if (available && fd >= 0) nativeCloseFd(fd)
    }

    fun pid(fd: Int): Int = if (available && fd >= 0) nativePid(fd) else -1
}
