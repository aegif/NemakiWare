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
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     aegif - WebAuthn credential-repository binding tests
 ******************************************************************************/
package jp.aegif.nemaki.rest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.WebAuthnCredential;

/**
 * Regression tests for the WebAuthn credential-user binding in
 * {@code WebAuthnResource.NemakiCredentialRepository.lookup(...)}.
 *
 * <p>These directly exercise the production inner class (via reflection, since it
 * is a private non-static member) so that a removal of the stored-owner binding
 * would fail the suite. They guard against the authentication-bypass where the
 * caller-supplied (attacker-controllable) userHandle is echoed back instead of
 * the credential's registered owner.
 */
public class WebAuthnResourceLookupTest {

	private static final String REPO = "bedroom";
	/** base64url for bytes {1,2,3} — only needs to be parseable by ByteArray. */
	private static final String VALID_COSE_B64URL = "AQID";

	private WebAuthnResource resource;
	private ContentDaoService dao;

	@BeforeEach
	public void setUp() throws Exception {
		resource = new WebAuthnResource();
		dao = mock(ContentDaoService.class);
		Field f = WebAuthnResource.class.getDeclaredField("contentDaoService");
		f.setAccessible(true);
		f.set(resource, dao);
	}

	@SuppressWarnings("unchecked")
	private Optional<RegisteredCredential> invokeLookup(ByteArray credentialId, ByteArray userHandle)
			throws Exception {
		Class<?> repoClass = Class.forName(
				"jp.aegif.nemaki.rest.WebAuthnResource$NemakiCredentialRepository");
		Constructor<?> ctor = repoClass.getDeclaredConstructor(WebAuthnResource.class, String.class);
		ctor.setAccessible(true);
		Object repo = ctor.newInstance(resource, REPO);
		Method lookup = repoClass.getDeclaredMethod("lookup", ByteArray.class, ByteArray.class);
		lookup.setAccessible(true);
		return (Optional<RegisteredCredential>) lookup.invoke(repo, credentialId, userHandle);
	}

	private WebAuthnCredential storedCredentialOwnedBy(String userId) {
		WebAuthnCredential cred = new WebAuthnCredential();
		cred.setUserId(userId);
		cred.setPublicKeyCose(VALID_COSE_B64URL);
		cred.setSignCount(0L);
		return cred;
	}

	private static ByteArray handleOf(String userId) {
		return new ByteArray(userId.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void lookupBindsToStoredOwnerWhenUserHandleMatches() throws Exception {
		ByteArray credId = handleOf("cred-owned-by-bob");
		when(dao.getWebAuthnCredentialByCredentialId(REPO, credId.getBase64Url()))
				.thenReturn(storedCredentialOwnedBy("bob"));

		Optional<RegisteredCredential> result = invokeLookup(credId, handleOf("bob"));

		assertTrue(result.isPresent(), "matching userHandle should resolve the credential");
		assertEquals(handleOf("bob"), result.get().getUserHandle(),
				"userHandle must be the stored owner");
	}

	@Test
	public void lookupRejectsMismatchedUserHandle() throws Exception {
		// Attacker presents their own credential but spoofs userHandle = 'admin'.
		ByteArray attackerCredId = handleOf("cred-owned-by-attacker");
		when(dao.getWebAuthnCredentialByCredentialId(REPO, attackerCredId.getBase64Url()))
				.thenReturn(storedCredentialOwnedBy("attacker"));

		Optional<RegisteredCredential> result = invokeLookup(attackerCredId, handleOf("admin"));

		assertFalse(result.isPresent(),
				"a credential must NOT resolve under a userHandle other than its registered owner");
	}

	@Test
	public void lookupWithNullUserHandleBindsToStoredOwner() throws Exception {
		ByteArray credId = handleOf("cred-owned-by-carol");
		when(dao.getWebAuthnCredentialByCredentialId(REPO, credId.getBase64Url()))
				.thenReturn(storedCredentialOwnedBy("carol"));

		Optional<RegisteredCredential> result = invokeLookup(credId, null);

		assertTrue(result.isPresent(), "null userHandle should still resolve to the stored owner");
		assertEquals(handleOf("carol"), result.get().getUserHandle());
	}

	@Test
	public void lookupReturnsEmptyForUnknownCredential() throws Exception {
		ByteArray credId = handleOf("unknown-cred");
		when(dao.getWebAuthnCredentialByCredentialId(REPO, credId.getBase64Url()))
				.thenReturn(null);

		assertFalse(invokeLookup(credId, handleOf("anyone")).isPresent());
	}
}
