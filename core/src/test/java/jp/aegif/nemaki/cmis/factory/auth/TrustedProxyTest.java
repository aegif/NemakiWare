package jp.aegif.nemaki.cmis.factory.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the trusted proxy IP check.
 *
 * <p>The implementation lived as a private static helper in
 * {@code AuthenticationServiceImpl} until RC13 extracted it into
 * {@link jp.aegif.nemaki.util.TrustedProxyResolver} so the audit-log
 * IP capture path could share the same policy. The reflection-based
 * indirection here is now redundant; the assertions remain as a
 * regression net for the policy itself.
 */
class TrustedProxyTest {

    private boolean isTrustedProxy(String remoteAddr, String trustedProxies) {
        return jp.aegif.nemaki.util.TrustedProxyResolver.isTrusted(remoteAddr, trustedProxies);
    }

    @Test
    void exactMatch() throws Exception {
        assertTrue(isTrustedProxy("10.0.0.1", "10.0.0.1"));
    }

    @Test
    void multipleProxies_secondMatches() throws Exception {
        assertTrue(isTrustedProxy("10.0.0.2", "10.0.0.1, 10.0.0.2, 10.0.0.3"));
    }

    @Test
    void noMatch() throws Exception {
        assertFalse(isTrustedProxy("192.168.1.1", "10.0.0.1, 10.0.0.2"));
    }

    @Test
    void nullRemoteAddr_rejected() throws Exception {
        assertFalse(isTrustedProxy(null, "10.0.0.1"));
    }

    @Test
    void nullTrustedProxies_rejected() throws Exception {
        assertFalse(isTrustedProxy("10.0.0.1", null));
    }

    @Test
    void localhostEquivalence_127() throws Exception {
        assertTrue(isTrustedProxy("127.0.0.1", "localhost"));
    }

    @Test
    void localhostEquivalence_ipv6() throws Exception {
        assertTrue(isTrustedProxy("::1", "127.0.0.1"));
    }

    @Test
    void localhostEquivalence_ipv6Full() throws Exception {
        assertTrue(isTrustedProxy("0:0:0:0:0:0:0:1", "localhost"));
    }

    @Test
    void emptyTrustedProxies_rejected() throws Exception {
        assertFalse(isTrustedProxy("10.0.0.1", ""));
    }

    @Test
    void spacesInList_handled() throws Exception {
        assertTrue(isTrustedProxy("10.0.0.2", "  10.0.0.1 , 10.0.0.2 "));
    }
}
