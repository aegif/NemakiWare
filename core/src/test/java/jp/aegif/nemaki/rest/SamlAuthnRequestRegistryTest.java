package jp.aegif.nemaki.rest;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Tests for the redesigned {@link SamlAuthnRequestRegistry} (server-issued
 * AuthnRequest IDs bound by HttpOnly cookie).
 *
 * <p>The trust model is: only the server can produce a (bindingToken,
 * authnRequestId) pair via {@link SamlAuthnRequestRegistry#issue()}. The
 * verifier later requires the caller to present BOTH the cookie value
 * (bindingToken) and the InResponseTo value (authnRequestId) and they
 * must match exactly. Without the cookie, an attacker who knows the
 * AuthnRequest ID (e.g. read it from a captured Response) cannot satisfy
 * {@link SamlAuthnRequestRegistry#consume(String, String)}.
 */
class SamlAuthnRequestRegistryTest {

    @Test
    void issueProducesPairThatConsumeAccepts() {
        SamlAuthnRequestRegistry r = new SamlAuthnRequestRegistry(60);
        try {
            SamlAuthnRequestRegistry.Issued issued = r.issue();
            assertNotNull(issued);
            assertNotNull(issued.getAuthnRequestId());
            assertNotNull(issued.getBindingToken());
            assertNotEquals(issued.getAuthnRequestId(), issued.getBindingToken(),
                    "ID and binding token must be distinct values");
            assertTrue(r.consume(issued.getBindingToken(), issued.getAuthnRequestId()),
                    "consume with the matching pair must succeed");
            assertFalse(r.consume(issued.getBindingToken(), issued.getAuthnRequestId()),
                    "second consume must fail (entry removed)");
        } finally {
            r.clear();
        }
    }

    @Test
    void consumeRejectsMismatchedAuthnRequestId() {
        SamlAuthnRequestRegistry r = new SamlAuthnRequestRegistry(60);
        try {
            SamlAuthnRequestRegistry.Issued issued = r.issue();
            // Attacker presents a valid binding token (they somehow stole the
            // cookie, e.g. via sub-resource) but tries to bind to a different
            // AuthnRequest ID. Must fail.
            assertFalse(r.consume(issued.getBindingToken(), "_attacker-controlled-id"));
            // The defensive removal-on-mismatch leaves no entry behind.
            assertEquals(0, r.size());
        } finally {
            r.clear();
        }
    }

    @Test
    void consumeRejectsUnknownBindingToken() {
        SamlAuthnRequestRegistry r = new SamlAuthnRequestRegistry(60);
        try {
            SamlAuthnRequestRegistry.Issued issued = r.issue();
            // Attacker presents the leaked AuthnRequest ID (from the SAML
            // Response) but no binding cookie / a forged one. Must fail.
            assertFalse(r.consume("attacker-forged-token", issued.getAuthnRequestId()));
            // The legitimate user can still consume their own pair.
            assertTrue(r.consume(issued.getBindingToken(), issued.getAuthnRequestId()));
        } finally {
            r.clear();
        }
    }

    @Test
    void consumeRejectsBlankInputs() {
        SamlAuthnRequestRegistry r = new SamlAuthnRequestRegistry(60);
        try {
            assertFalse(r.consume(null, "_id"));
            assertFalse(r.consume("", "_id"));
            assertFalse(r.consume("token", null));
            assertFalse(r.consume("token", ""));
        } finally {
            r.clear();
        }
    }

    @Test
    void issueRespectsCapacity() {
        // Tiny registry to verify capacity behaviour without allocating 10k entries.
        SamlAuthnRequestRegistry r = new SamlAuthnRequestRegistry(60);
        try {
            for (int i = 0; i < 50; i++) {
                assertNotNull(r.issue());
            }
            assertEquals(50, r.size());
        } finally {
            r.clear();
        }
    }

    @Test
    void expiredEntryIsRejectedOnConsume() throws InterruptedException {
        // 0-second TTL exposes the millisecond-precision boundary check.
        SamlAuthnRequestRegistry r = new SamlAuthnRequestRegistry(0);
        try {
            SamlAuthnRequestRegistry.Issued issued = r.issue();
            Thread.sleep(50);
            assertFalse(r.consume(issued.getBindingToken(), issued.getAuthnRequestId()),
                    "expired AuthnRequest must not authenticate a Response");
        } finally {
            r.clear();
        }
    }

    @Test
    void singletonInstanceShared() {
        assertSame(SamlAuthnRequestRegistry.getInstance(), SamlAuthnRequestRegistry.getInstance());
    }
}
