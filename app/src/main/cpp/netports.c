// Which of the guest's sockets are listening TCP sockets, and on what address and port
// (linux/NetPorts.kt). Apps targeting Android 10+ may not read /proc/net/tcp, and ss's
// sock_diag netlink is closed to them too; but guest processes are our own, so
// pidfd_getfd (Linux 5.6, allowed to apps from Android 12) can borrow each socket for a
// getsockname.
#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/syscall.h>

#ifndef __NR_pidfd_open
#define __NR_pidfd_open 434
#endif
#ifndef __NR_pidfd_getfd
#define __NR_pidfd_getfd 438
#endif

// pids[i] and fds[i] name one socket. Returns a line "i address port" per listening TCP
// socket, or null when the kernel can't lend sockets (no pidfd_getfd, or every try denied).
JNIEXPORT jstring JNICALL
Java_dev_omalauncher_pp_linux_NetPorts_listening(JNIEnv *env, jclass cls, jintArray jpids, jintArray jfds) {
    (void) cls;
    jsize n = (*env)->GetArrayLength(env, jpids);
    if ((*env)->GetArrayLength(env, jfds) != n) return NULL;
    jint *pids = (*env)->GetIntArrayElements(env, jpids, NULL);
    jint *fds = (*env)->GetIntArrayElements(env, jfds, NULL);
    // A line is at most 10 + 1 + INET6_ADDRSTRLEN + 1 + 5 + 1 characters.
    size_t cap = (size_t) n * 72 + 1, len = 0;
    char *out = calloc(cap, 1);
    int lent = 0, unsupported = 0;
    int pidfd = -1, pidfd_of = -1;
    for (jsize i = 0; i < n && out != NULL && !unsupported; i++) {
        if (pids[i] != pidfd_of) {
            if (pidfd >= 0) close(pidfd);
            pidfd_of = pids[i];
            pidfd = (int) syscall(__NR_pidfd_open, pids[i], 0);
            if (pidfd < 0 && errno == ENOSYS) unsupported = 1;
        }
        if (pidfd < 0) continue;
        int fd = (int) syscall(__NR_pidfd_getfd, pidfd, fds[i], 0);
        if (fd < 0) {
            if (errno == ENOSYS) unsupported = 1;
            continue;
        }
        lent++;
        int listening = 0, type = 0;
        socklen_t sl = sizeof listening;
        getsockopt(fd, SOL_SOCKET, SO_ACCEPTCONN, &listening, &sl);
        sl = sizeof type;
        getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &sl);
        struct sockaddr_storage addr;
        socklen_t al = sizeof addr;
        if (listening && type == SOCK_STREAM && getsockname(fd, (struct sockaddr *) &addr, &al) == 0) {
            char host[INET6_ADDRSTRLEN] = "";
            int port = -1;
            if (addr.ss_family == AF_INET) {
                struct sockaddr_in *a = (struct sockaddr_in *) &addr;
                inet_ntop(AF_INET, &a->sin_addr, host, sizeof host);
                port = ntohs(a->sin_port);
            } else if (addr.ss_family == AF_INET6) {
                struct sockaddr_in6 *a = (struct sockaddr_in6 *) &addr;
                inet_ntop(AF_INET6, &a->sin6_addr, host, sizeof host);
                port = ntohs(a->sin6_port);
            }
            if (port >= 0) len += (size_t) snprintf(out + len, cap - len, "%d %s %d\n", (int) i, host, port);
        }
        close(fd);
    }
    if (pidfd >= 0) close(pidfd);
    (*env)->ReleaseIntArrayElements(env, jpids, pids, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, jfds, fds, JNI_ABORT);
    jstring result = NULL;
    if (out != NULL && !unsupported && (lent > 0 || n == 0)) result = (*env)->NewStringUTF(env, out);
    free(out);
    return result;
}
