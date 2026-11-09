package com.xterm.ui

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xterm.data.RemoteFileEntry
import com.xterm.data.Server
import com.xterm.data.formatBytes
import com.xterm.ssh.SftpSession
import com.xterm.ssh.SftpState
import com.xterm.ssh.TransferMode
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 对齐 Aurora `pages/sftp_page.dart`：SFTP 文件列表页。
 *
 * - 真 SFTP，服务器无 sftp-server 时自动回退命令模式（标题追加「· 兼容模式」）；
 * - 路径栏、上级目录、刷新、上传（系统文件选择器多选）、新建文件夹；
 * - 点击目录进入、点击文件进入编辑；长按或点 more 弹出操作 sheet；
 * - 下载、重命名、删除，编辑页返回后刷新列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpPage(vm: MainViewModel, server: Server) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stack by vm.backStack.collectAsState()

    // 列表页与编辑页共享同一个会话（对齐 Aurora 把 session 传给 SftpEditPage）。
    val session: SftpSession = remember(server.id) {
        vm.sftpSession ?: SftpSession(server).also { vm.sftpSession = it }
    }

    val state by session.state.collectAsState()
    val mode by session.mode.collectAsState()
    val entries by session.entries.collectAsState()
    val loading by session.loading.collectAsState()
    val path by session.currentPath.collectAsState()
    val error by session.error.collectAsState()

    var uploading by remember { mutableStateOf(false) }
    var pendingInput by remember { mutableStateOf<PendingInput?>(null) }
    var pendingDelete by remember { mutableStateOf<RemoteFileEntry?>(null) }
    var sheetEntry by remember { mutableStateOf<RemoteFileEntry?>(null) }

    // 首次进入建立连接。
    LaunchedEffect(server.id) {
        if (state == SftpState.IDLE || state == SftpState.CLOSED) {
            runCatching { session.connect() }
        }
    }
    // 从编辑页返回列表时刷新（Aurora 的 _edit 返回后 refresh）。
    LaunchedEffect(stack.lastOrNull()) {
        if (stack.lastOrNull() is Screen.Sftp && state == SftpState.READY) {
            runCatching { session.refresh() }
        }
    }

    fun runAction(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                vm.showMessage(e.message ?: e.toString())
            }
        }
    }

    fun download(entry: RemoteFileEntry) {
        scope.launch {
            try {
                val data = session.readFile(entry.filename)
                val dir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    "aurora_downloads",
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, entry.filename)
                file.writeBytes(data)
                vm.showMessage("已保存到 ${file.absolutePath}")
            } catch (e: Exception) {
                vm.showMessage("下载失败: $e")
            }
        }
    }

    // 系统文件选择器（SAF），对齐 Aurora FilePicker.pickFiles(allowMultiple: true)。
    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uploading = true
        scope.launch {
            var ok = 0
            var fail = 0
            for (uri in uris) {
                val name = queryDisplayName(context, uri) ?: "upload_${System.currentTimeMillis()}"
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
                if (bytes == null) {
                    fail++
                    continue
                }
                if (runCatching { session.writeFile(name, bytes) }.isSuccess) ok++ else fail++
            }
            uploading = false
            vm.showMessage(if (fail == 0) "已上传 $ok 个文件" else "成功 $ok，失败 $fail")
            runCatching { session.refresh() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(server.name, fontSize = 16.sp, maxLines = 1)
                        Text(
                            text = if (mode == TransferMode.COMMAND) "$path  ·  兼容模式" else path,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { vm.pop() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { runAction { session.cd("..") } },
                        enabled = state == SftpState.READY && path != "/",
                    ) { Icon(Icons.Filled.ArrowUpward, contentDescription = "上级目录") }
                    IconButton(
                        onClick = { runAction { session.refresh() } },
                        enabled = state == SftpState.READY,
                    ) { Icon(Icons.Filled.Refresh, contentDescription = "刷新") }
                    IconButton(
                        onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                        enabled = state == SftpState.READY && !uploading,
                    ) {
                        if (uploading) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                            )
                        } else {
                            Icon(Icons.Filled.UploadFile, contentDescription = "上传文件")
                        }
                    }
                    IconButton(
                        onClick = { pendingInput = PendingInput.Mkdir },
                        enabled = state == SftpState.READY,
                    ) { Icon(Icons.Filled.CreateNewFolder, contentDescription = "新建文件夹") }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state == SftpState.CONNECTING -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                state == SftpState.FAILED -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ErrorPane(
                            message = error ?: "文件服务连接失败",
                            onRetry = { runAction { session.connect() } },
                        )
                    }
                }

                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (loading || uploading) AuroraLinearProgress(progress = null)
                        PathBar(path = path, onSegment = { p -> runAction { session.list(p) } })
                        val visible = entries.filter { !it.isDotDot }
                        if (visible.isEmpty() && !loading && path == "/") {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("空目录", color = MaterialTheme.colorScheme.outline)
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                if (path != "/") {
                                    item(key = "__parent__") {
                                        ListItem(
                                            leadingContent = {
                                                Icon(
                                                    Icons.Filled.Folder,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                            },
                                            headlineContent = { Text("..") },
                                            supportingContent = { Text("返回上级") },
                                            modifier = Modifier.clickable { runAction { session.cd("..") } },
                                        )
                                        HorizontalDivider()
                                    }
                                }
                                items(visible.size, key = { visible[it].filename }) { i ->
                                    val entry = visible[i]
                                    ListItem(
                                        leadingContent = {
                                            Icon(
                                                if (entry.isDirectory) Icons.Filled.Folder
                                                else Icons.Filled.InsertDriveFile,
                                                contentDescription = null,
                                                tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        headlineContent = { Text(entry.filename, maxLines = 1) },
                                        supportingContent = { Text(entrySubtitle(entry), fontSize = 12.sp) },
                                        trailingContent = {
                                            IconButton(onClick = { sheetEntry = entry }) {
                                                Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                                            }
                                        },
                                        modifier = Modifier.clickable {
                                            when {
                                                entry.isDirectory -> runAction { session.cd(entry.filename) }
                                                else -> vm.navigate(Screen.SftpEdit(entry.filename))
                                            }
                                        },
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 新建文件夹 / 重命名 ────────────────────────────────────────────────
    when (val input = pendingInput) {
        is PendingInput.Mkdir -> InputDialog(
            title = "新建文件夹",
            label = "名称",
            confirmText = "创建",
            onConfirm = { name ->
                pendingInput = null
                if (name.isNotEmpty()) runAction { session.mkdir(name) }
            },
            onDismiss = { pendingInput = null },
        )

        is PendingInput.Rename -> InputDialog(
            title = "重命名",
            label = "新名称",
            initial = input.entry.filename,
            onConfirm = { name ->
                pendingInput = null
                if (name.isNotEmpty() && name != input.entry.filename) {
                    runAction { session.rename(input.entry.filename, name) }
                }
            },
            onDismiss = { pendingInput = null },
        )

        null -> Unit
    }

    if (pendingDelete != null) {
        val target = pendingDelete!!
        ConfirmDialog(
            title = "删除",
            message = "确定删除「${target.filename}」？",
            confirmText = "删除",
            onConfirm = {
                pendingDelete = null
                runAction { session.remove(target) }
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (sheetEntry != null) {
        val entry = sheetEntry!!
        ModalBottomSheet(onDismissRequest = { sheetEntry = null }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                ListItem(
                    leadingContent = {
                        Icon(
                            if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    headlineContent = { Text(entry.filename) },
                    supportingContent = { Text(entrySubtitle(entry)) },
                )
                HorizontalDivider()
                if (!entry.isDirectory) {
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        headlineContent = { Text("编辑") },
                        modifier = Modifier.clickable {
                            sheetEntry = null
                            vm.navigate(Screen.SftpEdit(entry.filename))
                        },
                    )
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Download, contentDescription = null) },
                        headlineContent = { Text("下载到本机") },
                        modifier = Modifier.clickable {
                            sheetEntry = null
                            download(entry)
                        },
                    )
                }
                ListItem(
                    leadingContent = { Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null) },
                    headlineContent = { Text("重命名") },
                    modifier = Modifier.clickable {
                        sheetEntry = null
                        pendingInput = PendingInput.Rename(entry)
                    },
                )
                ListItem(
                    leadingContent = {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    headlineContent = {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.clickable {
                        sheetEntry = null
                        pendingDelete = entry
                    },
                )
            }
        }
    }
}

private sealed interface PendingInput {
    data object Mkdir : PendingInput
    data class Rename(val entry: RemoteFileEntry) : PendingInput
}

/** 对齐 Aurora `_entrySubtitle`：文件夹 / 大小 · 修改时间。 */
private fun entrySubtitle(entry: RemoteFileEntry): String {
    val parts = mutableListOf<String>()
    parts.add(if (entry.isDirectory) "文件夹" else formatBytes(entry.size))
    val m = entry.modifyTime
    if (m != null) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        parts.add(sdf.format(Date(m * 1000)))
    }
    return parts.joinToString(" · ")
}

/** 对齐 Aurora `_PathBar`：根目录 chip + 各级目录 chip，点击回到对应层级。 */
@Composable
private fun PathBar(path: String, onSegment: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = auroraBarColor(), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            AssistChip(
                onClick = { onSegment("/") },
                label = { Text("/") },
                leadingIcon = {
                    Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(16.dp))
                },
            )
            val parts = path.split("/").filter { it.isNotEmpty() }
            parts.forEachIndexed { i, part ->
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = scheme.outline,
                    modifier = Modifier.size(16.dp),
                )
                AssistChip(
                    onClick = { onSegment("/" + parts.subList(0, i + 1).joinToString("/")) },
                    label = { Text(part) },
                )
            }
        }
    }
}

/** 从 SAF Uri 取显示文件名（对齐 FilePicker 的 f.name）。 */
private fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
