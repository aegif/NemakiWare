package jp.aegif.nemaki.util;

import org.mindrot.jbcrypt.BCrypt;

public class AuthenticationUtil {
	/**
	 * Check whether a password matches a hash.
	 */
	public static  boolean passwordMatches(String candidate, String hashed) {
		return BCrypt.checkpw(candidate, hashed);
	}
}
