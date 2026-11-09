/*
 * 本地伪终端（PTY）后端。
 *
 * Aurora 的「本机 / Linux」入口用 flutter_pty 打开真 PTY，因此有提示符、
 * 可 resize、交互程序可用。xterm 是原生 Compose 工程，这里用 /dev/ptmx +
 * fork/exec 提供等价能力（Termux / proot-distro 同款做法）。
 *
 * 只暴露 master fd：读写、resize、kill、close，Kotlin 侧把字节流接到
 * termlib 的 TerminalEmulator（writeInput / onKeyboardInput）。
 */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>

/* asm-generic/ioctls.h 的值；NDK 的头文件不一定导出，显式兜底。 */
#ifndef TIOCSPTLCK
#define TIOCSPTLCK 0x40045431
#endif
#ifndef TIOCGPTN
#define TIOCGPTN 0x80045430
#endif
#ifndef TIOCSCTTY
#define TIOCSCTTY 0x540E
#endif
#ifndef TIOCSWINSZ
#define TIOCSWINSZ 0x5414
#endif

#define MAX_PTY 8

static struct pty_rec {
    int fd;
    pid_t pid;
} g_records[MAX_PTY];

static void records_init(void) {
    static int initialized = 0;
    if (!initialized) {
        for (int i = 0; i < MAX_PTY; i++) g_records[i].fd = -1;
        initialized = 1;
    }
}

static void reg(int fd, pid_t pid) {
    records_init();
    for (int i = 0; i < MAX_PTY; i++) {
        if (g_records[i].fd < 0) {
            g_records[i].fd = fd;
            g_records[i].pid = pid;
            return;
        }
    }
}

static pid_t find_pid(int fd) {
    records_init();
    for (int i = 0; i < MAX_PTY; i++) {
        if (g_records[i].fd == fd) return g_records[i].pid;
    }
    return -1;
}

static void unreg(int fd) {
    records_init();
    for (int i = 0; i < MAX_PTY; i++) {
        if (g_records[i].fd == fd) {
            g_records[i].fd = -1;
            g_records[i].pid = -1;
            return;
        }
    }
}

static char **jni_string_array(JNIEnv *env, jobjectArray arr) {
    jsize n = (*env)->GetArrayLength(env, arr);
    char **out = (char **)calloc((size_t)(n + 1), sizeof(char *));
    if (!out) return NULL;
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, arr, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = c ? strdup(c) : strdup("");
        if (c) (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
    }
    return out;
}

static void free_string_array(char **arr) {
    if (!arr) return;
    for (int i = 0; arr[i]; i++) free(arr[i]);
    free(arr);
}

JNIEXPORT jint JNICALL
Java_com_xterm_ssh_PtyBridge_nativeStart(
        JNIEnv *env, jobject thiz,
        jobjectArray argv_, jobjectArray envp_, jstring cwd_,
        jint rows, jint cols) {
    (void)thiz;
    records_init();

    char **argv = jni_string_array(env, argv_);
    char **envp = jni_string_array(env, envp_);
    const char *cwd = (*env)->GetStringUTFChars(env, cwd_, NULL);
    if (!argv || !envp || !cwd || !argv[0]) {
        free_string_array(argv);
        free_string_array(envp);
        if (cwd) (*env)->ReleaseStringUTFChars(env, cwd_, cwd);
        return -1;
    }

    int master = open("/dev/ptmx", O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) goto fail;

    int unlock = 0;
    if (ioctl(master, TIOCSPTLCK, &unlock) < 0) goto fail_close_master;
    unsigned int ptn = 0;
    if (ioctl(master, TIOCGPTN, &ptn) < 0) goto fail_close_master;

    char slave_path[64];
    snprintf(slave_path, sizeof(slave_path), "/dev/pts/%u", ptn);
    int slave = open(slave_path, O_RDWR | O_NOCTTY);
    if (slave < 0) goto fail_close_master;

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short)(cols > 0 ? cols : 80);
    ioctl(slave, TIOCSWINSZ, &ws);

    pid_t pid = fork();
    if (pid < 0) {
        close(slave);
        goto fail_close_master;
    }

    if (pid == 0) {
        /* child：建立新的会话并把 slave 设为控制终端 */
        setsid();
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        close(master);

        for (int i = 0; envp[i]; i++) {
            char *eq = strchr(envp[i], '=');
            if (eq) {
                *eq = '\0';
                setenv(envp[i], eq + 1, 1);
                *eq = '=';
            }
        }
        if (chdir(cwd) != 0) chdir("/");
        execvp(argv[0], argv);
        _exit(127);
    }

    /* parent：只保留 master */
    close(slave);
    reg(master, pid);
    free_string_array(argv);
    free_string_array(envp);
    (*env)->ReleaseStringUTFChars(env, cwd_, cwd);
    return master;

fail_close_master:
    close(master);
fail:
    free_string_array(argv);
    free_string_array(envp);
    (*env)->ReleaseStringUTFChars(env, cwd_, cwd);
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_xterm_ssh_PtyBridge_nativeRead(
        JNIEnv *env, jobject thiz, jint fd, jbyteArray buf, jint len) {
    (void)thiz;
    if (len <= 0) return 0;
    unsigned char *tmp = (unsigned char *)malloc((size_t)len);
    if (!tmp) return -1;
    ssize_t n = read(fd, tmp, (size_t)len);
    if (n > 0) (*env)->SetByteArrayRegion(env, buf, 0, (jsize)n, (const jbyte *)tmp);
    free(tmp);
    if (n < 0) return (errno == EINTR) ? 0 : -1;
    return (jint)n;
}

JNIEXPORT jint JNICALL
Java_com_xterm_ssh_PtyBridge_nativeWrite(
        JNIEnv *env, jobject thiz, jint fd, jbyteArray buf, jint len) {
    (void)thiz;
    if (len <= 0) return 0;
    unsigned char *tmp = (unsigned char *)malloc((size_t)len);
    if (!tmp) return -1;
    (*env)->GetByteArrayRegion(env, buf, 0, len, (jbyte *)tmp);
    ssize_t n = write(fd, tmp, (size_t)len);
    free(tmp);
    return n < 0 ? -1 : (jint)n;
}

JNIEXPORT void JNICALL
Java_com_xterm_ssh_PtyBridge_nativeResize(
        JNIEnv *env, jobject thiz, jint fd, jint rows, jint cols) {
    (void)env;
    (void)thiz;
    if (rows <= 0 || cols <= 0) return;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    ioctl(fd, TIOCSWINSZ, &ws);
    pid_t pid = find_pid(fd);
    if (pid > 0) kill(pid, SIGWINCH);
}

JNIEXPORT void JNICALL
Java_com_xterm_ssh_PtyBridge_nativeKill(
        JNIEnv *env, jobject thiz, jint fd, jint sig) {
    (void)env;
    (void)thiz;
    pid_t pid = find_pid(fd);
    if (pid > 0) kill(pid, sig);
}

JNIEXPORT void JNICALL
Java_com_xterm_ssh_PtyBridge_nativeCloseFd(
        JNIEnv *env, jobject thiz, jint fd) {
    (void)env;
    (void)thiz;
    pid_t pid = find_pid(fd);
    close(fd);
    unreg(fd);
    if (pid > 0) {
        int status = 0;
        waitpid(pid, &status, WNOHANG);
    }
}

JNIEXPORT jint JNICALL
Java_com_xterm_ssh_PtyBridge_nativePid(
        JNIEnv *env, jobject thiz, jint fd) {
    (void)env;
    (void)thiz;
    return (jint)find_pid(fd);
}
