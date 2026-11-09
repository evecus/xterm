package com.xterm.ssh

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

enum class LinuxEnvState(val label: String) {
    UNKNOWN("检查中"),
    MISSING("未部署"),
    DOWNLOADING("下载中"),
    EXTRACTING("部署中"),
    READY("已部署"),
    FAILED("部署失败"),
}

class LinuxEnvException(message: String) : Exception(message)

/**
 * 对齐 Aurora `services/linux_env.dart`：本机 Linux 环境（proot + Alpine minirootfs）。
 * 全部默认值，无配置项：
 * - 发行版 Alpine 3.22.5 / aarch64
 * - 镜像 dl-cdn.alpinelinux.org
 * - DNS 1.1.1.1 / 8.8.8.8
 * - 位置 `<files>/linux/rootfs`
 * - 部署完成标记 `.aurora-installed`（不能用 bin/sh，它是绝对符号链接）
 *
 * proot 二进制（libproot.so / libproot-loader.so）不进仓库，由
 * scripts/build-proot-android.sh 在构建时生成到 jniLibs/arm64-v8a/。
 */
class LinuxEnv private constructor(private val app: Application) {

    private val _state = MutableStateFlow(LinuxEnvState.UNKNOWN)
    val state: StateFlow<LinuxEnvState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    @Volatile private var installing = false

    private fun set(state: LinuxEnvState, msg: String = "", progress: Float? = null) {
        _state.value = state
        _message.value = msg
        progress?.let { _progress.value = it }
    }

    fun nativeLibraryDir(): String = app.applicationInfo.nativeLibraryDir.orEmpty()

    fun prootPath(): String? = File(nativeLibraryDir(), "libproot.so")
        .takeIf { it.exists() }?.absolutePath

    fun loaderPath(): String? = File(nativeLibraryDir(), "libproot-loader.so")
        .takeIf { it.exists() }?.absolutePath

    fun linuxDir(): File = File(app.filesDir, "linux")
    fun rootfsDir(): File = File(linuxDir(), "rootfs")
    fun prootTmpDir(): File = File(linuxDir(), "ptmp")

    fun ensureProotTmpDir(): String {
        val dir = prootTmpDir()
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }

    fun isInstalled(): Boolean {
        val rootfs = rootfsDir()
        return File(rootfs, MARKER).exists() && File(rootfs, "bin/busybox").exists()
    }

    fun refresh() {
        if (installing) return
        val installed = isInstalled()
        set(
            if (installed) LinuxEnvState.READY else LinuxEnvState.MISSING,
            progress = if (installed) 1f else 0f,
        )
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        val dir = rootfsDir()
        if (!dir.exists()) return@withContext 0L
        dir.walkTopDown().filter { it.isFile }.sumOf { runCatching { it.length() }.getOrDefault(0L) }
    }

    /** 部署 Alpine rootfs。已部署则直接返回，重复调用安全。 */
    suspend fun install() = withContext(Dispatchers.IO) {
        if (installing) return@withContext
        if (isInstalled()) {
            set(LinuxEnvState.READY, "已部署", 1f)
            return@withContext
        }
        installing = true
        _error.value = null
        try {
            if (prootPath() == null) throw LinuxEnvException("缺少 proot（libproot.so 未随包安装）")
            if (loaderPath() == null) throw LinuxEnvException("缺少 proot loader（libproot-loader.so 未随包安装）")

            val base = linuxDir()
            if (!base.exists()) base.mkdirs()
            ensureProotTmpDir()
            val rootfs = rootfsDir()
            if (rootfs.exists()) rootfs.deleteRecursively()
            rootfs.mkdirs()
            val archive = File(base, "rootfs.tar.gz")

            download(archive)
            extract(archive, rootfs)
            configure(rootfs)
            archive.delete()
            set(LinuxEnvState.READY, "部署完成", 1f)
        } catch (e: Exception) {
            _error.value = e.message ?: e.toString()
            set(LinuxEnvState.FAILED, e.message ?: e.toString())
        } finally {
            installing = false
        }
    }

    suspend fun uninstall() = withContext(Dispatchers.IO) {
        if (installing) return@withContext
        val base = linuxDir()
        if (base.exists()) base.deleteRecursively()
        _error.value = null
        set(LinuxEnvState.MISSING, "", 0f)
    }

    private fun download(target: File) {
        set(LinuxEnvState.DOWNLOADING, "下载 Alpine $VERSION…", 0f)
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(rootfsUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "aurora")
                connect()
            }
            if (conn.responseCode != 200) throw LinuxEnvException("镜像返回 ${conn.responseCode}")
            val total = conn.contentLength.toLong()
            var received = 0L
            conn.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        received += n
                        val ratio = if (total > 0) received.toFloat() / total else 0f
                        _progress.value = 0.85f * ratio.coerceIn(0f, 1f)
                        _message.value = "下载 Alpine $VERSION：${humanBytes(received)}" +
                            if (total > 0) " / ${humanBytes(total)}" else ""
                    }
                    out.flush()
                }
            }
            if (received == 0L) throw LinuxEnvException("下载内容为空")
        } catch (e: LinuxEnvException) {
            throw e
        } catch (e: Exception) {
            throw LinuxEnvException("下载失败：${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    /** 依次尝试三种解压方式，与 Aurora 一致。 */
    private fun extract(archive: File, rootfs: File) {
        set(LinuxEnvState.EXTRACTING, "解压 rootfs…", 0.88f)
        val detail = mutableListOf<String>()
        val r1 = extractWithSystemTar(archive, rootfs)
        detail.add("gzip|tar: ${r1.second}")
        var ok = r1.first
        if (!ok) {
            val r2 = extractWithSystemTarFile(archive, rootfs)
            detail.add("tar: ${r2.second}")
            ok = r2.first
        }
        if (!ok) {
            val r3 = extractWithJava(archive, rootfs)
            detail.add("java: ${r3.second}")
            ok = r3.first
        }
        val info = detail.joinToString("；")
        if (!ok) throw LinuxEnvException("解压失败（$info）")
        if (!File(rootfs, "bin/busybox").exists()) {
            throw LinuxEnvException("rootfs 不完整（缺少 bin/busybox）：$info")
        }
        _progress.value = 0.95f
    }

    private fun extractWithSystemTar(archive: File, rootfs: File): Pair<Boolean, String> = try {
        val p = ProcessBuilder(
            "/system/bin/sh", "-c",
            "/system/bin/gzip -dc '${archive.absolutePath}' | /system/bin/tar -xf - -C '${rootfs.absolutePath}'",
        ).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        val code = p.waitFor()
        Pair(code == 0 && File(rootfs, "bin/busybox").exists(), "exit=$code")
    } catch (e: Exception) {
        Pair(false, "err=$e")
    }

    private fun extractWithSystemTarFile(archive: File, rootfs: File): Pair<Boolean, String> = try {
        val tar = File(archive.parentFile, "rootfs.tar")
        GZIPInputStream(archive.inputStream()).use { gz ->
            FileOutputStream(tar).use { out -> gz.copyTo(out, 16 * 1024) }
        }
        val p = ProcessBuilder("/system/bin/tar", "-xf", tar.absolutePath, "-C", rootfs.absolutePath)
            .redirectErrorStream(true).start()
        p.inputStream.readBytes()
        val code = p.waitFor()
        tar.delete()
        Pair(code == 0 && File(rootfs, "bin/busybox").exists(), "exit=$code")
    } catch (e: Exception) {
        Pair(false, "err=$e")
    }

    /** 纯 Java 兜底：gzip + 最小 TAR 解析（普通文件 / 目录 / 符号链接）。 */
    private fun extractWithJava(archive: File, rootfs: File): Pair<Boolean, String> {
        var count = 0
        try {
            GZIPInputStream(archive.inputStream()).use { gz ->
                val bytes = gz.readBytes()
                var offset = 0
                while (offset + 512 <= bytes.size) {
                    if ((offset until offset + 512).all { bytes[it] == 0.toByte() }) break
                    var name = ascii(bytes, offset, 100)
                    val prefix = ascii(bytes, offset + 345, 155)
                    if (prefix.isNotEmpty()) name = "$prefix/$name"
                    val sizeField = ascii(bytes, offset + 124, 12).trim()
                    val size = if (sizeField.isEmpty()) 0 else sizeField.toLongOrNull(8) ?: 0
                    val typeFlag = bytes[offset + 156]
                    val linkName = ascii(bytes, offset + 157, 100)
                    offset += 512
                    val end = (offset + size).coerceAtMost(bytes.size.toLong()).toInt()
                    val content = if (size > 0) bytes.copyOfRange(offset, end) else ByteArray(0)
                    offset += ((size + 511) / 512 * 512).toInt()

                    name = name.removePrefix("./")
                    while (name.startsWith("/")) name = name.drop(1)
                    if (name.isEmpty() || name.contains("..")) continue
                    val dest = File(rootfs, name)
                    when (typeFlag) {
                        '5'.code.toByte() -> dest.mkdirs()
                        '2'.code.toByte() -> {
                            dest.parentFile?.mkdirs()
                            runCatching {
                                if (dest.exists()) dest.delete()
                                android.system.Os.symlink(linkName, dest.absolutePath)
                            }
                        }
                        '0'.code.toByte(), 0.toByte() -> {
                            dest.parentFile?.mkdirs()
                            dest.writeBytes(content)
                        }
                    }
                    count++
                    if (count % 200 == 0) _message.value = "解压 $count 项…"
                }
            }
            for (bin in listOf("bin", "sbin", "usr/bin", "usr/sbin")) {
                val dir = File(rootfs, bin)
                if (dir.exists()) {
                    runCatching {
                        ProcessBuilder("/system/bin/chmod", "-R", "755", dir.absolutePath).start().waitFor()
                    }
                }
            }
            return Pair(count > 0 && File(rootfs, "bin/busybox").exists(), "条目=$count")
        } catch (e: Exception) {
            return Pair(false, "err=$e")
        }
    }

    private fun ascii(bytes: ByteArray, start: Int, length: Int): String {
        val end = (start + length).coerceAtMost(bytes.size)
        var stop = end
        for (i in start until end) {
            if (bytes[i] == 0.toByte()) {
                stop = i
                break
            }
        }
        return String(bytes, start, stop - start, Charsets.UTF_8)
    }

    private fun configure(rootfs: File) {
        set(LinuxEnvState.EXTRACTING, "写入配置…", 0.96f)
        val etc = File(rootfs, "etc").apply { mkdirs() }
        File(etc, "resolv.conf").writeText(DNS.joinToString("\n") { "nameserver $it" } + "\n")
        File(File(etc, "apk").apply { mkdirs() }, "repositories").writeText(
            "$MIRROR/alpine/$MAJOR/main\n$MIRROR/alpine/$MAJOR/community\n"
        )
        File(etc, "hosts").writeText(
            "127.0.0.1\tlocalhost\n::1\tlocalhost ip6-localhost ip6-loopback\n"
        )
        File(rootfs, "tmp").mkdirs()
        File(rootfs, "root").mkdirs()
        File(rootfs, MARKER).writeText("$VERSION\n")
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.0f KB", bytes / 1024.0)
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024))
    }

    private val rootfsUrl: String
        get() = "$MIRROR/alpine/$MAJOR/releases/$ARCH/alpine-minirootfs-$VERSION-$ARCH.tar.gz"

    companion object {
        const val VERSION = "3.22.5"
        const val ARCH = "aarch64"
        const val MIRROR = "https://dl-cdn.alpinelinux.org"
        val DNS = listOf("1.1.1.1", "8.8.8.8")
        private val MAJOR = "v${VERSION.split('.').take(2).joinToString(".")}"
        private const val MARKER = ".aurora-installed"

        lateinit var instance: LinuxEnv
            private set

        fun init(app: Application) {
            instance = LinuxEnv(app)
            instance.refresh()
        }
    }
}
