package com.xterm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.xterm.data.Server

/**
 * 对齐 Aurora `pages/home_page.dart`：服务器列表（含本机 / Linux 入口）、
 * 长按拖动排序、点击连接、系统信息 / SFTP / 编辑 / 删除操作、设置入口、添加按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePage(vm: MainViewModel) {
    // collectAsState 订阅 hosts / settings 的变化，驱动列表与排序重组
    val hosts by vm.hosts.collectAsState()
    val settings by vm.settings.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val dependency = hosts.size to settings.fontSize
    val items = vm.orderedItems()
    val scheme = MaterialTheme.colorScheme
    var pendingDelete by remember { mutableStateOf<Server?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("服务器") },
                actions = {
                    IconButton(onClick = { vm.navigate(Screen.Settings) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.navigate(Screen.EditServer(null)) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("添加") },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.Dns,
                    contentDescription = null,
                    tint = scheme.outline,
                    modifier = Modifier.size(72.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "还没有服务器",
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "点击右下角添加一台，或在设置中启用本机终端 / Linux 终端",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.outline,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        } else {
            val itemPx = with(LocalDensity.current) { 76.dp.toPx() }
            var dragAccum by remember { mutableStateOf(0f) }
            var dragTo by remember { mutableStateOf(0) }
            var draggingId by remember { mutableStateOf<String?>(null) }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(items, key = { _, s -> s.id }) { index, server ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = auroraCardColor()),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(
                            defaultElevation = if (draggingId == server.id) 6.dp else 0.dp,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(server.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingId = server.id
                                        dragTo = index
                                        dragAccum = 0f
                                    },
                                    onDragEnd = { draggingId = null },
                                    onDragCancel = { draggingId = null },
                                    onDrag = { _, dragAmount: Offset ->
                                        dragAccum += dragAmount.y
                                        val step = (dragAccum / itemPx).toInt()
                                        if (step != 0) {
                                            val target = (dragTo + step).coerceIn(0, items.lastIndex)
                                            if (target != dragTo) {
                                                vm.moveHomeItemExact(dragTo, target)
                                                dragTo = target
                                            }
                                            dragAccum = 0f
                                        }
                                    },
                                )
                            }
                            .clickable { vm.openTerminal(server) },
                    ) {
                        ServerTile(
                            server = server,
                            onSysInfo = { vm.openSysInfo(server) },
                            onSftp = { vm.openSftp(server) },
                            onEdit = { vm.navigate(Screen.EditServer(server.id)) },
                            onDelete = { pendingDelete = server },
                        )
                    }
                }
            }
        }
    }

    if (pendingDelete != null) {
        val target = pendingDelete!!
        ConfirmDialog(
            title = "删除服务器",
            message = "确定删除「${target.name}」？",
            confirmText = "删除",
            onConfirm = {
                vm.deleteServer(target.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun ServerTile(
    server: Server,
    onSysInfo: () -> Unit,
    onSftp: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val badgeColor: Color
    val badgeFg: Color
    val icon: ImageVector
    when {
        server.isLocal -> {
            badgeColor = scheme.tertiaryContainer
            badgeFg = scheme.onTertiaryContainer
            icon = Icons.Filled.PhoneAndroid
        }
        server.isLinux -> {
            badgeColor = scheme.secondaryContainer
            badgeFg = scheme.onSecondaryContainer
            icon = Icons.Filled.Computer
        }
        else -> {
            badgeColor = scheme.primaryContainer
            badgeFg = scheme.onPrimaryContainer
            icon = Icons.Filled.Code
        }
    }
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(badgeColor, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = badgeFg)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = server.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.W600,
            modifier = Modifier.weight(1f),
        )
        if (!server.isOnDevice) {
            IconButton(onClick = onSysInfo) {
                Icon(Icons.Filled.Info, contentDescription = "系统信息", tint = scheme.primary)
            }
            IconButton(onClick = onSftp) {
                Icon(Icons.Filled.Folder, contentDescription = "SFTP 文件", tint = scheme.primary)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}
