package dev.omalauncher.pp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkTest {
    @Test fun localAddresses() {
        for (url in listOf("wss://192.168.1.20:8759/stream", "http://10.0.2.2/", "https://172.16.0.1/a", "ws://169.254.1.1/", "wss://[fd12::1]:9443/", "ws://[fe80::1]/")) {
            assertTrue(url, LocalNetwork.isLocal(url))
        }
        for (url in listOf("https://1.1.1.1/", "wss://[2606:4700::1111]/", "ws://127.0.0.1:9001/", "not a url")) {
            assertFalse(url, LocalNetwork.isLocal(url))
        }
    }

    @Test fun reachableAddresses() {
        fun a(iface: String, host: String) = LanAddress(iface, java.net.InetAddress.getByName(host))
        val all =
            listOf(
                a("rmnet_data0", "10.120.4.7"),
                a("tun0", "100.101.102.103"),
                a("wlan0", "fd00::5"),
                a("wlan0", "fe80::1"),
                a("lo", "127.0.0.1"),
                a("wlan0", "192.168.1.5"),
                a("v4-rmnet_data0", "192.0.0.4"),
            )
        assertEquals(listOf("192.168.1.5", "fd00:0:0:0:0:0:0:5", "100.101.102.103"), LocalNetwork.reachable(all).map { it.host })
    }
}
