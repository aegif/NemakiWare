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
        UserItem syncedFromGoogle = account("nemaki:allowedAuthMethods", "cloud",
                SsoLogin.ROUTE_KEY, SsoLogin.GOOGLE);

        String refused = SsoLogin.refusal(syncedFromGoogle, SsoLogin.OIDC, "https://idp.example iss-subject");
        assertNotNull(refused, "a generic OIDC token adopted an account the Google directory sync made");
        assertTrue(refused.contains(SsoLogin.GOOGLE), "the refusal does not name the route that "
                + "made the account: " + refused);

        // AND THE PROVIDER'S OWN LOGIN STILL WORKS. The first version of this assertion passed
        // 「directory-sync」 as the route — a value no login can arrive by — and read green while
        // every one of the four routes refused such an account. The nightly sync marks every user
        // it touches, so that was an SSO lockout by morning with no way to clear the mark
        // (batch-22 review: both reviewers, P1). [[unreachable-arm-is-a-false-claim]]
        assertNull(SsoLogin.refusal(syncedFromGoogle, SsoLogin.GOOGLE, "https://accounts.google.com 123"),
                "an account the Google sync provisioned cannot log in with Google — and a synced "
                        + "account has a random password, so it has no other door");
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
    @DisplayName("on a LINKED account, a login carrying no subject does not pass as «not linked»")
    public void aLoginWithNoSubjectIsRefusedNotTreatedAsUnlinked() {
        // ON A LINKED ACCOUNT. An UNLINKED account that lists cloud is still adopted by a login that
        // carries no subject (a SAML response with no NameID, a Microsoft token with no oid): nothing
        // is written and the name alone stays the credential. That is residual R208 — the owner's
        // constraint (d) is about the stored value being empty, and widening it would refuse those
        // deployments outright (batch-22..24 review, subagent P3-5).
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
            for (String route : List.of(SsoLogin.OIDC, SsoLogin.SAML, SsoLogin.GOOGLE, SsoLogin.MICROSOFT)) {
                // EVERY route, as the name says: the first version looped over the two method
                // spellings with the route fixed to SAML (batch-22 review, subagent P3-7).
                UserItem disabled = account("nemaki:allowedAuthMethods", methods,
                        SsoLogin.subjectKey(route), "https://idp.example alice-nameid");
                assertEquals("the account is disabled",
                        SsoLogin.refusal(disabled, route, "https://idp.example alice-nameid"),
                        "a disabled account was logged into by " + route + " because it was already linked");
            }
        }
    }

    @Test
    @DisplayName("every SSO arm asks the rule with its OWN route, and none takes an account without asking")
    public void everyArmAsksTheRuleWithItsOwnRoute() throws Exception {
        // Literal text out, not comments: these assertions are about what the code CALLS, and a
        // string in an error message would otherwise answer for a call (batch-22 review, subagent
        // P3-4). [[test-helpers-already-exist-in-util-test]]
        String rest = JavaSource.withoutLiteralText(JavaSource.read(REST));

        // IN ITS OWN METHOD, not once each in the file: counting occurrences let the SAML and Google
        // constants be SWAPPED and stay green, which binds an account for one route and answers for
        // the other (batch-22 review, Codex P2-1).
        for (String[] arm : new String[][] {
                { "public String convertSAMLToken", "SsoLogin.SAML" },
                { "public String convertOIDCToken", "SsoLogin.OIDC" },
                { "public String convertGoogleToken", "SsoLogin.GOOGLE" },
                { "public String convertMicrosoftToken", "SsoLogin.MICROSOFT" } }) {
            String body = JavaSource.methodBody(rest, arm[0]);
            Matcher asks = Pattern.compile("accountForSsoLogin\\([^;]*?" + Pattern.quote(arm[1]))
                    .matcher(body);
            assertTrue(asks.find(), arm[0] + " does not ask the shared rule with " + arm[1]
                    + ". Each of the four has to ask it, with ITS OWN route: an arm that does not is "
                    + "back to taking whatever account carries the name the IdP gave (R175), and an "
                    + "arm that names another route compares against the wrong binding");
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
        //
        // EACH WRITE SITE, not once per file: 「the file mentions ROUTE_KEY somewhere」 stayed green
        // when the mark was taken off the sync's create path, because its update path still had one
        // (batch-22 review, Codex P2-1). Each span below is the method that builds the properties.
        for (String[] site : new String[][] {
                { REST, "private UserItem getOrCreateUser" },
                { API_V1, "private UserItem getOrCreateUser" },
                { SYNC, "private void ensureUserItem" } }) {
            String body = JavaSource.methodBody(
                    JavaSource.withoutLiteralText(JavaSource.read(site[0])), site[1]);
            assertTrue(body.contains("ROUTE_KEY"), site[0] + "'s " + site[1] + " writes accounts with "
                    + "nemaki:allowedAuthMethods and does not record which route made them, so the rule "
                    + "cannot tell an administrator's decision from its own writing (R168)");
        }

        // The sync's UPDATE path as well as its create: that is where an account made with a password
        // becomes a cloud account (the same method writes allowedAuthMethods on an existing item, which
        // is how an adoptable account appears without anyone deciding anything).
        String ensure = JavaSource.methodBody(
                JavaSource.withoutLiteralText(JavaSource.read(SYNC)), "private void ensureUserItem");
        int update = ensure.indexOf("if (existing != null)");
        int create = ensure.indexOf("Folder usersFolder");
        assertTrue(update > 0 && create > update, "ensureUserItem no longer has an update path followed "
                + "by a create path, so this lock is not reading the two places it is about: update="
                + update + " create=" + create);
        assertTrue(ensure.substring(update, create).contains("ROUTE_KEY"),
                "the sync writes allowedAuthMethods onto an account that already exists without recording "
                        + "that IT did: such an account then reads as 「an administrator allowed SSO」 and the "
                        + "first token naming it adopts it (R168)");
        assertTrue(ensure.substring(create).contains("ROUTE_KEY"),
                "the sync creates accounts without recording that it did");

        // AND THE VALUE has to be a route a login can arrive by. 「it writes the key」 was not enough:
        // writing 「directory-sync」 refuses the account at all four routes, and the nightly sync marks
        // every user it touches — an SSO lockout by morning with no way to clear it (batch-22 review:
        // both reviewers, P1; measured — control MA5 did not fire until this assertion).
        String syncBody = JavaSource.withoutLiteralText(JavaSource.read(SYNC));
        for (String[] provider : new String[][] {
                { "private void syncGoogleUser(", "SsoLogin.GOOGLE" },
                { "private void syncMicrosoftUser(", "SsoLogin.MICROSOFT" } }) {
            String body = JavaSource.methodBody(syncBody, provider[0]);
            // EVERY call, not at least one: each provider's sync calls ensureUserItem twice (the
            // account exists, or it does not), and a sabotage of one of them left 「at least one」
            // green (measured — control MA5).
            Matcher ensures = Pattern.compile("ensureUserItem\\([^;]*?" + Pattern.quote(provider[1]))
                    .matcher(body);
            int withTheRoute = 0;
            while (ensures.find()) {
                withTheRoute++;
            }
            Matcher all = Pattern.compile("ensureUserItem\\(").matcher(body);
            int calls = 0;
            while (all.find()) {
                calls++;
            }
            assertTrue(calls > 0, provider[0] + " no longer calls ensureUserItem, so this lock is not "
                    + "reading the place where the mark is written");
            assertEquals(calls, withTheRoute, provider[0] + " calls ensureUserItem " + calls + " time(s) "
                    + "and passes " + provider[1] + " in " + withTheRoute + " of them. A mark no login "
                    + "can arrive by refuses the account at every route, and a synced account has a "
                    + "random password — it has no other door");
        }
    }

    @Test
    @DisplayName("both OIDC entry points read the binding BACK after writing it")
    public void bothOidcEntryPointsReadTheBindingBack() throws Exception {
        // Constraint (d): a save can return without storing, and the next reader is handed the instance
        // this request mutated — so 「we wrote it」 is not 「it is stored」. What the read-back does NOT
        // undo is residual R196 (a refusal that throws never reaches it).
        for (String source : List.of(REST, API_V1)) {
            String body = JavaSource.withoutLiteralText(JavaSource.read(source));
            int write = body.indexOf("applyUserUpdate");
            assertTrue(write > 0, source + " no longer writes the binding through applyUserUpdate, so "
                    + "this lock is not reading the write it is about");
            String after = body.substring(write);
            // The NAME was not enough: reading the stored value and then not comparing it, or
            // comparing it and not refusing, both left this green (batch-22 review, Codex P2-1).
            assertTrue(after.contains("boundSubject"), source + " writes the binding and does not read it "
                    + "back, so a login proceeds as an account whose link was never stored");
            int read = after.indexOf("boundSubject");
            String afterRead = after.substring(read);
            assertTrue(afterRead.contains("binding.equals(") || afterRead.contains(".equals(storedBinding)"),
                    source + " reads the stored binding back and does not compare it with the one this "
                            + "login carries");
            assertTrue(afterRead.contains("return null") || afterRead.contains("throw "),
                    source + " compares the stored binding and does not refuse the login when it differs");
            // FROM THE STORE: a read through the user cache is handed the instance the write just
            // produced, so it compares that instance with itself and proves nothing (R207).
            assertTrue(after.contains("getUserItemByIdFromStore("),
                    source + " reads the binding back through the cache, which holds what the write "
                            + "produced — the comparison is then the written value against itself");
        }

        // THE CREATE PATH is no longer a separate span (R210, 3.4.1 closing batch 5): an account this login
        // created — or found in the race — is read back from the store and then takes the SAME steps as one found
        // at first, the rule and the binding written and read back. What this half of the lock read by shape is
        // DRIVEN now, in both doors, by OidcLoginEntryPointsTest: the rule asked of the store's copy
        // (anAccountThatAppearedDuringTheCreateIsJudgedByTheRule, R209), the binding read from the store
        // (aCreatedAccountWhoseBindingIsNotStoredRefusesTheLogin, R207), the race's unbound account linked
        // (anUnboundAccountFoundInTheRaceIsLinked, R210) and the absent store copy refused for that reason
        // (aCreatedAccountTheStoreDoesNotHaveIsRefusedForThatReason, R211). Two forms got past the shape reading
        // (a unicode escape in an identifier, mutating the object instead of the reference) and none gets past
        // a run.
    }
}
