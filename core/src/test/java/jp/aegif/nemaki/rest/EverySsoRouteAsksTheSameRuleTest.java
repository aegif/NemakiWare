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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.test.JavaSource;

/**
 * Every login that acts on an identity provider's word asks the same rule, and that rule does not read
 * {@code cloud} as permission (R175 + R168, 3.4.1).
 *
 * <p>Three of the four routes — SAML, Google, Microsoft — took whatever {@code getOrCreateUser} handed
 * back and asked one question: {@code isAuthMethodAllowed(account, "cloud")}, which answers TRUE for an
 * account that lists no method at all. The built-in {@code admin}, the service accounts and every
 * account made with a password were therefore enterable by anyone the IdP let choose that name (R175).
 * The generic OIDC route did ask a rule, but the rule read 「{@code cloud} is listed」 as 「an
 * administrator allowed SSO」 — and {@code cloud} is written by those three routes when they provision,
 * and by the cloud directory sync, which also writes it onto accounts that ALREADY EXIST (R168).
 *
 * <p>The rule's arms are driven here directly because it is a pure function of the stored account. What
 * that cannot show is that each route ASKS it, so the wiring is read from the source: the three routes
 * cannot be driven end to end without an IdP's signed answer (the OIDC route can, and is — in
 * {@code OidcLoginEntryPointsTest}).
 */
public class EverySsoRouteAsksTheSameRuleTest {

    private static final String REST = "src/main/java/jp/aegif/nemaki/rest/AuthTokenResource.java";
    private static final String API_V1 = "src/main/java/jp/aegif/nemaki/api/v1/resource/AuthResource.java";
    private static final String SYNC =
            "src/main/java/jp/aegif/nemaki/sync/service/CloudDirectorySyncServiceImpl.java";

    /** An account as the store holds it. */
    private static UserItem account(String... keysAndValues) {
        UserItem user = new UserItem("doc-alice", "nemaki:user", "alice", "alice", "$2a$10$x", false, "users");
        List<Property> properties = new ArrayList<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            if (keysAndValues[i + 1] != null) {
                properties.add(new Property(keysAndValues[i], keysAndValues[i + 1]));
            }
        }
        user.setSubTypeProperties(properties);
        return user;
    }

    @Test
    @DisplayName("an account that lists no authentication method is NOT adopted by any route")
    public void anAccountThatListsNoMethodIsNotAdopted() {
        UserItem unset = account();

        for (String route : List.of(SsoLogin.OIDC, SsoLogin.SAML, SsoLogin.GOOGLE, SsoLogin.MICROSOFT)) {
            String refused = SsoLogin.refusal(unset, route, "https://idp.example iss-subject");
            assertNotNull(refused, "a " + route + " login entered an account that lists no authentication "
                    + "method — which is the built-in admin, the service accounts and every account made "
                    + "with a password (R175)");
            assertTrue(refused.contains("administrator"),
                    "the refusal does not say who can allow it: " + refused);
        }
    }

    @Test
    @DisplayName("an account another ROUTE made is not adopted, even with cloud listed (R168)")
    public void anAccountAnotherRouteMadeIsNotAdopted() {
        // cloud is listed — by the sync itself. Reading that as 「an administrator allowed SSO」 is what
        // let a generic-OIDC token adopt an account the sync had made, or had turned into a cloud account.
        UserItem madeBySync = account("nemaki:allowedAuthMethods", "cloud",
                SsoLogin.ROUTE_KEY, SsoLogin.DIRECTORY_SYNC);

        String refused = SsoLogin.refusal(madeBySync, SsoLogin.OIDC, "https://idp.example iss-subject");
        assertNotNull(refused, "a generic OIDC token adopted an account the directory sync made");
        assertTrue(refused.contains(SsoLogin.DIRECTORY_SYNC), "the refusal does not name the route that "
                + "made the account: " + refused);

        // And the route that made it still logs in.
        assertNull(SsoLogin.refusal(madeBySync, SsoLogin.DIRECTORY_SYNC, null),
                "the account the sync made is refused to the sync's own mark, so a synced user cannot "
                        + "log in at all");
    }

    @Test
    @DisplayName("an account with cloud and NO mark is adopted — the migration arm, the one line to flip")
    public void anAccountWithCloudAndNoMarkIsAdopted() {
        // An account made before 3.4.1 carries no mark, and the tree cannot tell 「an administrator
        // listed cloud」 from 「an older build wrote it」. Adopting is the arm that keeps existing
        // deployments logging in; refusing would lock out every user the sync had created until the next
        // sync wrote a mark. Recorded in RELEASE_NOTES with an inventory query rather than called safe.
        UserItem beforeThisRelease = account("nemaki:allowedAuthMethods", "cloud");

        assertNull(SsoLogin.refusal(beforeThisRelease, SsoLogin.SAML, "https://idp.example iss-subject"),
                "an account that lists cloud and carries no route mark is refused, so upgrading locks "
                        + "out the accounts an older build provisioned");
    }

    @Test
    @DisplayName("a login carrying NO subject does not pass as «not linked» on a linked account")
    public void aLoginWithNoSubjectIsRefusedNotTreatedAsUnlinked() {
        UserItem linked = account("nemaki:allowedAuthMethods", "cloud",
                SsoLogin.subjectKey(SsoLogin.SAML), "https://idp.example alice-nameid");

        for (String noSubject : new String[] { null, "", "   " }) {
            String refused = SsoLogin.refusal(linked, SsoLogin.SAML, noSubject);
            assertNotNull(refused, "a login that carries no subject was let into an account linked to "
                    + "one — «could not ask» read as «asked, and it matches»");
            // WHICH refusal, not just that there is one: falling through to the comparison refuses
            // too (null equals nothing), so «there was no subject to compare» and «the subject is
            // someone else's» would be the same answer and the arm would protect nothing (measured
            // — control LQ5 DID NOT FIRE until this line).
            assertTrue(refused.contains("no subject to compare"),
                    "the refusal does not say that the login carried no subject, so «could not ask» "
                            + "and «asked, and it did not match» are one answer: " + refused);
        }
        assertNull(SsoLogin.refusal(linked, SsoLogin.SAML, "https://idp.example alice-nameid"),
                "the subject the account is linked to does not log in");
        assertNotNull(SsoLogin.refusal(linked, SsoLogin.SAML, "https://idp.example someone-else"),
                "another subject of the same name logs into the linked account");
    }

    @Test
    @DisplayName("an account linked to ANOTHER route's identity is not re-linked by logging in")
    public void anAccountLinkedToAnotherRouteIsNotRelinked() {
        UserItem linkedToGoogle = account("nemaki:allowedAuthMethods", "cloud",
                SsoLogin.subjectKey(SsoLogin.GOOGLE), "https://accounts.google.com 123");

        String refused = SsoLogin.refusal(linkedToGoogle, SsoLogin.MICROSOFT, "https://login.microsoft oid-1");
        assertNotNull(refused, "a Microsoft token re-linked an account that is Google's — re-linking is "
                + "an administrator's act, not a consequence of someone logging in");
        assertTrue(refused.contains("Google"), "the refusal does not name the identity it is linked to: " + refused);
    }

    @Test
    @DisplayName("a disabled account is refused by every route, linked or not")
    public void aDisabledAccountIsRefusedByEveryRoute() {
        for (String methods : List.of("disabled", "cloud,disabled")) {
            UserItem disabled = account("nemaki:allowedAuthMethods", methods,
                    SsoLogin.subjectKey(SsoLogin.SAML), "https://idp.example alice-nameid");
            assertEquals("the account is disabled",
                    SsoLogin.refusal(disabled, SsoLogin.SAML, "https://idp.example alice-nameid"),
                    "a disabled account was logged into because it was already linked");
        }
    }

    @Test
    @DisplayName("every SSO arm asks the rule with its OWN route, and none takes an account without asking")
    public void everyArmAsksTheRuleWithItsOwnRoute() throws Exception {
        String rest = JavaSource.withoutComments(JavaSource.read(REST));

        // One call per route, each naming its own constant. A copied line would key two routes the same
        // way and an account bound for one would answer for the other.
        for (String route : List.of("SsoLogin.OIDC", "SsoLogin.SAML", "SsoLogin.GOOGLE", "SsoLogin.MICROSOFT")) {
            Matcher asks = Pattern.compile("accountForSsoLogin\\([^;]*?" + Pattern.quote(route))
                    .matcher(rest);
            int found = 0;
            while (asks.find()) {
                found++;
            }
            assertEquals(1, found, "the SSO conversions ask the shared rule for " + route + " " + found
                    + " time(s). Each of the four has to ask it, with its own route: a route that does "
                    + "not is back to taking whatever account carries the name the IdP gave (R175)");
        }

        // And NOTHING in the conversions takes an account by name without asking. getOrCreateUser is
        // reachable only through the helper now; a direct call is the shape R175 is about.
        Matcher direct = Pattern.compile("getOrCreateUser\\(repositoryId, userName[,)]").matcher(rest);
        List<String> callers = new ArrayList<>();
        while (direct.find()) {
            callers.add(direct.group());
        }
        assertEquals(1, callers.size(), "getOrCreateUser is called " + callers.size() + " times with a "
                + "name from a token. Exactly one call — the one inside accountForSsoLogin — may do that; "
                + "any other is a route that adopts an existing account without asking the rule: " + callers);
    }

    @Test
    @DisplayName("every place that provisions or re-methods an account records WHICH ROUTE did it")
    public void everyProvisionerRecordsItsRoute() throws Exception {
        // The mark is what tells 「an administrator listed cloud」 from 「a login path wrote it」. A
        // provisioner that does not write it hands its accounts to whichever IdP names them first.
        for (String source : List.of(REST, API_V1, SYNC)) {
            String body = JavaSource.withoutComments(JavaSource.read(source));
            assertTrue(body.contains("SsoLogin.ROUTE_KEY"), source + " writes accounts with "
                    + "nemaki:allowedAuthMethods and does not record which route made them, so the rule "
                    + "cannot tell an administrator's decision from its own writing (R168)");
        }

        // The sync's UPDATE path too, not only its create: that is where an account made with a password
        // becomes a cloud account (measured: the same method writes allowedAuthMethods on an existing
        // item, which is how an adoptable account appears without anyone deciding anything).
        String sync = JavaSource.withoutComments(JavaSource.read(SYNC));
        String ensure = JavaSource.methodBody(sync, "private void ensureUserItem");
        int update = ensure.indexOf("if (existing != null)");
        assertTrue(update > 0, "ensureUserItem no longer has an update path, so this lock is not reading "
                + "the place where an existing account's methods are rewritten");
        String updatePath = ensure.substring(update, ensure.indexOf("Folder usersFolder", update));
        assertTrue(updatePath.contains("SsoLogin.ROUTE_KEY"),
                "the sync writes allowedAuthMethods onto an account that already exists without recording "
                        + "that IT did: such an account then reads as 「an administrator allowed SSO」 and the "
                        + "first token naming it adopts it (R168)");
    }

    @Test
    @DisplayName("both OIDC entry points read the binding BACK after writing it")
    public void bothOidcEntryPointsReadTheBindingBack() throws Exception {
        // Constraint (d): a save can return without storing, and the next reader is handed the instance
        // this request mutated — so 「we wrote it」 is not 「it is stored」. What the read-back does NOT
        // undo is residual R196 (a refusal that throws never reaches it).
        for (String source : List.of(REST, API_V1)) {
            String body = JavaSource.withoutComments(JavaSource.read(source));
            int write = body.indexOf("applyUserUpdate");
            assertTrue(write > 0, source + " no longer writes the binding through applyUserUpdate, so "
                    + "this lock is not reading the write it is about");
            String after = body.substring(write);
            assertTrue(after.contains("boundSubject"), source + " writes the binding and does not read it "
                    + "back, so a login proceeds as an account whose link was never stored");
        }
    }
}
