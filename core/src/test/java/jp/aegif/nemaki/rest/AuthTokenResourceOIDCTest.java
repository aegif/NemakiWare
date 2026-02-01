package jp.aegif.nemaki.rest;

import org.json.simple.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.*;

/**
 * Unit tests for OIDC-related private methods in AuthTokenResource.
 * Tests URI validation (SSRF prevention) and username extraction logic.
 *
 * Uses reflection to test private methods directly, avoiding the need
 * to change access modifiers in production code.
 */
public class AuthTokenResourceOIDCTest {

	private AuthTokenResource resource;
	private Method isAllowedMethod;
	private Method extractUsernameMethod;

	@Before
	public void setup() throws Exception {
		resource = new AuthTokenResource();

		isAllowedMethod = AuthTokenResource.class.getDeclaredMethod(
				"isAllowedUserInfoEndpoint", String.class);
		isAllowedMethod.setAccessible(true);

		extractUsernameMethod = AuthTokenResource.class.getDeclaredMethod(
				"extractUserNameFromOIDCUserInfo", JSONObject.class, boolean.class);
		extractUsernameMethod.setAccessible(true);
	}

	private boolean isAllowed(String url) throws Exception {
		return (boolean) isAllowedMethod.invoke(resource, url);
	}

	private String extractUsername(JSONObject userInfo, boolean isMicrosoft) throws Exception {
		return (String) extractUsernameMethod.invoke(resource, userInfo, isMicrosoft);
	}

	// ---- isAllowedUserInfoEndpoint: allowed URLs ----

	@Test
	public void testAllowed_GoogleOAuth2() throws Exception {
		assertTrue(isAllowed("https://www.googleapis.com/oauth2/v3/userinfo"));
	}

	@Test
	public void testAllowed_GoogleOIDC() throws Exception {
		assertTrue(isAllowed("https://openidconnect.googleapis.com/v1/userinfo"));
	}

	@Test
	public void testAllowed_MicrosoftGraphOIDC() throws Exception {
		assertTrue(isAllowed("https://graph.microsoft.com/oidc/userinfo"));
	}

	@Test
	public void testAllowed_MicrosoftGraphMe() throws Exception {
		assertTrue(isAllowed("https://graph.microsoft.com/v1.0/me"));
	}

	@Test
	public void testAllowed_MicrosoftLogin() throws Exception {
		assertTrue(isAllowed("https://login.microsoftonline.com/common/openid/userinfo"));
	}

	@Test
	public void testAllowed_MicrosoftLoginV2() throws Exception {
		assertTrue(isAllowed("https://login.microsoftonline.com/common/v2.0/userinfo"));
	}

	// ---- isAllowedUserInfoEndpoint: rejected URLs ----

	@Test
	public void testRejected_HttpScheme() throws Exception {
		assertFalse(isAllowed("http://www.googleapis.com/oauth2/v3/userinfo"));
	}

	@Test
	public void testRejected_FtpScheme() throws Exception {
		assertFalse(isAllowed("ftp://www.googleapis.com/oauth2/v3/userinfo"));
	}

	@Test
	public void testRejected_UserinfoInUrl() throws Exception {
		// user:pass@ component should be rejected
		assertFalse(isAllowed("https://admin:pass@graph.microsoft.com/v1.0/me"));
	}

	@Test
	public void testRejected_UserinfoSsrfBypass() throws Exception {
		// graph.microsoft.com used as userinfo, actual host is evil.com
		assertFalse(isAllowed("https://graph.microsoft.com@evil.com/v1.0/me"));
	}

	@Test
	public void testRejected_NonStandardPort() throws Exception {
		assertFalse(isAllowed("https://graph.microsoft.com:8443/v1.0/me"));
	}

	@Test
	public void testRejected_Port80() throws Exception {
		assertFalse(isAllowed("https://graph.microsoft.com:80/v1.0/me"));
	}

	@Test
	public void testAllowed_ExplicitPort443() throws Exception {
		// Port 443 is the default for HTTPS, should be allowed
		assertTrue(isAllowed("https://graph.microsoft.com:443/v1.0/me"));
	}

	@Test
	public void testRejected_UnknownHost() throws Exception {
		assertFalse(isAllowed("https://evil.com/v1.0/me"));
	}

	@Test
	public void testRejected_SubdomainOfAllowedHost() throws Exception {
		assertFalse(isAllowed("https://evil.graph.microsoft.com/v1.0/me"));
	}

	@Test
	public void testRejected_DisallowedPath() throws Exception {
		assertFalse(isAllowed("https://graph.microsoft.com/v2.0/something"));
	}

	@Test
	public void testRejected_EncodedSlash() throws Exception {
		// %2f in raw path should be rejected (path traversal)
		assertFalse(isAllowed("https://graph.microsoft.com/v1.0%2fme"));
	}

	@Test
	public void testRejected_EncodedDot() throws Exception {
		// %2e in raw path should be rejected (path traversal)
		assertFalse(isAllowed("https://graph.microsoft.com/v1.0/%2e%2e/admin"));
	}

	@Test
	public void testRejected_EncodedBackslash() throws Exception {
		assertFalse(isAllowed("https://graph.microsoft.com/v1.0%5cme"));
	}

	@Test
	public void testRejected_DotDotInPath() throws Exception {
		// URI.normalize() should collapse ../
		assertFalse(isAllowed("https://graph.microsoft.com/v1.0/me/../../../etc/passwd"));
	}

	@Test
	public void testRejected_NullUrl() throws Exception {
		assertFalse(isAllowed(null));
	}

	@Test
	public void testRejected_EmptyUrl() throws Exception {
		assertFalse(isAllowed(""));
	}

	@Test
	public void testRejected_MalformedUrl() throws Exception {
		assertFalse(isAllowed("not-a-url"));
	}

	// ---- extractUserNameFromOIDCUserInfo: Standard OIDC (non-Microsoft) ----

	@Test
	@SuppressWarnings("unchecked")
	public void testExtract_Standard_PreferredUsername() throws Exception {
		JSONObject info = new JSONObject();
		info.put("preferred_username", "user@example.com");
		info.put("email", "alt@example.com");
		assertEquals("user@example.com", extractUsername(info, false));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testExtract_Standard_EmailFallback() throws Exception {
		JSONObject info = new JSONObject();
		info.put("email", "user@example.com");
		assertEquals("user@example.com", extractUsername(info, false));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testExtract_Standard_SubFallback() throws Exception {
		JSONObject info = new JSONObject();
		info.put("sub", "12345");
		assertEquals("12345", extractUsername(info, false));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testExtract_Standard_EmptyReturnsNull() throws Exception {
		JSONObject info = new JSONObject();
		assertNull(extractUsername(info, false));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testExtract_Standard_IgnoresUPN() throws Exception {
		// Non-Microsoft should not prioritize userPrincipalName
		JSONObject info = new JSONObject();
		info.put("userPrincipalName", "upn@example.com");
		info.put("preferred_username", "pu@example.com");
		assertEquals("pu@example.com", extractUsername(info, false));
	}

	// ---- extractUserNameFromOIDCUserInfo: Microsoft ----

	@Test
	@SuppressWarnings("unchecked")
	public void testExtract_Microsoft_UPNFirst() throws Exception {
		JSONObject info = new JSONObject();
		info.put("userPrincipalName", "upn@contoso.com");
		info.put("mail", "mail@contoso.com");
		info.put("preferred_username", "pu@contoso.com");
		assertEquals("upn@contoso.com", extractUsername(info, true));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testExtract_Microsoft_MailFallback() throws Exception {
		JSONObject info = new JSONObject();
		info.put("mail", "mail@contoso.com");
		info.put("preferred_username", "pu@contoso.com");
		assertEquals("mail@contoso.com", extractUsername(info, true));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testExtract_Microsoft_FallsBackToStandardOIDC() throws Exception {
		// If neither UPN nor mail, falls through to standard OIDC claims
		JSONObject info = new JSONObject();
		info.put("preferred_username", "pu@contoso.com");
		assertEquals("pu@contoso.com", extractUsername(info, true));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testExtract_Microsoft_EmptyUPN_FallsToMail() throws Exception {
		JSONObject info = new JSONObject();
		info.put("userPrincipalName", "");
		info.put("mail", "mail@contoso.com");
		assertEquals("mail@contoso.com", extractUsername(info, true));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testExtract_Microsoft_NullUPN_FallsToMail() throws Exception {
		JSONObject info = new JSONObject();
		info.put("userPrincipalName", null);
		info.put("mail", "mail@contoso.com");
		assertEquals("mail@contoso.com", extractUsername(info, true));
	}
}
