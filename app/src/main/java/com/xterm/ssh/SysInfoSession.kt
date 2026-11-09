package com.xterm.ssh

import com.trilead.ssh2.Connection
import com.xterm.data.BasicInfo
import com.xterm.data.BatteryInfo
import com.xterm.data.CronEntry
import com.xterm.data.DiskInfo
import com.xterm.data.DockerContainer
import com.xterm.data.DockerImage
import com.xterm.data.DockerInfo
import com.xterm.data.FirewallInfo
import com.xterm.data.MemInfo
import com.xterm.data.NetIface
import com.xterm.data.PowerInfo
import com.xterm.data.ProcessInfo
import com.xterm.data.Server
import com.xterm.data.ServiceInfo
import com.xterm.data.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class SysInfoTab(val label: String) {
    BASIC("基础"),
    PROCESS("进程"),
    SERVICE("服务"),
    FIREWALL("防火墙"),
    POWER("电源"),
    USERS("用户"),
    CRON("计划"),
    DOCKER("Docker"),
}

data class SysInfoData(
    val basic: BasicInfo? = null,
    val processes: List<ProcessInfo>? = null,
    val services: List<ServiceInfo>? = null,
    val failedServices: List<ServiceInfo>? = null,
    val firewall: FirewallInfo? = null,
    val power: PowerInfo? = null,
    val users: List<UserInfo>? = null,
    val cron: List<CronEntry>? = null,
    val docker: DockerInfo? = null,
)

/**
 * 对齐 Aurora `services/sysinfo_session.dart`：按需采集（进入 Tab 才跑命令），
 * 用 `@@KEY@@` 分段解析为结构化模型。
 */
class SysInfoSession(private val server: Server) {

    private var conn: Connection? = null

    private val _data = MutableStateFlow(SysInfoData())
    val data: StateFlow<SysInfoData> = _data.asStateFlow()

    private val _loaded = MutableStateFlow<Set<SysInfoTab>>(emptySet())
    val loaded: StateFlow<Set<SysInfoTab>> = _loaded.asStateFlow()

    private val _loading = MutableStateFlow<Set<SysInfoTab>>(emptySet())
    val loading: StateFlow<Set<SysInfoTab>> = _loading.asStateFlow()

    private val _errors = MutableStateFlow<Map<SysInfoTab, String?>>(emptyMap())
    val errors: StateFlow<Map<SysInfoTab, String?>> = _errors.asStateFlow()

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    private val _pageError = MutableStateFlow<String?>(null)
    val pageError: StateFlow<String?> = _pageError.asStateFlow()

    suspend fun connect() = withContext(Dispatchers.IO) {
        if (conn != null) return@withContext
        try {
            conn = SshConnector.connect(server)
            _pageError.value = null
        } catch (e: Exception) {
            close()
            _pageError.value = e.message ?: e.toString()
            throw e
        }
    }

    suspend fun bootstrap() {
        _connecting.value = true
        _pageError.value = null
        try {
            connect()
            _connecting.value = false
            fetch(SysInfoTab.BASIC, force = true)
        } catch (e: Exception) {
            _connecting.value = false
            _pageError.value = e.message ?: e.toString()
        }
    }

    suspend fun fetch(tab: SysInfoTab, force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!force && tab in _loaded.value && _errors.value[tab] == null) return@withContext
        if (conn == null) {
            connect()
        }
        _loading.value = _loading.value + tab
        _errors.value = _errors.value + (tab to null)
        try {
            val connection = conn ?: return@withContext
            when (tab) {
                SysInfoTab.BASIC -> _data.value = _data.value.copy(basic = fetchBasic(connection))
                SysInfoTab.PROCESS -> _data.value = _data.value.copy(processes = fetchProcesses(connection))
                SysInfoTab.SERVICE -> {
                    val (running, failed) = fetchServices(connection)
                    _data.value = _data.value.copy(services = running, failedServices = failed)
                }
                SysInfoTab.FIREWALL -> _data.value = _data.value.copy(firewall = fetchFirewall(connection))
                SysInfoTab.POWER -> _data.value = _data.value.copy(power = fetchPower(connection))
                SysInfoTab.USERS -> _data.value = _data.value.copy(users = fetchUsers(connection))
                SysInfoTab.CRON -> _data.value = _data.value.copy(cron = fetchCron(connection))
                SysInfoTab.DOCKER -> _data.value = _data.value.copy(docker = fetchDocker(connection))
            }
            _loaded.value = _loaded.value + tab
        } catch (e: Exception) {
            _errors.value = _errors.value + (tab to (e.message ?: e.toString()))
            throw e
        } finally {
            _loading.value = _loading.value - tab
        }
    }

    private fun run(connection: Connection, cmd: String): String = SshConnector.runCommand(connection, cmd)

    // ── 基础 ────────────────────────────────────────────────────────────────
    private val basicScript = """
printf '@@HOST@@\n'
hostname 2>/dev/null
printf '@@KERNEL@@\n'
uname -r 2>/dev/null
uname -m 2>/dev/null
printf '@@OS@@\n'
(grep -E '^(PRETTY_NAME|NAME)=' /etc/os-release 2>/dev/null; cat /etc/openwrt_release 2>/dev/null | head -5)
printf '@@UPTIME@@\n'
uptime 2>/dev/null
printf '@@LOAD@@\n'
cat /proc/loadavg 2>/dev/null
printf '@@CPU@@\n'
grep -m1 -E 'model name|Hardware|cpu model' /proc/cpuinfo 2>/dev/null
nproc 2>/dev/null
printf '@@MEM@@\n'
cat /proc/meminfo 2>/dev/null
printf '@@DF@@\n'
df -hP 2>/dev/null || df -h 2>/dev/null
printf '@@NET@@\n'
ip -br addr 2>/dev/null || true
""".trimIndent()

    private fun fetchBasic(connection: Connection): BasicInfo {
        val sec = sections(run(connection, basicScript))
        val host = sec["HOST"].orEmpty().trim().lines().firstOrNull().orEmpty()
        val kernel = sec["KERNEL"].orEmpty().trim().lines().filter { it.isNotEmpty() }.joinToString(" · ")
        val osRaw = sec["OS"].orEmpty()
        val pretty = Regex("""PRETTY_NAME="?([^"\n]+)"?""").find(osRaw)?.groupValues?.get(1)?.trim()
        val name = Regex("""^NAME="?([^"\n]+)"?""", RegexOption.MULTILINE).find(osRaw)?.groupValues?.get(1)?.trim()
        val osPretty = pretty ?: name ?: osRaw.trim().lines().firstOrNull().orEmpty()
        val uptimeText = sec["UPTIME"].orEmpty().trim()
        val loadParts = sec["LOAD"].orEmpty().trim().split(Regex("\\s+"))
        val l1 = loadParts.getOrNull(0)?.toDoubleOrNull()
        val l5 = loadParts.getOrNull(1)?.toDoubleOrNull()
        val l15 = loadParts.getOrNull(2)?.toDoubleOrNull()
        var cpuModel = ""
        var cores: Int? = null
        for (line in sec["CPU"].orEmpty().trim().lines().filter { it.isNotEmpty() }) {
            val m = Regex(""":\s*(.+)""").find(line)
            if (m != null) cpuModel = m.groupValues[1].trim()
            else line.trim().toIntOrNull()?.let { cores = it }
        }
        return BasicInfo(
            hostname = host,
            kernel = kernel,
            osPretty = osPretty,
            uptimeText = uptimeText,
            load1 = l1,
            load5 = l5,
            load15 = l15,
            cpuModel = cpuModel,
            cpuCores = cores,
            mem = parseMeminfo(sec["MEM"].orEmpty()),
            disks = parseDf(sec["DF"].orEmpty()),
            ifaces = parseIpBr(sec["NET"].orEmpty()),
        )
    }

    private fun parseMeminfo(raw: String): MemInfo? {
        var total: Long? = null
        var free = 0L
        var avail: Long? = null
        var buffers = 0L
        var cached = 0L
        var swapT: Long? = null
        var swapF: Long? = null
        for (line in raw.lines()) {
            val p = line.trim().split(Regex("\\s+"))
            if (p.size < 2) continue
            val v = p[1].toLongOrNull() ?: continue
            when (p[0]) {
                "MemTotal:" -> total = v
                "MemFree:" -> free = v
                "MemAvailable:" -> avail = v
                "Buffers:" -> buffers = v
                "Cached:" -> cached = v
                "SwapTotal:" -> swapT = v
                "SwapFree:" -> swapF = v
            }
        }
        val t = total ?: return null
        if (t <= 0) return null
        val used = (t - free - buffers - cached).coerceIn(0, t)
        return MemInfo(
            totalKb = t,
            usedKb = used,
            freeKb = free,
            availableKb = avail,
            swapTotalKb = swapT,
            swapUsedKb = if (swapT != null && swapF != null) swapT!! - swapF!! else null,
        )
    }

    private fun parseDf(raw: String): List<DiskInfo> {
        val list = mutableListOf<DiskInfo>()
        for (line in raw.lines()) {
            if (line.startsWith("Filesystem") || line.trim().isEmpty()) continue
            val p = line.trim().split(Regex("\\s+"))
            if (p.size < 6) continue
            val fs = p[0]
            if (fs.startsWith("tmpfs") || fs.startsWith("devtmpfs") || fs.startsWith("overlay") ||
                fs.contains("docker") || fs.startsWith("udev")
            ) continue
            val mount = p.subList(5, p.size).joinToString(" ")
            if (mount.startsWith("/snap") || mount.startsWith("/run")) continue
            list.add(
                DiskInfo(
                    filesystem = fs,
                    size = p[1],
                    used = p[2],
                    avail = p[3],
                    usedPercent = p[4].replace("%", "").toIntOrNull(),
                    mount = mount,
                )
            )
        }
        return list
    }

    private fun parseIpBr(raw: String): List<NetIface> {
        val list = mutableListOf<NetIface>()
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val p = t.split(Regex("\\s+"))
            if (p.isEmpty() || p[0] == "lo") continue
            list.add(
                NetIface(
                    name = p[0],
                    state = p.getOrNull(1).orEmpty(),
                    addrs = if (p.size > 2) p.subList(2, p.size).joinToString(" ") else "",
                )
            )
        }
        return list
    }

    // ── 进程 ────────────────────────────────────────────────────────────────
    private fun fetchProcesses(connection: Connection): List<ProcessInfo> {
        val out = run(connection, """ps aux --sort=-%cpu 2>/dev/null | head -80 || ps w 2>/dev/null | head -80""")
        val list = mutableListOf<ProcessInfo>()
        out.lines().forEachIndexed { i, raw ->
            val line = raw.trimEnd()
            if (line.isEmpty()) return@forEachIndexed
            if (i == 0 && (line.contains("PID") || line.startsWith("USER"))) return@forEachIndexed
            val p = line.trim().split(Regex("\\s+"))
            if (p.size < 11) {
                if (p.size >= 2) list.add(ProcessInfo(pid = p[1], command = p.last()))
                return@forEachIndexed
            }
            list.add(
                ProcessInfo(
                    user = p[0],
                    pid = p[1],
                    cpu = p[2].toDoubleOrNull(),
                    mem = p[3].toDoubleOrNull(),
                    command = p.subList(10, p.size).joinToString(" "),
                )
            )
        }
        return list
    }

    // ── 服务 ────────────────────────────────────────────────────────────────
    private val serviceScript = """
printf '@@RUN@@\n'
systemctl list-units --type=service --state=running --no-legend --no-pager 2>/dev/null | head -100
printf '@@FAIL@@\n'
systemctl list-units --type=service --state=failed --no-legend --no-pager 2>/dev/null | head -40
printf '@@INIT@@\n'
ls /etc/init.d/ 2>/dev/null | head -60
""".trimIndent()

    private fun fetchServices(connection: Connection): Pair<List<ServiceInfo>, List<ServiceInfo>> {
        val sec = sections(run(connection, serviceScript))
        val running = parseSystemctl(sec["RUN"].orEmpty())
        val failed = parseSystemctl(sec["FAIL"].orEmpty())
        if (running.isEmpty() && failed.isEmpty()) {
            val init = sec["INIT"].orEmpty().lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith(".") }
                .map { ServiceInfo(name = it, active = "init.d", description = "SysV / OpenWrt") }
            return Pair(init, emptyList())
        }
        return Pair(running, failed)
    }

    private fun parseSystemctl(raw: String): List<ServiceInfo> = raw.lines().mapNotNull { line ->
        val t = line.trim()
        if (t.isEmpty()) return@mapNotNull null
        val p = t.split(Regex("\\s+"))
        if (p.size < 4) return@mapNotNull null
        ServiceInfo(
            name = p[0].replace(".service", ""),
            load = p[1],
            active = p[2],
            sub = p[3],
            description = if (p.size > 4) p.subList(4, p.size).joinToString(" ") else "",
        )
    }

    // ── 防火墙 ──────────────────────────────────────────────────────────────
    private val firewallScript = """
printf '@@UFW@@\n'
ufw status 2>/dev/null | head -40
printf '@@FWCMD@@\n'
firewall-cmd --state 2>/dev/null
firewall-cmd --list-all 2>/dev/null | head -40
printf '@@IPT@@\n'
iptables -L -n 2>/dev/null | head -60
printf '@@NFT@@\n'
nft list ruleset 2>/dev/null | head -40
""".trimIndent()

    private fun fetchFirewall(connection: Connection): FirewallInfo {
        val sec = sections(run(connection, firewallScript))
        val highlights = mutableListOf<String>()
        val ufw = sec["UFW"].orEmpty().trim()
        if (ufw.isNotEmpty() && !ufw.contains("command not found", true)) highlights.add("UFW\n$ufw")
        val fw = sec["FWCMD"].orEmpty().trim()
        if (fw.isNotEmpty()) highlights.add("firewalld\n$fw")
        val ipt = sec["IPT"].orEmpty().trim()
        if (ipt.isNotEmpty()) {
            val chains = Regex("""^Chain\s+(\S+)""", RegexOption.MULTILINE)
                .findAll(ipt).map { it.groupValues[1] }.toList()
            highlights.add(
                "iptables · ${chains.ifEmpty { listOf("规则") }.joinToString(", ")}\n" +
                    ipt.lines().take(25).joinToString("\n")
            )
        }
        val nft = sec["NFT"].orEmpty().trim()
        if (nft.isNotEmpty()) highlights.add("nftables\n${nft.lines().take(20).joinToString("\n")}")
        if (highlights.isEmpty()) {
            return FirewallInfo(summary = "未检测到常见防火墙，或当前用户无权限查看")
        }
        return FirewallInfo(summary = "已识别 ${highlights.size} 类防火墙配置", highlights = highlights)
    }

    // ── 电源 ────────────────────────────────────────────────────────────────
    private val powerScript = """
printf '@@UP@@\n'
uptime -p 2>/dev/null || uptime 2>/dev/null
printf '@@BOOT@@\n'
who -b 2>/dev/null || true
printf '@@BAT@@\n'
for d in /sys/class/power_supply/*; do
  [ -d "${'$'}d" ] || continue
  echo "NAME=$(basename "${'$'}d")"
  echo -n "TYPE="; cat "${'$'}d/type" 2>/dev/null; echo
  echo -n "STATUS="; cat "${'$'}d/status" 2>/dev/null; echo
  echo -n "CAP="; cat "${'$'}d/capacity" 2>/dev/null; echo
  echo "---"
done
printf '@@TEMP@@\n'
for t in /sys/class/thermal/thermal_zone*/temp; do
  [ -f "${'$'}t" ] || continue
  echo "$(basename "$(dirname "${'$'}t")"): $(cat "${'$'}t" 2>/dev/null)"
done
vcgencmd measure_temp 2>/dev/null || true
printf '@@LAST@@\n'
last -x 2>/dev/null | head -15 || true
""".trimIndent()

    private fun fetchPower(connection: Connection): PowerInfo {
        val sec = sections(run(connection, powerScript))
        val bats = mutableListOf<BatteryInfo>()
        var name: String? = null
        var type: String? = null
        var status: String? = null
        var cap: Int? = null
        for (line in sec["BAT"].orEmpty().lines()) {
            when {
                line.startsWith("NAME=") -> name = line.substring(5).trim()
                line.startsWith("TYPE=") -> type = line.substring(5).trim()
                line.startsWith("STATUS=") -> status = line.substring(7).trim()
                line.startsWith("CAP=") -> cap = line.substring(4).trim().toIntOrNull()
                line.trim() == "---" && name != null -> {
                    bats.add(BatteryInfo(name = name!!, type = type.orEmpty(), status = status.orEmpty(), capacity = cap))
                    name = null; type = null; status = null; cap = null
                }
            }
        }
        val temps = sec["TEMP"].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.map { e ->
            val m = Regex("""^(.*?):\s*(\d+)$""").find(e)
            val milli = m?.groupValues?.get(2)?.toLongOrNull()
            if (m != null && milli != null && milli > 1000) {
                "${m.groupValues[1]}: ${String.format("%.1f", milli / 1000.0)} °C"
            } else e
        }
        val events = sec["LAST"].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        return PowerInfo(
            uptimeText = sec["UP"].orEmpty().trim(),
            bootTime = sec["BOOT"].orEmpty().trim(),
            batteries = bats,
            temps = temps,
            recentEvents = events,
        )
    }

    // ── 用户 ────────────────────────────────────────────────────────────────
    private val usersScript = """
printf '@@WHO@@\n'
who 2>/dev/null
printf '@@PASSWD@@\n'
cat /etc/passwd 2>/dev/null
""".trimIndent()

    private fun fetchUsers(connection: Connection): List<UserInfo> {
        val sec = sections(run(connection, usersScript))
        val logged = sec["WHO"].orEmpty().lines()
            .map { it.trim().split(Regex("\\s+")).firstOrNull().orEmpty() }
            .filter { it.isNotEmpty() }.toSet()
        val list = mutableListOf<UserInfo>()
        for (line in sec["PASSWD"].orEmpty().lines()) {
            val p = line.split(":")
            if (p.size < 7) continue
            list.add(
                UserInfo(
                    name = p[0], uid = p[2], gid = p[3], home = p[5], shell = p[6],
                    loggedIn = logged.contains(p[0]),
                )
            )
        }
        return list.sortedWith(
            compareBy<UserInfo> { if (it.loggedIn) 0 else 1 }
                .thenBy { it.uid.toIntOrNull() ?: 0 }
        )
    }

    // ── 计划任务 ────────────────────────────────────────────────────────────
    private val cronScript = """
printf '@@USER@@\n'
crontab -l 2>/dev/null
printf '@@ROOT@@\n'
cat /etc/crontabs/root 2>/dev/null
printf '@@ETC@@\n'
cat /etc/crontab 2>/dev/null
printf '@@D@@\n'
for f in /etc/cron.d/*; do
  [ -f "${'$'}f" ] || continue
  echo "## FILE:${'$'}f"
  cat "${'$'}f" 2>/dev/null
done
""".trimIndent()

    private fun fetchCron(connection: Connection): List<CronEntry> {
        val sec = sections(run(connection, cronScript))
        val list = mutableListOf<CronEntry>()
        fun parseBlock(source: String, body: String) {
            for (line in body.lines()) {
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("SHELL=") ||
                    t.startsWith("PATH=") || t.startsWith("MAILTO=")
                ) continue
                val p = t.split(Regex("\\s+"))
                if (p.size < 6) {
                    list.add(CronEntry(source = source, command = t))
                    continue
                }
                list.add(
                    CronEntry(
                        source = source,
                        schedule = p.subList(0, 5).joinToString(" "),
                        command = p.subList(5, p.size).joinToString(" "),
                    )
                )
            }
        }
        parseBlock("用户 crontab", sec["USER"].orEmpty())
        parseBlock("OpenWrt root", sec["ROOT"].orEmpty())
        parseBlock("/etc/crontab", sec["ETC"].orEmpty())
        var file: String? = null
        val buf = StringBuilder()
        for (line in sec["D"].orEmpty().lines()) {
            if (line.startsWith("## FILE:")) {
                if (file != null) parseBlock(file!!, buf.toString())
                file = line.substring(8).trim()
                buf.clear()
            } else {
                buf.appendLine(line)
            }
        }
        if (file != null) parseBlock(file!!, buf.toString())
        return list
    }

    // ── Docker ──────────────────────────────────────────────────────────────
    private val dockerScript = """
printf '@@VER@@\n'
docker --version 2>/dev/null || echo NO_DOCKER
printf '@@PS@@\n'
docker ps -a --format '{{.ID}}\t{{.Image}}\t{{.Status}}\t{{.Names}}\t{{.Ports}}' 2>/dev/null
printf '@@IMG@@\n'
docker images --format '{{.Repository}}\t{{.Tag}}\t{{.ID}}\t{{.Size}}' 2>/dev/null
""".trimIndent()

    private fun fetchDocker(connection: Connection): DockerInfo {
        val sec = sections(run(connection, dockerScript))
        val ver = sec["VER"].orEmpty().trim()
        if (ver.contains("NO_DOCKER") || ver.isEmpty()) {
            return DockerInfo(available = false, errorHint = "未安装 Docker，或不在 PATH / 无权限")
        }
        val containers = sec["PS"].orEmpty().lines().mapNotNull { line ->
            val p = line.split("\t")
            if (p.size < 4) return@mapNotNull null
            DockerContainer(
                id = p[0], image = p[1], status = p[2], names = p[3],
                ports = p.getOrNull(4).orEmpty(),
            )
        }
        val images = sec["IMG"].orEmpty().lines().mapNotNull { line ->
            val p = line.split("\t")
            if (p.size < 4) return@mapNotNull null
            DockerImage(repository = p[0], tag = p[1], id = p[2], size = p[3])
        }
        return DockerInfo(available = true, versionLine = ver, containers = containers, images = images)
    }

    private fun sections(raw: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val matches = Regex("""@@(\w+)@@\n""").findAll(raw).toList()
        matches.forEachIndexed { i, m ->
            val start = m.range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else raw.length
            map[m.groupValues[1]] = raw.substring(start, end)
        }
        return map
    }

    fun close() {
        runCatching { conn?.close() }
        conn = null
    }
}
