package com.xterm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.xterm.data.Server
import java.util.UUID

/**
 * 对齐 Aurora `pages/server_edit_page.dart`：字段、校验、密码/私钥分段切换、
 * 编辑「保存修改」、新建「连接（不保存）+ 添加服务器」。
 */
@Composable
fun ServerEditPage(vm: MainViewModel, editId: String?) {
    val initial = remember(editId) { editId?.let { vm.serverById(it) } }
    val isEdit = initial != null

    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var host by remember(initial) { mutableStateOf(initial?.host ?: "") }
    var port by remember(initial) { mutableStateOf((initial?.port ?: 22).toString()) }
    var username by remember(initial) { mutableStateOf(initial?.username ?: "root") }
    var useKey by remember(initial) { mutableStateOf(initial?.useKey ?: false) }
    var password by remember(initial) { mutableStateOf(initial?.password ?: "") }
    var privateKey by remember(initial) { mutableStateOf(initial?.privateKey ?: "") }
    var passphrase by remember(initial) { mutableStateOf(initial?.passphrase ?: "") }
    var obscure by remember { mutableStateOf(true) }
    var hostError by remember { mutableStateOf(false) }
    var userError by remember { mutableStateOf(false) }
    var keyError by remember { mutableStateOf(false) }

    fun validate(): Boolean {
        hostError = host.trim().isEmpty()
        userError = username.trim().isEmpty()
        keyError = useKey && privateKey.trim().isEmpty()
        return !hostError && !userError && !keyError
    }

    fun buildServer(id: String = initial?.id ?: UUID.randomUUID().toString()): Server = Server(
        id = id,
        name = name.trim().ifEmpty { host.trim() },
        host = host.trim(),
        port = port.trim().toIntOrNull() ?: 22,
        username = username.trim(),
        password = if (useKey) "" else password,
        privateKey = if (useKey) privateKey.trim() else "",
        passphrase = if (useKey && passphrase.isNotEmpty()) passphrase else "",
        term = initial?.term ?: "xterm-256color",
        startupCommand = initial?.startupCommand ?: "",
        bracketedPaste = initial?.bracketedPaste ?: true,
    )

    Scaffold(
        topBar = {
            AuroraTopAppBar(
                title = if (isEdit) "编辑" else "添加",
                onBack = { vm.pop() },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称（可选）") },
                placeholder = { Text("默认使用主机名") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Next),
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it; hostError = false },
                label = { Text("主机 *") },
                placeholder = { Text("IP 或域名") },
                isError = hostError,
                supportingText = if (hostError) { { Text("请输入主机") } } else null,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("端口") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; userError = false },
                label = { Text("用户名 *") },
                isError = userError,
                supportingText = if (userError) { { Text("请输入用户名") } } else null,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))

            // 分段按钮：密码 / 私钥（Aurora SegmentedButton 的等价实现）
            Row(modifier = Modifier.fillMaxWidth()) {
                val selectedColor = MaterialTheme.colorScheme.secondaryContainer
                val unselectedColor = MaterialTheme.colorScheme.surface
                Button(
                    onClick = { useKey = false },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!useKey) selectedColor else unselectedColor,
                        contentColor = if (!useKey) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.height(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("密码")
                }
                Button(
                    onClick = { useKey = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (useKey) selectedColor else unselectedColor,
                        contentColor = if (useKey) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Icon(Icons.Filled.VpnKey, contentDescription = null, modifier = Modifier.height(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("私钥")
                }
            }
            Spacer(Modifier.height(4.dp))

            if (!useKey) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (obscure) PasswordVisualTransformation() else VisualTransformation.None,
                    trailingIcon = {
                        IconButton(onClick = { obscure = !obscure }) {
                            Icon(
                                if (obscure) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = "切换密码可见",
                            )
                        }
                    },
                )
            } else {
                OutlinedTextField(
                    value = privateKey,
                    onValueChange = { privateKey = it; keyError = false },
                    label = { Text("私钥 (PEM)") },
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----\n...") },
                    isError = keyError,
                    supportingText = if (keyError) { { Text("请粘贴私钥") } } else null,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("私钥密码（如有）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
            Spacer(Modifier.height(20.dp))

            if (isEdit) {
                Button(
                    onClick = {
                        if (!validate()) return@Button
                        vm.serverStore.update(buildServer())
                        vm.showMessage("已保存")
                        vm.pop()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("保存修改")
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            if (!validate()) return@OutlinedButton
                            vm.openTerminal(buildServer())
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.height(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("连接")
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = {
                            if (!validate()) return@Button
                            vm.serverStore.add(buildServer())
                            vm.showMessage("已保存")
                            vm.pop()
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.height(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("添加服务器")
                    }
                }
            }
        }
    }
}
