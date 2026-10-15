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

	/** The repository's OIDC settings, as {@link PropertyManager} reads them (the stored values first — admin-managed). */
	public record Settings(boolean enabled, String issuer, String clientId) {
		public static Settings read(PropertyManager propertyManager, String repositoryId) {
			if (propertyManager == null) {
				return new Settings(false, null, null);
			}
			String enabled = propertyManager.readValue(repositoryId, PropertyKey.SSO_OIDC_ENABLED);
			return new Settings(enabled != null && "true".equalsIgnoreCase(enabled.trim()),
					trimmed(propertyManager.readValue(repositoryId, PropertyKey.OIDC_ISSUER)),
					trimmed(propertyManager.readValue(repositoryId, PropertyKey.OIDC_CLIENT_ID)));
		}
	}

	/** The user name a verified ID token names, or why the login is refused — never both. */
	public record Outcome(String userName, String refusal) {
		static Outcome refused(String why) {
			return new Outcome(null, why);
		}

		public boolean verified() {
			return userName != null;
		}
	}

	/** The issuer the provider signs as, and its keys — from its discovery document. */
	public record Provider(String issuer, JWKSource<SecurityContext> keys) {
	}

	/** Reads a provider's discovery document. */
	public interface Discovery {
		Provider at(String configuredIssuer) throws Exception;
	}

	private final Discovery discovery;

	public OidcLogin() {
		this(new CachedDiscovery());
	}

	public OidcLogin(Discovery discovery) {
		this.discovery = discovery;
	}

	/** Verifies the ID token against the repository's settings. */
	public Outcome verify(Settings settings, String idToken) {
		if (!settings.enabled()) {
			return Outcome.refused("OIDC login is not enabled for this repository (sso.oidc.enabled)");
		}
		if (settings.issuer() == null || settings.clientId() == null) {
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
			processor.setJWTClaimsSetVerifier(claimsVerifier(provider.issuer(), settings.clientId()));
			claims = processor.process(idToken, null);
		} catch (Exception e) {
			logger.info("OIDC: an ID token was refused: {}", e.getMessage());
			return Outcome.refused("the ID token was not issued to this application by the configured provider, or has expired");
		}
		String userName = userName(claims);
		if (userName == null) {
			return Outcome.refused("the ID token names no user (preferred_username, a verified email, or sub)");
		}
		return new Outcome(userName, null);
	}

	/** The issuer, this application as the audience (and as the authorised party where there are others), the times. */
	DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier(String issuer, String clientId) {
		return new DefaultJWTClaimsVerifier<>(clientId, new JWTClaimsSet.Builder().issuer(issuer).build(),
				Set.of("sub", "iat", "exp")) {
			@Override
			public void verify(JWTClaimsSet claimsSet, SecurityContext context) throws BadJWTException {
				super.verify(claimsSet, context);
				List<String> audience = claimsSet.getAudience();
				Object azp = claimsSet.getClaim("azp");
				if ((audience.size() > 1 || azp != null) && !clientId.equals(azp)) {
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
		Object preferred = claims.getClaim("preferred_username");
		if (preferred instanceof String s && !s.isBlank()) {
			return s;
		}
		Object email = claims.getClaim("email");
		if (email instanceof String s && !s.isBlank() && Boolean.TRUE.equals(claims.getClaim("email_verified"))) {
			return s;
		}
		String subject = claims.getSubject();
		return subject == null || subject.isBlank() ? null : subject;
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
