package com.xterm.data

import org.json.JSONObject

/** 对齐 Aurora `models/virt_key_def.dart` 的按键行为类型。 */
enum class VirtKeyKind { MODIFIER, TERMINAL_KEY, CHAR, FUNC }

/** Aurora 的 `TerminalKey` 枚举（只用到目录里的这些）。 */
enum class TermKey {
    ESCAPE, TAB, ENTER, BACKSPACE, DELETE,
    ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT,
    HOME, END, PAGE_UP, PAGE_DOWN,
}

/**
 * 一枚可配置的虚拟按键（内置目录或用户自定义）。
 * 字段、目录、默认/精简布局、行为选项与 Aurora 完全一致。
 */
data class VirtKeyDef(
    val id: String,
    val label: String,
    val kind: VirtKeyKind,
    val terminalKey: TermKey? = null,
    val char: String? = null,
    val icon: String? = null,
    val payload: String? = null,
) {
    val isCustom: Boolean get() = id.startsWith("custom_")

    /** 实际发送的文本（自定义用 payload，内置单字符用 char）。 */
    val sendText: String
        get() = if (!payload.isNullOrEmpty()) payload else (char ?: "")

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("kind", kind.name.lowercase())
        terminalKey?.let { put("terminalKey", it.name) }
        char?.let { put("char", it) }
        icon?.let { put("icon", it) }
        payload?.let { put("payload", it) }
    }

    /** 编辑弹窗用的行为 id。 */
    val actionId: String
        get() {
            if (kind == VirtKeyKind.CHAR &&
                (payload != null || (char != null && id !in catalogIds))
            ) {
                if (isCustom || payload != null) return "send_text"
            }
            if (kind == VirtKeyKind.MODIFIER) {
                if (id.contains("ctrl") || label.equals("ctrl", true)) return "ctrl"
                if (id.contains("alt") || label.equals("alt", true)) return "alt"
                if (id.contains("shift") || label.equals("shift", true)) return "shift"
            }
            if (kind == VirtKeyKind.FUNC) return "ime"
            if (kind == VirtKeyKind.TERMINAL_KEY) {
                return when (terminalKey) {
                    TermKey.ESCAPE -> "esc"
                    TermKey.TAB -> "tab"
                    TermKey.ENTER -> "enter"
                    TermKey.BACKSPACE -> "backspace"
                    TermKey.DELETE -> "delete"
                    TermKey.ARROW_UP -> "up"
                    TermKey.ARROW_DOWN -> "down"
                    TermKey.ARROW_LEFT -> "left"
                    TermKey.ARROW_RIGHT -> "right"
                    TermKey.HOME -> "home"
                    TermKey.END -> "end"
                    TermKey.PAGE_UP -> "pgup"
                    TermKey.PAGE_DOWN -> "pgdn"
                    else -> ""
                }.ifEmpty { id }
            }
            if (id in catalogIds) return id
            if (kind == VirtKeyKind.CHAR) return "send_text"
            return id
        }

    companion object {
        fun fromJson(o: JSONObject): VirtKeyDef {
            val kindName = o.optString("kind", "char")
            val kind = VirtKeyKind.entries.firstOrNull {
                it.name.equals(kindName, ignoreCase = true)
            } ?: VirtKeyKind.CHAR
            val tkName = o.optString("terminalKey", null)
            val tk = if (tkName.isNullOrEmpty()) null else
                TermKey.entries.firstOrNull { it.name == tkName }
            return VirtKeyDef(
                id = o.optString("id", "custom_${System.currentTimeMillis()}"),
                label = o.optString("label", "?"),
                kind = kind,
                terminalKey = tk,
                char = o.optString("char", null),
                icon = o.optString("icon", null),
                payload = o.optString("payload", null),
            )
        }

        fun byId(id: String, extra: List<VirtKeyDef> = emptyList()): VirtKeyDef? =
            extra.firstOrNull { it.id == id } ?: catalog.firstOrNull { it.id == id }

        /** 按行为类型快速创建（编辑弹窗用）。 */
        fun fromAction(id: String, label: String, actionId: String, payload: String = ""): VirtKeyDef =
            when (actionId) {
                "ctrl" -> VirtKeyDef(id = id, label = label, kind = VirtKeyKind.MODIFIER)
                "alt" -> VirtKeyDef(id = id, label = label, kind = VirtKeyKind.MODIFIER)
                "shift" -> VirtKeyDef(id = id, label = label, kind = VirtKeyKind.MODIFIER)
                "ime" -> VirtKeyDef(id = id, label = label, kind = VirtKeyKind.FUNC, icon = "keyboard")
                "esc" -> VirtKeyDef(id, label, VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.ESCAPE)
                "tab" -> VirtKeyDef(id, label, VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.TAB)
                "enter" -> VirtKeyDef(id, label, VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.ENTER)
                "backspace" -> VirtKeyDef(id, label, VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.BACKSPACE)
                "delete" -> VirtKeyDef(id, label, VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.DELETE)
                "up" -> VirtKeyDef(id, label, VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.ARROW_UP)
                "down" -> VirtKeyDef(id, label, VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.ARROW_DOWN)
                "left" -> VirtKeyDef(id, label, VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.ARROW_LEFT)
                "right" -> VirtKeyDef(id, label, VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.ARROW_RIGHT)
                "home" -> VirtKeyDef(id, label, VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.HOME)
                "end" -> VirtKeyDef(id, label, VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.END)
                "pgup" -> VirtKeyDef(id, label, VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.PAGE_UP)
                "pgdn" -> VirtKeyDef(id, label, VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.PAGE_DOWN)
                else -> VirtKeyDef(
                    id = id,
                    label = label,
                    kind = VirtKeyKind.CHAR,
                    payload = payload,
                    char = if (payload.length == 1) payload else null,
                )
            }

        val catalog: List<VirtKeyDef> = listOf(
            VirtKeyDef("ctrl", "Ctrl", VirtKeyKind.MODIFIER),
            VirtKeyDef("alt", "Alt", VirtKeyKind.MODIFIER),
            VirtKeyDef("shift", "Shift", VirtKeyKind.MODIFIER),
            VirtKeyDef("ime", "键盘", VirtKeyKind.FUNC, icon = "keyboard"),
            VirtKeyDef("esc", "Esc", VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.ESCAPE),
            VirtKeyDef("tab", "Tab", VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.TAB),
            VirtKeyDef("dash", "—", VirtKeyKind.CHAR, char = "-"),
            VirtKeyDef("slash", "/", VirtKeyKind.CHAR, char = "/"),
            VirtKeyDef("pipe", "|", VirtKeyKind.CHAR, char = "|"),
            VirtKeyDef("tilde", "~", VirtKeyKind.CHAR, char = "~"),
            VirtKeyDef("dollar", "\$", VirtKeyKind.CHAR, char = "\$"),
            VirtKeyDef("star", "*", VirtKeyKind.CHAR, char = "*"),
            VirtKeyDef("amp", "&", VirtKeyKind.CHAR, char = "&"),
            VirtKeyDef("semi", ";", VirtKeyKind.CHAR, char = ";"),
            VirtKeyDef("gt", ">", VirtKeyKind.CHAR, char = ">"),
            VirtKeyDef("lt", "<", VirtKeyKind.CHAR, char = "<"),
            VirtKeyDef("up", "↑", VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.ARROW_UP),
            VirtKeyDef("down", "↓", VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.ARROW_DOWN),
            VirtKeyDef("left", "←", VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.ARROW_LEFT),
            VirtKeyDef("right", "→", VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.ARROW_RIGHT),
            VirtKeyDef("home", "Home", VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.HOME),
            VirtKeyDef("end", "End", VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.END),
            VirtKeyDef("pgup", "PgUp", VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.PAGE_UP),
            VirtKeyDef("pgdn", "PgDn", VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.PAGE_DOWN),
            VirtKeyDef("backspace", "⌫", VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.BACKSPACE),
            VirtKeyDef("delete", "Del", VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.DELETE),
            VirtKeyDef("enter", "Enter", VirtKeyKind.TERMINAL_KEY, terminalKey = TermKey.ENTER),
        )

        val catalogIds: Set<String> = catalog.map { it.id }.toSet()

        val defaultIds: List<String> = listOf(
            "ctrl", "alt", "shift", "ime", "esc", "tab", "dash", "slash", "pipe",
            "up", "down", "left", "right", "home", "end", "backspace", "enter",
        )

        val compactIds: List<String> = listOf(
            "ctrl", "esc", "tab", "ime", "up", "down", "left", "right", "enter",
        )

        val defaultKeys: List<VirtKeyDef> = defaultIds.mapNotNull { byId(it) }
        val compactKeys: List<VirtKeyDef> = compactIds.mapNotNull { byId(it) }

        /** 编辑弹窗可选行为（分组与 Aurora 一致）。 */
        val actionOptions: List<Triple<String, String, String>> = listOf(
            Triple("up", "↑ 上", "方向"),
            Triple("down", "↓ 下", "方向"),
            Triple("left", "← 左", "方向"),
            Triple("right", "→ 右", "方向"),
            Triple("pgup", "PgUp", "功能"),
            Triple("pgdn", "PgDn", "功能"),
            Triple("home", "Home", "功能"),
            Triple("end", "End", "功能"),
            Triple("esc", "Esc", "功能"),
            Triple("tab", "Tab", "功能"),
            Triple("enter", "Enter", "功能"),
            Triple("backspace", "Backspace", "功能"),
            Triple("delete", "Delete", "功能"),
            Triple("ctrl", "Ctrl", "修饰"),
            Triple("alt", "Alt", "修饰"),
            Triple("shift", "Shift", "修饰"),
            Triple("ime", "切换键盘", "其他"),
            Triple("send_text", "自定义文本", "其他"),
        )
    }
}
