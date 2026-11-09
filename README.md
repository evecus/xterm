# SSH Term · Aurora 全功能适配版

以 **xterm（Android 原生 Kotlin + Jetpack Compose）** 工程为交付主体，把 **Aurora（Flutter/Dart）** 的
全部页面、操作逻辑与使用体验 1:1 复刻过来。

> 不是文档对照，也不是把 Flutter 塞进原生工程：保留 xterm 的 `termlib`（libvterm via JNI）+
> `sshlib`（trilead-ssh2）技术栈，用 Compose 逐页重写 Aurora 的 UI，用 Kotlin / StateFlow /
> SharedPreferences / JNI PTY / NDK 补齐 Aurora 的每一项能力。

## 页面 1:1 映射

| # | Aurora（Flutter） | 本工程（Compose） | 状态 |
| --- | --- | --- | --- |
| 1 | `pages/home_page.dart` | `ui/HomePage.kt` | 服务器列表、本机/Linux 入口、长按拖动排序、系统信息/SFTP/编辑/删除、设置、添加 |
| 2 | `pages/terminal_page.dart` | `ui/TerminalPage.kt` | SSH / 本机 / Linux(proot) 会话、虚拟按键栏、选区复制粘贴、SFTP 入口、重连/断开、退出确认、Linux 部署进度与重试 |
| 3 | `pages/server_edit_page.dart` | `ui/ServerEditPage.kt` | 字段与校验、密码/私钥分段切换、编辑「保存修改」、新建「连接（不保存）+ 添加服务器」 |
| 4 | `pages/settings_page.dart` | `ui/SettingsPage.kt` | 主题、终端字号、本机/Linux 终端开关、Alpine 环境状态、退出确认、虚拟按键开关与自动取消 |
| 5 | `pages/virt_keys_settings_page.dart` | `ui/VirtKeysSettingsPage.kt` | 完整/精简/恢复默认预设、每行键数、拖动排序、点按编辑、删除、内置目录添加、保存布局 |
| 6 | `pages/sftp_page.dart` | `ui/SftpPage.kt` | 路径栏、真 SFTP + 命令模式兼容、进入/新建/删除/重命名/下载/编辑/上传、入口 sheet |
| 7 | `pages/sftp_edit_page.dart` | `ui/SftpEditPage.kt` | 远程文本编辑（2 MB 上限、二进制检测、dirty 离开确认、保存失败提示） |
| 8 | `pages/sysinfo_page.dart` | `ui/SysInfoPage.kt` | 8 个 Tab：基础 / 进程 / 服务 / 防火墙 / 电源 / 用户 / 计划 / Docker |

配套控件：`widgets/virt_keys.dart` → `ui/VirtKeysBar.kt`，`widgets/selection_handles.dart` → termlib 内置选区能力。

## 能力补齐

| 模块 | 实现 |
| --- | --- |
| 终端 | `termlib` 的 `Terminal` + `TerminalEmulatorFactory.create`，256 色/真彩、滚动回溯、双指缩放、长按选区、复制/粘贴（bracketed paste）、OSC 52 |
| SSH | `sshlib`：密码 / keyboard-interactive 自动回退 / PEM 私钥 + 口令、PTY shell、window-change 尺寸同步、30s 保活、断线检测 |
| 主机密钥 | 保留 xterm 原有 **TOFU**：首次连接/指纹变更弹窗确认，写入应用私有 `known_hosts`（Aurora 无此流程，作为额外安全能力，不改变页面操作顺序） |
| 凭据 | 密码/私钥/口令落盘前用 AndroidKeystore 的 AES-256-GCM 加密（`data/Crypto.kt`） |
| SFTP | 先连 `SFTPv3Client`，服务器无 sftp-server 时自动回退到 SSH 命令模式（`ls -la` / base64 读写），标题追加「· 兼容模式」 |
| 系统信息 | 每个 Tab 按需执行 Aurora 同款 shell 脚本，用 `@@KEY@@` 分段解析为结构化卡片 |
| 本机终端 | NDK/JNI `/dev/ptmx` 实现真 PTY（`cpp/pty.c` + `ssh/PtyBridge.kt`），等价 Aurora 的 `flutter_pty` |
| Linux 终端 | `ssh/LinuxEnv.kt`：Alpine 3.22.5 minirootfs 下载/解压/配置 DNS+apk repositories+hosts/部署标记；`LocalShellSession` 复刻 proot 参数、绑定目录、环境变量、`-0` fake root |
| 虚拟按键 | `data/VirtKeyDef.kt` 内置目录 + 默认/精简布局；`ui/VirtKeyInputHandler.kt` 实现 Ctrl/Alt/Shift 粘滞修饰键；按每行键数分行渲染 |
| 设置 | `data/SettingsStore.kt` 对齐 Aurora 的 SharedPreferences 键名与默认值，StateFlow 驱动实时重组 |
| 服务器 | `data/ServerStore.kt` 兼容 Aurora `servers_v1` 语义，并迁移旧版 xterm `sshterm_hosts` 数据 |

## 技术栈与版本

版本必须与 termlib 0.3.14 对齐，否则 Compose 运行时会二进制不兼容。

| 组件 | 版本 |
| --- | --- |
| AGP / Gradle / Kotlin | 9.4.1 / 9.8.0 / 2.4.21（AGP 9 内置 Kotlin） |
| Compose BOM | 2026.09.00 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 24 |
| org.connectbot:termlib | 0.3.14 |
| org.connectbot:sshlib | 2.2.23 |
| NDK / CMake | 27.x / 3.22.1（编译 `libxtermpty.so`） |

新增依赖：`androidx.compose.material:material-icons-extended`（页面图标 1:1 对齐 Aurora，版本由 BOM 管理）。

## 构建

本地：

```bash
# 需要 JDK 17+、Android SDK Platform 37 + Build-Tools 37.0.0、NDK 与 CMake 3.22.1
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

沙箱内若 SDK 平台目录名为 `android-37.0`，而 AGP 查找 `android-37`，加一个软链即可：

```bash
ln -s "$ANDROID_HOME/platforms/android-37.0" "$ANDROID_HOME/platforms/android-37"
```

国内网络若 `services.gradle.org` 下载缓慢，把 `gradle/wrapper/gradle-wrapper.properties` 的
`distributionUrl` 换成腾讯镜像（本项目在沙箱内即用此方式构建）：

```properties
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-9.8.0-bin.zip
```

内存受限环境（如 8GB cgroup）建议保持 `gradle.properties` 里的
`kotlin.compiler.execution.strategy=in-process` 与 `-Xmx2560m`，避免多 JVM 守护进程叠加被 OOM 杀掉。

### Linux（proot）终端

`libproot.so` / `libproot-loader.so` 与 Aurora 一样不进仓库，由构建脚本生成到
`app/src/main/jniLibs/<abi>/`：

```bash
# 脚本原本面向 Flutter 工程，默认输出到 android/app/src/main/jniLibs/...，
# 本工程是原生 Android 结构，必须覆盖：
PROOT_OUT_DIR="$PWD/app/src/main/jniLibs/arm64-v8a" ./scripts/build-proot-android.sh
```

脚本需要 NDK 交叉编译工具链。CI 中已通过 `PROOT_OUT_DIR` 覆盖输出路径，并把该步骤标记为
`continue-on-error`：脚本失败也照常产出 APK，此时 Linux 终端仍会出现在首页，进入时会给出
「缺少 proot」的明确提示，不会静默降级。

> `packaging.jniLibs.useLegacyPackaging = true` 与 `android:extractNativeLibs="true"` 是必需的：
> proot 需要按 `nativeLibraryDir` 里的绝对路径 `exec`，.so 必须真实解压到磁盘。

CI：`.github/workflows/release.yml` — push 或手动触发时构建 **arm64-v8a release APK**（R8 精简 +
资源收缩），配置了签名 Secrets 则自动签名，并创建 GitHub Release 上传 APK 与 `mapping.txt`。

### Release 签名 Secrets

在仓库 **Settings → Secrets and variables → Actions** 添加 4 个 secret：
`KEYSTORE_BASE64`（`base64 -w0 release.keystore`）、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。
不配置时 workflow 仍可运行，产出 unsigned APK。

## 目录结构

```
app/src/main/
├── cpp/                     pty.c / CMakeLists.txt（JNI 真 PTY）
├── java/com/xterm/
│   ├── MainActivity.kt      入口：LinuxEnv.init → SettingsStore.init → App
│   ├── data/                Server / ServerStore / SettingsStore / VirtKeyDef /
│   │                        RemoteFileEntry / SysInfoModels / Crypto
│   ├── ssh/                 SshConnector / SshSession / SftpSession / SysInfoSession /
│   │                        LocalShellSession / LinuxEnv / PtyBridge /
│   │                        TerminalConnection / ConnModels
│   └── ui/                  App(导航) / MainViewModel(状态) / Theme / Components /
│                            HomePage / ServerEditPage / SettingsPage /
│                            VirtKeysSettingsPage / VirtKeysBar / TerminalPage /
│                            SftpPage / SftpEditPage / SysInfoPage /
│                            VirtKeyInputHandler
├── res/                     图标、字体、颜色、主题
└── AndroidManifest.xml
```

## 数据流

```
软键盘 / 虚拟按键 → TerminalEmulator.dispatchKey / writeInput → onKeyboardInput
  → VirtKeyInputHandler.transform（粘滞 Ctrl/Alt/Shift）→ TerminalConnection.write → SSH/PTY stdin
SSH stdout / PTY master → onOutput → TerminalEmulator.writeInput → libvterm → Terminal(Compose)
Terminal 尺寸变化 → onResize(TerminalDimensions) → TerminalConnection.resize → SSH window-change
```

## 与 Aurora 的已知差异

| 项 | 说明 |
| --- | --- |
| 主机密钥 | 多一层 TOFU 确认弹窗（xterm 原有安全能力），其余流程不变 |
| 凭据 | 落盘 AES-256-GCM 加密（Aurora 为明文 JSON） |
| 选区手柄 | 使用 termlib 内置的长按选区 / 手柄 / 复制全选粘贴工具条，而非 Aurora 的自绘 `SelectionHandles` |
| 文件下载 | 保存到 `getExternalFilesDir(DIRECTORY_DOWNLOADS)/aurora_downloads/`（对标 Aurora 的 `应用文档目录/aurora_downloads`），不弹系统保存框 |
| 文件上传 | 用系统文件选择器（SAF，支持多选）替代 `file_picker` |
| proot 二进制 | 同 Aurora，不进仓库，需构建时生成 |

## 本次交付的验证状态

| 检查项 | 结果 |
| --- | --- |
| 8 个页面 + 控件、导航栈、ViewModel 公开 API 交叉引用 | ✅ 调用点全部有定义，签名一致 |
| termlib 0.3.14 / sshlib 2.2.23 API 逐项核对（官方 sources jar） | ✅ `Terminal(...)`、`TerminalEmulatorFactory.create(...)`、`applyColorScheme`、`dispatchKey`、`pasteText`、`SFTPv3Client`/`SFTPv3DirectoryEntry`/`SFTPv3FileAttributes`、`ExtendedServerHostKeyVerifier`、`KnownHosts` 全部匹配 |
| Kotlin 语法解析（31 个 `.kt`） | ✅ 0 语法错误、0 括号/字符串不平衡 |
| `cpp/pty.c` 语法检查（`gcc -fsyntax-only -Wall` + JNI 头文件） | ✅ 无错误 |
| 完整 Gradle `assembleDebug` / `assembleRelease` | ⚠️ **未执行**：当前沙箱无 Android SDK（无 `ANDROID_HOME`、无 Platform 37 / Build-Tools / NDK / CMake），不具备编译条件 |

> Kotlin 诊断日志里出现的 `unresolved reference 'androidx'`、`overrides nothing`、
> `argument type mismatch (String→String)` 等，都是缺少 Android / Compose / coroutines
> classpath 造成的连锁伪类型错误，不是源码缺陷；真实发现并已修复的类型问题是
> `SysInfoPage.kt` 的 `0L` 与 `TerminalPage.kt` 的显式可空判断。
>
> 请在装有 JDK 17+、Android SDK Platform 37 / Build-Tools 37、NDK 与 CMake 3.22.1 的机器上
> 执行 `./gradlew assembleDebug`，或直接推送触发 `.github/workflows/release.yml`（CI 已配置
> NDK / CMake 与 proot 构建步骤）。

## 已知限制

- **无后台保活**：App 退到后台且进程被系统回收时连接会断开（未做前台 Service）。
- keyboard-interactive 目前用同一密码应答所有提示，真正的 2FA/多因素需扩展交互 UI。
- 私钥不支持 OpenSSH 新格式 `BEGIN OPENSSH PRIVATE KEY`，需先 `ssh-keygen -p -m PEM -f ~/.ssh/id_rsa` 转换。
- Linux 终端依赖 `libproot.so` / `libproot-loader.so`，未构建时不可用。

## 致谢与许可

- [connectbot/termlib](https://github.com/connectbot/termlib) — Apache-2.0
- [connectbot/sshlib](https://github.com/connectbot/sshlib) — BSD-3-Clause
- 页面与交互设计参考 Aurora（Flutter/Dart）
- 本项目代码可按需要自由使用。
