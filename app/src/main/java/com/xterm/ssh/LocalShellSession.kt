package com.xterm.ssh

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 本机终端 / 本机 Linux（proot）终端会话。
 *
 * 与 Aurora `local_session.dart` / `linux_session.dart` 同构：真伪终端、
 * 支持 resize、交互程序可用；区别是此处用 [PtyBridge]（NDK /dev/ptmx）
 * 代替 flutter_pty。终端显示层仍由 termlib 统一负责。
 */
class LocalShellSession(
    private val app: Application,
    private val linux: Boolean,
    private val onOutput: (ByteArray) -> Unit,
    private val onState: (ConnState) -> Unit,
    private val onInfo: (String) -> Unit = {},
) : TerminalConnection {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var fd = -1

    override fun connect(cols: Int, rows: Int) {
        scope.launch {
            onState(ConnState.Connecting)
            try {
                val (argv, env, cwd) = if (linux) buildLinuxCommand() else buildLocalCommand()
                val master = PtyBridge.start(argv, env, cwd, rows, cols)
                if (master < 0) {
                    throw IllegalStateException(
                        if (!PtyBridge.available) "缺少本地 PTY 库（libxtermpty.so）"
                        else "无法启动本地伪终端",
                    )
                }
                fd = master
                onState(ConnState.Connected)
                pump(master)
            } catch (e: Exception) {
                onState(ConnState.Failed(e.message ?: e.toString()))
            }
        }
    }

    private fun pump(master: Int) {
        val buf = ByteArray(16 * 1024)
        while (fd == master) {
            val n = PtyBridge.read(master, buf)
            if (n > 0) {
                onOutput(buf.copyOf(n))
            } else if (n < 0) {
                break
            }
        }
        if (fd == master) {
            fd = -1
            onState(ConnState.Disconnected)
        }
    }

    override fun write(data: ByteArray) {
        val current = fd
        if (current < 0 || data.isEmpty()) return
        scope.launch { PtyBridge.write(current, data) }
    }

    override fun resize(cols: Int, rows: Int) {
        val current = fd
        if (current >= 0) PtyBridge.resize(current, rows, cols)
    }

    override fun close() {
        val current = fd
        if (current >= 0) {
            fd = -1
            // 与 Aurora 一致：SIGHUP 结束会话内的进程组。
            // close() 会注销 fd 记录，之后再 kill 已拿不到 pid，故不做延迟补刀。
            PtyBridge.kill(current, 1) // SIGHUP
            PtyBridge.close(current)
        }
        onState(ConnState.Disconnected)
    }

    private fun buildLocalCommand(): Triple<List<String>, Map<String, String>, String> {
        val home = app.filesDir.absolutePath
        val env = mapOf(
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "HOME" to home,
            "PATH" to "/system/bin:/system/xbin:/sbin:/bin",
        )
        return Triple(listOf("/system/bin/sh"), env, home)
    }

    private fun buildLinuxCommand(): Triple<List<String>, Map<String, String>, String> {
        val env = LinuxEnv.instance
        if (!env.isInstalled()) {
            throw LinuxEnvException("Alpine 未部署，请到设置中开启 Linux 终端")
        }
        val proot = env.prootPath() ?: throw LinuxEnvException("找不到 proot（libproot.so）")
        val loader = env.loaderPath() ?: throw LinuxEnvException("找不到 proot loader（libproot-loader.so）")
        val rootfs = env.rootfsDir().absolutePath
        val tmpDir = env.ensureProotTmpDir()

        val binds = mutableListOf("/dev:/dev", "/proc:/proc", "/sys:/sys")
        // /dev 已整体绑定，/dev/pts、/dev/shm、/dev/ashmem 等子路径会随之可见，
        // 再单独绑定只会触发 proot 的 "can't sanitize binding" 警告，故不再重复绑定。
        for (path in listOf("/sdcard", "/storage", "/mnt")) {
            if (accessible(path)) binds.add("$path:$path")
        }
        val args = mutableListOf<String>(
            "-v", "-1", // 关闭 proot 的非致命警告输出（致命错误仍会打印）
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", rootfs,
            "-w", "/root",
        )
        for (b in binds) {
            args.add("-b")
            args.add(b)
        }
        args.add("/bin/sh")
        args.add("-l")

        val guestEnv = mapOf(
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME" to "/root",
            "SHELL" to "/bin/sh",
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "LANG" to "C.UTF-8",
            "PWD" to "/root",
            "USER" to "root",
        )
        val prootEnv = guestEnv + mapOf(
            "PROOT_LOADER" to loader,
            "PROOT_TMP_DIR" to tmpDir,
            "TMPDIR" to tmpDir,
            "PROOT_NO_SECCOMP" to "1",
        )

        val su = detectSu()
        if (su != null) {
            val exports = prootEnv.entries.joinToString("; ") { "export ${it.key}=${shQuote(it.value)}" }
            val exec = "exec ${shQuote(proot)} " + args.joinToString(" ") { shQuote(it) }
            val cmd = "$exports; $exec"
            onInfo("Linux 已通过 su 启动")
            return Triple(
                listOf(su, "-c", cmd),
                mapOf(
                    "PATH" to "/system/bin:/system/xbin:/sbin:/bin",
                    "TERM" to "xterm-256color",
                ),
                rootfs,
            )
        }
        onInfo("Linux 以普通 uid 启动（fake root -0）")
        return Triple(listOf(proot) + args, prootEnv, rootfs)
    }

    private fun accessible(path: String): Boolean = try {
        File(path).exists()
    } catch (_: Exception) {
        false
    }

    private fun detectSu(): String? {
        val candidates = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/data/local/tmp/su",
        )
        val su = candidates.firstOrNull { File(it).exists() } ?: "su"
        val found = AtomicReference<String?>(null)
        val t = thread {
            try {
                val p = ProcessBuilder(su, "-c", "id -u").redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText().trim()
                if (p.waitFor() == 0 && out == "0") found.set(su)
            } catch (_: Exception) {
            }
        }
        t.join(3000)
        if (t.isAlive) t.interrupt()
        return found.get()
    }

    private fun shQuote(s: String): String = "'${s.replace("'", "'\\''")}'"

    fun dispose() {
        close()
        scope.cancel()
    }
}
