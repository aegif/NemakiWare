/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package jp.aegif.nemaki.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Shared SSRF address classification helpers used by both
 * {@code jp.aegif.nemaki.webhook.HttpWebhookDispatcher} and
 * {@code jp.aegif.nemaki.rest.ingest.AdapterHttpClient}.
 *
 * <p>Extracted in v3.1.1-RC6.10 from previously-duplicated copies in
 * those two files. The classification rules are byte-for-byte identical
 * to the prior implementations:
 *
 * <ul>
 *   <li>{@link #isAddressSafe(InetAddress)} — returns {@code false} for
 *       any address that NemakiWare's outbound dispatchers must NOT
 *       connect to: loopback, link-local, site-local (RFC 1918),
 *       any-local (0.0.0.0), multicast, plus 9 additional IPv4
 *       special-use ranges (RC6.6+) and IPv6 ULA (fc00::/7). For IPv6
 *       transition addresses (NAT64 well-known + local-use, 6to4,
 *       Teredo, IPv4-compatible, IPv4-mapped) the embedded IPv4 is
 *       extracted via {@link #extractEmbeddedIpv4(InetAddress)} and
 *       re-classified.</li>
 *   <li>{@link #extractEmbeddedIpv4(InetAddress)} — returns the
 *       embedded IPv4 as an {@code Inet4Address} for any of the 6
 *       recognized IPv6 transition formats, or {@code null} for any
 *       other address.</li>
 * </ul>
 *
 * <p>Both methods are pure functions: no I/O, no mutable state. The
 * dispatchers' own additional logic (URI rewrite, redirect handling,
 * Host header preservation, retry, etc.) stays in their respective
 * files — this class is strictly the "is this IP safe to connect to,
 * and if it's an IPv6 transition format, what IPv4 does it embed"
 * primitive.
 *
 * <p>RC6.10 introduces this class as the 2nd consumer (HttpWebhookDispatcher)
 * crosses the "Rule of Three" threshold — adding a 3rd would have
 * required updating the duplicated logic in three places. Future
 * SSRF-guard consumers should call this class directly rather than
 * re-implementing the logic.
 */
public final class SsrfGuard {

    private SsrfGuard() { /* utility */ }

    /**
     * Returns {@code true} if the given resolved IP address is safe for
     * NemakiWare's outbound dispatchers to connect to, {@code false}
     * otherwise. The classification:
     *
     * <ul>
     *   <li>Reject if JDK predicates classify as loopback / link-local /
     *       site-local (RFC 1918) / any-local / multicast.</li>
     *   <li>For 4-byte IPv4: additionally reject 10/8, 172.16-31/12,
     *       192.168/16, 169.254/16, 0/8, 100.64/10, 192.0.0/24,
     *       198.18/15, 240/4, and 255.255.255.255.</li>
     *   <li>For 16-byte IPv6: reject ULA (fc00::/7). If the address is
     *       an IPv6 transition format that embeds an IPv4 address,
     *       extract the embedded IPv4 via
     *       {@link #extractEmbeddedIpv4(InetAddress)} and recursively
     *       re-classify.</li>
     * </ul>
     */
    public static boolean isAddressSafe(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }

        byte[] addrBytes = address.getAddress();

        if (addrBytes.length == 4) {
            int first = addrBytes[0] & 0xFF;
            int second = addrBytes[1] & 0xFF;
            int third = addrBytes[2] & 0xFF;
            int fourth = addrBytes[3] & 0xFF;

            // RFC 1918 + 169.254/16 (link-local + AWS metadata)
            if (first == 10) return false;
            if (first == 172 && second >= 16 && second <= 31) return false;
            if (first == 192 && second == 168) return false;
            if (first == 169 && second == 254) return false;

            // RC6.6 additions — IPv4 special-use ranges not classified
            // by JDK isLoopback/isLinkLocal/isSiteLocal predicates.
            if (first == 0) return false;                       // 0.0.0.0/8 ("this" network)
            if (first == 100 && second >= 64 && second <= 127) return false;  // 100.64/10 CGNAT
            if (first == 192 && second == 0 && third == 0) return false;       // 192.0.0/24 IETF
            if (first == 198 && (second == 18 || second == 19)) return false;  // 198.18/15 benchmark
            if (first >= 240) return false;                     // 240/4 reserved
            if (first == 255 && second == 255 && third == 255 && fourth == 255) return false; // broadcast
            return true;
        }

        if (addrBytes.length == 16) {
            int firstByte = addrBytes[0] & 0xFF;
            // IPv6 ULA fc00::/7  (covers fc00::/8 and fd00::/8)
            if (firstByte == 0xFC || firstByte == 0xFD) {
                return false;
            }

            // IPv6 transition addresses: unwrap embedded IPv4 + recursively
            // re-classify. Covers NAT64 well-known (64:ff9b::/96), NAT64
            // local-use (64:ff9b:1::/48 with RFC 6052 §2.2 /48 layout +
            // /96-PLR fallback), 6to4 (2002::/16), Teredo (2001::/32 with
            // one's-complement client IPv4), IPv4-compatible (::a.b.c.d),
            // IPv4-mapped (::ffff:0:0/96).
            InetAddress embedded = extractEmbeddedIpv4(address);
            if (embedded != null && !isAddressSafe(embedded)) {
                return false;
            }
            return true;
        }

        // Unknown address family (shouldn't happen — InetAddress is
        // always 4 or 16 bytes) — fail-shut.
        return false;
    }

    /**
     * Returns the embedded IPv4 (as an {@code Inet4Address}) for any
     * recognized IPv6 transition format; {@code null} otherwise.
     *
     * <p>Recognized formats:
     *
     * <ul>
     *   <li>{@code ::ffff:0:0/96} — IPv4-mapped IPv6 (RFC 4291 §2.5.5.2).
     *       Bytes 12-15. The JDK usually returns these as
     *       {@code Inet4Address} already, so this branch is defensive.</li>
     *   <li>{@code ::a.b.c.d} (IPv4-compatible, deprecated by RFC 4291
     *       §2.5.5.1 but still parseable). Bytes 12-15. Skips
     *       {@code ::0} (any-local) and {@code ::1} (loopback) which
     *       are caught by the predicate checks in
     *       {@link #isAddressSafe(InetAddress)} and don't carry an
     *       "embedded" IPv4 in the SSRF sense.</li>
     *   <li>{@code 64:ff9b::/96} — NAT64 well-known prefix (RFC 6052 §2.1).
     *       Bytes 12-15.</li>
     *   <li>{@code 64:ff9b:1::/48} — NAT64 local-use prefix (RFC 8215).
     *       Supports the RFC 6052 /48 layout (IPv4 bytes 6-7 + 9-10,
     *       skipping byte 8 = reserved "u" octet, suffix bytes 11-15
     *       must be zero) AND the /96-style PLR layout (bytes 12-15)
     *       as fallback. Re-classification by {@link #isAddressSafe}
     *       gates the result either way.</li>
     *   <li>{@code 2002::/16} — 6to4 (RFC 3056 §2). Bytes 2-5.</li>
     *   <li>{@code 2001::/32} — Teredo (RFC 4380 §4). Strict prefix
     *       check (bytes 0-3 = {@code 20:01:00:00}) so other
     *       {@code 2001::/16} addresses ({@code 2001:db8::}
     *       documentation, {@code 2001:4860::} Google) are NOT
     *       mis-extracted. Client IPv4 in bytes 12-15 stored as
     *       one's complement; decoded via {@code (byte) ~b[i]}.</li>
     * </ul>
     */
    public static InetAddress extractEmbeddedIpv4(InetAddress address) {
        byte[] b = address.getAddress();
        if (b.length != 16) {
            return null;
        }
        byte[] embedded = null;

        // IPv4-mapped ::ffff:0:0/96  (bytes 0-9 = 0, bytes 10-11 = 0xFF)
        boolean mappedPrefix = true;
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) { mappedPrefix = false; break; }
        }
        if (mappedPrefix && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
            embedded = new byte[]{b[12], b[13], b[14], b[15]};
        }

        // IPv4-compatible ::a.b.c.d  (bytes 0-11 = 0).
        if (embedded == null) {
            boolean compatPrefix = true;
            for (int i = 0; i < 12; i++) {
                if (b[i] != 0) { compatPrefix = false; break; }
            }
            boolean trivialLow = b[12] == 0 && b[13] == 0 && b[14] == 0
                    && (b[15] == 0 || b[15] == 1);
            if (compatPrefix && !trivialLow) {
                embedded = new byte[]{b[12], b[13], b[14], b[15]};
            }
        }

        // NAT64 64:ff9b::/96  (bytes 0-3 = 00:64:FF:9B, bytes 4-11 = 0)
        if (embedded == null
                && (b[0] & 0xFF) == 0x00 && (b[1] & 0xFF) == 0x64
                && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B) {
            boolean nat64WellKnown = true;
            for (int i = 4; i < 12; i++) {
                if (b[i] != 0) { nat64WellKnown = false; break; }
            }
            if (nat64WellKnown) {
                embedded = new byte[]{b[12], b[13], b[14], b[15]};
            }
        }

        // NAT64 local-use 64:ff9b:1::/48  (RFC 8215).
        // RFC 6052 /48 layout: prefix bytes 0-5, IPv4[0..1] in bytes
        // 6-7, byte 8 reserved "u" octet, IPv4[2..3] in bytes 9-10,
        // suffix bytes 11-15 must be zero. Falls back to /96-PLR
        // (bytes 12-15) when the suffix isn't clear.
        if (embedded == null
                && (b[0] & 0xFF) == 0x00 && (b[1] & 0xFF) == 0x64
                && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B
                && (b[4] & 0xFF) == 0x00 && (b[5] & 0xFF) == 0x01) {
            boolean rfc6052SuffixClear = true;
            for (int i = 11; i < 16; i++) {
                if (b[i] != 0) { rfc6052SuffixClear = false; break; }
            }
            if (rfc6052SuffixClear) {
                embedded = new byte[]{b[6], b[7], b[9], b[10]};
            } else {
                embedded = new byte[]{b[12], b[13], b[14], b[15]};
            }
        }

        // 6to4 2002::/16  (bytes 0-1 = 20:02, bytes 2-5 = embedded IPv4)
        if (embedded == null && (b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x02) {
            embedded = new byte[]{b[2], b[3], b[4], b[5]};
        }

        // Teredo 2001::/32  (bytes 0-3 = 20:01:00:00). Strict prefix
        // so other 2001::/16 ranges are NOT mis-extracted. Bytes
        // 12-15 = client IPv4 as one's-complement.
        if (embedded == null
                && (b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x01
                && b[2] == 0 && b[3] == 0) {
            embedded = new byte[]{
                    (byte) ~b[12], (byte) ~b[13], (byte) ~b[14], (byte) ~b[15]
            };
        }

        if (embedded == null) {
            return null;
        }
        try {
            return InetAddress.getByAddress(embedded);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
