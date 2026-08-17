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
package jp.aegif.nemaki.init;

/**
 * The lowest CouchDB this release will start against.
 *
 * <h2>Where the number comes from</h2>
 *
 * <p>Not from "the release notes say 3.x". The binding constraint is the ACL-epoch scanner's
 * promise that it never full-scans a content database: {@code AclEpochFinalizationService} pins
 * every Mango query with {@code use_index} and then relies on
 * {@code AclEpochFinalizationService.requireIndexes} for the deterministic half of that guarantee,
 * <b>because CouchDB 3.3.x does not reject a {@code use_index} naming a missing index — it silently
 * falls back to {@code _all_docs} and returns HTTP 200</b> (verified against 3.3.3, recorded at
 * {@code AclEpochFinalizationService.java:577}). The stricter {@code allow_fallback=false} is a
 * 3.4+/Cloudant parameter that 3.3.x rejects with {@code invalid_key}, so it is deliberately not
 * sent.
 *
 * <p>That reasoning — and the {@code _index} definition matching it depends on — was done against
 * 3.3.x, and 3.3.3 is what every compose file pins and what the suite runs against. <b>3.0 to 3.2
 * are not known to be broken; they are untested, and the no-full-scan argument has never been made
 * for them.</b> Choosing the tested floor rather than the documented one is the same fail-closed
 * posture as {@link #isSatisfiedBy} refusing an unparseable version.
 *
 * <h2>Why this stops startup</h2>
 *
 * <p>The version was already being read at startup and used only for a health field and a log
 * line, so a 2.x CouchDB — the ordinary shape of a NemakiWare 2.4-era deployment — came up
 * cleanly and failed later, somewhere else, as something else. Failing at startup names the cause
 * once instead of leaving it to be inferred from a Mango error hours into an upgrade.
 */
public final class CouchDbVersionRequirement {

	/** Major component of the minimum. */
	public static final int MINIMUM_MAJOR = 3;
	/** Minor component of the minimum. */
	public static final int MINIMUM_MINOR = 3;

	/** Human-readable form, for messages. */
	public static final String MINIMUM = MINIMUM_MAJOR + "." + MINIMUM_MINOR;

	private CouchDbVersionRequirement() {
	}

	/**
	 * Whether a version string reported by {@code GET /} is high enough to run on.
	 *
	 * <p><b>Unknown means no.</b> Null, blank, and anything that does not parse as
	 * {@code major.minor...} are all refused: the point of the check is that an unsupported server
	 * must not get through, and "I could not tell" is not evidence that it is supported. A version
	 * that parses but carries extra components or suffixes ({@code 3.3.3}, {@code 3.4.0-RC1}) is
	 * compared on major and minor only.
	 */
	public static boolean isSatisfiedBy(String reported) {
		if (reported == null) {
			return false;
		}
		String v = reported.trim();
		if (v.isEmpty()) {
			return false;
		}
		// Strip anything after the numeric part so pre-release suffixes still compare.
		String[] parts = v.split("[.+-]");
		if (parts.length < 2) {
			return false;
		}
		int major;
		int minor;
		try {
			major = Integer.parseInt(parts[0]);
			minor = Integer.parseInt(parts[1]);
		} catch (NumberFormatException e) {
			return false;
		}
		if (major != MINIMUM_MAJOR) {
			return major > MINIMUM_MAJOR;
		}
		return minor >= MINIMUM_MINOR;
	}

	/** The message used when refusing to start, so the operator is told what to do. */
	public static String refusalMessage(String reported) {
		String seen = (reported == null || reported.trim().isEmpty())
				? "no version reported" : reported.trim();
		return "CouchDB " + MINIMUM + " or later is required and this server reports " + seen
				+ ". NemakiWare will not start. The ACL-epoch scanner's guarantee that it never"
				+ " full-scans a content database was established against CouchDB 3.3.x and has not"
				+ " been established below it; an unreadable version is refused for the same reason."
				+ " Upgrade CouchDB before upgrading NemakiWare — see"
				+ " docs/operations/v3.3.0-upgrade-runbook.md.";
	}
}
