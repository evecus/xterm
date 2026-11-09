package com.xterm.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 对齐 Aurora `services/virt_key_input_handler.dart`：
 * Ctrl / Alt / Shift 是粘性修饰键，点亮后作用于随后的 IME 键盘 / 物理键盘 /
 * 虚拟按键输入，生效一次后按需自动熄灭。
 */
class VirtKeyInputHandler(var autoOff: Boolean = true) {

    private val _ctrl = MutableStateFlow(false)
    val ctrl: StateFlow<Boolean> = _ctrl.asStateFlow()

    private val _alt = MutableStateFlow(false)
    val alt: StateFlow<Boolean> = _alt.asStateFlow()

    private val _shift = MutableStateFlow(false)
    val shift: StateFlow<Boolean> = _shift.asStateFlow()

    fun toggleCtrl() {
        _ctrl.value = !_ctrl.value
        if (_ctrl.value) _alt.value = false
    }

    fun toggleAlt() {
        _alt.value = !_alt.value
        if (_alt.value) _ctrl.value = false
    }

    fun toggleShift() {
        _shift.value = !_shift.value
    }

    fun resetAll() {
        _ctrl.value = false
        _alt.value = false
        _shift.value = false
    }

    /** 把当前修饰键合并进一段输入（等价于 Aurora 的 inputHandler.call）。 */
    fun transform(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val altActive = _alt.value
        val ctrlActive = _ctrl.value
        val shiftActive = _shift.value
        if (!altActive && !ctrlActive && !shiftActive) return data

        var out = data
        if (ctrlActive) {
            if (out.size == 1) {
                val c = out[0].toInt() and 0xFF
                out = when {
                    c in 0x41..0x5F || c in 0x61..0x7A -> byteArrayOf((c and 0x1F).toByte())
                    c in 0x5B..0x5F -> byteArrayOf((c - 0x5B + 27).toByte())
                    else -> out
                }
            }
        } else if (altActive) {
            if (out.size == 1 && (out[0].toInt() and 0xFF) in 0x20..0x7E) {
                out = byteArrayOf(0x1B, out[0])
            }
        } else if (shiftActive) {
            if (out.size == 1) {
                val c = out[0].toInt() and 0xFF
                if (c in 0x61..0x7A) out = byteArrayOf((c - 32).toByte())
            }
        }
        if (autoOff) {
            if (ctrlActive) _ctrl.value = false
            if (altActive) _alt.value = false
            if (shiftActive) _shift.value = false
        }
        return out
    }
}
