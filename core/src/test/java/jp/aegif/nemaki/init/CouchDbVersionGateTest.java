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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.context.event.ContextRefreshedEvent;

/**
 * An unsupported CouchDB must stop the application, not decorate a health page.
 *
 * <h2>What was wrong (G6)</h2>
 *
 * <p>The startup probe already read CouchDB's version. It used it for a {@code /health} field and
 * a log line and nothing else, so a 2.x server — the ordinary shape of a NemakiWare 2.4-era
 * deployment, which is exactly who upgrades — started cleanly and failed later, elsewhere, as
 * something else.
 *
 * <h2>What these tests pin</h2>
 *
 * <p>The CONSEQUENCE, not the string handling: does the application start or not. Two of them go
 * through {@code onApplicationEvent} with a stubbed connection, so deleting the
 * {@code enforceCouchDbVersion} call from the startup path — not just weakening the comparison —
 * fails them.
 *
 * <p>Unknown counts as unsupported. The check exists so an unsupported server cannot get through,
 * and "the version could not be read" is not evidence that it is supported.
 */
class CouchDbVersionGateTest {

	private static CouchDbConnectionResult reachableWith(String version) {
		CouchDbConnectionResult r = new CouchDbConnectionResult();
		r.setReachable(true);
		r.setCouchDbVersion(version);
		return r;
	}

	/** A stub that answers the probe without a network, so the real startup path can run. */
	private static StartupProbeService probeReporting(String version) {
		return new StartupProbeService() {
			@Override
			public CouchDbConnectionResult testConnection(String url, String user, String pass) {
				return reachableWith(version);
			}
		};
	}

	/**
	 * The listener resolves CouchDB credentials before it probes, and refuses to run without
	 * them. These are only here so the real startup path can be reached; the stub above means
	 * nothing is connected to.
	 */
	private static void runStartup(StartupProbeService probe) {
		String url = System.getProperty("db.couchdb.url");
		String user = System.getProperty("db.couchdb.auth.username");
		String pass = System.getProperty("db.couchdb.auth.password");
		System.setProperty("db.couchdb.url", "http://couchdb-not-contacted:5984");
		System.setProperty("db.couchdb.auth.username", "gate-test-user");
		System.setProperty("db.couchdb.auth.password", "gate-test-password");
		try {
			probe.onApplicationEvent(new ContextRefreshedEvent(
					new org.springframework.context.support.StaticApplicationContext()));
		} finally {
			restore("db.couchdb.url", url);
			restore("db.couchdb.auth.username", user);
			restore("db.couchdb.auth.password", pass);
		}
	}

	private static void restore(String key, String previous) {
		if (previous == null) {
			System.clearProperty(key);
		} else {
			System.setProperty(key, previous);
		}
	}

	/** The shipped and tested version passes the gate. */
	@Test
	void theSupportedVersionStarts() {
		assertDoesNotThrow(() -> probeReporting("3.3.3")
				.enforceCouchDbVersion(reachableWith("3.3.3")));
		assertTrue(CouchDbVersionRequirement.isSatisfiedBy("3.4.0"), "later majors/minors are fine");
		assertTrue(CouchDbVersionRequirement.isSatisfiedBy("4.0.0"));
	}

	/**
	 * The case this exists for, driven through the real startup listener.
	 *
	 * <p>2.x is what a 2.4-era deployment is running. Removing the check from
	 * {@code onApplicationEvent} makes this pass, which is what makes it a wiring test.
	 */
	@Test
	void anOldCouchDbStopsStartup() {
		StartupProbeService probe = probeReporting("2.3.1");

		IllegalStateException thrown = assertThrows(IllegalStateException.class,
				() -> runStartup(probe),
				"an unsupported CouchDB must fail the context refresh, not be logged and ignored");
		assertTrue(thrown.getMessage().contains("2.3.1"),
				"the operator has to be told what was found: " + thrown.getMessage());
	}

	/**
	 * Fail closed. Also through the listener, for the same reason as above.
	 */
	@Test
	void anUnreadableVersionStopsStartup() {
		StartupProbeService probe = probeReporting(null);

		assertThrows(IllegalStateException.class,
				() -> runStartup(probe),
				"a version that could not be read is not evidence of a supported server");
	}

	/** The comparison itself, including the shapes that must not be mistaken for a number. */
	@Test
	void theComparisonRefusesEverythingItCannotVouchFor() {
		assertTrue(CouchDbVersionRequirement.isSatisfiedBy("3.3.0"));
		assertTrue(CouchDbVersionRequirement.isSatisfiedBy("3.4.0-RC1"), "suffixes still compare");

		for (String tooOld : new String[] { "2.3.1", "1.6.1", "3.0.0", "3.2.2" }) {
			assertTrue(!CouchDbVersionRequirement.isSatisfiedBy(tooOld),
					tooOld + " is below the tested floor and must be refused");
		}
		for (String unreadable : new String[] { null, "", "   ", "banana", "3", "x.y.z" }) {
			assertTrue(!CouchDbVersionRequirement.isSatisfiedBy(unreadable),
					"unknown must not pass: " + unreadable);
		}
	}
}
