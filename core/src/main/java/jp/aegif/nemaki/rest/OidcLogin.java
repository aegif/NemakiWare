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
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package jp.aegif.nemaki.rest;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

/**
 * A login through the repository's OIDC provider, verified on the ID token (3.4.1, zero-trust P0).
 *
 * <p>The generic OIDC logins (the REST {@code /authtoken/oidc/convert} and the API v1 {@code /auth/.../oidc}) used to
 * call a UserInfo endpoint with whatever access token the client sent — an endpoint the client could name, among a
 * fixed list that included Google's and Microsoft's — and trusted the answer, whether or not OIDC was enabled. A token
 * the provider issued to any application, the provider's or another's, logged in as its holder, and a user was created
 * for it. The login now needs the ID token the provider issued to this application: signed with a key the configured
 * issuer publishes, naming that issuer, naming this application ({@code oidc.clientId}) as its audience, and not
 * expired — and OIDC enabled for the repository ({@code sso.oidc.enabled}).
 */
public class OidcLogin {

	private static final Logger logger = LoggerFactory.getLogger(OidcLogin.class);

	/** Asymmetric signatures only: an HMAC would be keyed with the client secret, which a public client does not have. */
	static final Set<JWSAlgorithm> ALGORITHMS = Set.of(JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
			JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512, JWSAlgorithm.ES256, JWSAlgorithm.ES384,
			JWSAlgorithm.ES512);

	/**
	 * The repository's OIDC settings, as {@link PropertyManager} reads them (the stored values first — admin-managed). The
	 * client ids are this application's at the issuer: {@code oidc.clientId}, a comma-separated list where more than one
	 * client of the issuer logs in to it (the screen's, an API client's).
	 */
	public record Settings(boolean enabled, String issuer, Set<String> clientIds) {
		public Settings {
			clientIds = clientIds == null ? Set.of() : Set.copyOf(clientIds);
		}

		public static Settings read(PropertyManager propertyManager, String repositoryId) {
			if (propertyManager == null) {
				return new Settings(false, null, Set.of());
			}
			String enabled = propertyManager.readValue(repositoryId, PropertyKey.SSO_OIDC_ENABLED);
			Set<String> clientIds = new java.util.LinkedHashSet<>();
			String listed = propertyManager.readValue(repositoryId, PropertyKey.OIDC_CLIENT_ID);
			if (listed != null) {
				for (String clientId : listed.split(",")) {
					if (!clientId.isBlank()) {
						clientIds.add(clientId.trim());
					}
				}
			}
			return new Settings(enabled != null && "true".equalsIgnoreCase(enabled.trim()),
					trimmed(propertyManager.readValue(repositoryId, PropertyKey.OIDC_ISSUER)), clientIds);
		}
	}

	/** The user a verified ID token names — with the issuer and subject that identify them there — or why it is refused. */
	public record Outcome(String userName, String issuer, String subject, String refusal) {
		static Outcome refused(String why) {
			return new Outcome(null, null, null, why);
		}

		public boolean verified() {
			return userName != null;
		}
	}

	/**
	 * Where an account keeps the OIDC subject it is linked to — the issuer and the subject, as the ID token names them. A
	 * name the token carries ({@code preferred_username}) is the IdP's to give and may be anyone's to choose, so it finds the
	 * account; the subject, stable at the issuer, decides whether this token may act as it (3.4.1 — batch 16 review round 1).
	 */
	public static final String SUBJECT_KEY = "nemaki:oidcSubject";

	/** The issuer the provider signs as, and its keys — from its discovery document. */
	public record Provider(String issuer, JWKSource<SecurityContext> keys) {
	}

	/** Reads a provider's discovery document. */
	public interface Discovery {
		Provider at(String configuredIssuer) throws Exception;
	}

	private final Discovery discovery;

	/** One discovery cache for every login: the resources are made per request. */
	private static final Discovery SHARED = new CachedDiscovery();

	public OidcLogin() {
		this(SHARED);
	}

	public OidcLogin(Discovery discovery) {
		this.discovery = discovery;
	}

	/** Verifies the ID token against the repository's settings. */
	public Outcome verify(Settings settings, String idToken) {
		if (!settings.enabled()) {
			return Outcome.refused("OIDC login is not enabled for this repository (sso.oidc.enabled)");
		}
		if (settings.issuer() == null || settings.clientIds().isEmpty()) {
			return Outcome.refused("OIDC login is not configured for this repository (oidc.issuer and oidc.clientId)");
		}
		if (idToken == null || idToken.isBlank()) {
			return Outcome.refused("the ID token the provider issued is required (id_token)");
		}
		Provider provider;
		try {
			provider = discovery.at(settings.issuer());
		} catch (Exception e) {
			logger.warn("OIDC: the discovery document of {} could not be read: {}", settings.issuer(), e.getMessage());
			return Outcome.refused("the OIDC provider's discovery document could not be read");
		}
		JWTClaimsSet claims;
		try {
			DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
			processor.setJWSKeySelector(new JWSVerificationKeySelector<>(ALGORITHMS, provider.keys()));
			processor.setJWTClaimsSetVerifier(claimsVerifier(provider.issuer(), settings.clientIds()));
			claims = processor.process(idToken, null);
		} catch (Exception e) {
			logger.info("OIDC: an ID token was refused: {}", e.getMessage());
			return Outcome.refused("the ID token was not issued to this application by the configured provider, or has expired");
		}
		String userName = userName(claims);
		if (userName == null) {
			return Outcome.refused("the ID token names no user, or names one in a form this login does not read "
					+ "(preferred_username, a verified email, or sub)");
		}
		return new Outcome(userName, provider.issuer(), claims.getSubject(), null);
	}

	/** The issuer, this application as the audience (and as the authorised party where there are others), the times. */
	DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier(String issuer, Set<String> clientIds) {
		// A set that answers contains(null) — the verifier asks it whether a token naming no audience is accepted (no).
		return new DefaultJWTClaimsVerifier<>(new java.util.HashSet<>(clientIds), new JWTClaimsSet.Builder().issuer(issuer).build(),
				new java.util.HashSet<>(Set.of("sub", "iat", "exp")), null) {
			@Override
			public void verify(JWTClaimsSet claimsSet, SecurityContext context) throws BadJWTException {
				super.verify(claimsSet, context);
				List<String> audience = claimsSet.getAudience();
				Object azp = claimsSet.getClaim("azp");
				if ((audience.size() > 1 || azp != null) && (!(azp instanceof String) || !clientIds.contains(azp))) {
					throw new BadJWTException("the ID token's authorised party is not this application");
				}
			}

			@Override
			protected Date currentTime() {
				return now();
			}
		};
	}

	/** The clock the expiry is read against; tests move it. */
	Date now() {
		return new Date();
	}

	/**
	 * The user the token names: {@code preferred_username}, or the email where the provider says it verified it, or the
	 * subject. An email the provider has not verified is anyone's to write (2023's "nOAuth"), so it names no one.
	 */
	static String userName(JWTClaimsSet claims) {
		// A claim in a form this does not read is not taken for an absent one: it would name another user (the subject).
		Object preferred = claims.getClaim("preferred_username");
		Object email = claims.getClaim("email");
		Object verified = claims.getClaim("email_verified");
		if ((preferred != null && !(preferred instanceof String)) || (email != null && !(email instanceof String))
				|| (verified != null && !(verified instanceof Boolean))) {
			return null;
		}
		if (preferred instanceof String s && !s.isBlank()) {
			return s;
		}
		if (email instanceof String s && !s.isBlank() && Boolean.TRUE.equals(verified)) {
			return s;
		}
		String subject = claims.getSubject();
		return subject == null || subject.isBlank() ? null : subject;
	}

	/** What an account linked to this login's subject keeps under {@link #SUBJECT_KEY}. */
	public static String binding(Outcome outcome) {
		return outcome.issuer() + " " + outcome.subject();
	}

	/**
	 * Why the token may not act as the existing account it names, or null. An account linked to an OIDC subject is this
	 * token's only if the subject is the token's. An account not linked yet is the token's only where an administrator
	 * allowed SSO for it — {@code cloud} listed in {@code nemaki:allowedAuthMethods}, as the accounts SSO creates have it —
	 * and is linked by this login; an account that allows every method by listing none (the built-in {@code admin}, the
	 * service accounts, accounts made with a password) is not, whatever name the IdP gives (batch 16 review round 1: both
	 * reviewers — the name was trusted, and the built-in admin was anyone's whom the IdP let choose it).
	 */
	public static String refusal(jp.aegif.nemaki.model.UserItem account, Outcome outcome) {
		// A disabled account is not eligible, whatever else the list says, and whether or not it is already
		// linked (3.4.1, batch 18 review rounds 1 and 2). "cloud,disabled" used to be read as eligible, and the
		// caller writes the nemaki:oidcSubject binding BEFORE the authentication-method gate refuses the login —
		// so a disabled account was bound permanently to whoever presented a token for its name, and re-enabling
		// it let them in. This is asked FIRST: behind the linked branch it answered "no reason to refuse" for a
		// linked account that is disabled, which contradicts this method's own contract (only the caller's later
		// gate refused it). Round 2 moved it ahead of that branch.
		String methods = property(account, "nemaki:allowedAuthMethods");
		if (jp.aegif.nemaki.util.AuthenticationUtil.isDisabledValue(methods)) {
			return "the account is disabled";
		}
		String linked = property(account, SUBJECT_KEY);
		if (linked != null) {
			return linked.equals(binding(outcome)) ? null : "the account is linked to another OIDC subject";
		}
		if (methods != null) {
			for (String method : methods.split(",")) {
				if ("cloud".equalsIgnoreCase(method.trim())) {
					return null;
				}
			}
		}
		return "the account is not linked to this OIDC subject and does not allow SSO (cloud) — an administrator allows it";
	}

	/** Whether the account is linked to no OIDC subject yet. */
	public static boolean unlinked(jp.aegif.nemaki.model.UserItem account) {
		return property(account, SUBJECT_KEY) == null;
	}

	private static String property(jp.aegif.nemaki.model.UserItem account, String key) {
		if (account.getSubTypeProperties() == null) {
			return null;
		}
		for (jp.aegif.nemaki.model.Property property : account.getSubTypeProperties()) {
			if (key.equals(property.getKey())) {
				Object value = property.getValue();
				return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
			}
		}
		return null;
	}

	private static String trimmed(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String v = value.trim();
		return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
	}

	/**
	 * The provider's discovery document, read at {@code {oidc.issuer}/.well-known/openid-configuration} without following
	 * redirects, and kept for ten minutes. The issuer it names is the one the tokens must carry: behind split host names
	 * (a container reading the provider at its internal address while browsers use a public one) the provider signs as
	 * the public name, and says so here — the document is read from the address the administrator configured, as are
	 * the keys it lists, so trusting its issuer trusts nothing the keys do not.
	 */
	static final class CachedDiscovery implements Discovery {
		private static final long TTL_MILLIS = 10 * 60 * 1000L;
		private final ConcurrentHashMap<String, Object[]> cache = new ConcurrentHashMap<>();

		@Override
		public Provider at(String configuredIssuer) throws Exception {
			Object[] held = cache.get(configuredIssuer);
			if (held != null && (long) held[0] > System.currentTimeMillis()) {
				return (Provider) held[1];
			}
			HttpURLConnection connection = (HttpURLConnection) URI.create(configuredIssuer + "/.well-known/openid-configuration")
					.toURL().openConnection();
			connection.setInstanceFollowRedirects(false);
			connection.setRequestProperty("Accept", "application/json");
			connection.setConnectTimeout(5000);
			connection.setReadTimeout(5000);
			if (connection.getResponseCode() != 200) {
				throw new IllegalStateException("HTTP " + connection.getResponseCode());
			}
			JSONObject document;
			try (java.io.Reader reader = new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
				document = (JSONObject) new JSONParser().parse(reader);
			}
			Object issuer = document.get("issuer");
			Object jwksUri = document.get("jwks_uri");
			if (!(issuer instanceof String) || ((String) issuer).isBlank() || !(jwksUri instanceof String)) {
				throw new IllegalStateException("the document names no issuer or jwks_uri");
			}
			JWKSource<SecurityContext> keys = JWKSourceBuilder.create(new URL((String) jwksUri)).retrying(true).build();
			Provider provider = new Provider((String) issuer, keys);
			cache.put(configuredIssuer, new Object[] {System.currentTimeMillis() + TTL_MILLIS, provider});
			return provider;
		}
	}
}
