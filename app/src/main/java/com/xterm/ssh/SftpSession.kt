package com.xterm.ssh

import android.util.Base64
import com.trilead.ssh2.Connection
import com.trilead.ssh2.SFTPv3Client
import com.trilead.ssh2.SFTPv3DirectoryEntry
import com.xterm.data.RemoteFileEntry
import com.xterm.data.Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

enum class SftpState { IDLE, CONNECTING, READY, FAILED, CLOSED }

/** 真 SFTP，或 SSH 命令模拟（兼容无 sftp-server 的路由器）。 */
enum class TransferMode { SFTP, COMMAND }

/**
 * 对齐 Aurora `services/sftp_session.dart`：
 * 独立的文件会话（不占用终端 shell），先试 SFTP，失败自动切命令模式；
 * UI 仍是同一个文件浏览器，标题追加「· 兼容模式」。
 */
class SftpSession(private val server: Server) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var conn: Connection? = null
    private var sftp: SFTPv3Client? = null

    private val _state = MutableStateFlow(SftpState.IDLE)
    val state: StateFlow<SftpState> = _state.asStateFlow()

    private val _mode = MutableStateFlow(TransferMode.SFTP)
    val mode: StateFlow<TransferMode> = _mode.asStateFlow()

    private val _entries = MutableStateFlow<List<RemoteFileEntry>>(emptyList())
    val entries: StateFlow<List<RemoteFileEntry>> = _entries.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _currentPath = MutableStateFlow("/")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    suspend fun connect(initialPath: String? = null) = withContext(Dispatchers.IO) {
        if (_state.value == SftpState.CONNECTING || _state.value == SftpState.READY) return@withContext
        _state.value = SftpState.CONNECTING
        _error.value = null
        _loading.value = true
        try {
            val connection = SshConnector.connect(server)
            conn = connection

            try {
                val client = SFTPv3Client(connection)
                sftp = client
                var path = initialPath ?: "/"
                if (initialPath == null) {
                    try {
                        val home = client.canonicalPath(".")
                        if (home.isNotEmpty()) path = home
                    } catch (_: Exception) {
                    }
                }
                _mode.value = TransferMode.SFTP
                _state.value = SftpState.READY
                list(path)
            } catch (e: Exception) {
                runCatching { sftp?.close() }
                sftp = null

                // 命令模式：先确认能跑 ls
                val probe = SshConnector.runCommand(connection, "echo ok")
                if (!probe.contains("ok")) {
                    throw IOException("SSH 已连接，但无法执行命令（SFTP 与命令模式均失败）")
                }
                var path = initialPath ?: "/"
                if (initialPath == null) {
                    try {
                        val home = SshConnector.runCommand(connection, "pwd").trim()
                        if (home.startsWith("/")) path = home
                    } catch (_: Exception) {
                    }
                }
                _mode.value = TransferMode.COMMAND
                _state.value = SftpState.READY
                list(path)
            }
        } catch (e: Exception) {
            _error.value = e.message ?: e.toString()
            _state.value = SftpState.FAILED
            close()
        } finally {
            _loading.value = false
        }
    }

    suspend fun list(path: String) = withContext(Dispatchers.IO) {
        val connection = conn ?: return@withContext
        _loading.value = true
        try {
            val normalized = normalize(path)
            val items = if (_mode.value == TransferMode.SFTP) listSftp(normalized) else listCommand(normalized)
            val sorted = items.sortedWith(
                compareBy<RemoteFileEntry> { if (it.isDotDot) 0 else 1 }
                    .thenBy { if (it.isDirectory) 0 else 1 }
                    .thenBy { it.filename.lowercase() }
            )
            _currentPath.value = normalized
            _entries.value = sorted
        } catch (e: Exception) {
            _error.value = e.message ?: e.toString()
            throw e
        } finally {
            _loading.value = false
        }
    }

    private fun listSftp(path: String): List<RemoteFileEntry> {
        val client = sftp ?: throw IOException("SFTP not connected")
        val result = mutableListOf<RemoteFileEntry>()
        for (raw in client.ls(path)) {
            val e = raw as? SFTPv3DirectoryEntry ?: continue
            if (e.filename == ".") continue
            val attrs = e.attributes
            result.add(
                RemoteFileEntry(
                    filename = e.filename,
                    isDirectory = attrs?.isDirectory == true || attrs?.isSymlink == true,
                    size = attrs?.size,
                    modifyTime = attrs?.mtime,
                    longname = e.longEntry ?: "",
                )
            )
        }
        return result
    }

    /** 解析 `ls -la`，兼容 busybox / coreutils（对齐 Aurora _parseLsLine）。 */
    private fun listCommand(path: String): List<RemoteFileEntry> {
        val escaped = SshConnector.shellQuote(path)
        val raw = SshConnector.runCommand(conn!!, "ls -la $escaped 2>/dev/null || ls -l $escaped")
        val items = mutableListOf<RemoteFileEntry>()
        if (path != "/") {
            items.add(RemoteFileEntry(filename = "..", isDirectory = true))
        }
        for (line in raw.split('\n')) {
            val trimmed = line.trimEnd()
            if (trimmed.isEmpty() || trimmed.startsWith("total ")) continue
            val entry = parseLsLine(trimmed) ?: continue
            if (entry.filename == "." || entry.filename == "..") continue
            items.add(entry)
        }
        return items
    }

    private fun parseLsLine(line: String): RemoteFileEntry? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 6) return null
        val mode = parts[0]
        if (mode.length < 10) return null
        val isDir = mode.startsWith("d") || mode.startsWith("l")
        var size: Long? = null
        val nameIndex: Int
        if (parts.size >= 9) {
            size = parts[4].toLongOrNull()
            nameIndex = 8
        } else {
            size = parts[parts.size - 2].toLongOrNull()
            nameIndex = parts.size - 1
        }
        if (nameIndex >= parts.size) return null
        var name = parts.subList(nameIndex, parts.size).joinToString(" ")
        if (mode.startsWith("l") && name.contains(" -> ")) {
            name = name.substringBefore(" -> ")
        }
        if (name.isEmpty()) return null
        return RemoteFileEntry(
            filename = name,
            isDirectory = isDir,
            size = if (isDir) null else size,
            longname = line,
        )
    }

    suspend fun refresh() = list(_currentPath.value)

    suspend fun cd(name: String) = withContext(Dispatchers.IO) {
        if (name == "..") {
            list(parent(_currentPath.value))
        } else {
            val cur = _currentPath.value
            list(if (cur == "/") "/$name" else "$cur/$name")
        }
    }

    private fun fullPath(name: String): String {
        val cur = _currentPath.value
        return if (cur == "/") "/$name" else "$cur/$name"
    }

    suspend fun mkdir(name: String) = withContext(Dispatchers.IO) {
        val path = fullPath(name)
        if (_mode.value == TransferMode.SFTP) {
            sftp?.mkdir(path, 493) ?: throw IOException("SFTP not connected")  // 0755
        } else {
            SshConnector.runCommand(conn!!, "mkdir -p ${SshConnector.shellQuote(path)}")
        }
        refresh()
    }

    suspend fun remove(entry: RemoteFileEntry) = withContext(Dispatchers.IO) {
        val path = fullPath(entry.filename)
        if (_mode.value == TransferMode.SFTP) {
            val client = sftp ?: throw IOException("SFTP not connected")
            if (entry.isDirectory) client.rmdir(path) else client.rm(path)
        } else {
            SshConnector.runCommand(conn!!, "rm -rf ${SshConnector.shellQuote(path)}")
        }
        refresh()
    }

    suspend fun rename(fromName: String, toName: String) = withContext(Dispatchers.IO) {
        val from = fullPath(fromName)
        val to = fullPath(toName)
        if (_mode.value == TransferMode.SFTP) {
            sftp?.mv(from, to) ?: throw IOException("SFTP not connected")
        } else {
            SshConnector.runCommand(
                conn!!,
                "mv ${SshConnector.shellQuote(from)} ${SshConnector.shellQuote(to)}"
            )
        }
        refresh()
    }

    /** 读取整个文件到内存（适合小文件下载/编辑）。 */
    suspend fun readFile(name: String): ByteArray = withContext(Dispatchers.IO) {
        val path = fullPath(name)
        if (_mode.value == TransferMode.SFTP) {
            val client = sftp ?: throw IOException("SFTP not connected")
            val handle = client.openFileRO(path)
            try {
                val size = client.stat(path).size ?: 0L
                val out = ByteArrayOutputStream()
                val buf = ByteArray(32768)
                var offset = 0L
                while (true) {
                    val want = if (size > 0) minOf(32768L, size - offset).toInt() else 32768
                    if (want <= 0) break
                    val read = client.read(handle, offset, buf, 0, want)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    offset += read
                    if (size > 0 && offset >= size) break
                }
                return@withContext out.toByteArray()
            } finally {
                runCatching { client.closeFile(handle) }
            }
        }

        // 命令模式：base64 避免二进制损坏；无 base64 时回退 cat（文本）
        val b64 = SshConnector.runCommand(
            conn!!,
            "base64 ${SshConnector.shellQuote(path)} 2>/dev/null || base64 < ${SshConnector.shellQuote(path)} 2>/dev/null"
        )
        val cleaned = b64.replace(Regex("\\s+"), "")
        if (cleaned.isNotEmpty()) {
            try {
                return@withContext Base64.decode(cleaned, Base64.DEFAULT)
            } catch (_: Exception) {
            }
        }
        return@withContext SshConnector.runCommand(conn!!, "cat ${SshConnector.shellQuote(path)}")
            .toByteArray(Charsets.UTF_8)
    }

    suspend fun writeFile(name: String, data: ByteArray) = withContext(Dispatchers.IO) {
        val path = fullPath(name)
        if (_mode.value == TransferMode.SFTP) {
            val client = sftp ?: throw IOException("SFTP not connected")
            val handle = client.createFileTruncate(path)
            try {
                var offset = 0
                while (offset < data.size) {
                    val len = minOf(32768, data.size - offset)
                    client.write(handle, offset.toLong(), data, offset, len)
                    offset += len
                }
            } finally {
                runCatching { client.closeFile(handle) }
            }
            refresh()
            return@withContext
        }

        // 命令模式：通过 stdin 写 base64 再解码
        val encoded = Base64.encodeToString(data, Base64.NO_WRAP)
        val cmd = "base64 -d > ${SshConnector.shellQuote(path)} 2>/dev/null || " +
            "base64 -d > ${SshConnector.shellQuote(path)}"
        val exit = SshConnector.runCommandWithStdin(conn!!, cmd, encoded.toByteArray(Charsets.UTF_8))
        if (exit != null && exit != 0) {
            val cmd2 = "cat | base64 -d > ${SshConnector.shellQuote(path)} || " +
                "uudecode -o ${SshConnector.shellQuote(path)} 2>/dev/null || " +
                "cat > ${SshConnector.shellQuote(path)}"
            val hasBase64 = SshConnector.runCommand(conn!!, "command -v base64 || which base64")
                .trim().isNotEmpty()
            val payload = if (hasBase64) encoded.toByteArray(Charsets.UTF_8) else data
            SshConnector.runCommandWithStdin(conn!!, cmd2, payload)
        }
        refresh()
    }

    fun close() {
        runCatching { sftp?.close() }
        sftp = null
        runCatching { conn?.close() }
        conn = null
        if (_state.value != SftpState.FAILED) _state.value = SftpState.CLOSED
    }

    fun dispose() {
        close()
        scope.cancel()
    }

    companion object {
        fun normalize(path: String): String {
            if (path.isEmpty()) return "/"
            var p = path.replace(Regex("/+"), "/")
            if (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)
            if (!p.startsWith("/")) p = "/$p"
            return p.ifEmpty { "/" }
        }

        fun parent(path: String): String {
            if (path == "/" || path.isEmpty()) return "/"
            val i = path.lastIndexOf('/')
            return if (i <= 0) "/" else path.substring(0, i)
        }
    }
}
