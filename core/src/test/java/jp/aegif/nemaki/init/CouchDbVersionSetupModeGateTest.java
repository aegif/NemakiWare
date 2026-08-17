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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.Test;

import jakarta.ws.rs.core.Response;
import jp.aegif.nemaki.api.setup.resource.SetupApplyResource;

/**
 * The CouchDB floor has to hold on the way in through Setup Mode too.
 *
 * <h2>What was missing</h2>
 *
 * <p>{@link StartupProbeService#enforceCouchDbVersion} runs once, in {@code onApplicationEvent},
 * and only on the branch where CouchDB ANSWERED. That is deliberate — a database that is not up
 * yet is a different situation from one that is up and unsupported — but it leaves a way in:
 * start with CouchDB down, land in Setup Mode, point the wizard at a 2.x server, and setup
 * completes. {@code /apply} CREATES DATABASES before anything would have objected.
 *
 * <p>Neither {@code reprobe()} nor {@code reprobeState()} looked at the version, so the release
 * note's "3.3 未満なら起動しません" was true of the startup path and false of this one.
 *
 * <h2>What this test pins</h2>
 *
 * <p>The CONSEQUENCE at the endpoint: {@code POST /apply/mark-complete} must answer 400 while the
 * server it just re-probed is a 2.x, <em>even though the database state it reports is
 * {@code DB_CONNECTED_CURRENT}</em>. That last clause is what makes this discriminating: the
 * pre-existing state check is satisfied here, so deleting the version check from
 * {@code markComplete} makes the request succeed and this test fail.
 */
class CouchDbVersionSetupModeGateTest {

	/**
	 * A probe that answers without a network. The real {@code reprobeState()} runs on top of it,
	 * which is the point — the version it records is recorded by production code, not by the stub.
	 */
	private static StartupProbeService probeReporting(String version) {
		return new StartupProbeService() {
			@Override
			public CouchDbConnectionResult testConnection(String url, String user, String pass) {
				CouchDbConnectionResult r = new CouchDbConnectionResult();
				r.setReachable(true);
				r.setCouchDbVersion(version);
				return r;
			}

			@Override
			public List<RepositoryOverview> probeRepositories(String url, String user, String pass) {
				RepositoryOverview repo = new RepositoryOverview();
				repo.setRepositoryId("bedroom");
				repo.setJudgment("current");
				repo.setMainRepository(true);
				return List.of(repo);
			}

			@Override
			public boolean isMainRepository(String dbName) {
				return true;
			}
		};
	}

	/**
	 * The probe refuses to resolve CouchDB credentials it was not given, and re-probing resolves
	 * them before it does anything else. These are only here so the real path can be reached; the
	 * stub above means nothing is connected to.
	 */
	private static <T> T withCredentials(ThrowingSupplier<T> body) throws Exception {
		String url = System.getProperty("db.couchdb.url");
		String user = System.getProperty("db.couchdb.auth.username");
		String pass = System.getProperty("db.couchdb.auth.password");
		System.setProperty("db.couchdb.url", "http://couchdb-not-contacted:5984");
		System.setProperty("db.couchdb.auth.username", "gate-test-user");
		System.setProperty("db.couchdb.auth.password", "gate-test-password");
		try {
			return body.get();
		} finally {
			restore("db.couchdb.url", url);
			restore("db.couchdb.auth.username", user);
			restore("db.couchdb.auth.password", pass);
		}
	}

	private interface ThrowingSupplier<T> {
		T get() throws Exception;
	}

	private static void restore(String key, String previous) {
		if (previous == null) {
			System.clearProperty(key);
		} else {
			System.setProperty(key, previous);
		}
	}

	private static SetupApplyResource resourceUsing(StartupProbeService probe) throws Exception {
		SetupApplyResource resource = new SetupApplyResource();
		Field f = SetupApplyResource.class.getDeclaredField("startupProbeService");
		f.setAccessible(true);
		f.set(resource, probe);
		return resource;
	}

	/**
	 * {@code /apply} must refuse BEFORE it creates databases.
	 *
	 * <p>{@code apply()} is the endpoint that writes: step 3 sets the CouchDB system properties
	 * and step 4 runs {@code DatabasePreInitializer}. The version check sits between the
	 * reachability test and those steps, so an unsupported server is turned away with nothing
	 * written. Deleting the check makes the request run on past that point — with this test's
	 * bare resource that path answers something other than a 400 naming the version, so the
	 * assertion discriminates.
	 */
	@Test
	void applyRefusesAnOldCouchDbBeforeTouchingIt() throws Exception {
		SetupApplyResource resource = resourceUsing(probeReporting("2.3.1"));

		jp.aegif.nemaki.api.setup.model.CouchDbConnectionRequest couch =
				new jp.aegif.nemaki.api.setup.model.CouchDbConnectionRequest();
		couch.setUrl("http://localhost:5984");
		couch.setUsername("gate-test-user");
		couch.setPassword("gate-test-password");
		jp.aegif.nemaki.api.setup.model.SetupApplyRequest request =
				new jp.aegif.nemaki.api.setup.model.SetupApplyRequest();
		request.setCouchdb(couch);

		Response response = withCredentials(() -> resource.apply(request));

		assertEquals(400, response.getStatus(),
				"apply must not proceed to database creation against an unsupported CouchDB");
		assertTrue(String.valueOf(response.getEntity()).contains("2.3.1"),
				"the operator has to be told what was found: " + response.getEntity());
	}

	/**
	 * The auxiliary Setup endpoints write too — {@code /setup/auth/apply} persists auth settings
	 * into nemaki_conf, {@code /setup/admin/change-password} rewrites admin documents in every
	 * main repository DB. Both were outside the version gate (3.3.1 #3).
	 */
	@Test
	void authApplyRefusesAnOldCouchDb() throws Exception {
		jp.aegif.nemaki.api.setup.resource.SetupAuthResource resource =
				new jp.aegif.nemaki.api.setup.resource.SetupAuthResource();
		java.lang.reflect.Field f = resource.getClass().getDeclaredField("startupProbeService");
		f.setAccessible(true);
		f.set(resource, probeReporting("2.3.1"));

		jp.aegif.nemaki.api.setup.model.AuthConfigRequest request =
				new jp.aegif.nemaki.api.setup.model.AuthConfigRequest();
		request.setPasswordEnabled(true);

		Response response = withCredentials(() -> resource.apply(request));

		assertEquals(400, response.getStatus(),
				"auth settings must not be written into an unsupported CouchDB");
		assertTrue(String.valueOf(response.getEntity()).contains("2.3.1"),
				"the operator has to be told what was found: " + response.getEntity());
	}

	@Test
	void changePasswordRefusesAnOldCouchDb() throws Exception {
		jp.aegif.nemaki.api.setup.resource.SetupAdminResource resource =
				new jp.aegif.nemaki.api.setup.resource.SetupAdminResource();
		java.lang.reflect.Field f = resource.getClass().getDeclaredField("startupProbeService");
		f.setAccessible(true);
		f.set(resource, probeReporting("2.3.1"));

		jp.aegif.nemaki.api.setup.model.AdminSetupRequest request =
				new jp.aegif.nemaki.api.setup.model.AdminSetupRequest();
		request.setNewPassword("longenoughpassword");

		Response response = withCredentials(() -> resource.changePassword(request));

		assertEquals(400, response.getStatus(),
				"admin documents must not be rewritten in an unsupported CouchDB");
		assertTrue(String.valueOf(response.getEntity()).contains("2.3.1"),
				"the operator has to be told what was found: " + response.getEntity());
	}

	/**
	 * 3.3.1 #11: a PARTIAL admin-password update must not report a bare success. The stubbed
	 * update succeeds for one repository and fails for the other; the response must carry the
	 * per-DB outcome and a warning naming what was left on the old password.
	 */
	@Test
	void partialPasswordUpdateIsVisibleInTheResponse() throws Exception {
		jp.aegif.nemaki.api.setup.resource.SetupAdminResource resource =
				new jp.aegif.nemaki.api.setup.resource.SetupAdminResource() {
					@Override
					protected java.util.List<String> discoverMainRepositoryDbs(
							String couchUrl, String user, String pass) {
						return java.util.List.of("bedroom", "canopy");
					}

					@Override
					protected boolean updateAdminInDb(String couchUrl, String dbName,
							String authHeader, String bcryptHash) {
						return "bedroom".equals(dbName);
					}
				};
		java.lang.reflect.Field f = jp.aegif.nemaki.api.setup.resource.SetupAdminResource.class
				.getDeclaredField("startupProbeService");
		f.setAccessible(true);
		f.set(resource, probeReporting("3.3.3"));

		jp.aegif.nemaki.api.setup.model.AdminSetupRequest request =
				new jp.aegif.nemaki.api.setup.model.AdminSetupRequest();
		request.setNewPassword("longenoughpassword");

		Response response = withCredentials(() -> resource.changePassword(request));
		String body = String.valueOf(response.getEntity());

		assertEquals(200, response.getStatus(), "one updated repository is still a success: " + body);
		assertTrue(body.contains("\"db\":\"canopy\",\"updated\":false"),
				"the per-DB outcome must be visible: " + body);
		assertTrue(body.contains("warning") && body.contains("canopy"),
				"the repository left on the old password must be named: " + body);
	}

	/** The case this exists for. */
	@Test
	void markCompleteRefusesAnOldCouchDb() throws Exception {
		StartupProbeService probe = probeReporting("2.3.1");
		SetupApplyResource resource = resourceUsing(probe);

		Response response = withCredentials(resource::markComplete);

		// Guard the premise: if the state check alone were rejecting this, the test would pass
		// for the wrong reason and would not notice the version check disappearing.
		assertEquals(StartupProbeService.StartupState.DB_CONNECTED_CURRENT, probe.getCurrentState(),
				"the database state must be the one that would otherwise be accepted");

		assertEquals(400, response.getStatus(),
				"setup must not complete against a CouchDB the release refuses to run on");
		assertTrue(String.valueOf(response.getEntity()).contains("2.3.1"),
				"the operator has to be told what was found: " + response.getEntity());
	}

	/** Unknown counts as unsupported here too. */
	@Test
	void markCompleteRefusesAnUnreadableVersion() throws Exception {
		SetupApplyResource resource = resourceUsing(probeReporting(null));
		Response response = withCredentials(resource::markComplete);

		assertEquals(400, response.getStatus(),
				"a version that could not be read is not evidence of a supported server");
	}

	/**
	 * And it does not block the supported case — otherwise the wizard would be unusable and the
	 * two tests above would pass no matter what the check said.
	 */
	@Test
	void markCompleteDoesNotRefuseASupportedCouchDb() throws Exception {
		StartupProbeService probe = probeReporting("3.3.3");
		SetupApplyResource resource = resourceUsing(probe);

		Response response = withCredentials(() -> {
			probe.reprobeState();
			assertNull(probe.couchDbVersionRefusalFromLastProbe(),
					"3.3.3 is the shipped and tested version");
			return resource.markComplete();
		});
		assertNotNull(response);
		assertTrue(response.getStatus() != 400
						|| !String.valueOf(response.getEntity()).contains("CouchDB 3.3"),
				"a supported server must not be rejected on version grounds: " + response.getEntity());
	}
}
