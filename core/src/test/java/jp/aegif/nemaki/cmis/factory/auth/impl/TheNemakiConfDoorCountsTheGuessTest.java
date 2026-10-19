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
package jp.aegif.nemaki.cmis.factory.auth.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.server.impl.CallContextImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.SystemConst;

/**
 * The {@code nemaki_conf} door counts the guesses, on the same counter as the repository whose password
 * it checks (R186, 3.4.1).
 *
 * <p>{@code AuthenticationFilter} routes every request whose URL repository is {@code nemaki_conf} to
 * {@link AuthenticationServiceImpl#loginForNemakiConfDb}, which verified the password and nothing else:
 * no door anywhere counted the attempt. The guessing that stops after 15 failures over REST, the CMIS
 * bindings, API v1 and MCP was unlimited at
 * {@code curl -u admin:guess http://host/core/rest/repo/nemaki_conf/user/list}, with the 401 as the
 * oracle, and that is the BUILT-IN admin's password: the door reads it from the super-users repository.
 *
 * <p>Driven through the production method with the production throttle (the field is not injectable),
 * so what is measured is the door's own behaviour and not a collaborator's. The counter's own
 * semantics are {@code LoginThrottleTest}'s; the key's single construction is locked in
 * {@code GuessingIsCountedPerCallerAndSecretsStayOutOfTheLogTest}.
 *
 * <p>Two of these arms are about WHICH key, not whether: the super-users repository is a real CMIS
 * repository ({@code bedroom} as shipped), so the same credential is also checkable at
 * {@code /rest/repo/bedroom/...}, where it has counted since S5. A door that counted under
 * {@code nemaki_conf} would stop its own 16th guess and still hand out a fresh budget of 15 for the
 * one secret next door, in both directions.
 */
class TheNemakiConfDoorCountsTheGuessTest {

	/** {@code super.users} in repositories-default.yml — the repository the password is read from. */
	private static final String SUPER_USERS = "bedroom";

	private static final String ADMIN = "admin";
	private static final String PASSWORD = "the-real-one";

	/** {@code nemakiware.security.loginThrottle.maxFailures}'s default. */
	private static final int MAX_FAILURES = 15;

	private AuthenticationServiceImpl service;
	private ContentService contentService;

	@BeforeEach
	void setUp() {
		service = new AuthenticationServiceImpl();
		contentService = mock(ContentService.class);
		service.setContentService(contentService);
		service.setPropertyManager(mock(PropertyManager.class));
		// Every path that does NOT return a user ends at the anonymous check, so a wrong password
		// needs this wired or the door throws before it can count anything.
		service.setPrincipalService(mock(jp.aegif.nemaki.businesslogic.PrincipalService.class));

		RepositoryInfoMap repositories = mock(RepositoryInfoMap.class);
		RepositoryInfo superUsers = mock(RepositoryInfo.class);
		when(superUsers.getId()).thenReturn(SUPER_USERS);
		when(repositories.getSuperUsers()).thenReturn(superUsers);
		when(repositories.contains(SUPER_USERS)).thenReturn(true);
		service.setRepositoryInfoMap(repositories);

		accountExists(ADMIN, true, null);
	}

	/** An account in the super-users repository, with a cheap BCrypt hash. */
	private void accountExists(String userId, boolean admin, String allowedAuthMethods) {
		UserItem user = new UserItem(userId + "-doc", "nemaki:user", userId, userId,
				BCrypt.hashpw(PASSWORD, BCrypt.gensalt(4)), admin, null);
		if (allowedAuthMethods != null) {
			user.setSubTypeProperties(
					List.of(new Property("nemaki:allowedAuthMethods", allowedAuthMethods)));
		}
		when(contentService.getUserItemById(SUPER_USERS, userId)).thenReturn(user);
	}

	/** A request as the REST filter builds it for {@code /rest/repo/nemaki_conf/...}. */
	private CallContextImpl confDoor(String userId, String password) {
		return request(SystemConst.NEMAKI_CONF_DB, userId, password);
	}

	/** The same credential as the super-users repository's own Basic door sees it. */
	private CallContextImpl basicDoor(String userId, String password) {
		return request(SUPER_USERS, userId, password);
	}

	private CallContextImpl request(String repositoryId, String userId, String password) {
		CallContextImpl ctxt = new CallContextImpl(null, CmisVersion.CMIS_1_1, repositoryId,
				null, null, null, null, null);
		ctxt.put(org.apache.chemistry.opencmis.commons.server.CallContext.USERNAME, userId);
		ctxt.put(org.apache.chemistry.opencmis.commons.server.CallContext.PASSWORD, password);
		// One caller, so every arm here keys on the same address instead of on "unknown".
		ctxt.put("remoteAddress", "198.51.100.7");
		return ctxt;
	}

	@Test
	@DisplayName("15 wrong passwords at this door stop the 16th, even when the 16th is right")
	void fifteenWrongPasswordsStopTheCorrectOne() {
		for (int attempt = 0; attempt < MAX_FAILURES; attempt++) {
			assertFalse(service.loginForNemakiConfDb(confDoor(ADMIN, "guess-" + attempt)),
					"a wrong password was accepted");
		}

		assertFalse(service.loginForNemakiConfDb(confDoor(ADMIN, PASSWORD)),
				"the door answered after " + MAX_FAILURES + " failures, so the built-in admin's password"
						+ " can be tried here without limit while every other door stops at "
						+ MAX_FAILURES);
	}

	@Test
	@DisplayName("during the lockout it refuses without reading the account at all")
	void duringTheLockoutItDoesNotEvenReadTheAccount() {
		for (int attempt = 0; attempt < MAX_FAILURES; attempt++) {
			service.loginForNemakiConfDb(confDoor(ADMIN, "guess-" + attempt));
		}
		clearInvocations(contentService);

		assertFalse(service.loginForNemakiConfDb(confDoor(ADMIN, PASSWORD)),
				"the door answered during its own lockout");
		verify(contentService, never()).getUserItemById(SUPER_USERS, ADMIN);
	}

	@Test
	@DisplayName("the failures here block the super-users repository's own Basic door")
	void theFailuresHereBlockTheRepositoriesOwnDoor() {
		for (int attempt = 0; attempt < MAX_FAILURES; attempt++) {
			service.loginForNemakiConfDb(confDoor(ADMIN, "guess-" + attempt));
		}

		assertFalse(service.login(basicDoor(ADMIN, PASSWORD)),
				"guessing at /rest/repo/nemaki_conf/ left /rest/repo/" + SUPER_USERS + "/ unlocked for"
						+ " the same name, password and caller, so the counter is a second budget for"
						+ " one secret instead of the same one");
	}

	@Test
	@DisplayName("and the Basic door's failures block this one")
	void theRepositoriesOwnFailuresBlockThisDoor() {
		for (int attempt = 0; attempt < MAX_FAILURES; attempt++) {
			service.login(basicDoor(ADMIN, "guess-" + attempt));
		}

		assertFalse(service.loginForNemakiConfDb(confDoor(ADMIN, PASSWORD)),
				"a lockout at /rest/repo/" + SUPER_USERS + "/ means nothing: the guessing simply moves"
						+ " to /rest/repo/nemaki_conf/ and carries on");
	}

	@Test
	@DisplayName("a correct password clears the count")
	void aCorrectPasswordClearsTheCount() {
		for (int attempt = 0; attempt < MAX_FAILURES - 1; attempt++) {
			service.loginForNemakiConfDb(confDoor(ADMIN, "guess-" + attempt));
		}

		assertTrue(service.loginForNemakiConfDb(confDoor(ADMIN, PASSWORD)),
				"the correct password was refused at " + (MAX_FAILURES - 1) + " failures, one short of"
						+ " the lockout");
		service.loginForNemakiConfDb(confDoor(ADMIN, "guess-again"));

		assertTrue(service.loginForNemakiConfDb(confDoor(ADMIN, PASSWORD)),
				"a successful login did not clear the count, so the next single failure locks the"
						+ " account out: the door over-refuses the person who just logged in");
	}

	@Test
	@DisplayName("empty passwords are not counted against the name")
	void emptyPasswordsAreNotCountedAgainstTheName() {
		// Ambient credentials: a browser re-sends the Basic realm with nothing in it, and a probe can
		// be addressed at any name. Counting those would be a way to lock anyone out without ever
		// trying a password.
		for (int attempt = 0; attempt < MAX_FAILURES; attempt++) {
			assertFalse(service.loginForNemakiConfDb(confDoor(ADMIN, "")),
					"a blank password authenticated");
		}

		assertTrue(service.loginForNemakiConfDb(confDoor(ADMIN, PASSWORD)),
				MAX_FAILURES + " blank-password probes locked the name out, so anyone can lock out the"
						+ " built-in admin without guessing anything");
	}

	@Test
	@DisplayName("a correct password that is not an admin's is still refused, and still clears the count")
	void aCorrectPasswordThatIsNotAnAdminsIsStillRefused() {
		// What this door is for has not changed: it answers to admins. The counter is cleared for a
		// correct password all the same, because the refusal below is authorization and not a failed
		// password — and it is no way to reset someone else's lockout, since the key carries the name
		// whose password was given.
		accountExists("bob", false, null);

		assertFalse(service.loginForNemakiConfDb(confDoor("bob", PASSWORD)),
				"a non-admin was let into the configuration repository");

		for (int attempt = 0; attempt < MAX_FAILURES - 1; attempt++) {
			service.loginForNemakiConfDb(confDoor("bob", "guess-" + attempt));
		}
		service.loginForNemakiConfDb(confDoor("bob", PASSWORD));
		assertFalse(service.login(basicDoor("bob", "guess-last")),
				"a wrong password was accepted");
		assertTrue(service.login(basicDoor("bob", PASSWORD)),
				"the correct password did not clear the count for a non-admin, so " + MAX_FAILURES
						+ " attempts at this door lock a non-admin out of the repository itself");
	}
}
