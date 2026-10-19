package jp.aegif.nemaki.util;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mindrot.jbcrypt.BCrypt;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.UserItem;

public class AuthenticationUtil {
	private static final Log log = LogFactory.getLog(AuthenticationUtil.class);

	/** Subtype property key that stores the per-account authentication policy. */
	private static final String ALLOWED_AUTH_METHODS_KEY = "nemaki:allowedAuthMethods";

	/**
	 * Subtype property key that stores the account's credential generation: a monotonically
	 * increasing integer, raised by one every time the account's password changes (3.4.1, R170).
	 *
	 * <p>A generation and not a timestamp. {@code issuedAt < credentialsNotBefore} depends on the
	 * clock: two credentials issued in the same millisecond compare equal, and with replica clock
	 * skew a credential issued BEFORE a password change can carry a later time (round-7 design
	 * review). An integer read from the account document answers the same on every replica.
	 */
	public static final String CREDENTIAL_GENERATION_KEY = "nemaki:credentialGeneration";

	/** Why a credential is not accepted — logged, and asserted by the locks. */
	public enum CredentialBinding {
		/** Accepted. */
		OK,
		/** The account is gone, or {@code allowedAuthMethods} disables it (batch 17's rule). */
		ACCOUNT_CANNOT_ACT,
		/** The credential records no account id: issued before 3.4.1 (owner's decision: refuse). */
		NOT_BOUND,
		/** The credential was issued for a DIFFERENT account document with the same user id. */
		WRONG_ACCOUNT,
		/** The credential records no usable generation, and this store compares generations. */
		NO_GENERATION,
		/** Issued before the account's current generation: the password changed since. */
		STALE_GENERATION,
		/** Issued with a generation the account has not reached: another replica, or corruption. */
		FUTURE_GENERATION,
		/** The account's own generation cannot be read, so nothing can be compared. */
		ACCOUNT_GENERATION_UNREADABLE
	}

	/**
	 * What a save would do to an account's credential generation (R170, 3.4.1).
	 */
	public enum GenerationSave {
		/** The save keeps or raises it. */
		OK,
		/** The save would take it backwards, so credentials a password change ended would work again. */
		WOULD_LOWER,
		/** The stored value cannot be read as a non-negative integer, so nothing can be compared. */
		STORED_UNREADABLE,
		/** The value being saved cannot be read as a non-negative integer. */
		SAVING_UNREADABLE
	}

	/**
	 * Whether saving {@code saving} over {@code stored} may go ahead (R170, 3.4.1).
	 *
	 * <p>The generation is what makes a password change end the sessions it opened. A PUT that
	 * replaces the whole document can drop the field or write an older value without meaning to, and
	 * every credential that change invalidated would start working again — silently.
	 *
	 * <p>Absent on either side counts as 0 (an account that never changed its password), so dropping
	 * the field is refused exactly when the stored value is above 0. Unreadable is NOT 0: guessing
	 * would un-invalidate credentials, which is the fail-open this branch is named after.
	 */
	public static GenerationSave generationSave(UserItem stored, UserItem saving) {
		Long before = accountGeneration(stored);
		if (before == null) {
			return GenerationSave.STORED_UNREADABLE;
		}
		Long after = accountGeneration(saving);
		if (after == null) {
			return GenerationSave.SAVING_UNREADABLE;
		}
		return after < before ? GenerationSave.WOULD_LOWER : GenerationSave.OK;
	}

	/**
	 * The account's credential generation, or {@code null} when the stored value cannot be read.
	 *
	 * <p>Absent means {@code 0}: an account that existed before 3.4.1 has no such property, and
	 * refusing those would lock every user out the moment this version starts (the credentials
	 * themselves are refused for having no {@code accountId} — that is a different axis).
	 * A value that is PRESENT but not a non-negative integer returns {@code null}: unknown is not
	 * zero, and comparing against a guess would silently un-invalidate credentials.
	 */
	public static Long accountGeneration(UserItem account) {
		if (account == null) {
			return null;
		}
		List<Property> properties = account.getSubTypeProperties();
		if (properties == null) {
			return 0L;
		}
		for (Property property : properties) {
			if (!CREDENTIAL_GENERATION_KEY.equals(property.getKey())) {
				continue;
			}
			Object value = property.getValue();
			if (value == null || String.valueOf(value).trim().isEmpty()) {
				return 0L;
			}
			try {
				long generation = Long.parseLong(String.valueOf(value).trim());
				return generation < 0 ? null : generation;
			} catch (NumberFormatException notAnInteger) {
				log.warn("Account " + account.getUserId() + " has an unreadable "
						+ CREDENTIAL_GENERATION_KEY + " (" + value + "); refusing its credentials");
				return null;
			}
		}
		return 0L;
	}

	/**
	 * Whether a credential still belongs to the account it was issued for (3.4.1, R170).
	 *
	 * <p>Until this release a credential recorded only the user's NAME, so deleting a user and
	 * recreating the same user id handed the new account every credential the old one had.
	 *
	 * @param credentialAccountId the {@code UserItem.getId()} recorded when the credential was
	 *        issued; null or blank means it was issued before this release
	 * @param credentialGeneration the generation recorded when the credential was issued, as stored
	 *        (so this method, not each caller, decides what an unreadable value means)
	 * @param account the account as read for THIS request (batch 17 already reads it)
	 * @param generationApplies whether this kind of credential is invalidated by a password change —
	 *        true for session tokens and MCP sessions, false for API keys (owner's decision: an
	 *        automation must not stop when a human changes their password) and for passkeys (not
	 *        derived from the password) and RSS feed tokens
	 */
	public static CredentialBinding credentialBinding(String credentialAccountId,
			Object credentialGeneration, UserItem account, boolean generationApplies) {
		if (!mayStillAct(account)) {
			return CredentialBinding.ACCOUNT_CANNOT_ACT;
		}
		if (credentialAccountId == null || credentialAccountId.trim().isEmpty()) {
			return CredentialBinding.NOT_BOUND;
		}
		if (!credentialAccountId.equals(account.getId())) {
			return CredentialBinding.WRONG_ACCOUNT;
		}
		if (!generationApplies) {
			return CredentialBinding.OK;
		}
		Long accountGeneration = accountGeneration(account);
		if (accountGeneration == null) {
			return CredentialBinding.ACCOUNT_GENERATION_UNREADABLE;
		}
		Long issued = credentialGenerationOf(credentialGeneration);
		if (issued == null) {
			return CredentialBinding.NO_GENERATION;
		}
		if (issued < accountGeneration) {
			return CredentialBinding.STALE_GENERATION;
		}
		if (issued > accountGeneration) {
			return CredentialBinding.FUTURE_GENERATION;
		}
		return CredentialBinding.OK;
	}

	/**
	 * The generation a credential records, or {@code null} when it records none that can be used.
	 *
	 * <p>Unlike the account side, an ABSENT value is not zero here. A credential issued by this
	 * version always writes the generation it read, so a missing, non-numeric or negative value
	 * means the write did not finish or the record is damaged — and treating that as zero would let
	 * it pass against an account still at zero (round-9 design review).
	 */
	private static Long credentialGenerationOf(Object stored) {
		if (stored == null) {
			return null;
		}
		if (stored instanceof Number) {
			long generation = ((Number) stored).longValue();
			return generation < 0 ? null : generation;
		}
		String text = String.valueOf(stored).trim();
		if (text.isEmpty()) {
			return null;
		}
		try {
			long generation = Long.parseLong(text);
			return generation < 0 ? null : generation;
		} catch (NumberFormatException notAnInteger) {
			return null;
		}
	}

	/**
	 * Single source of truth for the {@code nemaki:allowedAuthMethods} account
	 * policy gate.
	 *
	 * <p>Every authentication entry point that verifies a password (the primary
	 * CMIS path {@code AuthenticationServiceImpl}, the api/v1 login resource, the
	 * MCP handler and the legacy admin-operation re-auth) must consult this method
	 * so a {@code disabled} or {@code cloud}-only account cannot authenticate via
	 * password on any path.
	 *
	 * <p>Semantics (kept byte-compatible with the historical
	 * {@code AuthenticationServiceImpl} implementation):
	 * <ul>
	 *   <li>null/empty/{@code "null"} &rarr; all methods allowed (backward compatibility)</li>
	 *   <li>{@code "disabled"} anywhere in the list &rarr; no authentication allowed (account disabled — {@link #isDisabledValue})</li>
	 *   <li>otherwise &rarr; comma-separated case-insensitive allow-list</li>
	 * </ul>
	 *
	 * @param user   the account to evaluate (null &rarr; not allowed, fail-closed)
	 * @param method the requested method, e.g. {@code "password"} or {@code "cloud"}
	 * @return true if the method is permitted for the account
	 */
	public static boolean isAuthMethodAllowed(UserItem user, String method) {
		if (user == null || method == null) {
			return false;
		}

		String allowedMethods = null;
		List<Property> subTypeProperties = user.getSubTypeProperties();
		if (subTypeProperties != null) {
			for (Property prop : subTypeProperties) {
				if (ALLOWED_AUTH_METHODS_KEY.equals(prop.getKey())) {
					allowedMethods = prop.getValue() == null ? null : String.valueOf(prop.getValue());
					break;
				}
			}
		}

		// null/empty means all methods allowed (backward compatibility)
		if (allowedMethods == null || allowedMethods.isEmpty() || "null".equals(allowedMethods)) {
			return true;
		}

		// "disabled" means no authentication allowed, wherever it appears in the list (isDisabledValue).
		if (isDisabledValue(allowedMethods)) {
			if (log.isDebugEnabled()) {
				log.debug("User " + user.getUserId() + " has authentication disabled");
			}
			return false;
		}

		for (String m : allowedMethods.split(",")) {
			if (method.equalsIgnoreCase(m.trim())) {
				return true;
			}
		}

		if (log.isDebugEnabled()) {
			log.debug("Auth method '" + method + "' not allowed for user " + user.getUserId()
					+ " (allowed: " + allowedMethods + ")");
		}
		return false;
	}
	/**
	 * Whether the account may still be acting through a credential it was issued earlier — a session token, an API key,
	 * an MCP session (3.4.1, zero-trust P0): it exists, and its {@code nemaki:allowedAuthMethods} is not
	 * {@code disabled} ({@link #isDisabledValue}). Those credentials were checked against the token store alone, so deleting
	 * or disabling an account left them working until they expired (a session token after 24 hours; an API key at its own
	 * expiry, if it has one). Which method issued the credential is not recorded with it, so an account narrowed to other
	 * methods keeps it until it expires.
	 */
	public static boolean mayStillAct(UserItem user) {
		if (user == null) {
			return false;
		}
		String allowedMethods = null;
		List<Property> subTypeProperties = user.getSubTypeProperties();
		if (subTypeProperties != null) {
			for (Property prop : subTypeProperties) {
				if (ALLOWED_AUTH_METHODS_KEY.equals(prop.getKey())) {
					allowedMethods = prop.getValue() == null ? null : String.valueOf(prop.getValue());
					break;
				}
			}
		}
		return !isDisabledValue(allowedMethods);
	}

	/**
	 * Whether an {@code nemaki:allowedAuthMethods} value disables the account: {@code disabled} anywhere in the
	 * comma-separated list, in any case and with any surrounding blanks (3.4.1, batch 17 review round 1). A compound value
	 * such as {@code password,disabled} is disabled too — the admin API stores any string, and it used to be read as a
	 * list that allows passwords. The readers that ask this: the login ({@link #isAuthMethodAllowed}), the credentials
	 * issued earlier ({@link #mayStillAct}), the passkey registration and authentication, the password change
	 * ({@code UserItemResource.isPasswordAuthAllowed}) and the generic OIDC eligibility ({@code OidcLogin.refusal}).
	 * The user interface does NOT read it this way (residual R174): the account screen splits the value and looks
	 * for {@code password}, so {@code password,disabled} still shows the password field, and the user list falls
	 * through its exact-match tags and prints the raw value.
	 */
	public static boolean isDisabledValue(String allowedMethods) {
		if (allowedMethods == null) {
			return false;
		}
		for (String m : allowedMethods.split(",")) {
			if ("disabled".equalsIgnoreCase(m.trim())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Check whether a password matches a hash.
	 * Supports both legacy MD5 hashes (32 hex chars) and modern BCrypt hashes.
	 */
	public static boolean passwordMatches(String candidate, String hashed) {
		// Fail closed: a blank candidate OR a blank stored hash never
		// authenticates. Previously "both blank" returned true, which let
		// a user whose stored hash was empty (legacy / sync / corrupted
		// record) log in with an empty password — an authentication bypass.
		// A legitimate account always has a non-blank BCrypt hash.
		if(StringUtils.isBlank(candidate) || StringUtils.isBlank(hashed)){
			return false;
		}
		
		// 後方互換性：MD5ハッシュの検出と検証（32文字の16進数）
		if (hashed.length() == 32 && hashed.matches("[a-f0-9]{32}")) {
			// DEPRECATED in 3.4, to be REMOVED in 3.5 (roadmap §2-5).
			//
			// WARN, not debug: an operator has to be able to find out that accounts are still
			// on MD5 before the path disappears under them. passwordMatchesWithUpgrade
			// migrates an account to BCrypt the first time it authenticates, so an account
			// that keeps landing here is one that has NOT logged in since the upgrade
			// mechanism shipped — exactly the population that breaks in 3.5.
			//
			// The account is not named at WARN: usernames in logs are personal data, and the
			// operator inventory (GET /api/v1/admin/security/legacy-password-hashes) exists to
			// answer "which ones" behind admin auth instead.
			log.warn("A legacy MD5 password hash was verified. MD5 verification is DEPRECATED"
					+ " in 3.4 and will be REMOVED in 3.5; accounts still on it will stop"
					+ " authenticating. Take an inventory with"
					+ " GET /api/v1/admin/security/legacy-password-hashes and have those users"
					+ " sign in once (which upgrades them) or reset their passwords.");
			return verifyMD5Password(candidate, hashed);
		}
		
		// BCryptハッシュの検証（$2a$または$2b$で始まる）
		if (hashed.startsWith("$2a$") || hashed.startsWith("$2b$")) {
			if (log.isDebugEnabled()) {
				log.debug("Detected BCrypt hash format, using modern verification");
			}
			return BCrypt.checkpw(candidate, hashed);
		}
		
		// 不明なハッシュ形式の場合、BCryptを試行（フォールバック）
		if (log.isDebugEnabled()) {
			log.debug("Unknown hash format, attempting BCrypt verification");
		}
		try {
			return BCrypt.checkpw(candidate, hashed);
		} catch (Exception e) {
			if (log.isDebugEnabled()) {
				log.debug("BCrypt verification failed: " + e.getMessage());
			}
			return false;
		}
	}
	
	/**
	 * Enhanced password matching with automatic hash upgrade for legacy MD5 hashes.
	 * Returns authentication result and indicates if hash should be upgraded.
	 */
	public static PasswordMatchResult passwordMatchesWithUpgrade(String candidate, String hashed) {
		// Fail closed: blank candidate or blank stored hash never matches
		// (see passwordMatches for rationale — prevents empty-password
		// authentication against accounts with an empty stored hash).
		if(StringUtils.isBlank(candidate) || StringUtils.isBlank(hashed)){
			return new PasswordMatchResult(false, false, null);
		}
		
		// MD5ハッシュの検出と検証（セキュリティ向上のため、成功時にBCryptに移行）
		if (hashed.length() == 32 && hashed.matches("[a-f0-9]{32}")) {
			if (log.isDebugEnabled()) {
				log.debug("Detected MD5 hash - verifying and preparing BCrypt upgrade");
			}
			
			boolean md5Matches = verifyMD5Password(candidate, hashed);
			
			if (md5Matches) {
				// MD5認証成功時、BCryptハッシュを生成してセキュリティを向上
				String newBCryptHash = BCrypt.hashpw(candidate, BCrypt.gensalt(12));
				if (log.isDebugEnabled()) {
					log.debug("MD5 auth successful - generated BCrypt hash for upgrade");
				}
				return new PasswordMatchResult(true, true, newBCryptHash);
			}
			return new PasswordMatchResult(false, false, null);
		}
		
		// BCryptハッシュの検証（アップグレード不要）
		if (hashed.startsWith("$2a$") || hashed.startsWith("$2b$")) {
			if (log.isDebugEnabled()) {
				log.debug("Using modern BCrypt verification");
			}
			boolean bcryptMatches = BCrypt.checkpw(candidate, hashed);
			return new PasswordMatchResult(bcryptMatches, false, null);
		}
		
		// 不明なハッシュ形式（フォールバック）
		if (log.isDebugEnabled()) {
			log.debug("Unknown hash format, attempting BCrypt verification");
		}
		try {
			boolean matches = BCrypt.checkpw(candidate, hashed);
			return new PasswordMatchResult(matches, false, null);
		} catch (Exception e) {
			if (log.isDebugEnabled()) {
				log.debug("BCrypt verification failed: " + e.getMessage());
			}
			return new PasswordMatchResult(false, false, null);
		}
	}
	
	/**
	 * Result class for password matching with upgrade information
	 */
	public static class PasswordMatchResult {
		private final boolean matches;
		private final boolean requiresUpgrade;
		private final String newHash;
		
		public PasswordMatchResult(boolean matches, boolean requiresUpgrade, String newHash) {
			this.matches = matches;
			this.requiresUpgrade = requiresUpgrade;
			this.newHash = newHash;
		}
		
		public boolean matches() { return matches; }
		public boolean requiresUpgrade() { return requiresUpgrade; }
		public String getNewHash() { return newHash; }
	}
	
	/**
	 * Legacy MD5 password verification for backward compatibility
	 */
	private static boolean verifyMD5Password(String candidate, String md5Hash) {
		try {
			MessageDigest md = MessageDigest.getInstance("MD5");
			byte[] candidateBytes = candidate.getBytes("UTF-8");
			byte[] hash = md.digest(candidateBytes);
			
			// Convert to hex string
			StringBuilder sb = new StringBuilder();
			for (byte b : hash) {
				sb.append(String.format("%02x", b));
			}
			
			String candidateHash = sb.toString();
			if (log.isDebugEnabled()) {
				log.debug("MD5 verification - candidate hash: " + candidateHash + ", stored hash: " + md5Hash);
			}
			
			boolean matches = candidateHash.equals(md5Hash);
			if (log.isDebugEnabled()) {
				log.debug("MD5 verification result: " + matches);
			}
			
			return matches;
		} catch (NoSuchAlgorithmException | java.io.UnsupportedEncodingException e) {
			log.error("MD5 verification error: " + e.getMessage(), e);
			return false;
		}
	}
}
