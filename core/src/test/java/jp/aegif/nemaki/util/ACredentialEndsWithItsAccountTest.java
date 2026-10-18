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
package jp.aegif.nemaki.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.AuthenticationUtil.CredentialBinding;

/**
 * A credential belongs to the account document it was issued for, not to a user NAME (3.4.1, R170).
 *
 * <p>Until this release every credential store — API keys, session tokens, MCP sessions, passkeys,
 * RSS feed tokens — recorded only {@code userId}. Deleting a user and recreating the same user id
 * handed the new account every credential the old one had: the API key of a departed employee
 * authenticating as their successor.
 *
 * <p>The rule has two axes and they are NOT the same question:
 *
 * <ul>
 * <li>{@code accountId} — which account document the credential was issued for. Applies to every
 *     store. A credential that records none was issued before this release and is refused (the
 *     owner chose fail-closed over "accept by name", knowing it invalidates existing keys).</li>
 * <li>{@code generation} — how many times that account's password has changed. Applies only to the
 *     stores a password change should invalidate (session tokens, MCP sessions), not to API keys
 *     (an automation must not stop because a human changed their password) and not to passkeys or
 *     RSS tokens (not derived from the password).</li>
 * </ul>
 *
 * <p>A generation and not a timestamp: two credentials issued in the same millisecond compare
 * equal, and replica clock skew can make a credential issued BEFORE a change carry a later time.
 */
class ACredentialEndsWithItsAccountTest {

    private static final String ACCOUNT_DOC = "user-doc-1";

    /** An account document; {@code generation} null means the property is absent. */
    private UserItem account(String documentId, String allowedAuthMethods, Object generation) {
        UserItem user = new UserItem(documentId, "nemaki:user", "alice", "alice", "hash", false, null);
        java.util.List<Property> properties = new java.util.ArrayList<>();
        if (allowedAuthMethods != null) {
            properties.add(new Property("nemaki:allowedAuthMethods", allowedAuthMethods));
        }
        if (generation != null) {
            properties.add(new Property("nemaki:credentialGeneration", generation));
        }
        user.setSubTypeProperties(properties);
        return user;
    }

    @Test
    @DisplayName("a credential issued for THIS account document is accepted")
    void aCredentialOfThisAccountIsAccepted() {
        assertEquals(CredentialBinding.OK,
                AuthenticationUtil.credentialBinding(ACCOUNT_DOC, 0, account(ACCOUNT_DOC, null, null), true));
    }

    @Test
    @DisplayName("a credential issued for the PREVIOUS account document with the same user id is refused")
    void aCredentialOfARecreatedAccountIsRefused() {
        // The defect R170 is about: delete alice, create alice again, and the old document id is
        // gone — the credential names an account that no longer exists.
        assertEquals(CredentialBinding.WRONG_ACCOUNT,
                AuthenticationUtil.credentialBinding("the-deleted-document",
                        0, account(ACCOUNT_DOC, null, null), true));
    }

    @Test
    @DisplayName("a credential issued before this release records no account and is refused")
    void aCredentialWithNoAccountIsRefused() {
        // The owner's decision (2026-10-16), knowing that upgrading invalidates every existing API
        // key, session token, passkey and MCP session: accepting by name leaves R170 open.
        for (String none : new String[] { null, "", "   " }) {
            assertEquals(CredentialBinding.NOT_BOUND,
                    AuthenticationUtil.credentialBinding(none, 0, account(ACCOUNT_DOC, null, null), true),
                    "a credential with accountId " + (none == null ? "null" : "'" + none + "'")
                            + " was accepted");
        }
    }

    @Test
    @DisplayName("an account that cannot act refuses its credentials first — batch 17's rule still runs")
    void anAccountThatCannotActRefusesFirst() {
        assertEquals(CredentialBinding.ACCOUNT_CANNOT_ACT,
                AuthenticationUtil.credentialBinding(ACCOUNT_DOC, 0, null, true),
                "a deleted account accepted its credential");
        assertEquals(CredentialBinding.ACCOUNT_CANNOT_ACT,
                AuthenticationUtil.credentialBinding(ACCOUNT_DOC, 0,
                        account(ACCOUNT_DOC, "password,disabled", 0), true),
                "a disabled account accepted its credential");
    }

    @Test
    @DisplayName("a password change invalidates what the generation covers, and nothing else")
    void aPasswordChangeInvalidatesWhatTheGenerationCovers() {
        UserItem changedOnce = account(ACCOUNT_DOC, null, 1);

        // A session token issued before the change: refused.
        assertEquals(CredentialBinding.STALE_GENERATION,
                AuthenticationUtil.credentialBinding(ACCOUNT_DOC, 0, changedOnce, true));
        // One issued after: accepted.
        assertEquals(CredentialBinding.OK,
                AuthenticationUtil.credentialBinding(ACCOUNT_DOC, 1, changedOnce, true));
        // An API key issued before the change: still accepted, because the generation does not
        // apply to it (the owner's decision — an automation must not stop silently).
        assertEquals(CredentialBinding.OK,
                AuthenticationUtil.credentialBinding(ACCOUNT_DOC, 0, changedOnce, false));
    }

    @Test
    @DisplayName("an account with no generation property behaves as 0 — upgrading does not lock everyone out")
    void anAccountWithNoGenerationIsZero() {
        assertEquals(0L, AuthenticationUtil.accountGeneration(account(ACCOUNT_DOC, null, null)));
        assertEquals(0L, AuthenticationUtil.accountGeneration(account(ACCOUNT_DOC, null, "")));
        assertEquals(CredentialBinding.OK,
                AuthenticationUtil.credentialBinding(ACCOUNT_DOC, 0, account(ACCOUNT_DOC, null, null), true),
                "a credential issued against an account that has never changed its password was"
                        + " refused, so this version would lock every user out");
    }

    @Test
    @DisplayName("a credential with NO generation is refused where the account's absence means zero")
    void aCredentialWithNoGenerationIsRefused() {
        // The asymmetry is the point (round-9 design review): this version always writes the
        // generation it read, so a missing one means the write did not finish or the record is
        // damaged. Treating it as 0 would let it pass against an account still at 0 — fail-open.
        for (Object unusable : new Object[] { null, "", "  ", "not-a-number", -1, "-3" }) {
            assertEquals(CredentialBinding.NO_GENERATION,
                    AuthenticationUtil.credentialBinding(ACCOUNT_DOC, unusable,
                            account(ACCOUNT_DOC, null, null), true),
                    "a credential whose generation is " + unusable + " was accepted");
        }
    }

    @Test
    @DisplayName("a generation the account has not reached is refused, not rounded down")
    void aFutureGenerationIsRefused() {
        assertEquals(CredentialBinding.FUTURE_GENERATION,
                AuthenticationUtil.credentialBinding(ACCOUNT_DOC, 5, account(ACCOUNT_DOC, null, 1), true),
                "a credential from a replica that had raised the generation further was accepted;"
                        + " comparing only '<' lets a corrupted or newer value through");
    }

    @Test
    @DisplayName("an account whose own generation cannot be read refuses its credentials")
    void anUnreadableAccountGenerationRefuses() {
        // Unknown is not zero. A stored value that is not a non-negative integer cannot be compared,
        // and guessing would silently un-invalidate every credential the account has.
        assertNull(AuthenticationUtil.accountGeneration(account(ACCOUNT_DOC, null, "nonsense")));
        assertNull(AuthenticationUtil.accountGeneration(account(ACCOUNT_DOC, null, -2)));
        assertEquals(CredentialBinding.ACCOUNT_GENERATION_UNREADABLE,
                AuthenticationUtil.credentialBinding(ACCOUNT_DOC, 0,
                        account(ACCOUNT_DOC, null, "nonsense"), true));
        // …but a store the generation does not apply to is unaffected: its credentials do not
        // depend on a value that cannot be read.
        assertEquals(CredentialBinding.OK,
                AuthenticationUtil.credentialBinding(ACCOUNT_DOC, 0,
                        account(ACCOUNT_DOC, null, "nonsense"), false));
    }

    @Test
    @DisplayName("the generation is read from the account, so every replica answers the same")
    void theGenerationIsReadFromTheAccount() {
        // Not a timestamp comparison: same-millisecond issuance and replica clock skew both make a
        // time-based rule answer differently in different places (round-7 design review). These are
        // integers from the account document the request already reads.
        UserItem account = account(ACCOUNT_DOC, null, 7);
        assertEquals(7L, AuthenticationUtil.accountGeneration(account));
        assertEquals(CredentialBinding.OK,
                AuthenticationUtil.credentialBinding(ACCOUNT_DOC, "7", account, true),
                "the stored value is a string in CouchDB documents, and that must read the same as"
                        + " the integer 7");
        assertEquals(CredentialBinding.STALE_GENERATION,
                AuthenticationUtil.credentialBinding(ACCOUNT_DOC, "6", account, true));
    }
}
