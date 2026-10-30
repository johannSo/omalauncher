package dev.omalauncher.pp.linux

import android.os.Build
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** A listening TCP socket: what it's bound to and its inode, which /proc/<pid>/fd links name. */
data class TcpListener(
    val address: InetAddress,
    val port: Int,
    val inode: Long,
)

/** /proc/net/tcp and /proc/net/tcp6, as the kernel writes them. */
object ProcNetTcp {
    private const val LISTEN = "0A"

    /**
     * The listening sockets in [text], either file. Each line is
     * `sl local_address rem_address st tx_queue:rx_queue tr:tm->when retrnsmt uid timeout inode …`, the local
     * address as hex `ADDR:PORT`: the port big-endian, the address as 32-bit words in the host's byte order,
     * little-endian on every phone.
     */
    fun parse(text: String): List<TcpListener> =
        text.lineSequence().drop(1).mapNotNull { line ->
            val f = line.trim().split(Regex("\\s+"))
            if (f.size < 10 || f[3] != LISTEN) return@mapNotNull null
            val (hexAddr, hexPort) = f[1].split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val address = address(hexAddr) ?: return@mapNotNull null
            val port = hexPort.toIntOrNull(16) ?: return@mapNotNull null
            val inode = f[9].toLongOrNull() ?: return@mapNotNull null
            TcpListener(address, port, inode)
        }.toList()

    private fun address(hex: String): InetAddress? {
        if (hex.length != 8 && hex.length != 32) return null
        val bytes = ByteArray(hex.length / 2)
        for (word in 0 until hex.length / 8) {
            for (b in 0 until 4) {
                // Byte b of the word is written last-first.
                val at = word * 8 + (3 - b) * 2
                bytes[word * 4 + b] = (hex.substring(at, at + 2).toIntOrNull(16) ?: return null).toByte()
            }
        }
        // A v4-mapped address (::ffff:a.b.c.d) comes back as the Inet4Address it is.
        return runCatching { InetAddress.getByAddress(bytes) }.getOrNull()
    }
}

/** One listening socket found in the guest, with the process holding it if it's known. */
data class GuestSocket(
    val address: InetAddress,
    val port: Int,
    val process: GuestProcess?,
)

/**
 * A port the guest listens on, from every socket on it (a server often has one for IPv4 and one for IPv6).
 * [onLan]: bound to every address, so other devices reach it at the phone's address. Otherwise only at
 * [addresses], usually the loopback, which only this phone reaches.
 */
data class LocalServer(
    val port: Int,
    val process: GuestProcess?,
    val onLan: Boolean,
    val addresses: List<InetAddress>,
) {
    /** Only this phone reaches it. */
    val loopbackOnly get() = !onLan && addresses.all { it.isLoopbackAddress }

    /** Where a LAN share connects to: IPv4's loopback if it's on that, else IPv6's. */
    val loopback: InetAddress get() = addresses.firstOrNull { it is Inet4Address && it.isLoopbackAddress } ?: addresses.first { it.isLoopbackAddress }
}

/** [sockets] by port, lowest first; a port's process is its lowest pid's, the parent of a forking server. */
fun localServers(sockets: List<GuestSocket>): List<LocalServer> =
    sockets.groupBy { it.port }.toSortedMap().map { (port, on) ->
        LocalServer(
            port = port,
            process = on.mapNotNull { it.process }.minByOrNull { it.pid },
            onLan = on.any { it.address.isAnyLocalAddress },
            addresses = on.map { it.address }.distinct(),
        )
    }

/** pidfd_getfd from cpp/netports.c, in the wlbridge library. */
object NetPorts {
    init {
        System.loadLibrary("wlbridge")
    }

    /** A line `i address port` for each of the sockets `pids[i]`/`fds[i]` that listens; null if none could be looked at. */
    @JvmStatic external fun listening(
        pids: IntArray,
        fds: IntArray,
    ): String?
}

/**
 * The TCP ports guest processes listen on, as [localServers]: dev servers, databases, sshd. proot shares
 * Android's network, so each is on the phone's localhost too, and on its LAN addresses when bound to all.
 * Null when Android doesn't let us find out.
 *
 * /proc/net/tcp is the simple way, but apps targeting Android 10+ can't read it. Then each guest socket is
 * borrowed with pidfd_getfd (Android 12+) and asked what it's bound to.
 */
fun guestServers(loader: String): List<LocalServer>? {
    val processes = guestProcesses(loader)
    val fds = processes.mapNotNull { p -> socketFds(p.pid)?.let { p.pid to it } }.toMap()
    // Their open files are hidden from us too: nothing to go on.
    if (fds.isEmpty() && processes.isNotEmpty()) return null
    val owner = HashMap<Long, GuestProcess>()
    for (p in processes) for (inode in fds[p.pid].orEmpty().values) owner.putIfAbsent(inode, p)
    val table =
        listOf("tcp", "tcp6").mapNotNull { name ->
            runCatching { File("/proc/net/$name").readText() }.getOrNull()?.let(ProcNetTcp::parse)
        }
    if (table.isNotEmpty()) {
        // Every socket in the phone's network is listed: keep the guest's.
        return localServers(table.flatten().mapNotNull { s -> owner[s.inode]?.let { GuestSocket(s.address, s.port, it) } })
    }
    // Before Android 12 the app's seccomp filter kills the process at pidfd_open.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    // One socket shared by a forking server's processes is looked at once.
    val sockets = owner.entries.mapNotNull { (inode, p) -> fds[p.pid]?.entries?.firstOrNull { it.value == inode }?.let { p to it.key } }.sortedBy { it.first.pid }
    val out =
        runCatching { NetPorts.listening(sockets.map { it.first.pid }.toIntArray(), sockets.map { it.second }.toIntArray()) }
            .onFailure { Log.w("ports", "pidfd_getfd failed", it) }
            .getOrNull() ?: return null
    return localServers(
        out.lines().mapNotNull { line ->
            val (i, host, port) = line.split(' ').takeIf { it.size == 3 } ?: return@mapNotNull null
            val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return@mapNotNull null
            GuestSocket(address, port.toIntOrNull() ?: return@mapNotNull null, sockets.getOrNull(i.toIntOrNull() ?: -1)?.first)
        },
    )
}

/** [address] as a URL's host: IPv6 in brackets. */
fun urlHost(address: InetAddress): String =
    when (address) {
        is Inet6Address -> "[" + address.hostAddress.orEmpty().substringBefore('%') + "]"
        is Inet4Address -> address.hostAddress.orEmpty()
        else -> address.hostAddress.orEmpty()
    }
