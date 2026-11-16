package com.example.ipscan

import com.example.ipscan.net.parseCidr
import com.example.ipscan.net.toIpv4OrNull
import com.example.ipscan.net.toIpv4String
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ipv4Test {

    @Test
    fun `cidr builds host range`() {
        val subnet = parseCidr("192.168.1.0/24")!!
        assertEquals(256L, subnet.addressCount)
        val hosts = subnet.hostAddresses()
        assertEquals(254, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
    }

    @Test
    fun `network address is normalized`() {
        assertEquals("192.168.1.0/24", parseCidr("192.168.1.77/24")!!.cidr)
    }

    @Test
    fun `mask defaults to slash 24`() {
        assertEquals("10.0.0.0/24", parseCidr("10.0.0.5")!!.cidr)
    }

    @Test
    fun `invalid input is rejected`() {
        assertNull(parseCidr("192.168.1.0/31"))
        assertNull(parseCidr("192.168.1.0/7"))
        assertNull(parseCidr("192.168.1.256/24"))
        assertNull(parseCidr("not an ip"))
        assertNull(parseCidr(""))
        assertNull("1.2.3".toIpv4OrNull())
    }

    @Test
    fun `host list respects its limit`() {
        val hosts = parseCidr("10.0.0.0/16")!!.hostAddresses(limit = 100)
        assertEquals(100, hosts.size)
    }

    @Test
    fun `addresses round trip`() {
        val value = "192.168.0.103".toIpv4OrNull()!!
        assertEquals("192.168.0.103", value.toIpv4String())
    }
}
