package com.xterm.data

import org.json.JSONObject
import java.util.UUID

/**
 * 与 Aurora `models/server.dart` 一一对应的服务器模型。
 *
 * 差异（不影响页面与操作逻辑）：xterm 原有能力保留在默认值里——
 * 凭据落盘前用 [Crypto] 做 AES-GCM 加密，TERM / 启动命令 / bracketed paste
 * 走默认配置，Aurora 的编辑页不暴露这些字段。
 */
data class Server(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "root",
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val term: String = "xterm-256color",
    val startupCommand: String = "",
    val bracketedPaste: Boolean = true,
) {
    val isLocal: Boolean get() = id == LOCAL_ID
    val isLinux: Boolean get() = id == LINUX_ID
    val isOnDevice: Boolean get() = isLocal || isLinux

    /** 编辑页「名称」留空时的回退，Aurora 用 host。 */
    val displayName: String
        get() = name.ifBlank { host }

    val displayAddr: String
        get() = when {
            isLocal -> "本机"
            isLinux -> "本机 Linux（Alpine）"
            port == 22 -> host
            else -> "$host:$port"
        }

    /** Aurora 的编辑页用「密码 / 私钥」分段按钮，切换时清空另一种凭据。 */
    val useKey: Boolean get() = privateKey.isNotBlank()

    fun toJson(crypto: Crypto): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("host", host)
        put("port", port)
        put("username", username)
        put("password", crypto.encrypt(password))
        put("privateKey", crypto.encrypt(privateKey))
        put("passphrase", crypto.encrypt(passphrase))
        put("term", term)
        put("startupCommand", startupCommand)
        put("bracketedPaste", bracketedPaste)
    }

    companion object {
        const val LOCAL_ID = "local"
        const val LINUX_ID = "linux"

        /** 首页展示用的本机终端实例（不持久化）。 */
        val local = Server(
            id = LOCAL_ID,
            name = "本机",
            host = "localhost",
            port = 0,
            username = "local",
        )

        /** 首页展示用的本机 Linux（proot Alpine）实例（不持久化）。 */
        val linux = Server(
            id = LINUX_ID,
            name = "Linux",
            host = "localhost",
            port = 0,
            username = "root",
        )

        fun fromJson(o: JSONObject, crypto: Crypto): Server = Server(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", o.optString("host", "")),
            host = o.optString("host", ""),
            port = o.optInt("port", 22),
            username = o.optString("username", "root"),
            password = crypto.decrypt(o.optString("password", "")),
            privateKey = crypto.decrypt(o.optString("privateKey", "")),
            passphrase = crypto.decrypt(o.optString("passphrase", "")),
            term = o.optString("term", "xterm-256color").ifBlank { "xterm-256color" },
            startupCommand = o.optString("startupCommand", ""),
            bracketedPaste = o.optBoolean("bracketedPaste", true),
        )
    }
}
