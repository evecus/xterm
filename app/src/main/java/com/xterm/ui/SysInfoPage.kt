package com.xterm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsApplications
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xterm.data.BasicInfo
import com.xterm.data.CronEntry
import com.xterm.data.DockerInfo
import com.xterm.data.FirewallInfo
import com.xterm.data.PowerInfo
import com.xterm.data.ProcessInfo
import com.xterm.data.Server
import com.xterm.data.ServiceInfo
import com.xterm.data.UserInfo
import com.xterm.data.formatKb
import com.xterm.ssh.SysInfoSession
import com.xterm.ssh.SysInfoTab
import kotlinx.coroutines.launch

/**
 * 对齐 Aurora `pages/sysinfo_page.dart`：8 个底部 Tab 的结构化系统信息页。
 * 每个 Tab 按需采集（进入才跑远程命令），共用一条 SysInfoSession。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SysInfoPage(vm: MainViewModel, server: Server) {
    val scope = rememberCoroutineScope()
    val session = remember(server.id) { SysInfoSession(server) }
    DisposableEffect(session) { onDispose { session.close() } }

    var tab by remember { mutableStateOf(SysInfoTab.BASIC) }

    val data by session.data.collectAsState()
    val loaded by session.loaded.collectAsState()
    val loading by session.loading.collectAsState()
    val errors by session.errors.collectAsState()
    val connecting by session.connecting.collectAsState()
    val pageError by session.pageError.collectAsState()

    fun fetch(t: SysInfoTab, force: Boolean = false) {
        scope.launch { runCatching { session.fetch(t, force = force) } }
    }

    fun bootstrap() {
        scope.launch { runCatching { session.bootstrap() } }
    }

    LaunchedEffect(server.id) { session.bootstrap() }

    fun onTabSelected(t: SysInfoTab) {
        if (t == tab) return
        tab = t
        if (t !in loaded) fetch(t)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(server.name, fontSize = 16.sp, maxLines = 1)
                        Text(
                            "${server.displayAddr} · ${tab.label}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
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
                        onClick = { fetch(tab, force = true) },
                        enabled = !connecting && tab !in loading,
                    ) { Icon(Icons.Filled.Refresh, contentDescription = "刷新") }
                },
            )
        },
        bottomBar = {
            SysInfoTabBar(selected = tab, onSelect = { onTabSelected(it) })
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                connecting -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                pageError != null -> ErrorPane(
                    message = pageError!!,
                    onRetry = { bootstrap() },
                )

                tab in loading && tab !in loaded ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                errors[tab] != null && tab !in loaded -> ErrorPane(
                    message = errors[tab]!!,
                    onRetry = { fetch(tab, force = true) },
                )

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    if (tab in loading) AuroraLinearProgress(progress = null)
                    when (tab) {
                        SysInfoTab.BASIC -> BasicView(data.basic)
                        SysInfoTab.PROCESS -> ProcessView(data.processes ?: emptyList())
                        SysInfoTab.SERVICE -> ServiceView(
                            data.services ?: emptyList(),
                            data.failedServices ?: emptyList(),
                        )
                        SysInfoTab.FIREWALL -> FirewallView(data.firewall)
                        SysInfoTab.POWER -> PowerView(data.power)
                        SysInfoTab.USERS -> UsersView(data.users ?: emptyList())
                        SysInfoTab.CRON -> CronView(data.cron ?: emptyList())
                        SysInfoTab.DOCKER -> DockerView(data.docker)
                    }
                }
            }
        }
    }
}

private fun tabIcon(t: SysInfoTab): ImageVector = when (t) {
    SysInfoTab.BASIC -> Icons.Filled.Info
    SysInfoTab.PROCESS -> Icons.Filled.Memory
    SysInfoTab.SERVICE -> Icons.Filled.SettingsApplications
    SysInfoTab.FIREWALL -> Icons.Filled.Security
    SysInfoTab.POWER -> Icons.Filled.PowerSettingsNew
    SysInfoTab.USERS -> Icons.Filled.People
    SysInfoTab.CRON -> Icons.Filled.Schedule
    SysInfoTab.DOCKER -> Icons.Filled.ViewInAr
}

/** 对齐 Aurora 底部横向 8 Tab（选中项 secondaryContainer 高亮）。 */
@Composable
private fun SysInfoTabBar(selected: SysInfoTab, onSelect: (SysInfoTab) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(shadowElevation = 3.dp, color = scheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (t in SysInfoTab.entries) {
                val active = t == selected
                val bg = if (active) scheme.secondaryContainer
                else androidx.compose.ui.graphics.Color.Transparent
                val fg = if (active) scheme.onSecondaryContainer else scheme.onSurfaceVariant
                Column(
                    modifier = Modifier
                        .padding(horizontal = 2.dp, vertical = 6.dp)
                        .background(bg, RoundedCornerShape(12.dp))
                        .clickable { onSelect(t) }
                        .padding(horizontal = 12.dp)
                        .height(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(tabIcon(t), contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(
                        t.label,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.W600 else FontWeight.Normal,
                        color = fg,
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 基础
// ═══════════════════════════════════════════════════════════

@Composable
private fun BasicView(info: BasicInfo?) {
    if (info == null) {
        EmptyHint("暂无数据")
        return
    }
    val scheme = MaterialTheme.colorScheme
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        item {
            SectionCard(title = "主机", icon = Icons.Filled.Dns) {
                Kv("主机名", info.hostname)
                Kv("系统", info.osPretty)
                Kv("内核", info.kernel)
                Kv("运行", info.uptimeText)
            }
        }
        if (info.load1 != null || info.cpuModel.isNotEmpty()) {
            item {
                SectionCard(title = "CPU / 负载", icon = Icons.Filled.DeveloperBoard) {
                    if (info.cpuModel.isNotEmpty()) Kv("型号", info.cpuModel)
                    if (info.cpuCores != null) Kv("核心", info.cpuCores.toString())
                    if (info.load1 != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            ChipStat("1 分钟", String.format("%.2f", info.load1))
                            ChipStat("5 分钟", String.format("%.2f", info.load5 ?: 0.0))
                            ChipStat("15 分钟", String.format("%.2f", info.load15 ?: 0.0))
                        }
                    }
                }
            }
        }
        info.mem?.let { mem ->
            item {
                SectionCard(title = "内存", icon = Icons.Filled.Memory) {
                    UsageBar(mem.usedRatio, "内存", "${mem.usedLabel} / ${mem.totalLabel}")
                    val swapT = mem.swapTotalKb
                    if (swapT != null && swapT > 0) {
                        Spacer(Modifier.height(12.dp))
                        val used = mem.swapUsedKb ?: 0L
                        UsageBar(
                            (used.toFloat() / swapT).coerceIn(0f, 1f),
                            "Swap",
                            "${formatKb(used)} / ${formatKb(swapT)}",
                        )
                    }
                }
            }
        }
        if (info.disks.isNotEmpty()) {
            item {
                SectionCard(title = "磁盘", icon = Icons.Filled.Storage) {
                    info.disks.forEachIndexed { i, d ->
                        if (i > 0) Spacer(Modifier.height(12.dp))
                        UsageBar(
                            (d.usedPercent ?: 0) / 100f,
                            d.mount,
                            "${d.used} / ${d.size} · ${d.filesystem}",
                        )
                    }
                }
            }
        }
        if (info.ifaces.isNotEmpty()) {
            item {
                SectionCard(title = "网络", icon = Icons.Filled.Lan) {
                    for (n in info.ifaces) {
                        ListItem(
                            leadingContent = {
                                Icon(
                                    Icons.Filled.Cable,
                                    contentDescription = null,
                                    tint = if (n.state.contains("UP", true))
                                        androidx.compose.ui.graphics.Color(0xFF4CAF50)
                                    else scheme.outline,
                                )
                            },
                            headlineContent = {
                                Text(n.name, fontSize = 13.sp, fontWeight = FontWeight.W600)
                            },
                            supportingContent = {
                                Text(
                                    listOf(n.state, n.addrs).filter { it.isNotEmpty() }.joinToString(" · "),
                                    fontSize = 12.sp,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 进程
// ═══════════════════════════════════════════════════════════

@Composable
private fun ProcessView(list: List<ProcessInfo>) {
    if (list.isEmpty()) {
        EmptyHint("无进程数据")
        return
    }
    val scheme = MaterialTheme.colorScheme
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(list.size, key = { list[it].pid + it }) { i ->
            val p = list[i]
            ListItem(
                leadingContent = {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(scheme.primaryContainer, RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (p.pid.length > 4) p.pid.take(4) else p.pid,
                            fontSize = 10.sp,
                            color = scheme.onPrimaryContainer,
                        )
                    }
                },
                headlineContent = {
                    Text(
                        p.command.ifEmpty { "(unknown)" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.W500,
                    )
                },
                supportingContent = {
                    Text(
                        listOf(p.user, "PID ${p.pid}").filter { it.isNotEmpty() }.joinToString(" · "),
                        fontSize = 12.sp,
                        color = scheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        p.cpu?.let { Text("CPU ${String.format("%.1f", it)}%", fontSize = 12.sp) }
                        p.mem?.let {
                            Text("MEM ${String.format("%.1f", it)}%", fontSize = 11.sp, color = scheme.onSurfaceVariant)
                        }
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 服务
// ═══════════════════════════════════════════════════════════

@Composable
private fun ServiceView(running: List<ServiceInfo>, failed: List<ServiceInfo>) {
    if (running.isEmpty() && failed.isEmpty()) {
        EmptyHint("无服务数据")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        if (failed.isNotEmpty()) {
            item {
                SectionCard(title = "失败 (${failed.size})", icon = Icons.Filled.ErrorOutline) {
                    for (s in failed) ServiceTile(s, failed = true)
                }
            }
        }
        item {
            SectionCard(
                title = if (running.any { it.active == "init.d" }) "init.d / 服务 (${running.size})"
                else "运行中 (${running.size})",
                icon = Icons.Filled.CheckCircle,
            ) {
                for (s in running) ServiceTile(s, failed = false)
            }
        }
    }
}

@Composable
private fun ServiceTile(s: ServiceInfo, failed: Boolean) {
    val scheme = MaterialTheme.colorScheme
    ListItem(
        leadingContent = { StatusDot(ok = !failed && s.isRunning) },
        headlineContent = { Text(s.name, fontWeight = FontWeight.W600, fontSize = 13.sp) },
        supportingContent = {
            Text(
                listOf(s.sub, s.description).filter { it.isNotEmpty() }.joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                color = scheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            if (failed) {
                Text("failed", fontSize = 11.sp, color = scheme.error)
            } else if (s.active.isNotEmpty()) {
                Text(s.active, fontSize = 12.sp, color = scheme.onSurfaceVariant)
            }
        },
    )
}

// ═══════════════════════════════════════════════════════════
// 防火墙
// ═══════════════════════════════════════════════════════════

@Composable
private fun FirewallView(info: FirewallInfo?) {
    if (info == null) {
        EmptyHint("暂无数据")
        return
    }
    if (info.highlights.isEmpty()) {
        EmptyHint(info.summary)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        item {
            SectionCard(title = "概览", icon = Icons.Filled.Security) { Text(info.summary, fontSize = 13.sp) }
        }
        items(info.highlights.size, key = { info.highlights[it] }) { i ->
            val block = info.highlights[i]
            SectionCard(
                title = block.lineSequence().first(),
                icon = Icons.Filled.Rule,
            ) {
                Text(
                    if (block.contains('\n')) block.substringAfter('\n') else block,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 电源
// ═══════════════════════════════════════════════════════════

@Composable
private fun PowerView(info: PowerInfo?) {
    if (info == null) {
        EmptyHint("暂无数据")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        item {
            SectionCard(title = "运行状态", icon = Icons.Filled.Timelapse) {
                Kv("运行时间", info.uptimeText)
                if (info.bootTime.isNotEmpty()) Kv("启动", info.bootTime)
            }
        }
        if (info.batteries.isNotEmpty()) {
            item {
                SectionCard(title = "电源 / 电池", icon = Icons.Filled.BatteryChargingFull) {
                    for (b in info.batteries) {
                        Kv(b.name, "${b.type} · ${b.status}")
                        b.capacity?.let { cap ->
                            Spacer(Modifier.height(6.dp))
                            UsageBar(cap / 100f, "电量", "$cap%")
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }
        }
        if (info.temps.isNotEmpty()) {
            item {
                SectionCard(title = "温度", icon = Icons.Filled.Thermostat) {
                    for (t in info.temps) Kv("传感器", t)
                }
            }
        }
        if (info.recentEvents.isNotEmpty()) {
            item {
                SectionCard(title = "最近事件", icon = Icons.Filled.History) {
                    for (e in info.recentEvents) {
                        Text(e, fontSize = 12.sp, modifier = Modifier.padding(vertical = 3.dp))
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 用户
// ═══════════════════════════════════════════════════════════

@Composable
private fun UsersView(list: List<UserInfo>) {
    if (list.isEmpty()) {
        EmptyHint("无用户数据")
        return
    }
    val scheme = MaterialTheme.colorScheme
    val loginShell = list.filter {
        it.shell.contains("sh") && !it.shell.contains("nologin") && !it.shell.contains("false")
    }
    val show = loginShell.ifEmpty { list }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(show.size, key = { show[it].name }) { i ->
            val u = show[i]
            ListItem(
                leadingContent = {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                if (u.loggedIn) scheme.primaryContainer else scheme.surfaceVariant,
                                RoundedCornerShape(20.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            tint = if (u.loggedIn) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                        )
                    }
                },
                headlineContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(u.name, fontWeight = FontWeight.W600, fontSize = 13.sp)
                        if (u.loggedIn) {
                            Spacer(Modifier.width(8.dp))
                            StatusDot(ok = true, label = "在线")
                        }
                    }
                },
                supportingContent = {
                    Text(
                        "uid ${u.uid} · ${u.home}\n${u.shell}",
                        fontSize = 12.sp,
                        color = scheme.onSurfaceVariant,
                    )
                },
            )
            HorizontalDivider()
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 计划任务
// ═══════════════════════════════════════════════════════════

@Composable
private fun CronView(list: List<CronEntry>) {
    if (list.isEmpty()) {
        EmptyHint("未发现计划任务")
        return
    }
    val scheme = MaterialTheme.colorScheme
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        items(list.size, key = { list[it].command + it }) { i ->
            val c = list[i]
            Card(
                colors = CardDefaults.cardColors(containerColor = auroraCardColor()),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                ListItem(
                    leadingContent = {
                        Icon(Icons.Filled.Alarm, contentDescription = null, tint = scheme.primary)
                    },
                    headlineContent = {
                        Text(c.command, fontSize = 13.sp, fontWeight = FontWeight.W500)
                    },
                    supportingContent = {
                        Text(
                            listOf(c.schedule, c.source).filter { it.isNotEmpty() }.joinToString(" · "),
                            fontSize = 12.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// Docker
// ═══════════════════════════════════════════════════════════

@Composable
private fun DockerView(info: DockerInfo?) {
    if (info == null) {
        EmptyHint("暂无数据")
        return
    }
    if (!info.available) {
        EmptyHint(info.errorHint ?: "Docker 不可用")
        return
    }
    val scheme = MaterialTheme.colorScheme
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        item {
            SectionCard(title = "Docker", icon = Icons.Filled.ViewInAr) {
                Text(info.versionLine, fontSize = 13.sp)
            }
        }
        item {
            SectionCard(title = "容器 (${info.containers.size})", icon = Icons.Filled.Widgets) {
                if (info.containers.isEmpty()) {
                    Text("无容器", fontSize = 13.sp)
                } else {
                    for (c in info.containers) {
                        ListItem(
                            leadingContent = { StatusDot(ok = c.isUp) },
                            headlineContent = {
                                Text(
                                    c.names.ifEmpty { c.id },
                                    fontWeight = FontWeight.W600,
                                    fontSize = 13.sp,
                                )
                            },
                            supportingContent = {
                                Text(
                                    listOf(c.image, c.status, c.ports).filter { it.isNotEmpty() }
                                        .joinToString("\n"),
                                    fontSize = 12.sp,
                                    color = scheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }
        }
        if (info.images.isNotEmpty()) {
            item {
                SectionCard(title = "镜像 (${info.images.size})", icon = Icons.Filled.Layers) {
                    for (img in info.images) {
                        ListItem(
                            headlineContent = {
                                Text("${img.repository}:${img.tag}", fontSize = 13.sp, fontWeight = FontWeight.W500)
                            },
                            supportingContent = {
                                Text("${img.id} · ${img.size}", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(24.dp),
        )
    }
}
