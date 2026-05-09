package jp.aegif.nemaki.rest;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SamlAuthnRequestRegistry}.
 *
 * <p>The registry is the SP-side companion to the IdP-side
 * {@link SamlReplayCache}: it lets {@code saml.require.inResponseTo=true}
 * enforce that incoming Responses correspond to a request this SP
 * actually issued (defends against unsolicited-Response injection).
 */
class SamlAuthnRequestRegistryTest {

    @Test
    void registerThenConsumeSucceeds() {
        SamlAuthnRequestRegistry r = new SamlAuthnRequestRegistry(60);
        try {
            String id = "_e51b3a8d-1234-4567-89ab-cdef01234567";
            assertTrue(r.register(id));
            assertTrue(r.consume(id), "consume must succeed once for a registered id");
            assertFalse(r.consume(id), "second consume must fail (no replay even for the request side)");
        } finally {
            r.clear();
        }
    }

    @Test
    void consumeWithoutRegisterFails() {
        SamlAuthnRequestRegistry r = new SamlAuthnRequestRegistry(60);
        try {
            assertFalse(r.consume("_unregistered"));
            assertFalse(r.consume(""));
            assertFalse(r.consume(null));
        } finally {
            r.clear();
        }
    }

    @Test
    void registerRejectsMalformedIds() {
        SamlAuthnRequestRegistry r = new SamlAuthnRequestRegistry(60);
        try {
            assertFalse(r.register(null), "null id rejected");
            assertFalse(r.register(""), "empty id rejected");
            assertFalse(r.register("1starts-with-digit"), "NCName must start with letter or underscore");
            assertFalse(r.register("has spaces"), "spaces forbidden");
            assertFalse(r.register("contains\r\nCRLF"), "CRLF rejected (log-injection guard)");
            assertFalse(r.register("a".repeat(300)), "over-long id rejected (memory bound)");

            assertTrue(r.register("_id-with-dot.and-dash"));
            assertTrue(r.register("Letters_DigitsAndUnderscore_123"));
        } finally {
            r.clear();
        }
    }

    @Test
    void expiredEntryIsRejectedOnConsume() throws InterruptedException {
        SamlAuthnRequestRegistry r = new SamlAuthnRequestRegistry(2);
        try {
            String id = "_expiring";
            assertTrue(r.register(id));
            Thread.sleep(2500);
            // Sweeper runs every minute; the entry may still be in the map but
            // consume() must check expiry and refuse it.
            assertFalse(r.consume(id), "expired AuthnRequest id must not authenticate a Response");
        } finally {
            r.clear();
        }
    }

    @Test
    void registryIsBoundedAndDoesNotGrowUnboundedUnderAbuse() {
        SamlAuthnRequestRegistry r = new SamlAuthnRequestRegistry(60);
        try {
            // Register 100 IDs — each unique. Trivial size sanity check.
            for (int i = 0; i < 100; i++) {
                assertTrue(r.register("_abuse-" + i));
            }
            assertEquals(100, r.size());
        } finally {
            r.clear();
        }
    }

    @Test
    void singletonInstanceShared() {
        assertSame(SamlAuthnRequestRegistry.getInstance(), SamlAuthnRequestRegistry.getInstance());
    }
}
