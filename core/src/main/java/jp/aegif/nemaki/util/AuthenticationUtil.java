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
	 *   <li>{@code "disabled"} &rarr; no authentication allowed (account disabled)</li>
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

		// "disabled" means no authentication allowed
		if ("disabled".equalsIgnoreCase(allowedMethods.trim())) {
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
