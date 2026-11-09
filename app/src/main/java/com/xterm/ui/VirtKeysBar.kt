package com.xterm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xterm.data.VirtKeyDef
import com.xterm.data.VirtKeyKind

/**
 * 对齐 Aurora `widgets/virt_keys.dart`：底部虚拟按键条。
 *
 * - 按设置里的「每行键数」分行，不足一行补空位（避免键被拉宽）；
 * - Ctrl / Alt / Shift 为粘滞修饰键，点亮后作用于随后的字符 / 方向键；
 * - func(ime) 切换手机输入法键盘；
 * - terminalKey 交给 termlib dispatchKey，以便在 application cursor keys
 *   模式下自动切换 ESC O / ESC [ 序列；
 * - char 支持字面量 `\n` 表示回车、多字符自定义命令。
 */
@Composable
fun VirtKeysBar(
    vm: MainViewModel,
    onToggleIme: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by vm.settings.collectAsState()
    val ctrl by vm.inputHandler.ctrl.collectAsState()
    val alt by vm.inputHandler.alt.collectAsState()
    val shift by vm.inputHandler.shift.collectAsState()

    val keys = settings.virtKeys
    val perRow = settings.virtKeysPerRow.coerceAtLeast(1)
    if (keys.isEmpty()) return

    val scheme = MaterialTheme.colorScheme

    val rows = mutableListOf<List<VirtKeyDef>>()
    var i = 0
    while (i < keys.size) {
        val end = minOf(i + perRow, keys.size)
        rows.add(keys.subList(i, end))
        i = end
    }

    fun resetModifiers() {
        if (vm.inputHandler.autoOff) vm.inputHandler.resetAll()
    }

    fun isActive(def: VirtKeyDef): Boolean {
        val aid = def.actionId
        return when {
            aid == "ctrl" || def.id.startsWith("ctrl") -> ctrl
            aid == "alt" || def.id.startsWith("alt") -> alt
            aid == "shift" || def.id.startsWith("shift") -> shift
            else -> false
        }
    }

    fun tap(def: VirtKeyDef) {
        when (def.kind) {
            VirtKeyKind.MODIFIER -> {
                val aid = def.actionId
                when {
                    aid == "ctrl" || def.id.startsWith("ctrl") -> vm.inputHandler.toggleCtrl()
                    aid == "alt" || def.id.startsWith("alt") -> vm.inputHandler.toggleAlt()
                    aid == "shift" || def.id.startsWith("shift") -> vm.inputHandler.toggleShift()
                }
            }

            VirtKeyKind.FUNC -> {
                if (def.actionId == "ime" || def.id == "ime" || def.icon == "keyboard") {
                    onToggleIme()
                }
            }

            VirtKeyKind.TERMINAL_KEY -> {
                val key = def.terminalKey ?: return
                // libvterm VTermModifier：SHIFT=1 / ALT=2 / CTRL=4
                var mods = 0
                if (ctrl) mods = mods or 4
                if (alt) mods = mods or 2
                if (shift) mods = mods or 1
                vm.dispatchTermKey(key, mods)
                resetModifiers()
            }

            VirtKeyKind.CHAR -> {
                val text = def.sendText
                if (text.isEmpty()) return
                val raw = text.replace("\\n", "\n")
                if (raw.length > 1) {
                    vm.sendBytes(raw.toByteArray(Charsets.UTF_8))
                    resetModifiers()
                    return
                }
                val ch = raw
                if (ctrl) {
                    val code = ch.uppercaseChar().code
                    val out = when {
                        code in 65..90 -> (code - 64).toChar().toString()   // A-Z → 1-26
                        code in 91..95 -> (code - 91 + 27).toChar().toString() // [\]^_ → 27-31
                        else -> ch
                    }
                    vm.sendBytes(out.toByteArray(Charsets.UTF_8))
                    resetModifiers()
                } else if (alt) {
                    vm.sendBytes("\u001B$ch".toByteArray(Charsets.UTF_8))
                    resetModifiers()
                } else {
                    vm.sendBytes(ch.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    Surface(
        color = scheme.surface,
        shadowElevation = 4.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            for (row in rows) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (def in row) {
                        val active = isActive(def)
                        val weight = if (def.id == "enter") 1.4f else 1f
                        Box(
                            modifier = Modifier
                                .weight(weight)
                                .padding(2.dp)
                                .height(36.dp)
                                .background(
                                    color = if (active) scheme.primary else scheme.surfaceVariant,
                                    shape = RoundedCornerShape(6.dp),
                                )
                                .clickable { tap(def) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (def.icon == "keyboard") {
                                Icon(
                                    Icons.Filled.Keyboard,
                                    contentDescription = def.label,
                                    tint = scheme.onSurface,
                                    modifier = Modifier.size(17.dp),
                                )
                            } else {
                                Text(
                                    text = def.label.ifEmpty { "?" },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.W500,
                                    color = if (active) scheme.onPrimary else scheme.onSurface,
                                )
                            }
                        }
                    }
                    // 不足一行时补空位，避免键被拉得过宽
                    repeat(perRow - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
