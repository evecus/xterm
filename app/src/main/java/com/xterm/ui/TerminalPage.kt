package com.xterm.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.xterm.R
import com.xterm.data.Server
import com.xterm.ssh.ConnState
import com.xterm.ssh.LinuxEnv
import com.xterm.ssh.LinuxEnvState
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.Terminal

/**
 * 终端必须显式指定打包进 APK 的等宽字体，不能依赖 termlib 默认的 Typeface.MONOSPACE。
 *
 * termlib 用 `paint.measureText("M")` 作为单元格宽度，再按 `col * cellWidth` 逐格绘制，
 * 并据此计算 cols/rows 通知 PTY resize。系统 MONOSPACE 在部分 ROM 上会走字体 fallback，
 * "M" 的 advance 与普通 ASCII 字形宽度不一致，导致字符间距被拉开、列数偏小、
 * 长行被提前折行。JetBrains Mono 自带字形、所有 ASCII 字符 advance 严格相等，
 * 与旧版 xterm / Flutter 版（引擎内置 monospace）表现一致。
 */
@Composable
private fun terminalTypeface(): Typeface {
    val context = LocalContext.current
    return remember {
        ResourcesCompat.getFont(context, R.font.jetbrainsmono_regular)
            ?: Typeface.MONOSPACE
    }
}

/**
 * 对齐 Aurora `pages/terminal_page.dart`：完整终端页。
 *
 * - SSH / 本机 / Linux(proot) 三类会话由 MainViewModel 统一分发；
 * - 连接中进度条、失败 MaterialBanner 重试、Linux rootfs 部署进度与失败重试；
 * - 仅单击终端弹出输入法，长按/选区不弹键盘；虚拟按键栏随键盘一同显隐；
 * - 长按有选区复制、无选区粘贴（termlib 内置选区工具条 + onPasteRequest）；
 * - SSH 连接中返回时按设置弹出「断开连接？」确认，本机 / Linux 直接退出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalPage(vm: MainViewModel, server: Server) {
    val settings by vm.settings.collectAsState()
    val connState by vm.connState.collectAsState()
    val linuxState by LinuxEnv.instance.state.collectAsState()
    val linuxProgress by LinuxEnv.instance.progress.collectAsState()
    val linuxMessage by LinuxEnv.instance.message.collectAsState()
    val linuxError by LinuxEnv.instance.error.collectAsState()

    val scheme = MaterialTheme.colorScheme
    val dark = vm.isDarkTheme()
    val bg = if (dark) AuroraColors.terminalDarkBg else AuroraColors.terminalLightBg
    val fg = if (dark) AuroraColors.terminalDarkFg else AuroraColors.terminalLightFg

    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val mono = terminalTypeface()

    var imeVisible by remember { mutableStateOf(false) }
    // showSoftKeyboard 是状态而非常量：进入页面不自动弹键盘，单击终端 / IME 键
    // 才请求弹出；收起后同步置 false，等价于 Aurora 的 _focusWithoutKeyboard/_toggleIme。
    var showSoftKeyboard by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var selectionController by remember { mutableStateOf<SelectionController?>(null) }

    // 主题切换时同步终端模拟器的默认前景/背景色（擦除、清屏用的颜色）。
    LaunchedEffect(dark) { vm.applyTerminalTheme(dark) }
    // 设置里的「修饰键自动取消」实时生效。
    LaunchedEffect(settings.virtKeyAutoOff) {
        vm.inputHandler.autoOff = settings.virtKeyAutoOff
    }

    fun requestExit() {
        // 本机 / Linux 始终直接退出；SSH 仅在确认开启且已连接时弹窗。
        if (!server.isOnDevice &&
            connState is ConnState.Connected &&
            settings.confirmExitTerminal
        ) {
            confirmExit = true
        } else {
            vm.leaveTerminal()
        }
    }

    fun toggleIme() {
        showSoftKeyboard = !showSoftKeyboard
        if (showSoftKeyboard) runCatching { focusRequester.requestFocus() }
    }

    BackHandler(enabled = true) { requestExit() }

    Scaffold(
        containerColor = bg,
        topBar = {
            TopAppBar(
                title = { Text(server.name, fontSize = 16.sp) },
                navigationIcon = {
                    IconButton(onClick = { requestExit() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (!server.isOnDevice) {
                        IconButton(onClick = { vm.openSftp(server) }) {
                            Icon(Icons.Filled.Folder, contentDescription = "SFTP 文件")
                        }
                        IconButton(
                            onClick = { vm.reconnect() },
                            enabled = connState !is ConnState.Connecting,
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = "重连")
                        }
                        IconButton(
                            onClick = { vm.disconnect() },
                            enabled = connState is ConnState.Connected,
                        ) {
                            Icon(Icons.Filled.LinkOff, contentDescription = "断开")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Linux：rootfs 尚未部署时先下载解压，再进入 shell。
            if (server.isLinux &&
                (linuxState == LinuxEnvState.DOWNLOADING || linuxState == LinuxEnvState.EXTRACTING)
            ) {
                AuroraLinearProgress(progress = if (linuxProgress <= 0f) null else linuxProgress)
                Text(
                    text = "正在部署 Alpine：${linuxMessage.ifEmpty { linuxState.label }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
                )
            }
            if (server.isLinux && linuxState == LinuxEnvState.FAILED) {
                AuroraBanner(
                    text = linuxError ?: "Alpine 部署失败",
                    onAction = { vm.ensureLinuxDeploy() },
                )
            }
            if (connState is ConnState.Connecting) {
                AuroraLinearProgress(progress = null)
            }
            if (connState is ConnState.Failed) {
                AuroraBanner(
                    text = (connState as ConnState.Failed).message,
                    onAction = { vm.reconnect() },
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val emu = vm.emulator
                if (emu != null) {
                    Terminal(
                        terminalEmulator = emu,
                        modifier = Modifier.fillMaxSize(),
                        typeface = mono,
                        initialFontSize = settings.fontSize.sp,
                        backgroundColor = bg,
                        foregroundColor = fg,
                        // Aurora _darkTheme.selection = 0x40FFFFFF / _lightTheme.selection = 0x40000000
                        selectionBackgroundColor = if (dark) Color(0x40FFFFFF) else Color(0x40000000),
                        keyboardEnabled = connState is ConnState.Connected,
                        showSoftKeyboard = showSoftKeyboard,
                        focusRequester = focusRequester,
                        onTerminalTap = {
                            showSoftKeyboard = true
                            runCatching { focusRequester.requestFocus() }
                        },
                        onImeVisibilityChanged = { visible ->
                            imeVisible = visible
                            if (!visible) showSoftKeyboard = false
                        },
                        onSelectionControllerAvailable = { controller ->
                            selectionController = controller
                        },
                        onPasteRequest = {
                            // 用 Android 原生剪贴板，避免依赖 Compose ClipboardManager 的版本差异。
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as? ClipboardManager
                            val clip = cm?.primaryClip
                            val text = if (clip != null && clip.itemCount > 0) {
                                clip.getItemAt(0).text?.toString()
                            } else null
                            if (text != null && text.isNotEmpty()) vm.paste(text)
                            selectionController?.clearSelection()
                        },
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("正在准备终端…", color = fg, fontSize = 13.sp)
                    }
                }
            }

            // 虚拟按键条随手机输入法键盘一同显示/消失（位于键盘上方）。
            if (settings.showVirtKeys && imeVisible) {
                VirtKeysBar(vm = vm, onToggleIme = { toggleIme() })
            }
        }
    }

    if (confirmExit) {
        ConfirmDialog(
            title = "断开连接？",
            message = "当前会话仍在连接中，确定退出吗？",
            confirmText = "断开并退出",
            onConfirm = {
                confirmExit = false
                vm.leaveTerminal()
            },
            onDismiss = { confirmExit = false },
        )
    }
}

/** 等价 Flutter MaterialBanner：错误底色 + 图标 + 文案 + 重试。 */
@Composable
private fun AuroraBanner(text: String, onAction: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = scheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                color = scheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAction) { Text("重试") }
        }
    }
}
