package com.xterm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.xterm.data.SettingsStore
import com.xterm.ssh.LinuxEnv
import com.xterm.ui.App
import com.xterm.ui.SshTermTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 对齐 Aurora main()：先加载持久化设置、刷新 Linux 部署状态，再启动 UI。
        LinuxEnv.init(application)
        SettingsStore.init(application)
        setContent {
            SshTermTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    App()
                }
            }
        }
    }
}
