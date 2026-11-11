package io.github.randomtools3377.keeppageloading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressTest {
    @Test
    fun aBareNameGetsHttps() {
        assertEquals("https://example.com", Address.fromTyped("example.com"))
        assertEquals("https://example.com/a/b?c=d", Address.fromTyped("  example.com/a/b?c=d  "))
        assertEquals("https://www.example.co.uk:8443/x", Address.fromTyped("www.example.co.uk:8443/x"))
    }

    @Test
    fun anAddressWithHttpOrHttpsIsKeptAsTyped() {
        assertEquals("http://192.168.1.20:8080/", Address.fromTyped("http://192.168.1.20:8080/"))
        assertEquals("HTTPS://Example.com", Address.fromTyped("HTTPS://Example.com"))
        assertEquals("http://localhost:3000", Address.fromTyped("http://localhost:3000"))
    }

    @Test
    fun thingsThatAreNotWebAddressesAreRefused() {
        assertNull(Address.fromTyped(""))
        assertNull(Address.fromTyped("   "))
        assertNull(Address.fromTyped("two words"))
        assertNull(Address.fromTyped("justaword"))
        assertNull(Address.fromTyped("file:///sdcard/secret.txt"))
        assertNull(Address.fromTyped("ftp://example.com"))
        assertNull(Address.fromTyped("javascript:alert(1)"))
        assertNull(Address.fromTyped("https://"))
        assertNull(Address.fromTyped("https://.example.com"))
        assertNull(Address.fromTyped("example..com"))
    }

    @Test
    fun webAddressesAreRecognised() {
        assertTrue(Address.isWebAddress("https://example.com/"))
        assertTrue(Address.isWebAddress("http://[::1]:8080/"))
        assertFalse(Address.isWebAddress("about:blank"))
        assertFalse(Address.isWebAddress("data:text/html,hi"))
    }

    @Test
    fun theNotificationShowsJustTheSiteName() {
        assertEquals("example.com", Address.hostOf("https://www.example.com/some/page"))
        assertEquals("sub.example.com", Address.hostOf("https://sub.example.com"))
        assertNull(Address.hostOf(null))
    }
}
