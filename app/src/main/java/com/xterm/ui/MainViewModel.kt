package com.xterm.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xterm.data.AppSettings
import com.xterm.data.Server
import com.xterm.data.ServerStore
import com.xterm.data.SettingsStore
import com.xterm.data.TermKey
import com.xterm.ssh.ConnState
import com.xterm.ssh.HostKeyPrompt
import com.xterm.ssh.LinuxEnv
import com.xterm.ssh.LinuxEnvState
import com.xterm.ssh.LocalShellSession
import com.xterm.ssh.SftpSession
import com.xterm.ssh.SshSession
import com.xterm.ssh.TerminalConnection
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import java.io.File
import kotlin.coroutines.resume

/** Aurora 的 8 个页面，1:1 映射为导航目的地。 */
sealed interface Screen {
    data object Home : Screen
    data class EditServer(val id: String?) : Screen
    data class Terminal(val server: Server) : Screen
    data object Settings : Screen
    data object VirtKeysSettings : Screen
    // 直接携带 Server 对象（对齐 Aurora 的 SftpPage(server:) / SysInfoPage(server:)）——
    // 编辑页「连接（不保存）」产生的临时服务器不在 Store 里，也能打开 SFTP / 系统信息。
    data class Sftp(val server: Server) : Screen
    data class SftpEdit(val fileName: String) : Screen
    data class SysInfo(val server: Server) : Screen
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val serverStore = ServerStore(app)
    val settingsStore = SettingsStore.instance
    val hosts: StateFlow<List<Server>> = serverStore.servers
    val settings: StateFlow<AppSettings> = settingsStore.settings

    private val _backStack = MutableStateFlow<List<Screen>>(listOf(Screen.Home))
    val backStack: StateFlow<List<Screen>> = _backStack.asStateFlow()
    val screen: Screen get() = _backStack.value.last()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // ── 终端会话状态 ────────────────────────────────────────────────────────
    private val _connState = MutableStateFlow<ConnState>(ConnState.Idle)
    val connState: StateFlow<ConnState> = _connState.asStateFlow()

    private val _hostKeyPrompt = MutableStateFlow<HostKeyPrompt?>(null)
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = _hostKeyPrompt.asStateFlow()

    /** 粘性修饰键状态机（对齐 Aurora 的 VirtKeyInputHandler）。 */
    val inputHandler = VirtKeyInputHandler()

    /** native 终端模拟器放在 ViewModel 中，避免旋转/重组时重建。 */
    var emulator: TerminalEmulator? = null
        private set
    var activeServer: Server? = null
        private set

    private var connection: TerminalConnection? = null
    private var pendingHostKey: CancellableContinuation<Boolean>? = null

    /** SFTP 列表页与编辑页共享同一个会话实例。 */
    var sftpSession: SftpSession? = null

    init {
        inputHandler.autoOff = settingsStore.value.virtKeyAutoOff
        // Linux rootfs（可能由设置页开关触发）部署完成后自动进入 shell。
        viewModelScope.launch {
            LinuxEnv.instance.state.collect { state ->
                if (state == LinuxEnvState.READY &&
                    activeServer?.isLinux == true &&
                    connection == null
                ) {
                    startConnection()
                }
            }
        }
    }

    // ── 导航 ────────────────────────────────────────────────────────────────
    fun navigate(to: Screen) {
        _backStack.value = _backStack.value + to
    }

    fun pop() {
        val stack = _backStack.value
        if (stack.size <= 1) return
        _backStack.value = stack.dropLast(1)
        if (!_backStack.value.any { it is Screen.Sftp || it is Screen.SftpEdit }) {
            sftpSession?.dispose()
            sftpSession = null
        }
    }

    fun showMessage(text: String) {
        _messages.tryEmit(text)
    }

    // ── 首页：排序与增删 ────────────────────────────────────────────────────
    /** 合并「本机 + Linux + 服务器」并按 homeListOrder 排序（Aurora _orderedItems）。 */
    fun orderedItems(): List<Server> {
        val servers = hosts.value
        val s = settings.value
        val byId = servers.associateBy { it.id }.toMutableMap()
        if (s.enableLocalTerminal) byId[Server.LOCAL_ID] = Server.local
        if (s.enableLinuxTerminal) byId[Server.LINUX_ID] = Server.linux

        val ordered = mutableListOf<Server>()
        val seen = mutableSetOf<String>()
        for (id in s.homeListOrder) {
            val item = byId[id] ?: continue
            if (id == Server.LOCAL_ID && !s.enableLocalTerminal) continue
            if (id == Server.LINUX_ID && !s.enableLinuxTerminal) continue
            ordered.add(item)
            seen.add(id)
        }
        if (s.enableLinuxTerminal && !seen.contains(Server.LINUX_ID)) {
            ordered.add(0, Server.linux)
            seen.add(Server.LINUX_ID)
        }
        if (s.enableLocalTerminal && !seen.contains(Server.LOCAL_ID)) {
            ordered.add(0, Server.local)
            seen.add(Server.LOCAL_ID)
        }
        for (item in servers) {
            if (!seen.contains(item.id)) ordered.add(item)
        }
        return ordered
    }

    fun moveHomeItem(oldIndex: Int, newIndex: Int) {
        val items = orderedItems().toMutableList()
        if (oldIndex !in items.indices) return
        var target = newIndex
        if (target > oldIndex) target -= 1
        if (target !in items.indices) return
        val moved = items.removeAt(oldIndex)
        items.add(target, moved)
        settingsStore.setHomeListOrder(items.map { it.id })
    }

    /** 长按拖动排序：把 from 位置直接移动到 to 位置。 */
    fun moveHomeItemExact(from: Int, to: Int) {
        val items = orderedItems().toMutableList()
        if (from !in items.indices || to !in items.indices || from == to) return
        val moved = items.removeAt(from)
        items.add(to, moved)
        settingsStore.setHomeListOrder(items.map { it.id })
    }

    fun deleteServer(id: String) {
        serverStore.remove(id)
        val order = settings.value.homeListOrder.toMutableList()
        if (order.remove(id)) settingsStore.setHomeListOrder(order)
        showMessage("已删除")
    }

    // ── 终端 ────────────────────────────────────────────────────────────────
    fun openTerminal(server: Server) {
        releaseSession(closeEmulator = true)
        activeServer = server
        createEmulator()
        if (server.isLinux && !LinuxEnv.instance.isInstalled()) {
            // 未部署：页面显示部署进度，init 中的状态监听会在 READY 后自动连接。
            _connState.value = ConnState.Idle
            viewModelScope.launch { LinuxEnv.instance.install() }
        } else {
            startConnection()
        }
        navigate(Screen.Terminal(server))
    }

    private fun createEmulator() {
        val dark = isDarkTheme()
        val fg = if (dark) Color(0xFFCCCCCC) else Color(0xFF333333)
        val bg = if (dark) Color(0xFF1E1E1E) else Color(0xFFFAFAFA)
        val emu = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            defaultForeground = fg,
            defaultBackground = bg,
            onKeyboardInput = { data -> connection?.write(inputHandler.transform(data)) },
            onResize = { dim -> connection?.resize(dim.columns, dim.rows) },
            onClipboardCopy = { text -> copyToClipboard(text) },
        )
        // 完整套用 Aurora terminal_page.dart 的 16 色 ANSI 调色板 + 默认前景/背景，
        // 而不是只设默认两色，保证 ls/vim/top 等彩色输出观感一致。
        emu.applyColorScheme(
            if (dark) AuroraDarkAnsi else AuroraLightAnsi,
            fg.toArgbPacked(),
            bg.toArgbPacked(),
        )
        // applyColorScheme 只改 libvterm 的「默认色」，不会刷新当前画笔（pen）：
        // 画笔里仍是 libvterm 初始的 240 灰前景 + 纯黑背景，直到收到第一条 SGR 0。
        // nano / vim / less 进入备用屏（?1049h）时会保存这支旧画笔，退出（?1049l）时原样恢复，
        // 于是此后输出与清屏都用黑底，界面从白底黑字变成黑底白字。
        // 这里主动让画笔对齐默认色：ESC[0m 复位画笔，ESC 7 同步写入「已保存画笔」。
        // 此时还没有连接、光标在 (0,0)，写入没有副作用。
        runCatching { emu.writeInput("\u001b[0m\u001b7".toByteArray()) }
        appliedDark = dark
        emulator = emu
    }

    private fun startConnection() {
        val server = activeServer ?: return
        val onOutput: (ByteArray) -> Unit = { bytes ->
            runCatching { emulator?.writeInput(bytes) }
        }
        val onState: (ConnState) -> Unit = { state -> _connState.value = state }
        val onInfo: (String) -> Unit = { }
        connection = if (server.isLocal || server.isLinux) {
            LocalShellSession(
                app = getApplication(),
                linux = server.isLinux,
                onOutput = onOutput,
                onState = onState,
                onInfo = onInfo,
            )
        } else {
            SshSession(
                server = server,
                knownHostsFile = File(getApplication<Application>().filesDir, "known_hosts"),
                onOutput = onOutput,
                onState = onState,
                requestHostKey = { prompt -> askHostKey(prompt) },
                onInfo = onInfo,
            )
        }
        connection?.connect(80, 24)
    }

    /** 重连：保留终端内容与当前键盘状态（Aurora 同款行为）。 */
    fun reconnect() {
        connection?.close()
        connection = null
        startConnection()
    }

    /** 断开：只关连接，保留终端内容。 */
    fun disconnect() {
        connection?.close()
        connection = null
    }

    fun ensureLinuxDeploy() {
        viewModelScope.launch { LinuxEnv.instance.install() }
    }

    fun leaveTerminal() {
        releaseSession(closeEmulator = true)
        _connState.value = ConnState.Idle
        pop()
    }

    private fun releaseSession(closeEmulator: Boolean) {
        (connection as? LocalShellSession)?.dispose() ?: runCatching { connection?.close() }
        connection = null
        if (closeEmulator) {
            runCatching { emulator?.close() }
            emulator = null
            activeServer = null
        }
        inputHandler.resetAll()
    }

    fun sendBytes(bytes: ByteArray) {
        connection?.write(bytes)
    }

    /**
     * 虚拟按键的 terminalKey → termlib dispatchKey。
     * key 用 libvterm 的 VTermKey 枚举值（稳定常量，避免依赖 termlib 内部符号），
     * modifiers 用 VTermModifier 位掩码：SHIFT=1 / ALT=2 / CTRL=4。
     * 走 emulator 才能在 application cursor keys 模式下自动切换 ESC O / ESC [ 序列。
     */
    fun dispatchTermKey(key: TermKey, modifiers: Int = 0) {
        val code = when (key) {
            TermKey.ENTER -> 1
            TermKey.TAB -> 2
            TermKey.BACKSPACE -> 3
            TermKey.ESCAPE -> 4
            TermKey.ARROW_UP -> 5
            TermKey.ARROW_DOWN -> 6
            TermKey.ARROW_LEFT -> 7
            TermKey.ARROW_RIGHT -> 8
            TermKey.DELETE -> 10
            TermKey.HOME -> 11
            TermKey.END -> 12
            TermKey.PAGE_UP -> 13
            TermKey.PAGE_DOWN -> 14
        }
        runCatching { emulator?.dispatchKey(modifiers, code) }
    }

    fun paste(text: String) {
        if (text.isEmpty()) return
        // termlib 的 pasteText 自动处理 bracketed paste（对齐 Aurora terminal.paste）
        runCatching { emulator?.pasteText(text) }
    }

    /** 终端模拟器当前已套用的主题；用于避免同主题重复套用时打断正在运行的程序。 */
    private var appliedDark: Boolean? = null

    fun applyTerminalTheme(dark: Boolean) {
        val emu = emulator ?: return
        // createEmulator 已按当前主题套用过；进入终端页的 LaunchedEffect 不必再来一次。
        if (appliedDark == dark) return
        val fg = if (dark) Color(0xFFCCCCCC) else Color(0xFF333333)
        val bg = if (dark) Color(0xFF1E1E1E) else Color(0xFFFAFAFA)
        runCatching {
            emu.applyColorScheme(
                if (dark) AuroraDarkAnsi else AuroraLightAnsi,
                fg.toArgbPacked(),
                bg.toArgbPacked(),
            )
            // 同 createEmulator：让画笔对齐新默认色（运行时切换主题不写 ESC 7，
            // 以免覆盖全屏程序自己保存的光标位置）。
            emu.writeInput("\u001b[0m".toByteArray())
            appliedDark = dark
        }
    }

    fun isDarkTheme(): Boolean = when (settingsStore.value.themeMode) {
        com.xterm.data.AppThemeMode.SYSTEM -> isSystemDark()
        com.xterm.data.AppThemeMode.LIGHT -> false
        com.xterm.data.AppThemeMode.DARK -> true
    }

    private fun isSystemDark(): Boolean {
        val config = getApplication<Application>().resources.configuration
        return (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    fun respondHostKey(accept: Boolean) {
        _hostKeyPrompt.value = null
        val continuation = pendingHostKey
        pendingHostKey = null
        if (continuation != null && continuation.isActive) continuation.resume(accept)
    }

    private suspend fun askHostKey(prompt: HostKeyPrompt): Boolean =
        suspendCancellableCoroutine { continuation ->
            pendingHostKey?.cancel()
            pendingHostKey = continuation
            _hostKeyPrompt.value = prompt
            continuation.invokeOnCancellation {
                if (_hostKeyPrompt.value === prompt) _hostKeyPrompt.value = null
            }
        }

    private fun copyToClipboard(text: String) {
        Handler(Looper.getMainLooper()).post {
            val manager = getApplication<Application>()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }
    }

    // ── SFTP / 系统信息 ─────────────────────────────────────────────────────
    fun serverById(id: String): Server? = serverStore.byId(id)

    fun openSftp(server: Server) {
        sftpSession?.dispose()
        sftpSession = null
        navigate(Screen.Sftp(server))
    }

    fun openSysInfo(server: Server) = navigate(Screen.SysInfo(server))

    override fun onCleared() {
        (connection as? LocalShellSession)?.dispose() ?: runCatching { connection?.close() }
        runCatching { emulator?.close() }
        sftpSession?.dispose()
        super.onCleared()
    }
}

/**
 * Aurora `terminal_page.dart` 的 `_darkTheme` ANSI 16 色（VS Code Dark+ 风格）。
 * 顺序即 libvterm 索引 0..15：black/red/green/yellow/blue/magenta/cyan/white
 * + 对应 bright 八色。
 */
private val AuroraDarkAnsi: IntArray = intArrayOf(
    0xFF000000.toInt(), 0xFFCD3131.toInt(), 0xFF0DBC79.toInt(), 0xFFE5E510.toInt(),
    0xFF2472C8.toInt(), 0xFFBC3FBC.toInt(), 0xFF11A8CD.toInt(), 0xFFE5E5E5.toInt(),
    0xFF666666.toInt(), 0xFFF14C4C.toInt(), 0xFF23D18B.toInt(), 0xFFF5F543.toInt(),
    0xFF3B8EEA.toInt(), 0xFFD670D6.toInt(), 0xFF29B8DB.toInt(), 0xFFE5E5E5.toInt(),
)

/** Aurora `terminal_page.dart` 的 `_lightTheme` ANSI 16 色。 */
private val AuroraLightAnsi: IntArray = intArrayOf(
    0xFF000000.toInt(), 0xFFCD3131.toInt(), 0xFF00BC00.toInt(), 0xFF949800.toInt(),
    0xFF0451A5.toInt(), 0xFFBC05BC.toInt(), 0xFF0598BC.toInt(), 0xFF555555.toInt(),
    0xFF666666.toInt(), 0xFFCD3131.toInt(), 0xFF14CE14.toInt(), 0xFFB5B000.toInt(),
    0xFF0451A5.toInt(), 0xFFBC05BC.toInt(), 0xFF0598BC.toInt(), 0xFFA5A5A5.toInt(),
)

/** Compose Color → 0xAARRGGBB（termlib setDefaultColors 需要的整型）。 */
private fun Color.toArgbPacked(): Int {
    val a = ((alpha * 255).toInt() and 0xFF) shl 24
    val r = ((red * 255).toInt() and 0xFF) shl 16
    val g = ((green * 255).toInt() and 0xFF) shl 8
    val b = ((blue * 255).toInt() and 0xFF)
    return a or r or g or b
}
