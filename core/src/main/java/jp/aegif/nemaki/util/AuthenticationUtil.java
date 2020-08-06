package jp.aegif.nemaki.util;

import org.apache.commons.lang3.StringUtils;
import org.mindrot.jbcrypt.BCrypt;

public class AuthenticationUtil {
	/**
	 * Check whether a password matches a hash.
	 */
	public static boolean passwordMatches(String candidate, String hashed) {
		if(StringUtils.isBlank(candidate) || StringUtils.isBlank(hashed)){
			//both blank pass
			return StringUtils.isBlank(candidate) && StringUtils.isBlank(hashed); 
		}
		return BCrypt.checkpw(candidate, hashed);
	}
}
