/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.model.WebAuthnCredential;
import jp.aegif.nemaki.util.test.JavaSource;
import jp.aegif.nemaki.util.test.HarnessBroken;

/**
 * A passkey belongs to the account DOCUMENT it was registered for (R170, 3.4.1 batch 19).
 *
 * <p>Deleting a user does not delete their passkeys, and a passkey never expires: before this, the
 * passkey of a departed employee logged in as whoever took the same user id next. The rule itself,
 * with all its arms, is {@code ACredentialEndsWithItsAccountTest}; what has to be measured here is
 * the WIRING — that the registration WRITES the binding and the assertion ASKS it.
 *
 * <p>Read from the source rather than driven, and the reason is recorded rather than hidden: both
 * entry points take a WebAuthn response built by an authenticator, and the yubico library verifies
 * it against a challenge this test cannot mint. The behaviour is driven end to end by
 * {@code ui/tests/auth/passkey.spec.ts}, which has a CDP virtual authenticator; this lock exists so
 * that a change which drops the binding is caught by a unit gate rather than only by that suite.
 */
class APasskeyEndsWithItsAccountTest {

    private static final Path RESOURCE =
            Path.of("src/main/java/jp/aegif/nemaki/rest/WebAuthnResource.java");

    private static String code() throws IOException {
        HarnessBroken.unless(Files.exists(RESOURCE), "this lock looks for " + RESOURCE + " and it is not there");
        return JavaSource.withoutLiteralText(
                Files.readString(RESOURCE, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the stored passkey carries the account document and the generation")
    void theStoredPasskeyCarriesTheBinding() {
        // The model side, driven: a reader that does not keep these fields would make every lock
        // below pass while nothing is ever compared.
        WebAuthnCredential credential = new WebAuthnCredential();
        credential.setAccountId("alice-doc");
        credential.setCredentialGeneration("2");

        assertEquals("alice-doc", credential.getAccountId());
        assertEquals("2", credential.getCredentialGeneration());
    }

    @Test
    @DisplayName("registration writes the account document it read, and refuses when it cannot read one")
    void registrationWritesTheBinding() throws IOException {
        String code = code();
        int registration = code.indexOf("createWebAuthnCredential(repositoryId, credential)");
        HarnessBroken.unless(registration > 0,
                "the registration no longer stores a credential here — this lock reads that path");
        String before = code.substring(0, registration);

        assertTrue(before.contains("credential.setAccountId("),
                "the registration stores a passkey without the account document it belongs to, so"
                        + " the user id alone decides whose passkey it is — R170 itself");
        assertTrue(before.contains("credential.setCredentialGeneration("),
                "the registration does not write the generation, so an administrator revoking every"
                        + " credential of this person has nothing to work with");
        assertTrue(before.contains("getUserItemById(repositoryId, userId)"),
                "the account is never read during registration, so whatever is written as the"
                        + " binding was not read from the account");
        // And it must refuse rather than store an unbound passkey: that one could never
        // authenticate, and the user would be told their passkey was registered.
        int read = before.lastIndexOf("getUserItemById(repositoryId, userId)");
        String betweenReadAndStore = code.substring(read, registration);
        assertTrue(betweenReadAndStore.contains("return makeResult(false"),
                "nothing between reading the account and storing the passkey refuses, so a passkey"
                        + " that cannot be bound is stored anyway: " + betweenReadAndStore.length()
                        + " characters with no refusal");
    }

    @Test
    @DisplayName("the assertion asks the rule before it issues a token")
    void theAssertionAsksTheRule() throws IOException {
        String code = code();
        int binding = code.indexOf("AuthenticationUtil.credentialBinding(");
        assertTrue(binding > 0,
                "the assertion path does not ask whether the passkey belongs to this account"
                        + " document, so a passkey of the previous holder of this user id still logs"
                        + " in — R170 itself");
        int token = code.indexOf("setToken(", binding);
        assertTrue(token > binding,
                "the rule is asked after the token is issued, or not before it: the refusal has to"
                        + " come first");
        String refusal = code.substring(binding, token);
        assertTrue(refusal.contains("CredentialBinding.OK"),
                "the answer is not compared with OK, so every answer passes: " + refusal);
        assertTrue(refusal.contains("return makeResult(false"),
                "the comparison does not refuse, so the binding is read and then ignored");
    }
}
