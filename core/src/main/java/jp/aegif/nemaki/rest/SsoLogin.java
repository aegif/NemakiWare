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

import java.util.List;

import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.UserItem;

/**
 * Whether an SSO login may act as the existing account it names, for every route that logs in with an
 * identity provider's word (R175 + R168, 3.4.1).
 *
 * <p>Batch 16 gave the generic OIDC route this rule; the other three — SAML, Google, Microsoft — kept
 * asking {@code isAuthMethodAllowed(account, "cloud")} alone, which answers TRUE for an account that
 * lists no method at all. So the built-in {@code admin}, the service accounts and every account made
 * with a password were enterable by anyone the IdP let choose that name (R175).
 *
 * <p>And the OIDC rule itself read 「{@code cloud} is listed」 as 「an administrator allowed SSO」. It is
 * not: {@code cloud} is written automatically by the three routes above when they provision a user, and
 * by the cloud directory sync — which also writes it onto accounts that ALREADY EXIST. So the first
 * generic-OIDC login could adopt an account another route had made (R168).
 *
 * <p>What this class does NOT change: {@link jp.aegif.nemaki.util.AuthenticationUtil#isAuthMethodAllowed},
 * whose 「unset means allowed」 every other door depends on (the owner's constraint). The arms ask this
 * rule in ADDITION, and it is this rule that refuses an unset account.
 */
public final class SsoLogin {

	private SsoLogin() {
	}

	/** Which route created an account, or wrote {@code cloud} onto one. Absent on accounts made before 3.4.1. */
	public static final String ROUTE_KEY = "nemaki:authRoute";

	public static final String OIDC = "oidc";
	public static final String SAML = "saml";
	public static final String GOOGLE = "google";
	public static final String MICROSOFT = "microsoft";

	/**
	 * The routes a login can arrive by, which is also every value a mark may take.
	 *
	 * <p>A mark naming anything else refuses the account at EVERY route, and there is no
	 * administrator operation that clears one: the first version of this wrote
	 * {@code "directory-sync"} on every account the cloud directory sync touched — including, on its
	 * update path, accounts that already existed — so the nightly sync would have locked every SSO
	 * user out by morning, permanently (batch-22 review: both reviewers, P1). The sync knows which
	 * provider it is reading, so it writes THAT provider's route: the matching login enters and
	 * binds, and a generic-OIDC token still cannot adopt the account, which is what the mark is for.
	 */
	private static final List<String> LOGIN_ROUTES = List.of(OIDC, SAML, GOOGLE, MICROSOFT);

	/**
	 * Where a route keeps the subject an account is linked to.
	 *
	 * <p>OIDC keeps the key it has had since batch 16 — accounts in the field carry it, and renaming it
	 * would unlink every one of them (which this rule would then read as 「not linked」 and adopt).
	 */
	public static String subjectKey(String route) {
		return OIDC.equals(route) ? OidcLogin.SUBJECT_KEY : "nemaki:" + route + "Subject";
	}

	/** The route's name as the refusals spell it. OIDC's wording is unchanged: locks read it. */
	static String label(String route) {
		switch (route) {
			case OIDC:
				return "OIDC";
			case SAML:
				return "SAML";
			case GOOGLE:
				return "Google";
			case MICROSOFT:
				return "Microsoft";
			default:
				return route;
		}
	}

	/**
	 * Why this login may not act as the existing account it names, or null.
	 *
	 * <p>In order, and the order is the point:
	 *
	 * <ol>
	 * <li>A disabled account is refused whatever else it says, and whether or not it is linked — asked
	 *     FIRST because the caller writes the binding before its own method gate refuses the login, so a
	 *     disabled account used to end up bound to whoever presented a token for its name (batch 18).</li>
	 * <li>Linked to THIS route: only this subject may act as it. A login carrying no subject to compare
	 *     is REFUSED rather than read as 「not linked」 — an empty binding is the absence of an answer,
	 *     not the answer 「unbound」 (the owner's constraint (d)).</li>
	 * <li>Linked to ANOTHER route: refused. Re-linking an account from one identity provider to another
	 *     is an administrator's act, not a consequence of someone logging in.</li>
	 * <li>Carrying another route's mark: refused, even with {@code cloud} listed — that is exactly the
	 *     shape R168 is about, because the other routes write {@code cloud} themselves.</li>
	 * <li>{@code cloud} listed and no mark: ADOPTED, and the caller links it. An account made before
	 *     3.4.1 carries no mark, so this is the arm that keeps existing deployments working; it is also
	 *     the arm that cannot tell 「an administrator allowed SSO」 from 「an older build wrote it」.
	 *     Recorded in RELEASE_NOTES with an inventory query rather than claimed to be safe.</li>
	 * <li>Nothing listed: refused. 「Unset」 is not 「adopt me」 (the owner's constraint (b); R175).</li>
	 * </ol>
	 */
	public static String refusal(UserItem account, String route, String binding) {
		String methods = property(account, "nemaki:allowedAuthMethods");
		if (jp.aegif.nemaki.util.AuthenticationUtil.isDisabledValue(methods)) {
			return "the account is disabled";
		}

		String linkedHere = property(account, subjectKey(route));
		if (linkedHere != null) {
			if (binding == null || binding.isBlank()) {
				return "this login carries no subject to compare with the account's " + label(route)
						+ " binding";
			}
			return linkedHere.equals(binding) ? null
					: "the account is linked to another " + label(route) + " subject";
		}

		for (String other : LOGIN_ROUTES) {
			if (other.equals(route)) {
				continue;
			}
			if (property(account, subjectKey(other)) != null) {
				return "the account is linked to a " + label(other)
						+ " identity — an administrator unlinks it";
			}
		}

		String mark = property(account, ROUTE_KEY);
		if (mark != null && !mark.equalsIgnoreCase(route)) {
			return "the account was made by the " + mark + " route, so a " + label(route)
					+ " login may not adopt it — an administrator allows it";
		}

		if (methods != null) {
			for (String method : methods.split(",")) {
				if ("cloud".equalsIgnoreCase(method.trim())) {
					return null;
				}
			}
		}
		return "the account is not linked to this " + label(route)
				+ " subject and does not allow SSO (cloud) — an administrator allows it";
	}

	/**
	 * The binding an identity provider's answer makes: its issuer and the subject it names, in the shape
	 * {@link OidcLogin#binding} has written since batch 16. Null when either half is missing — a login with
	 * no subject to compare is refused by {@link #refusal} rather than read as 「not linked」.
	 */
	public static String binding(String issuer, String subject) {
		if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
			return null;
		}
		return issuer.trim() + " " + subject.trim();
	}

	/** Whether the account is linked to no subject of this route yet. */
	public static boolean unlinked(UserItem account, String route) {
		return property(account, subjectKey(route)) == null;
	}

	/**
	 * The subject this account is linked to for {@code route}, or null.
	 *
	 * <p>Used to read a binding BACK after writing it: a save can be refused (the protected-principal
	 * guard) after the caller has already put the binding on the in-memory account, and the cache hands
	 * that same instance to the next reader — so 「we wrote it」 is not 「it is stored」 (the owner's
	 * constraint (d); residual R196).
	 */
	public static String boundSubject(UserItem account, String route) {
		return property(account, subjectKey(route));
	}

	/** A subTypeProperties list with {@code key} set to {@code value}, replacing any existing entry. */
	public static List<Property> with(UserItem account, String key, String value) {
		List<Property> properties = new java.util.ArrayList<>();
		if (account.getSubTypeProperties() != null) {
			for (Property property : account.getSubTypeProperties()) {
				if (!key.equals(property.getKey())) {
					properties.add(property);
				}
			}
		}
		properties.add(new Property(key, value));
		return properties;
	}

	static String property(UserItem account, String key) {
		if (account == null || account.getSubTypeProperties() == null) {
			return null;
		}
		for (Property property : account.getSubTypeProperties()) {
			if (key.equals(property.getKey())) {
				Object value = property.getValue();
				return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
			}
		}
		return null;
	}
}
