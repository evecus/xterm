package com.xterm.ssh

import android.util.Log
import com.trilead.ssh2.Connection
import com.trilead.ssh2.ConnectionMonitor
import com.trilead.ssh2.ExtendedServerHostKeyVerifier
import com.trilead.ssh2.KnownHosts
import com.xterm.data.Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 一条独立的 SSH 终端会话（对齐 Aurora `services/ssh_session.dart`）：
 * 连接、PTY、与终端显示层双向绑定、重连、断开、保活。
 * 认证复用 [SshConnector]，TOFU 主机密钥校验保留 xterm 原有能力。
 */
class SshSession(
    private val server: Server,
    private val knownHostsFile: File,
    private val onOutput: (ByteArray) -> Unit,
    private val onState: (ConnState) -> Unit,
    private val requestHostKey: suspend (HostKeyPrompt) -> Boolean,
    private val onInfo: (String) -> Unit = {},
) : TerminalConnection {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile private var connection: Connection? = null
    @Volatile private var session: com.trilead.ssh2.Session? = null
    @Volatile private var stopping = false
    @Volatile private var lastCols = 0
    @Volatile private var lastRows = 0
    private var keepAliveJob: Job? = null

    override fun connect(cols: Int, rows: Int) {
        lastCols = cols
        lastRows = rows
        scope.launch {
            try {
                onState(ConnState.Connecting)
                val conn = SshConnector.connect(server, HostKeyVerifier())
                connection = conn
                onInfo("已连接 ${server.displayAddr}")

                val sess = conn.openSession()
                session = sess
                sess.requestPTY(server.term, cols, rows, 0, 0, null)
                sess.startShell()
                onState(ConnState.Connected)

                conn.addConnectionMonitor(ConnectionMonitor { reason ->
                    if (!stopping) {
                        stopping = true
                        onState(ConnState.Failed("连接中断：${reason?.message ?: "未知原因"}"))
                        shutdown()
                    }
                })

                if (server.startupCommand.isNotBlank()) {
                    write((server.startupCommand + "\n").toByteArray(Charsets.UTF_8))
                }

                launchWriter()
                launch { pump(sess.stdout) }
                launch { pump(sess.stderr) }
                keepAliveJob = launch {
                    while (isActive) {
                        delay(KEEPALIVE_INTERVAL_MS)
                        try {
                            conn.ping()
                        } catch (e: IOException) {
                            break
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "SSH 连接失败", t)
                if (!stopping) onState(ConnState.Failed(t.message ?: t.toString()))
                shutdown()
            }
        }
    }

    /** 终端键盘输入 → SSH stdin，通过 Channel 串行化。 */
    override fun write(data: ByteArray) {
        if (!stopping) writes.trySend(data)
    }

    override fun resize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0 || (lastCols == cols && lastRows == rows)) return
        lastCols = cols
        lastRows = rows
        scope.launch {
            try {
                session?.resizePTY(cols, rows, 0, 0)
            } catch (_: Exception) {
                // 连接尚未就绪或已断开时忽略
            }
        }
    }

    override fun close() {
        if (!stopping) {
            stopping = true
            onState(ConnState.Disconnected)
        }
        shutdown()
    }

    private fun shutdown() {
        writes.close()
        runCatching { session?.close() }
        runCatching { connection?.close() }
        keepAliveJob?.cancel()
        scope.cancel()
    }

    private fun launchWriter() {
        scope.launch {
            val stdin = session?.stdin ?: return@launch
            for (data in writes) {
                try {
                    stdin.write(data)
                    stdin.flush()
                } catch (e: IOException) {
                    if (!stopping) onState(ConnState.Failed("写入失败：${e.message}"))
                    break
                }
            }
        }
    }

    private suspend fun CoroutineScope.pump(stream: InputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (isActive) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read > 0 && !stopping) onOutput(buffer.copyOf(read))
            }
        } catch (_: Exception) {
            // socket 关闭或会话结束
        } finally {
            if (!stopping) {
                stopping = true
                onState(ConnState.Disconnected)
                shutdown()
            }
        }
    }

    /**
     * TOFU：首次连接 / 指纹变更时挂起，由 UI 弹窗确认（xterm 原有能力，
     * Aurora 无此流程，其余页面交互不受影响）。
     */
    private inner class HostKeyVerifier : ExtendedServerHostKeyVerifier() {

        private fun pattern(host: String, port: Int): String =
            if (port == 22) host else "[$host]:$port"

        private fun knownHosts(): KnownHosts = try {
            if (knownHostsFile.exists()) KnownHosts(knownHostsFile) else KnownHosts()
        } catch (_: IOException) {
            KnownHosts()
        }

        override fun verifyServerHostKey(
            hostname: String,
            port: Int,
            serverHostKeyAlgorithm: String,
            serverHostKey: ByteArray,
        ): Boolean {
            val host = pattern(hostname, port)
            val verdict = try {
                knownHosts().verifyHostkey(host, serverHostKeyAlgorithm, serverHostKey)
            } catch (_: IOException) {
                KnownHosts.HOSTKEY_IS_NEW
            }
            if (verdict == KnownHosts.HOSTKEY_IS_OK) return true

            val fingerprint = KnownHosts.createHexFingerprint(serverHostKeyAlgorithm, serverHostKey)
            val accepted = runBlocking {
                requestHostKey(
                    HostKeyPrompt(
                        host = hostname,
                        port = port,
                        algorithm = serverHostKeyAlgorithm,
                        fingerprint = fingerprint,
                        changed = verdict == KnownHosts.HOSTKEY_HAS_CHANGED,
                    )
                )
            }
            if (!accepted) return false

            return try {
                KnownHosts.addHostkeyToFile(
                    knownHostsFile,
                    arrayOf(host),
                    serverHostKeyAlgorithm,
                    serverHostKey,
                )
                true
            } catch (e: IOException) {
                Log.w(TAG, "写入 known_hosts 失败", e)
                true
            }
        }

        override fun getKnownKeyAlgorithmsForHost(hostname: String, port: Int): List<String> =
            knownHosts().getPreferredServerHostkeyAlgorithmOrder(pattern(hostname, port))
                ?.toList() ?: emptyList()

        override fun removeServerHostKey(hostname: String, port: Int, algorithm: String, key: ByteArray) = Unit

        override fun addServerHostKey(hostname: String, port: Int, algorithm: String, key: ByteArray) {
            try {
                KnownHosts.addHostkeyToFile(
                    knownHostsFile,
                    arrayOf(pattern(hostname, port)),
                    algorithm,
                    key,
                )
            } catch (e: IOException) {
                Log.w(TAG, "写入 known_hosts 失败", e)
            }
        }
    }

    private companion object {
        const val TAG = "SshSession"
        const val KEEPALIVE_INTERVAL_MS = 30_000L
        const val BUFFER_SIZE = 16 * 1024
    }
}
