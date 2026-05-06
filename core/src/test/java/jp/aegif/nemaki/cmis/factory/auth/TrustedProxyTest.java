package jp.aegif.nemaki.cmis.factory.auth;

import jp.aegif.nemaki.cmis.factory.auth.impl.AuthenticationServiceImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the trusted proxy IP check in AuthenticationServiceImpl.
 */
class TrustedProxyTest {

    // Access the private static isTrustedProxy method via reflection
    private boolean isTrustedProxy(String remoteAddr, String trustedProxies) throws Exception {
        Method m = AuthenticationServiceImpl.class.getDeclaredMethod("isTrustedProxy", String.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, remoteAddr, trustedProxies);
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
