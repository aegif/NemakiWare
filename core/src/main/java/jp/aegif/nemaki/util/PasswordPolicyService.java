package jp.aegif.nemaki.util;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Reads {@code password.policy.minLength} from CouchDB {@code nemaki_conf}
 * (via {@link PropertyManager}) and validates passwords against the configured
 * minimum length.
 *
 * <p>Default value is {@code 0} (no constraint), so Setup Wizard can set short
 * passwords like {@code admin} without being blocked.</p>
 */
public class PasswordPolicyService {

	private static final Log log = LogFactory.getLog(PasswordPolicyService.class);

	private static final String KEY_MIN_LENGTH = "password.policy.minLength";
	private static final int DEFAULT_MIN_LENGTH = 0;

	private PropertyManager propertyManager;

	/**
	 * Returns the currently configured minimum password length.
	 * Checks the repository-specific value first, then falls back to global.
	 */
	public int getMinLength(String repositoryId) {
		try {
			String val = propertyManager.readValue(repositoryId, KEY_MIN_LENGTH);
			if (val != null && !val.isEmpty()) {
				return Integer.parseInt(val.trim());
			}
		} catch (NumberFormatException e) {
			log.warn("Invalid password.policy.minLength value, using default " + DEFAULT_MIN_LENGTH);
		}
		return DEFAULT_MIN_LENGTH;
	}

	/**
	 * Returns the global minimum password length (no repository context).
	 */
	public int getMinLength() {
		try {
			String val = propertyManager.readValue(KEY_MIN_LENGTH);
			if (val != null && !val.isEmpty()) {
				return Integer.parseInt(val.trim());
			}
		} catch (NumberFormatException e) {
			log.warn("Invalid password.policy.minLength value, using default " + DEFAULT_MIN_LENGTH);
		}
		return DEFAULT_MIN_LENGTH;
	}

	/**
	 * Validates a password against the policy.
	 *
	 * @param password     the password to validate
	 * @param repositoryId the repository context
	 * @return a {@link PasswordPolicyResult} indicating success or failure
	 */
	public PasswordPolicyResult validate(String password, String repositoryId) {
		// Empty password is always rejected (safety net, independent of policy)
		if (password == null || password.isEmpty()) {
			return PasswordPolicyResult.error("Password must not be empty");
		}

		int minLength = (repositoryId != null) ? getMinLength(repositoryId) : getMinLength();
		if (minLength > 0 && password.length() < minLength) {
			return PasswordPolicyResult.error(
					"Password must be at least " + minLength + " characters");
		}

		return PasswordPolicyResult.ok();
	}

	// --- Result value object ---

	public static class PasswordPolicyResult {
		private final boolean ok;
		private final String errorMessage;

		private PasswordPolicyResult(boolean ok, String errorMessage) {
			this.ok = ok;
			this.errorMessage = errorMessage;
		}

		public static PasswordPolicyResult ok() {
			return new PasswordPolicyResult(true, null);
		}

		public static PasswordPolicyResult error(String message) {
			return new PasswordPolicyResult(false, message);
		}

		public boolean isOk() {
			return ok;
		}

		public String getErrorMessage() {
			return errorMessage;
		}
	}

	// --- DI setter ---

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}
}
