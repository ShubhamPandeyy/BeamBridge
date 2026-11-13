package app.beambridge.core

import app.beambridge.core.data.BridgeSettings
import app.beambridge.core.mdns.Dns
import app.beambridge.core.mdns.HostNames
import app.beambridge.core.mdns.MdnsResponder
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MdnsTest {
    private val ip = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 50)) as Inet4Address
    private val responder = MdnsResponder("remote", ip)

    @Test fun answersAQuestionForOurName() {
        val out = responder.reply(Dns.query("remote.local", Dns.TYPE_A))
        assertNotNull(out)
        val m = Dns.parse(out)!!
        assertTrue(m.isResponse)
        assertEquals("remote.local", m.answers.single().name)
        assertContentEquals(ip.address, m.answers.single().data)
    }

    @Test fun nameMatchingIgnoresCaseAndAnyType() {
        assertNotNull(responder.reply(Dns.query("REMOTE.Local", Dns.TYPE_ANY)))
    }

    @Test fun ignoresOtherNamesOtherTypesAndAnswers() {
        assertNull(responder.reply(Dns.query("printer.local", Dns.TYPE_A)))
        assertNull(responder.reply(Dns.query("remote.local", 28))) // AAAA: not answered
        assertNull(responder.reply(Dns.response("remote.local", ip.address)))
        assertNull(responder.reply(byteArrayOf(1, 2, 3)))
    }

    @Test fun parsesCompressedNames() {
        // Question "remote.local", then an answer whose name is a pointer (0xC00C) back to it.
        val q = Dns.query("remote.local", Dns.TYPE_A)
        val hdr = q.copyOf(12).also { it[7] = 1 }
        val body = q.copyOfRange(12, q.size)
        val rr = byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 120, 0, 4, 10, 0, 0, 7)
        val m = Dns.parse(hdr + body + rr)!!
        assertEquals("remote.local", m.answers.single().name)
        assertContentEquals(byteArrayOf(10, 0, 0, 7), m.answers.single().data)
    }

    @Test fun hostNameRules() {
        assertEquals("remote", HostNames.sanitize("  "))
        assertEquals("my-tv", HostNames.sanitize("My TV.local"))
        assertEquals("a-b", HostNames.sanitize("a_b"))
        assertTrue(HostNames.isValid("remote") && HostNames.isValid("remote-2"))
        assertTrue(!HostNames.isValid("-bad") && !HostNames.isValid("Bad") && !HostNames.isValid(""))
        assertNull(BridgeSettings().validate())
        assertEquals("Name: 1-32 letters, digits or dashes", BridgeSettings(hostName = "no spaces").validate())
    }

    @Test fun defaultsAreRemoteLocalOn6969() {
        val s = BridgeSettings()
        assertEquals(6969, s.port); assertEquals("remote", s.hostName); assertTrue(s.useHostName)
    }
}
