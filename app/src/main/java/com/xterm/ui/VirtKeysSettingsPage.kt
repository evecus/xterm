package com.xterm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.xterm.data.VirtKeyDef
import com.xterm.data.VirtKeyKind

/**
 * 对齐 Aurora `pages/virt_keys_settings_page.dart`：预设、每行键数、已启用列表
 * （拖动排序 / 点按编辑 / 删除）、添加按键、内置目录、保存布局。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VirtKeysSettingsPage(vm: MainViewModel) {
    val settings by vm.settings.collectAsState()
    var keys by remember { mutableStateOf(settings.virtKeys) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    val itemPx = with(LocalDensity.current) { 64.dp.toPx() }
    var dragAccum by remember { mutableStateOf(0f) }
    var dragTo by remember { mutableStateOf(0) }

    fun persist() {
        vm.settingsStore.setVirtKeys(keys)
        vm.showMessage("已保存")
    }

    fun applyPreset(preset: List<VirtKeyDef>) {
        keys = preset
        vm.settingsStore.setVirtKeys(keys)
    }

    Scaffold(
        topBar = {
            AuroraTopAppBar(
                title = "虚拟按键",
                onBack = { vm.pop() },
                actions = {
                    TextButton(onClick = { persist() }) { Text("保存") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "预设",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.W600,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = { applyPreset(VirtKeyDef.defaultKeys) }) { Text("完整") }
                FilledTonalButton(onClick = { applyPreset(VirtKeyDef.compactKeys) }) { Text("精简") }
                FilledTonalButton(onClick = { applyPreset(VirtKeyDef.defaultKeys) }) { Text("恢复默认") }
            }
            ListItem(
                headlineContent = { Text("每行键数") },
                supportingContent = { Text("${settings.virtKeysPerRow}") },
                trailingContent = {
                    Slider(
                        value = settings.virtKeysPerRow.toFloat(),
                        onValueChange = { vm.settingsStore.setVirtKeysPerRow(it.toInt()) },
                        valueRange = 4f..10f,
                        steps = 6,
                        modifier = Modifier.width(160.dp),
                    )
                },
            )
            HorizontalDivider()
            Text(
                "已启用（长按拖动排序，点按编辑）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.W600,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            )
            if (keys.isEmpty()) {
                Text(
                    "尚未添加任何按键",
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                keys.forEachIndexed { index, def ->
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.DragHandle, contentDescription = null) },
                        headlineContent = { Text(if (def.label.isEmpty()) "?" else def.label) },
                        supportingContent = { Text(actionSubtitle(def)) },
                        trailingContent = {
                            IconButton(onClick = {
                                keys = keys.toMutableList().also { it.removeAt(index) }
                            }) {
                                Icon(Icons.Filled.RemoveCircleOutline, contentDescription = "删除")
                            }
                        },
                        modifier = Modifier
                            .pointerInput(def.id + index) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragTo = index; dragAccum = 0f },
                                    onDragEnd = { },
                                    onDragCancel = { },
                                    onDrag = { _, amount: Offset ->
                                        dragAccum += amount.y
                                        val step = (dragAccum / itemPx).toInt()
                                        if (step != 0) {
                                            val target = (dragTo + step).coerceIn(0, keys.lastIndex)
                                            if (target != dragTo) {
                                                val next = keys.toMutableList()
                                                val moved = next.removeAt(dragTo)
                                                next.add(target, moved)
                                                keys = next
                                                dragTo = target
                                            }
                                            dragAccum = 0f
                                        }
                                    },
                                )
                            }
                            .clickable { editIndex = index },
                    )
                }
            }
            OutlinedButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(44.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("添加按键")
            }
            HorizontalDivider()
            Text(
                "从内置目录添加",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.W600,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            )
            for (def in VirtKeyDef.catalog) {
                ListItem(
                    leadingContent = {
                        Icon(
                            when (def.kind) {
                                VirtKeyKind.MODIFIER -> Icons.Filled.Keyboard
                                VirtKeyKind.TERMINAL_KEY -> Icons.Filled.Keyboard
                                VirtKeyKind.CHAR -> Icons.Filled.TextFields
                                VirtKeyKind.FUNC -> Icons.Filled.TouchApp
                            },
                            contentDescription = null,
                        )
                    },
                    headlineContent = { Text(def.label) },
                    supportingContent = { Text(def.id) },
                    trailingContent = {
                        IconButton(onClick = {
                            val exists = keys.any { it.id == def.id }
                            val copy = if (exists) {
                                def.copy(id = "${def.id}_${System.currentTimeMillis()}")
                            } else def
                            keys = keys + copy
                        }) {
                            Icon(Icons.Filled.AddCircleOutline, contentDescription = "添加")
                        }
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
            androidx.compose.material3.Button(
                onClick = { persist() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp),
            ) {
                Icon(Icons.Filled.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("保存布局")
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (editIndex != null) {
        val index = editIndex!!
        KeyEditDialog(
            title = "编辑按键",
            initial = keys[index],
            isNew = false,
            onConfirm = { result ->
                keys = keys.toMutableList().also { it[index] = result }
                editIndex = null
            },
            onDismiss = { editIndex = null },
        )
    }
    if (showAddDialog) {
        KeyEditDialog(
            title = "添加按键",
            initial = VirtKeyDef.fromAction(
                id = "custom_${System.currentTimeMillis()}",
                label = "",
                actionId = "send_text",
                payload = "",
            ),
            isNew = true,
            onConfirm = { result ->
                keys = keys + result
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

private fun actionSubtitle(k: VirtKeyDef): String {
    val aid = k.actionId
    if (aid == "send_text") {
        val t = k.sendText
        return if (t.isEmpty()) "自定义文本" else "发送：$t"
    }
    return VirtKeyDef.actionOptions.firstOrNull { it.first == aid }?.second ?: aid
}

@Composable
private fun KeyEditDialog(
    title: String,
    initial: VirtKeyDef,
    isNew: Boolean,
    onConfirm: (VirtKeyDef) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(initial.label) }
    var payload by remember { mutableStateOf(initial.sendText) }
    var actionId by remember {
        mutableStateOf(
            initial.actionId.let { id ->
                if (VirtKeyDef.actionOptions.any { it.first == id }) id else "send_text"
            }
        )
    }
    var expanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val currentOption = VirtKeyDef.actionOptions.firstOrNull { it.first == actionId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("显示文字") },
                    placeholder = { Text("如：↑、ls、Ctrl") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "${currentOption?.second ?: actionId}（${currentOption?.third ?: "其他"}）",
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        for (o in VirtKeyDef.actionOptions) {
                            DropdownMenuItem(
                                text = { Text("${o.second}（${o.third}）") },
                                onClick = {
                                    actionId = o.first
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                if (actionId == "send_text") {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = payload,
                        onValueChange = { payload = it },
                        label = { Text("发送内容") },
                        placeholder = { Text("如：ls -la 或 path/") },
                        supportingText = { Text("可多字符；需要回车可在末尾加 \\n（设置里直接敲回车无效）") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val text = label.trim()
                when {
                    text.isEmpty() -> error = "请填写显示文字"
                    actionId == "send_text" && payload.isEmpty() -> error = "请填写要发送的文本"
                    else -> {
                        var id = initial.id
                        if (actionId == "ctrl" || actionId == "alt" || actionId == "shift" || actionId == "ime") {
                            id = actionId
                        } else if (isNew && !id.startsWith("custom_")) {
                            id = "custom_${System.currentTimeMillis()}"
                        }
                        onConfirm(VirtKeyDef.fromAction(id, text, actionId, payload))
                    }
                }
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
