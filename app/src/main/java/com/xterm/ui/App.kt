package com.xterm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

/** 全局导航壳：页面栈、Snackbar、TOFU 主机密钥弹窗、系统返回。 */
@Composable
fun App(vm: MainViewModel = viewModel()) {
    val stack by vm.backStack.collectAsState()
    val prompt by vm.hostKeyPrompt.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val screen = stack.last()) {
            is Screen.Home -> HomePage(vm)
            is Screen.EditServer -> ServerEditPage(vm, screen.id)
            is Screen.Terminal -> TerminalPage(vm, screen.server)
            is Screen.Settings -> SettingsPage(vm)
            is Screen.VirtKeysSettings -> VirtKeysSettingsPage(vm)
            is Screen.Sftp -> SftpPage(vm, screen.server)
            is Screen.SftpEdit -> SftpEditPage(vm, screen.fileName)
            is Screen.SysInfo -> SysInfoPage(vm, screen.server)
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (prompt != null) {
        val p = prompt!!
        AlertDialog(
            onDismissRequest = { vm.respondHostKey(false) },
            title = { Text(if (p.changed) "主机密钥已变更" else "首次连接确认") },
            text = {
                Text(
                    buildString {
                        append(
                            if (p.changed) {
                                "警告：服务器主机密钥与本地记录不一致，可能存在中间人攻击。\n\n"
                            } else {
                                "本地尚无该主机的密钥记录，请核对指纹后决定是否信任。\n\n"
                            }
                        )
                        append("主机：${p.host}:${p.port}\n")
                        append("算法：${p.algorithm}\n")
                        append("指纹：${p.fingerprint}")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.respondHostKey(true) }) { Text("信任并连接") }
            },
            dismissButton = {
                TextButton(onClick = { vm.respondHostKey(false) }) { Text("拒绝") }
            },
        )
    }

    // 终端页（断开确认）与编辑页（未保存确认）自己处理返回，其余页面统一出栈。
    // 这两个页面若同时启用外层 BackHandler，会按 LIFO 抢先执行而绕过确认弹窗。
    BackHandler(
        enabled = stack.size > 1 &&
            stack.last() !is Screen.Terminal &&
            stack.last() !is Screen.SftpEdit,
    ) {
        vm.pop()
    }
}
