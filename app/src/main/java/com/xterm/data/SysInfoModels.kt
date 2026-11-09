package com.xterm.data

/** 对齐 Aurora `models/sysinfo_models.dart` 的全部结构化模型。 */

data class BasicInfo(
    val hostname: String = "",
    val kernel: String = "",
    val osPretty: String = "",
    val uptimeText: String = "",
    val load1: Double? = null,
    val load5: Double? = null,
    val load15: Double? = null,
    val cpuModel: String = "",
    val cpuCores: Int? = null,
    val mem: MemInfo? = null,
    val disks: List<DiskInfo> = emptyList(),
    val ifaces: List<NetIface> = emptyList(),
)

data class MemInfo(
    val totalKb: Long = 0,
    val usedKb: Long = 0,
    val freeKb: Long = 0,
    val availableKb: Long? = null,
    val swapTotalKb: Long? = null,
    val swapUsedKb: Long? = null,
) {
    val usedRatio: Float
        get() {
            if (totalKb <= 0) return 0f
            val u = availableKb?.let { totalKb - it } ?: usedKb
            return (u.toFloat() / totalKb).coerceIn(0f, 1f)
        }
    val usedLabel: String get() = formatKb(availableKb?.let { totalKb - it } ?: usedKb)
    val totalLabel: String get() = formatKb(totalKb)
}

data class DiskInfo(
    val filesystem: String,
    val size: String,
    val used: String,
    val avail: String,
    val usedPercent: Int?,
    val mount: String,
)

data class NetIface(val name: String, val state: String = "", val addrs: String = "")

data class ProcessInfo(
    val user: String = "",
    val pid: String = "",
    val cpu: Double? = null,
    val mem: Double? = null,
    val command: String = "",
)

data class ServiceInfo(
    val name: String,
    val load: String = "",
    val active: String = "",
    val sub: String = "",
    val description: String = "",
) {
    val isRunning: Boolean
        get() = active.equals("active", true) || sub.contains("running", true)
}

data class FirewallInfo(
    val summary: String = "",
    val highlights: List<String> = emptyList(),
)

data class PowerInfo(
    val uptimeText: String = "",
    val bootTime: String = "",
    val batteries: List<BatteryInfo> = emptyList(),
    val temps: List<String> = emptyList(),
    val recentEvents: List<String> = emptyList(),
)

data class BatteryInfo(
    val name: String,
    val type: String = "",
    val status: String = "",
    val capacity: Int? = null,
)

data class UserInfo(
    val name: String,
    val uid: String = "",
    val gid: String = "",
    val home: String = "",
    val shell: String = "",
    val loggedIn: Boolean = false,
)

data class CronEntry(
    val source: String,
    val schedule: String = "",
    val command: String = "",
)

data class DockerContainer(
    val id: String = "",
    val image: String = "",
    val status: String = "",
    val names: String = "",
    val ports: String = "",
) {
    val isUp: Boolean get() = status.startsWith("Up", true)
}

data class DockerImage(
    val repository: String = "",
    val tag: String = "",
    val id: String = "",
    val size: String = "",
)

data class DockerInfo(
    val available: Boolean = false,
    val versionLine: String = "",
    val containers: List<DockerContainer> = emptyList(),
    val images: List<DockerImage> = emptyList(),
    val errorHint: String? = null,
)

fun formatKb(kb: Long): String = when {
    kb < 1024 -> "$kb KB"
    kb < 1024 * 1024 -> String.format("%.1f MB", kb / 1024.0)
    else -> String.format("%.2f GB", kb / (1024.0 * 1024))
}
