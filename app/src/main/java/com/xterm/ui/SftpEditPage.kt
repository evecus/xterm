package com.xterm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 对齐 Aurora `pages/sftp_edit_page.dart`：远程文本文件编辑（拉取 → 编辑 → 写回）。
 *
 * - 2 MB 上限、二进制粗检（前 512 字节 NUL 数 > 2）；
 * - dirty 状态、离开确认、保存中/失败提示；
 * - 等宽字体无边框多行输入框。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpEditPage(vm: MainViewModel, fileName: String) {
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var binary by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    suspend fun load() {
        loading = true
        error = null
        binary = false
        val session = vm.sftpSession
        if (session == null) {
            loading = false
            error = "文件会话已关闭"
            return
        }
        try {
            val data = session.readFile(fileName)
            if (data.size > MAX_EDIT_BYTES) {
                loading = false
                error = "文件过大（>${MAX_EDIT_BYTES / (1024 * 1024)} MB），不支持在线编辑"
                return
            }
            if (looksBinary(data)) {
                loading = false
                binary = true
                error = "该文件可能是二进制，无法用文本编辑器打开"
                return
            }
            text = String(data, Charsets.UTF_8)   // 等价 utf8.decode(allowMalformed: true)
            dirty = false
            loading = false
        } catch (e: Exception) {
            loading = false
            error = e.toString()
        }
    }

    LaunchedEffect(fileName) { load() }

    fun save() {
        val session = vm.sftpSession ?: return
        if (saving || binary) return
        scope.launch {
            saving = true
            try {
                session.writeFile(fileName, text.toByteArray(Charsets.UTF_8))
                dirty = false
                vm.showMessage("已保存")
            } catch (e: Exception) {
                vm.showMessage("保存失败: $e")
            } finally {
                saving = false
            }
        }
    }

    fun requestExit() {
        if (dirty) confirmLeave = true else vm.pop()
    }

    BackHandler(enabled = true) { requestExit() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName, fontSize = 16.sp, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { requestExit() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (dirty) {
                        Text(
                            "已修改",
                            fontSize = 12.sp,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    IconButton(
                        onClick = { save() },
                        enabled = !loading && !binary && error == null && !saving,
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                            )
                        } else {
                            Icon(Icons.Filled.Save, contentDescription = "保存")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                error != null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(error!!, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        if (!binary) {
                            Button(onClick = { scope.launch { load() } }) { Text("重试") }
                        }
                    }
                }

                else -> {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it; dirty = true },
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            }
        }
    }

    if (confirmLeave) {
        ConfirmDialog(
            title = "未保存的更改",
            message = "离开将丢弃修改，确定吗？",
            confirmText = "离开",
            onConfirm = {
                confirmLeave = false
                vm.pop()
            },
            onDismiss = { confirmLeave = false },
        )
    }
}

private const val MAX_EDIT_BYTES = 2 * 1024 * 1024

/** 前 512 字节里 NUL 多于 2 个视为二进制（对齐 Aurora _looksBinary）。 */
private fun looksBinary(data: ByteArray): Boolean {
    val n = minOf(data.size, 512)
    var nulls = 0
    for (i in 0 until n) {
        if (data[i] == 0.toByte()) nulls++
    }
    return nulls > 2
}
