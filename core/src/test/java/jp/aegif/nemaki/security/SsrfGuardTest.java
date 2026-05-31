/*****************************************************************************
 Copyright (c) 2026 aegif.

 This file is part of NemakiWare.
 *****************************************************************************/
package jp.aegif.nemaki.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;

/**
 * Direct unit test for {@link SsrfGuard}. The same classification rules
 * are exercised transitively through {@code HttpWebhookDispatcherTest}
 * (54 cases) and {@code AdapterRegistryTest} (26 cases); this class
 * pins the extracted helper directly so the next consumer can read one
 * test file to understand the contract.
 *
 * <p>Cases are organized to match the {@code SsrfGuard} javadoc table:
 * JDK-classified predicates, IPv4 special-use ranges, IPv6 ULA, IPv6
 * transition formats (6 variants), and "regular public" addresses
 * that must NOT be over-blocked.
 */
public class SsrfGuardTest {

    // ─── JDK predicate reject categories ───

    @Test
    public void rejectsLoopback() throws Exception {
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("127.0.0.1")));
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("::1")));
    }

    @Test
    public void rejectsLinkLocal() throws Exception {
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("169.254.1.1")));
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("fe80::1")));
    }

    @Test
    public void rejectsSiteLocalRfc1918() throws Exception {
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("10.0.0.1")));
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("172.16.0.1")));
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("172.31.255.255")));
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("192.168.1.1")));
    }

    @Test
    public void rejectsAnyLocalAndMulticast() throws Exception {
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("0.0.0.0")));
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("::")));
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("224.0.0.1")));
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("ff02::1")));
    }

    // ─── IPv4 special-use ranges (RC6.6 additions) ───

    @Test
    public void rejectsThisNetworkSlash8() throws Exception {
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("0.1.2.3")));
    }

    @Test
    public void rejectsCgnatSlash10() throws Exception {
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("100.64.0.1")));
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("100.127.255.254")));
        // boundary: 100.63.x.x is public Hong Kong allocation
        assertTrue(SsrfGuard.isAddressSafe(InetAddress.getByName("100.63.255.255")));
        // boundary: 100.128.x.x is public US allocation
        assertTrue(SsrfGuard.isAddressSafe(InetAddress.getByName("100.128.0.1")));
    }

    @Test
    public void rejectsIetfProtocolSlash24() throws Exception {
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("192.0.0.1")));
    }

    @Test
    public void rejectsBenchmarkSlash15() throws Exception {
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("198.18.0.1")));
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("198.19.255.254")));
    }

    @Test
    public void rejectsReservedSlash4AndBroadcast() throws Exception {
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("240.0.0.1")));
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("255.255.255.255")));
    }

    // ─── IPv6 ULA ───

    @Test
    public void rejectsIpv6Ula() throws Exception {
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("fc00::1")));
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("fdab::1")));
    }

    // ─── IPv6 transition addresses — must unwrap + re-classify ───

    @Test
    public void rejectsNat64WellKnownWrappingPrivateIpv4() throws Exception {
        // 64:ff9b::a00:1 = NAT64-wrap of 10.0.0.1
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("64:ff9b::a00:1")));
    }

    @Test
    public void allowsNat64WellKnownWrappingPublicIpv4() throws Exception {
        // 64:ff9b::808:808 = NAT64-wrap of 8.8.8.8
        assertTrue(SsrfGuard.isAddressSafe(InetAddress.getByName("64:ff9b::808:808")));
    }

    @Test
    public void rejectsNat64LocalUseRfc6052Slash48WrappingPrivateIpv4() throws Exception {
        // 64:ff9b:1::/48 with RFC 6052 §2.2 layout: IPv4 = bytes 6-7 + 9-10.
        // 10.0.0.1 → bytes 6=0x0A, 7=0x00, 9=0x00, 10=0x01.
        // Expressed as 64:ff9b:1:a00:0:1::  (suffix bytes 11-15 = 0)
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("64:ff9b:1:a00:0:1::")));
    }

    @Test
    public void allowsNat64LocalUseRfc6052Slash48WrappingPublicIpv4() throws Exception {
        // 8.8.8.8 → bytes 6=0x08, 7=0x08, 9=0x08, 10=0x08.
        // 64:ff9b:1:808:8:800::
        assertTrue(SsrfGuard.isAddressSafe(InetAddress.getByName("64:ff9b:1:808:8:800::")));
    }

    @Test
    public void rejects6to4WrappingPrivateIpv4() throws Exception {
        // 2002::/16 with bytes 2-5 = 10.0.0.1
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("2002:a00:1::")));
    }

    @Test
    public void allows6to4WrappingPublicIpv4() throws Exception {
        // 2002:808:808:: = 6to4-wrap of 8.8.8.8
        assertTrue(SsrfGuard.isAddressSafe(InetAddress.getByName("2002:808:808::")));
    }

    @Test
    public void rejectsTeredoWrappingPrivateIpv4() throws Exception {
        // Teredo: bytes 12-15 = one's-complement client IPv4.
        // 10.0.0.1 = 0x0A,0x00,0x00,0x01 → ~ = 0xF5,0xFF,0xFF,0xFE
        // So address bytes 12-15 = F5 FF FF FE = "f5ff:fffe"
        InetAddress teredoPrivate = InetAddress.getByName(
                "2001:0:4136:e378:8000:63bf:f5ff:fffe");
        assertFalse(SsrfGuard.isAddressSafe(teredoPrivate));
    }

    @Test
    public void allowsTeredoWrappingPublicIpv4() throws Exception {
        // 8.8.8.8 → ~ = 0xF7,0xF7,0xF7,0xF7 → "f7f7:f7f7"
        InetAddress teredoPublic = InetAddress.getByName(
                "2001:0:4136:e378:8000:63bf:f7f7:f7f7");
        assertTrue(SsrfGuard.isAddressSafe(teredoPublic));
    }

    @Test
    public void rejectsIpv4MappedWrappingPrivateIpv4() throws Exception {
        // ::ffff:10.0.0.1 — the JDK might canonicalize this to Inet4Address,
        // in which case the IPv4 special-use branch catches it directly.
        // Either way, must return false.
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("::ffff:10.0.0.1")));
    }

    @Test
    public void rejectsIpv4CompatibleWrappingPrivateIpv4() throws Exception {
        // ::10.0.0.1 (IPv4-compatible, deprecated but parseable)
        assertFalse(SsrfGuard.isAddressSafe(InetAddress.getByName("::a00:1")));
    }

    // ─── Public addresses — must NOT be over-blocked ───

    @Test
    public void allowsPublicIpv4() throws Exception {
        assertTrue(SsrfGuard.isAddressSafe(InetAddress.getByName("8.8.8.8")));
        assertTrue(SsrfGuard.isAddressSafe(InetAddress.getByName("1.1.1.1")));
        // 172.15 + 172.32 are public (RFC 1918 only covers 172.16-31)
        assertTrue(SsrfGuard.isAddressSafe(InetAddress.getByName("172.15.0.1")));
        assertTrue(SsrfGuard.isAddressSafe(InetAddress.getByName("172.32.0.1")));
    }

    @Test
    public void allowsPublicIpv6() throws Exception {
        // Cloudflare public DNS — must not be mistaken for any transition format
        assertTrue(SsrfGuard.isAddressSafe(InetAddress.getByName("2606:4700:4700::1111")));
        // Google public DNS
        assertTrue(SsrfGuard.isAddressSafe(InetAddress.getByName("2001:4860:4860::8888")));
        // documentation prefix 2001:db8:: — not blocked (not in private ranges,
        // Teredo strict prefix excludes 2001:db8 because bytes 2-3 != 0)
        assertTrue(SsrfGuard.isAddressSafe(InetAddress.getByName("2001:db8::1")));
    }

    // ─── extractEmbeddedIpv4 direct tests ───

    @Test
    public void extractReturnsNullForRegularPublicIpv6() throws Exception {
        assertNull(SsrfGuard.extractEmbeddedIpv4(
                InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test
    public void extractReturnsNullForUla() throws Exception {
        // ULA is rejected by isAddressSafe via the firstByte check, not
        // via embedded-IPv4 unwrap.
        assertNull(SsrfGuard.extractEmbeddedIpv4(InetAddress.getByName("fc00::1")));
    }

    @Test
    public void extractReturnsNullFor2001DocPrefix() throws Exception {
        // Strict Teredo prefix (bytes 0-3 = 20:01:00:00) must NOT match
        // 2001:db8:: documentation addresses.
        assertNull(SsrfGuard.extractEmbeddedIpv4(InetAddress.getByName("2001:db8::1")));
    }

    @Test
    public void extractUnwrapsNat64WellKnown() throws Exception {
        InetAddress extracted = SsrfGuard.extractEmbeddedIpv4(
                InetAddress.getByName("64:ff9b::808:808"));
        assertNotNull(extracted);
        assertEquals("8.8.8.8", extracted.getHostAddress());
    }

    @Test
    public void extractUnwraps6to4() throws Exception {
        InetAddress extracted = SsrfGuard.extractEmbeddedIpv4(
                InetAddress.getByName("2002:808:808::"));
        assertNotNull(extracted);
        assertEquals("8.8.8.8", extracted.getHostAddress());
    }

    @Test
    public void extractUnwrapsTeredo() throws Exception {
        InetAddress extracted = SsrfGuard.extractEmbeddedIpv4(
                InetAddress.getByName("2001:0:4136:e378:8000:63bf:f7f7:f7f7"));
        assertNotNull(extracted);
        assertEquals("8.8.8.8", extracted.getHostAddress());
    }

    @Test
    public void extractUnwrapsNat64LocalUseRfc6052Slash48() throws Exception {
        InetAddress extracted = SsrfGuard.extractEmbeddedIpv4(
                InetAddress.getByName("64:ff9b:1:808:8:800::"));
        assertNotNull(extracted);
        assertEquals("8.8.8.8", extracted.getHostAddress());
    }

    @Test
    public void extractSkipsTrivialIpv4Compatible() throws Exception {
        // ::0 (any-local) and ::1 (loopback) must NOT be treated as
        // embedded IPv4 — they're caught by JDK predicates instead.
        assertNull(SsrfGuard.extractEmbeddedIpv4(InetAddress.getByName("::")));
        assertNull(SsrfGuard.extractEmbeddedIpv4(InetAddress.getByName("::1")));
    }
}
