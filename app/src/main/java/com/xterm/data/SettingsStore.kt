package com.xterm.data

import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import com.xterm.ssh.LinuxEnv

enum class AppThemeMode { SYSTEM, LIGHT, DARK }

/** 对齐 Aurora `services/settings_store.dart` 的全部设置项与默认值。 */
data class AppSettings(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val fontSize: Float = 14f,
    val showVirtKeys: Boolean = true,
    val virtKeyAutoOff: Boolean = true,
    val enableLocalTerminal: Boolean = false,
    val enableLinuxTerminal: Boolean = false,
    val confirmExitTerminal: Boolean = true,
    val homeListOrder: List<String> = emptyList(),
    val virtKeys: List<VirtKeyDef> = VirtKeyDef.defaultKeys,
    val virtKeysPerRow: Int = 7,
)

/**
 * 用 SharedPreferences 持久化，以 StateFlow 驱动 Compose 重组（等价于 Aurora 的
 * ChangeNotifier + addListener）。
 */
class SettingsStore private constructor(private val app: Application) {

    private val prefs = app.getSharedPreferences("aurora_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val value: AppSettings get() = _settings.value

    private fun update(transform: (AppSettings) -> AppSettings) {
        _settings.value = transform(_settings.value)
    }

    private fun load(): AppSettings {
        val themeMode = when (prefs.getString(K_THEME, null)) {
            "light" -> AppThemeMode.LIGHT
            "dark" -> AppThemeMode.DARK
            else -> AppThemeMode.SYSTEM
        }
        val order = try {
            val raw = prefs.getString(K_HOME_ORDER, null)
            if (raw.isNullOrEmpty()) emptyList()
            else JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } }
        } catch (_: Exception) {
            emptyList()
        }
        val keys = loadVirtKeys()
        return AppSettings(
            themeMode = themeMode,
            fontSize = prefs.getFloat(K_FONT_SIZE, 14f).coerceIn(10f, 24f),
            showVirtKeys = prefs.getBoolean(K_SHOW_VIRT_KEYS, true),
            virtKeyAutoOff = prefs.getBoolean(K_AUTO_OFF, true),
            enableLocalTerminal = prefs.getBoolean(K_LOCAL_TERMINAL, false),
            enableLinuxTerminal = prefs.getBoolean(K_LINUX_TERMINAL, false),
            confirmExitTerminal = prefs.getBoolean(K_EXIT_CONFIRM, true),
            homeListOrder = order,
            virtKeys = keys,
            virtKeysPerRow = prefs.getInt(K_PER_ROW, 7).coerceIn(4, 10),
        )
    }

    private fun loadVirtKeys(): List<VirtKeyDef> {
        val cfg = prefs.getString(K_VIRT_KEYS_CONFIG, null)
        if (!cfg.isNullOrEmpty()) {
            try {
                val a = JSONArray(cfg)
                val parsed = (0 until a.length()).map { VirtKeyDef.fromJson(a.getJSONObject(it)) }
                if (parsed.isNotEmpty()) return parsed
            } catch (_: Exception) {
                // 落到默认
            }
        } else {
            // 兼容只存 id 列表的旧数据
            val raw = prefs.getString(K_VIRT_KEY_IDS, null)
            if (!raw.isNullOrEmpty()) {
                try {
                    val a = JSONArray(raw)
                    val ids = (0 until a.length()).map { a.getString(it) }
                    if (ids.isNotEmpty()) {
                        val keys = ids.mapNotNull { VirtKeyDef.byId(it) }
                        if (keys.isNotEmpty()) return keys
                    }
                } catch (_: Exception) {
                    // 落到默认
                }
            }
        }
        return VirtKeyDef.defaultKeys
    }

    fun setThemeMode(mode: AppThemeMode) {
        update { it.copy(themeMode = mode) }
        prefs.edit().putString(
            K_THEME,
            when (mode) {
                AppThemeMode.SYSTEM -> "system"
                AppThemeMode.LIGHT -> "light"
                AppThemeMode.DARK -> "dark"
            },
        ).apply()
    }

    fun setFontSize(size: Float) {
        val v = size.coerceIn(10f, 24f)
        update { it.copy(fontSize = v) }
        prefs.edit().putFloat(K_FONT_SIZE, v).apply()
    }

    fun setShowVirtKeys(v: Boolean) {
        update { it.copy(showVirtKeys = v) }
        prefs.edit().putBoolean(K_SHOW_VIRT_KEYS, v).apply()
    }

    fun setVirtKeyAutoOff(v: Boolean) {
        update { it.copy(virtKeyAutoOff = v) }
        prefs.edit().putBoolean(K_AUTO_OFF, v).apply()
    }

    fun setEnableLocalTerminal(v: Boolean) {
        update { it.copy(enableLocalTerminal = v) }
        prefs.edit().putBoolean(K_LOCAL_TERMINAL, v).apply()
    }

    /** 打开即部署 rootfs，关闭即清理（与 Aurora 的设置开关一致）。 */
    suspend fun setEnableLinuxTerminal(v: Boolean) {
        update { it.copy(enableLinuxTerminal = v) }
        prefs.edit().putBoolean(K_LINUX_TERMINAL, v).apply()
        if (v) LinuxEnv.instance.install() else LinuxEnv.instance.uninstall()
    }

    fun setConfirmExitTerminal(v: Boolean) {
        update { it.copy(confirmExitTerminal = v) }
        prefs.edit().putBoolean(K_EXIT_CONFIRM, v).apply()
    }

    fun setHomeListOrder(ids: List<String>) {
        update { it.copy(homeListOrder = ids) }
        prefs.edit().putString(K_HOME_ORDER, JSONArray(ids).toString()).apply()
    }

    fun setVirtKeys(keys: List<VirtKeyDef>) {
        val next = if (keys.isEmpty()) VirtKeyDef.defaultKeys else keys
        update { it.copy(virtKeys = next) }
        val ids = JSONArray(next.map { it.id })
        val cfg = JSONArray()
        next.forEach { cfg.put(it.toJson()) }
        prefs.edit()
            .putString(K_VIRT_KEY_IDS, ids.toString())
            .putString(K_VIRT_KEYS_CONFIG, cfg.toString())
            .apply()
    }

    fun setVirtKeysPerRow(n: Int) {
        val v = n.coerceIn(4, 10)
        update { it.copy(virtKeysPerRow = v) }
        prefs.edit().putInt(K_PER_ROW, v).apply()
    }

    /** 预设：完整 / 精简 / 恢复默认。 */
    fun applyPreset(keys: List<VirtKeyDef>) = setVirtKeys(keys)

    companion object {
        private const val K_THEME = "theme_mode"
        private const val K_FONT_SIZE = "font_size"
        private const val K_SHOW_VIRT_KEYS = "virt_keys"
        private const val K_VIRT_KEY_IDS = "virt_key_ids"
        private const val K_VIRT_KEYS_CONFIG = "virt_keys_config"
        private const val K_PER_ROW = "virt_key_per_row"
        private const val K_AUTO_OFF = "virt_key_auto_off"
        private const val K_LOCAL_TERMINAL = "enable_local_terminal"
        private const val K_LINUX_TERMINAL = "enable_linux_terminal"
        private const val K_EXIT_CONFIRM = "terminal_exit_confirm"
        private const val K_HOME_ORDER = "home_list_order"

        lateinit var instance: SettingsStore
            private set

        fun init(app: Application) {
            instance = SettingsStore(app)
        }
    }
}
