package com.xterm.ssh

import com.trilead.ssh2.Connection
import com.trilead.ssh2.InteractiveCallback
import com.trilead.ssh2.ServerHostKeyVerifier
import com.xterm.data.Server
import java.io.IOException
import kotlin.concurrent.thread

/**
 * 共享的 SSH 连接 / 认证 / 命令执行。
 *
 * Aurora 里每个页面各自 `SSHSocket.connect` + `SSHClient`；xterm 侧 sshlib
 * 的 Connection 同样可开多个 session，这里集中复用认证逻辑，保证终端、
 * SFTP、系统信息三条路径的认证行为一致（含 keyboard-interactive 回退）。
 */
object SshConnector {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val KEX_TIMEOUT_MS = 20_000

    /**
     * [verifier] 为 null 时接受任意主机密钥（对齐 Aurora：无 TOFU 弹窗）；
     * 终端页传 xterm 原有的 TOFU 校验器。
     */
    fun connect(server: Server, verifier: ServerHostKeyVerifier? = null): Connection {
        val conn = Connection(server.host, server.port)
        try {
            conn.connect(verifier, CONNECT_TIMEOUT_MS, KEX_TIMEOUT_MS)
            if (!authenticate(conn, server)) {
                throw IOException("认证失败：请检查用户名、密码或私钥及口令")
            }
            return conn
        } catch (t: Throwable) {
            runCatching { conn.close() }
            throw t
        }
    }

    fun authenticate(conn: Connection, server: Server): Boolean {
        return if (server.privateKey.isNotBlank()) {
            conn.authenticateWithPublicKey(
                server.username,
                server.privateKey.toCharArray(),
                server.passphrase.ifBlank { null },
            )
        } else {
            if (conn.authenticateWithPassword(server.username, server.password)) {
                true
            } else if (conn.isAuthMethodAvailable(server.username, "keyboard-interactive")) {
                conn.authenticateWithKeyboardInteractive(
                    server.username,
                    InteractiveCallback { _, _, numPrompts, prompts, _ ->
                        Array(numPrompts) { index ->
                            val text = prompts.getOrNull(index).orEmpty()
                            if (index > 0 && !text.contains("password", ignoreCase = true)) "" else server.password
                        }
                    },
                )
            } else {
                false
            }
        }
    }

    /** 执行一条远程命令，返回合并后的 stdout（对齐 Aurora `client.run`）。 */
    fun runCommand(conn: Connection, command: String): String {
        val session = conn.openSession()
        try {
            session.execCommand(command)
            val outBox = arrayOfNulls<ByteArray>(2)
            val tOut = thread {
                outBox[0] = runCatching { session.stdout.readBytes() }.getOrDefault(ByteArray(0))
            }
            val tErr = thread {
                outBox[1] = runCatching { session.stderr.readBytes() }.getOrDefault(ByteArray(0))
            }
            tOut.join()
            tErr.join()
            return String(outBox[0] ?: ByteArray(0), Charsets.UTF_8) +
                String(outBox[1] ?: ByteArray(0), Charsets.UTF_8)
        } finally {
            runCatching { session.close() }
        }
    }

    /** 通过 stdin 写数据再执行命令（命令模式上传文件用）。 */
    fun runCommandWithStdin(conn: Connection, command: String, data: ByteArray): Int? {
        val session = conn.openSession()
        try {
            session.execCommand(command)
            session.stdin.use { out ->
                out.write(data)
                out.flush()
            }
            // 读到 EOF，确保命令真正执行完
            runCatching { session.stdout.readBytes() }
            runCatching { session.stderr.readBytes() }
            return session.exitStatus
        } finally {
            runCatching { session.close() }
        }
    }

    fun shellQuote(s: String): String =
        if (s.isEmpty()) "''" else "'${s.replace("'", "'\\''")}'"
}
