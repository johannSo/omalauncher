package dev.omalauncher.pp.linux

import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Puts a guest server that only listens on localhost (most dev servers' default) on the LAN: the launcher
 * listens on the phone's LAN addresses at the same port and passes each connection through to localhost.
 * A specific address doesn't clash with the server's loopback one, so the URL keeps the server's port.
 * Shares last until stopped or until Linux shuts down.
 */
class LanShares {
    /** Port to the URLs it's shared at. */
    val shared = mutableStateMapOf<Int, List<String>>()

    private val open = HashMap<Int, List<Closeable>>()

    /**
     * Shares [port], reached at [target] (127.0.0.1 or ::1), on [addresses]. Returns the URLs, empty if no
     * address could be listened on: the port is taken there.
     */
    @Synchronized
    fun share(
        port: Int,
        target: InetAddress,
        addresses: List<InetAddress>,
    ): List<String> {
        stop(port)
        val servers =
            addresses.mapNotNull { address ->
                runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(address, port)) } }
                    .onFailure { Log.w(TAG, "can't listen on $address:$port", it) }
                    .getOrNull()
                    ?.let { address to it }
            }
        for ((_, server) in servers) thread(name = "lan-share-$port", isDaemon = true) { accept(server, target, port) }
        open[port] = servers.map { it.second }
        val urls = servers.map { (address, _) -> "http://${urlHost(address)}:$port" }
        if (urls.isNotEmpty()) shared[port] = urls
        return urls
    }

    @Synchronized
    fun stop(port: Int) {
        open.remove(port)?.forEach { runCatching { it.close() } }
        shared.remove(port)
    }

    @Synchronized
    fun stopAll() {
        for (port in open.keys.toList()) stop(port)
    }

    private fun accept(
        server: ServerSocket,
        target: InetAddress,
        port: Int,
    ) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            thread(name = "lan-share-$port", isDaemon = true) {
                val upstream = runCatching { Socket(target, port) }.getOrNull()
                if (upstream == null) {
                    client.close()
                    return@thread
                }
                thread(name = "lan-share-$port", isDaemon = true) { pump(client.getInputStream(), upstream.getOutputStream()) { runCatching { upstream.shutdownOutput() } } }
                pump(upstream.getInputStream(), client.getOutputStream()) { runCatching { client.shutdownOutput() } }
                // Both ways are done (or one failed): the other pump's read ends with these.
                runCatching { client.close() }
                runCatching { upstream.close() }
            }
        }
    }

    /** Copies until [from] ends, then [done] half-closes the other side, so the request or reply is complete. */
    private fun pump(
        from: InputStream,
        to: OutputStream,
        done: () -> Unit,
    ) {
        runCatching {
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        }
        done()
    }

    private companion object {
        const val TAG = "lan-share"
    }
}
