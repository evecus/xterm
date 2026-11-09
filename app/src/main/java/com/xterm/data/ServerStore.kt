package com.xterm.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.util.UUID

/**
 * 对齐 Aurora `services/server_store.dart`：本地持久化服务器列表。
 * 额外兼容 xterm 旧版 `sshterm_hosts` 数据（alias → name），升级不丢配置。
 */
class ServerStore(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("aurora_servers", Context.MODE_PRIVATE)
    private val legacyPrefs = app.getSharedPreferences("sshterm_hosts", Context.MODE_PRIVATE)
    private val crypto = Crypto()

    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<Server>> = _servers.asStateFlow()

    private fun load(): List<Server> {
        val raw = prefs.getString(KEY_SERVERS, null)
        if (!raw.isNullOrEmpty()) {
            try {
                val array = JSONArray(raw)
                return (0 until array.length()).mapNotNull { i ->
                    runCatching { Server.fromJson(array.getJSONObject(i), crypto) }.getOrNull()
                }
            } catch (_: Exception) {
                // 落到旧数据迁移
            }
        }
        // 旧版 xterm：profiles，字段 alias / authType / keyPassphrase
        val legacy = legacyPrefs.getString("profiles", null) ?: return emptyList()
        return try {
            val array = JSONArray(legacy)
            val migrated = (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                runCatching {
                    Server(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        name = o.optString("alias", o.optString("host", "")),
                        host = o.optString("host", ""),
                        port = o.optInt("port", 22),
                        username = o.optString("username", "root"),
                        password = crypto.decrypt(o.optString("password", "")),
                        privateKey = crypto.decrypt(o.optString("privateKey", "")),
                        passphrase = crypto.decrypt(o.optString("keyPassphrase", "")),
                        term = o.optString("term", "xterm-256color"),
                        startupCommand = o.optString("startupCommand", ""),
                        bracketedPaste = o.optBoolean("bracketedPaste", true),
                    )
                }.getOrNull()
            }
            if (migrated.isNotEmpty()) commit(migrated)
            migrated
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun byId(id: String): Server? = _servers.value.firstOrNull { it.id == id }

    fun add(server: Server) = commit(_servers.value + server)

    fun update(server: Server) {
        val next = _servers.value.toMutableList()
        val index = next.indexOfFirst { it.id == server.id }
        if (index < 0) return
        next[index] = server
        commit(next)
    }

    fun remove(id: String) = commit(_servers.value.filterNot { it.id == id })

    private fun commit(list: List<Server>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson(crypto)) }
        prefs.edit().putString(KEY_SERVERS, array.toString()).apply()
        _servers.value = list
    }

    private companion object {
        const val KEY_SERVERS = "servers_v1"
    }
}
