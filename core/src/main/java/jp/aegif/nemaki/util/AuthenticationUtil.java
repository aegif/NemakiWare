package jp.aegif.nemaki.util;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mindrot.jbcrypt.BCrypt;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class AuthenticationUtil {
	private static final Log log = LogFactory.getLog(AuthenticationUtil.class);
	/**
	 * Check whether a password matches a hash.
	 * Supports both legacy MD5 hashes (32 hex chars) and modern BCrypt hashes.
	 */
	public static boolean passwordMatches(String candidate, String hashed) {
		if(StringUtils.isBlank(candidate) || StringUtils.isBlank(hashed)){
			//both blank pass
			return StringUtils.isBlank(candidate) && StringUtils.isBlank(hashed); 
		}
		
		// 後方互換性：MD5ハッシュの検出と検証（32文字の16進数）
		if (hashed.length() == 32 && hashed.matches("[a-f0-9]{32}")) {
			if (log.isDebugEnabled()) {
				log.debug("Detected MD5 hash format, using legacy verification");
			}
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
		if(StringUtils.isBlank(candidate) || StringUtils.isBlank(hashed)){
			//both blank pass
			boolean matches = StringUtils.isBlank(candidate) && StringUtils.isBlank(hashed);
			return new PasswordMatchResult(matches, false, null);
		}
		
		// MD5ハッシュの検出と検証（セキュリティ向上のため、成功時にBCryptに移行）
		if (hashed.length() == 32 && hashed.matches("[a-f0-9]{32}")) {
			try {
				java.io.FileWriter debugWriter = new java.io.FileWriter("/tmp/nemaki-auth-debug.log", true);
				debugWriter.write("AuthenticationUtil: Detected MD5 hash - verifying and preparing BCrypt upgrade\n");
				debugWriter.close();
			} catch (Exception e) {}
			
			boolean md5Matches = verifyMD5Password(candidate, hashed);
			
			if (md5Matches) {
				// MD5認証成功時、BCryptハッシュを生成してセキュリティを向上
				String newBCryptHash = BCrypt.hashpw(candidate, BCrypt.gensalt(12));
				try {
					java.io.FileWriter debugWriter = new java.io.FileWriter("/tmp/nemaki-auth-debug.log", true);
					debugWriter.write("AuthenticationUtil: MD5 auth successful - generated BCrypt hash for upgrade\n");
					debugWriter.close();
				} catch (Exception e) {}
				return new PasswordMatchResult(true, true, newBCryptHash);
			}
			return new PasswordMatchResult(false, false, null);
		}
		
		// BCryptハッシュの検証（アップグレード不要）
		if (hashed.startsWith("$2a$") || hashed.startsWith("$2b$")) {
			System.out.println("AuthenticationUtil: Using modern BCrypt verification");
			boolean bcryptMatches = BCrypt.checkpw(candidate, hashed);
			return new PasswordMatchResult(bcryptMatches, false, null);
		}
		
		// 不明なハッシュ形式（フォールバック）
		System.out.println("AuthenticationUtil: Unknown hash format, attempting BCrypt verification");
		try {
			boolean matches = BCrypt.checkpw(candidate, hashed);
			return new PasswordMatchResult(matches, false, null);
		} catch (Exception e) {
			System.out.println("AuthenticationUtil: BCrypt verification failed: " + e.getMessage());
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
			try {
				java.io.FileWriter debugWriter = new java.io.FileWriter("/tmp/nemaki-auth-debug.log", true);
				debugWriter.write("AuthenticationUtil: MD5 verification - candidate hash: " + candidateHash + ", stored hash: " + md5Hash + "\n");
				debugWriter.close();
			} catch (Exception e) {}
			
			boolean matches = candidateHash.equals(md5Hash);
			try {
				java.io.FileWriter debugWriter = new java.io.FileWriter("/tmp/nemaki-auth-debug.log", true);
				debugWriter.write("AuthenticationUtil: MD5 verification result: " + matches + "\n");
				debugWriter.close();
			} catch (Exception e) {}
			
			return matches;
		} catch (NoSuchAlgorithmException | java.io.UnsupportedEncodingException e) {
			System.out.println("AuthenticationUtil: MD5 verification error: " + e.getMessage());
			return false;
		}
	}
}
