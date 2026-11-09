package com.xterm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xterm.data.AppThemeMode
import com.xterm.ssh.LinuxEnv
import com.xterm.ssh.LinuxEnvState
import kotlinx.coroutines.launch

/** 对齐 Aurora `pages/settings_page.dart`：外观 / 终端 / 关于三个分区。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(vm: MainViewModel) {
    val settings by vm.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var showThemeSheet by remember { mutableStateOf(false) }

    val linux = LinuxEnv.instance
    val linuxState by linux.state.collectAsState()
    val linuxProgress by linux.progress.collectAsState()
    val linuxMessage by linux.message.collectAsState()
    val linuxError by linux.error.collectAsState()
    var linuxSize by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) { linux.refresh() }
    LaunchedEffect(linuxState) {
        if (linuxState == LinuxEnvState.READY && linuxSize == null) {
            linuxSize = linux.sizeBytes()
        }
    }

    Scaffold(
        topBar = { AuroraTopAppBar(title = "设置", onBack = { vm.pop() }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("外观")
            ListItem(
                headlineContent = { Text("主题") },
                supportingContent = { Text(themeLabel(settings.themeMode)) },
                trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable { showThemeSheet = true },
            )
            ListItem(
                headlineContent = { Text("终端字号") },
                supportingContent = { Text(settings.fontSize.toInt().toString()) },
                trailingContent = {
                    Slider(
                        value = settings.fontSize,
                        onValueChange = { vm.settingsStore.setFontSize(it) },
                        valueRange = 10f..22f,
                        steps = 12,
                        modifier = Modifier.width(160.dp),
                    )
                },
            )
            HorizontalDivider()
            SectionHeader("终端")
            SettingSwitch(
                title = "启用本机终端",
                subtitle = "首页显示本机 shell 入口（不支持 SFTP）",
                checked = settings.enableLocalTerminal,
                onChange = { vm.settingsStore.setEnableLocalTerminal(it) },
            )
            SettingSwitch(
                title = "启用 Linux 终端",
                subtitle = "首页显示 Linux 入口：本机 proot 运行 Alpine 3.22.5",
                checked = settings.enableLinuxTerminal,
                onChange = { value -> scope.launch { vm.settingsStore.setEnableLinuxTerminal(value) } },
            )
            if (settings.enableLinuxTerminal) {
                LinuxStatusTile(
                    state = linuxState,
                    progress = linuxProgress,
                    message = linuxMessage,
                    error = linuxError,
                    sizeBytes = linuxSize,
                    onRedeploy = {
                        linuxSize = null
                        scope.launch {
                            linux.uninstall()
                            linux.install()
                        }
                    },
                    onClean = {
                        linuxSize = null
                        scope.launch { linux.uninstall() }
                    },
                )
            }
            SettingSwitch(
                title = "终端退出确认",
                subtitle = "SSH 连接中返回时弹出确认（本机终端始终不弹）",
                checked = settings.confirmExitTerminal,
                onChange = { vm.settingsStore.setConfirmExitTerminal(it) },
            )
            SettingSwitch(
                title = "显示虚拟按键",
                subtitle = "底部 Ctrl / Esc / 方向键等",
                checked = settings.showVirtKeys,
                onChange = { vm.settingsStore.setShowVirtKeys(it) },
            )
            SettingSwitch(
                title = "修饰键自动取消",
                subtitle = "Ctrl 等组合生效一次后自动熄灭",
                checked = settings.virtKeyAutoOff,
                onChange = {
                    vm.settingsStore.setVirtKeyAutoOff(it)
                    vm.inputHandler.autoOff = it
                },
            )
            ListItem(
                headlineContent = { Text("自定义虚拟按键") },
                supportingContent = { Text("已选 ${settings.virtKeys.size} 键 · 每行 ${settings.virtKeysPerRow}") },
                trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable(enabled = settings.showVirtKeys) {
                    vm.navigate(Screen.VirtKeysSettings)
                },
            )
            HorizontalDivider()
            SectionHeader("关于")
            ListItem(
                headlineContent = { Text("Aurora") },
                supportingContent = { Text("v1.0.0 · xterm + dartssh2") },
            )
            ListItem(
                headlineContent = { Text("说明") },
                supportingContent = { Text("轻量 SSH 客户端。服务器信息保存在本地，不会上传。") },
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showThemeSheet) {
        ModalBottomSheet(
            onDismissRequest = { showThemeSheet = false },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                for (mode in AppThemeMode.entries) {
                    ListItem(
                        headlineContent = { Text(themeLabel(mode)) },
                        trailingContent = if (settings.themeMode == mode) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else null,
                        modifier = Modifier.clickable {
                            vm.settingsStore.setThemeMode(mode)
                            showThemeSheet = false
                        },
                    )
                }
            }
        }
    }
}

private fun themeLabel(mode: AppThemeMode): String = when (mode) {
    AppThemeMode.SYSTEM -> "跟随系统"
    AppThemeMode.LIGHT -> "浅色"
    AppThemeMode.DARK -> "深色"
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onChange)
        },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun LinuxStatusTile(
    state: LinuxEnvState,
    progress: Float,
    message: String,
    error: String?,
    sizeBytes: Long?,
    onRedeploy: () -> Unit,
    onClean: () -> Unit,
) {
    val busy = state == LinuxEnvState.DOWNLOADING || state == LinuxEnvState.EXTRACTING
    val subtitle = when (state) {
        LinuxEnvState.READY -> "已部署" + if (sizeBytes != null) " · 占用 ${String.format("%.0f", sizeBytes / 1024.0 / 1024.0)} MB" else ""
        LinuxEnvState.FAILED -> error ?: "部署失败"
        LinuxEnvState.MISSING -> "未部署"
        LinuxEnvState.UNKNOWN -> "检查中…"
        else -> if (message.isEmpty()) state.label else "${state.label}：$message"
    }
    var menuOpen by remember { mutableStateOf(false) }
    Column {
        ListItem(
            headlineContent = { Text("Alpine 环境") },
            supportingContent = { Text(subtitle) },
            trailingContent = {
                if (busy) {
                    Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                } else {
                    Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("重新部署") },
                                onClick = { menuOpen = false; onRedeploy() },
                            )
                            DropdownMenuItem(
                                text = { Text("清理 rootfs") },
                                onClick = { menuOpen = false; onClean() },
                            )
                        }
                    }
                }
            },
        )
        if (busy) {
            AuroraLinearProgress(
                progress = if (progress <= 0f) null else progress,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}
