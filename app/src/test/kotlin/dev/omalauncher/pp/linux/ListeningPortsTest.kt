package dev.omalauncher.pp.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class ListeningPortsTest {
    private val tcp =
        """
          sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
           0: 0100007F:0BB8 00000000:0000 0A 00000000:00000000 00:00000000 00000000  10234        0 21067074 1 0000000000000000 100 0 0 10 0
           1: 00000000:1F56 00000000:0000 0A 00000000:00000000 00:00000000 00000000  10234        0 106459729 1 0000000000000000 100 0 0 10 0
           2: 0501A8C0:1F56 0201A8C0:D431 01 00000000:00000000 00:00000000 00000000  10234        0 106459999 1 0000000000000000 100 0 0 10 0
        """.trimIndent()

    private val tcp6 =
        """
          sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
           0: 0000000000000000FFFF00000100007F:8DB7 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 94562013 1 0000000000000000 100 0 0 10 0
           1: 00000000000000000000000001000000:0BB9 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 94562012 1 0000000000000000 100 0 0 10 0
           2: 00000000000000000000000000000000:1F56 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 94562014 1 0000000000000000 100 0 0 10 0
        """.trimIndent()

    @Test fun parsesIpv4Listeners() {
        val listeners = ProcNetTcp.parse(tcp)
        // The established connection (state 01) isn't a server.
        assertEquals(
            listOf(
                TcpListener(InetAddress.getByName("127.0.0.1"), 3000, 21067074),
                TcpListener(InetAddress.getByName("0.0.0.0"), 8022, 106459729),
            ),
            listeners,
        )
    }

    @Test fun parsesIpv6Listeners() {
        val listeners = ProcNetTcp.parse(tcp6)
        assertEquals(InetAddress.getByName("127.0.0.1"), listeners[0].address) // v4-mapped
        assertEquals(36279, listeners[0].port)
        assertEquals(InetAddress.getByName("::1"), listeners[1].address)
        assertEquals(3001, listeners[1].port)
        assertTrue(listeners[2].address.isAnyLocalAddress)
        assertEquals(94562014L, listeners[2].inode)
    }

    @Test fun skipsJunk() {
        assertTrue(ProcNetTcp.parse("").isEmpty())
        assertTrue(ProcNetTcp.parse("header\n   0: XYZ:0BB8 00000000:0000 0A 0 0 0 0 0 1\n   1: 0100007F 0 0A 0 0 0 0 0 0 1").isEmpty())
    }

    @Test fun groupsByPort() {
        val node = GuestProcess(120, listOf("/usr/bin/node", "/root/app/node_modules/.bin/vite"))
        val worker = GuestProcess(130, listOf("/usr/bin/node", "worker.js"))
        val db = GuestProcess(90, listOf("postgres"))
        val servers =
            localServers(
                listOf(
                    GuestSocket(InetAddress.getByName("::1"), 5173, worker),
                    GuestSocket(InetAddress.getByName("127.0.0.1"), 5173, node),
                    GuestSocket(InetAddress.getByName("0.0.0.0"), 5432, db),
                    GuestSocket(InetAddress.getByName("::"), 5432, db),
                    GuestSocket(InetAddress.getByName("192.168.1.5"), 9000, null),
                ),
            )
        assertEquals(listOf(5173, 5432, 9000), servers.map { it.port })
        val vite = servers[0]
        assertEquals(node, vite.process)
        assertEquals("node vite", vite.process!!.label)
        assertFalse(vite.onLan)
        assertTrue(vite.loopbackOnly)
        assertEquals(listOf("::1", "127.0.0.1").map(InetAddress::getByName), vite.addresses)
        assertEquals(InetAddress.getByName("127.0.0.1"), vite.loopback)
        assertTrue(servers[1].onLan)
        assertFalse(servers[1].loopbackOnly)
        assertFalse(servers[2].onLan)
        assertFalse(servers[2].loopbackOnly)
    }

    @Test fun processLabels() {
        assertEquals("sshd", processLabel(listOf("/usr/bin/sshd", "-D")))
        assertEquals("python3 http.server", processLabel(listOf("python3", "-m", "http.server", "8000")))
        assertEquals("node server.js", processLabel(listOf("node", "--watch", "/srv/server.js")))
        assertEquals("node", processLabel(listOf("node")))
        assertEquals("?", processLabel(emptyList()))
    }

    @Test fun urlHosts() {
        assertEquals("192.168.1.5", urlHost(InetAddress.getByName("192.168.1.5")))
        assertEquals("[fd12:0:0:0:0:0:0:1]", urlHost(InetAddress.getByName("fd12::1")))
    }
}
