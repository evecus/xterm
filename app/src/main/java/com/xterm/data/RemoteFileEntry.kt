package com.xterm.data

/** 对齐 Aurora `models/remote_file_entry.dart`：SFTP / 命令模式通用的文件条目。 */
data class RemoteFileEntry(
    val filename: String,
    val isDirectory: Boolean,
    val size: Long? = null,
    /** Unix 秒时间戳，命令模式无此信息。 */
    val modifyTime: Long? = null,
    val longname: String = "",
) {
    val isDotDot: Boolean get() = filename == ".."
}

fun formatBytes(bytes: Long?): String {
    if (bytes == null) return "—"
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0)
    if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024))
    return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
}
