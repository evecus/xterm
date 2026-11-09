# sshlib（trilead-ssh2 血统）内部有反射与加密 provider 加载，整体保留
-keep class com.trilead.ssh2.** { *; }
-dontwarn com.trilead.ssh2.**

# termlib 的 JNI 回调方法名不能被混淆
-keep class org.connectbot.terminal.** { *; }
-dontwarn org.connectbot.terminal.**

# 本机 PTY 的 JNI 方法按 Java_com_xterm_ssh_PtyBridge_* 查找，必须保留
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.xterm.ssh.PtyBridge { *; }

# sshlib 依赖 BouncyCastle / Conscrypt 时按名字反射加载，避免 R8 报缺省警告
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Compose 运行时
-dontwarn androidx.compose.**

# kotlinx.coroutines（若启用 R8 时有元数据警告）
-dontwarn kotlinx.coroutines.**

# 保留行号便于 release 崩溃堆栈还原（配合 mapping 文件）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
