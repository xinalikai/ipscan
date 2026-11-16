package com.example.ipscan

import com.example.ipscan.net.WebProbe
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebTitleTest {

    @Test
    fun `loading placeholders are not device names`() {
        assertTrue(WebProbe.isGenericTitle("Opening..."))
        assertTrue(WebProbe.isGenericTitle("Loading…"))
        assertTrue(WebProbe.isGenericTitle("Login"))
        assertTrue(WebProbe.isGenericTitle("Welcome to nginx!"))
        assertTrue(WebProbe.isGenericTitle("192.168.0.1"))
        assertTrue(WebProbe.isGenericTitle("   "))
    }

    @Test
    fun `real device titles are kept`() {
        assertFalse(WebProbe.isGenericTitle("TP-LINK Archer C24"))
        assertFalse(WebProbe.isGenericTitle("Synology DiskStation"))
        assertFalse(WebProbe.isGenericTitle("Hikvision IP Camera"))
        assertFalse(WebProbe.isGenericTitle("Brother MFC-J480DW"))
    }
}
