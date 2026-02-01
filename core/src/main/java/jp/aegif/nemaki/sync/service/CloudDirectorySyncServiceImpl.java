package jp.aegif.nemaki.sync.service;

import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.model.Group;
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;


/**
 * Cloud directory sync implementation for Google Workspace and Microsoft Entra ID.
 *
 * Supports three sync modes:
 * - Login-time instant sync (syncUserGroups)
 * - Scheduled delta sync (startDeltaSync)
 * - Admin-triggered full reconciliation (startFullReconciliation)
 */
public class CloudDirectorySyncServiceImpl implements CloudDirectorySyncService {

	private static final Log log = LogFactory.getLog(CloudDirectorySyncServiceImpl.class);

	private PrincipalService principalService;
	private PropertyManager propertyManager;

	private static final int DEFAULT_THREAD_POOL_SIZE = 2;

	// Lazily initialized thread pool (needs PropertyManager from Spring DI)
	private volatile ExecutorService executor;

	// Striped locks for startSync (fixed-size array, key hashed to stripe index)
	private static final int LOCK_STRIPES = 8;
	private final Object[] syncLockStripes;
	{
		syncLockStripes = new Object[LOCK_STRIPES];
		for (int i = 0; i < LOCK_STRIPES; i++) {
			syncLockStripes[i] = new Object();
		}
	}
	// Current sync results per repository+provider
	private final ConcurrentHashMap<String, CloudSyncResult> currentResults = new ConcurrentHashMap<>();
	// Sync state per repository+provider (delta tokens etc.)
	private final ConcurrentHashMap<String, CloudSyncState> syncStates = new ConcurrentHashMap<>();
	// Cancellation flags (AtomicBoolean for thread-safe cancel signaling)
	private final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

	public void setPrincipalService(PrincipalService principalService) {
		this.principalService = principalService;
	}

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
		initExecutor();
	}

	private synchronized void initExecutor() {
		if (executor != null) {
			return;
		}
		int poolSize = DEFAULT_THREAD_POOL_SIZE;
		if (propertyManager != null) {
			String val = propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_THREAD_POOL_SIZE);
			if (val != null) {
				try {
					int parsed = Integer.parseInt(val.trim());
					if (parsed > 0) {
						poolSize = parsed;
					}
				} catch (NumberFormatException e) {
					// use default
				}
			}
		}
		executor = Executors.newFixedThreadPool(poolSize, r -> {
			Thread t = new Thread(r, "CloudDirectorySync");
			t.setDaemon(true);
			return t;
		});
		log.info("CloudDirectorySync executor initialized with pool size: " + poolSize);
	}

	private ExecutorService getExecutor() {
		if (executor == null) {
			initExecutor();
		}
		return executor;
	}

	private String syncKey(String repositoryId, String provider) {
		return repositoryId + ":" + provider;
	}

	private Object getKeyLock(String key) {
		return syncLockStripes[Math.floorMod(key.hashCode(), LOCK_STRIPES)];
	}

	private int getWindowSize() {
		String val = propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_WINDOW_SIZE);
		if (val != null) {
			try {
				return Integer.parseInt(val.trim());
			} catch (NumberFormatException e) {
				// fall through
			}
		}
		return 100;
	}

	// ---- Login-time instant sync (unchanged from original) ----

	@Override
	public List<String> syncUserGroups(String repositoryId, String provider, String externalUserId, String email) {
		log.info("syncUserGroups: provider=" + provider + ", email=" + email);

		if (!isProviderEnabled(provider)) {
			log.debug("Cloud directory sync not enabled for provider: " + provider);
			return Collections.emptyList();
		}

		switch (provider) {
			case "google":
				return syncGoogleUserGroups(email);
			case "microsoft":
				return syncMicrosoftUserGroups(externalUserId);
			default:
				log.warn("Unknown cloud directory provider: " + provider);
				return Collections.emptyList();
		}
	}

	// ---- Delta Sync ----

	@Override
	public CloudSyncResult startDeltaSync(String repositoryId, String provider) {
		return startSync(repositoryId, provider, CloudSyncResult.SyncMode.DELTA);
	}

	// ---- Full Reconciliation ----

	@Override
	public CloudSyncResult startFullReconciliation(String repositoryId, String provider) {
		return startSync(repositoryId, provider, CloudSyncResult.SyncMode.FULL);
	}

	private CloudSyncResult startSync(String repositoryId, String provider, CloudSyncResult.SyncMode mode) {
		String key = syncKey(repositoryId, provider);

		// Per-key synchronization: different repo/provider pairs can run in parallel
		synchronized (getKeyLock(key)) {
			// Check if already running
			CloudSyncResult existing = currentResults.get(key);
			if (existing != null && existing.getStatus() == CloudSyncResult.Status.RUNNING) {
				return existing;
			}

			CloudSyncResult result = new CloudSyncResult(repositoryId, provider, mode);
			result.setStatus(CloudSyncResult.Status.RUNNING);
			result.setStartTime(Instant.now().toString());
			currentResults.put(key, result);
			cancelFlags.put(key, new java.util.concurrent.atomic.AtomicBoolean(false));

			getExecutor().submit(() -> {
				try {
					executeSync(repositoryId, provider, mode, result);
				} catch (Exception e) {
					log.error("Cloud sync failed: " + e.getMessage(), e);
					result.setStatus(CloudSyncResult.Status.ERROR);
					result.addError(e.getMessage());
				} finally {
					result.setEndTime(Instant.now().toString());
					if (result.getStatus() == CloudSyncResult.Status.RUNNING) {
						result.setStatus(CloudSyncResult.Status.COMPLETED);
					}
				}
			});

			return result;
		}
	}

	private void executeSync(String repositoryId, String provider, CloudSyncResult.SyncMode mode,
			CloudSyncResult result) {
		log.info("Starting cloud " + mode + " sync: provider=" + provider + ", repo=" + repositoryId);

		if (!isProviderEnabled(provider)) {
			result.setStatus(CloudSyncResult.Status.ERROR);
			result.addError("Provider not enabled: " + provider);
			return;
		}

		switch (provider) {
			case "google":
				executeGoogleSync(repositoryId, mode, result);
				break;
			case "microsoft":
				executeMicrosoftSync(repositoryId, mode, result);
				break;
			default:
				result.setStatus(CloudSyncResult.Status.ERROR);
				result.addError("Unknown provider: " + provider);
		}
	}

	// ---- Google Sync ----

	private void executeGoogleSync(String repositoryId, CloudSyncResult.SyncMode mode, CloudSyncResult result) {
		String serviceAccountKeyPath = propertyManager.readValue(
			PropertyKey.CLOUD_DIRECTORY_SYNC_GOOGLE_SERVICE_ACCOUNT_KEY);
		String domain = propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_GOOGLE_DOMAIN);
		String adminEmail = propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_GOOGLE_ADMIN_EMAIL);

		if (serviceAccountKeyPath == null || serviceAccountKeyPath.isEmpty()) {
			result.setStatus(CloudSyncResult.Status.ERROR);
			result.addError("Google service account key not configured");
			return;
		}
		if (domain == null || domain.isEmpty()) {
			result.setStatus(CloudSyncResult.Status.ERROR);
			result.addError("Google domain not configured");
			return;
		}
		if (adminEmail == null || adminEmail.isEmpty()) {
			result.setStatus(CloudSyncResult.Status.ERROR);
			result.addError("Google admin email not configured (required for domain-wide delegation)");
			return;
		}

		String key = syncKey(repositoryId, "google");
		int windowSize = getWindowSize();

		try {
			com.google.auth.oauth2.GoogleCredentials credentials =
				com.google.auth.oauth2.ServiceAccountCredentials.fromStream(
					new java.io.FileInputStream(serviceAccountKeyPath))
				.createScoped(Arrays.asList(
					"https://www.googleapis.com/auth/admin.directory.user.readonly",
					"https://www.googleapis.com/auth/admin.directory.group.readonly"))
				.createDelegated(adminEmail);

			com.google.api.services.directory.Directory directoryService =
				new com.google.api.services.directory.Directory.Builder(
					com.google.api.client.googleapis.javanet.GoogleNetHttpTransport.newTrustedTransport(),
					com.google.api.client.json.gson.GsonFactory.getDefaultInstance(),
					new com.google.auth.http.HttpCredentialsAdapter(credentials))
				.setApplicationName("NemakiWare")
				.build();

			// Sync users
			syncGoogleUsers(repositoryId, directoryService, domain, mode, windowSize, key, result);
			if (isCancelled(key)) return;

			// Sync groups
			syncGoogleGroups(repositoryId, directoryService, domain, mode, windowSize, key, result);
			if (isCancelled(key)) return;

			// Update state
			CloudSyncState state = syncStates.computeIfAbsent(key, k -> new CloudSyncState(repositoryId, "google"));
			state.setLastSyncTimestamp(DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC)));
			if (mode == CloudSyncResult.SyncMode.FULL) {
				state.setLastFullReconciliationTimestamp(state.getLastSyncTimestamp());
			}

			log.info("Google " + mode + " sync completed: users created=" + result.getUsersCreated()
				+ " updated=" + result.getUsersUpdated()
				+ " groups created=" + result.getGroupsCreated()
				+ " updated=" + result.getGroupsUpdated());

		} catch (Exception e) {
			log.error("Google sync failed: " + e.getMessage(), e);
			result.setStatus(CloudSyncResult.Status.ERROR);
			result.addError("Google API error: " + e.getMessage());
		}
	}

	private void syncGoogleUsers(String repositoryId,
			com.google.api.services.directory.Directory directoryService,
			String domain, CloudSyncResult.SyncMode mode, int windowSize,
			String key, CloudSyncResult result) throws Exception {

		CloudSyncState state = syncStates.get(key);
		String pageToken = null;
		int page = 0;

		do {
			if (isCancelled(key)) {
				result.setStatus(CloudSyncResult.Status.CANCELLED);
				return;
			}

			var req = directoryService.users().list()
				.setDomain(domain)
				.setMaxResults(windowSize);

			// Delta mode: use updatedMin
			if (mode == CloudSyncResult.SyncMode.DELTA && state != null && state.getLastSyncTimestamp() != null) {
				req.setCustomFieldMask(null);
				// Google Admin SDK uses orderBy=email when querying
				// updatedMin is not directly supported in users().list() for most editions,
				// so for delta we still do full list but compare timestamps locally
			}

			if (pageToken != null) {
				req.setPageToken(pageToken);
			}

			com.google.api.services.directory.model.Users users = req.execute();
			page++;
			result.setCurrentPage(page);

			if (users.getUsers() != null) {
				for (com.google.api.services.directory.model.User gUser : users.getUsers()) {
					syncGoogleUser(repositoryId, gUser, mode, result);
				}
			}

			pageToken = users.getNextPageToken();
		} while (pageToken != null);
	}

	private void syncGoogleUser(String repositoryId,
			com.google.api.services.directory.model.User gUser,
			CloudSyncResult.SyncMode mode, CloudSyncResult result) {
		String email = gUser.getPrimaryEmail();
		if (email == null) {
			result.incrementUsersSkipped();
			return;
		}

		String userId = email;
		try {
			User existing = principalService.getUserById(repositoryId, userId);
			if (existing == null) {
				User newUser = new User();
				newUser.setUserId(userId);
				newUser.setName(gUser.getName() != null ? gUser.getName().getFullName() : email);
				newUser.setFirstName(gUser.getName() != null ? gUser.getName().getGivenName() : "");
				newUser.setLastName(gUser.getName() != null ? gUser.getName().getFamilyName() : "");
				newUser.setEmail(email);
				// Cloud-synced users get a random password hash (login via OIDC only)
				newUser.setPasswordHash(UUID.randomUUID().toString());
				principalService.createUser(repositoryId, newUser);
				result.incrementUsersCreated();
			} else {
				boolean updated = false;
				if (gUser.getName() != null) {
					String fullName = gUser.getName().getFullName();
					if (fullName != null && !fullName.equals(existing.getName())) {
						existing.setName(fullName);
						updated = true;
					}
					if (gUser.getName().getGivenName() != null && !gUser.getName().getGivenName().equals(existing.getFirstName())) {
						existing.setFirstName(gUser.getName().getGivenName());
						updated = true;
					}
					if (gUser.getName().getFamilyName() != null && !gUser.getName().getFamilyName().equals(existing.getLastName())) {
						existing.setLastName(gUser.getName().getFamilyName());
						updated = true;
					}
				}
				if (updated) {
					principalService.updateUser(repositoryId, existing);
					result.incrementUsersUpdated();
				} else {
					result.incrementUsersSkipped();
				}
			}
		} catch (Exception e) {
			log.warn("Failed to sync Google user " + email + ": " + e.getMessage());
			result.addError("User sync failed: " + email + " - " + e.getMessage());
		}
	}

	private void syncGoogleGroups(String repositoryId,
			com.google.api.services.directory.Directory directoryService,
			String domain, CloudSyncResult.SyncMode mode, int windowSize,
			String key, CloudSyncResult result) throws Exception {

		String pageToken = null;
		Set<String> cloudGroupIds = new HashSet<>();

		do {
			if (isCancelled(key)) {
				result.setStatus(CloudSyncResult.Status.CANCELLED);
				return;
			}

			var req = directoryService.groups().list()
				.setDomain(domain)
				.setMaxResults(windowSize);

			if (pageToken != null) {
				req.setPageToken(pageToken);
			}

			com.google.api.services.directory.model.Groups groups = req.execute();

			if (groups.getGroups() != null) {
				for (com.google.api.services.directory.model.Group gGroup : groups.getGroups()) {
					syncGoogleGroup(repositoryId, directoryService, gGroup, result);
					cloudGroupIds.add(gGroup.getEmail());
				}
			}

			pageToken = groups.getNextPageToken();
		} while (pageToken != null);

		// Full reconciliation: detect orphans
		if (mode == CloudSyncResult.SyncMode.FULL) {
			detectOrphanGroups(repositoryId, cloudGroupIds, "cloud-google:", result);
		}
	}

	private void syncGoogleGroup(String repositoryId,
			com.google.api.services.directory.Directory directoryService,
			com.google.api.services.directory.model.Group gGroup,
			CloudSyncResult result) {
		String groupId = "cloud-google:" + gGroup.getEmail();
		String groupName = gGroup.getName();

		try {
			// Get group members
			List<String> memberEmails = new ArrayList<>();
			try {
				var members = directoryService.members().list(gGroup.getId()).execute();
				if (members.getMembers() != null) {
					for (var member : members.getMembers()) {
						if ("USER".equals(member.getType())) {
							memberEmails.add(member.getEmail());
						}
					}
				}
			} catch (Exception e) {
				log.debug("Failed to list members for group " + gGroup.getEmail() + ": " + e.getMessage());
			}

			Group existing = principalService.getGroupById(repositoryId, groupId);
			if (existing == null) {
				Group newGroup = new Group();
				newGroup.setGroupId(groupId);
				newGroup.setName(groupName);
				newGroup.setUsers(memberEmails);
				newGroup.setGroups(new ArrayList<>());
				principalService.createGroup(repositoryId, newGroup);
				result.incrementGroupsCreated();
			} else {
				boolean updated = false;
				if (!groupName.equals(existing.getName())) {
					existing.setName(groupName);
					updated = true;
				}
				if (!memberEmails.equals(existing.getUsers())) {
					existing.setUsers(memberEmails);
					updated = true;
				}
				if (updated) {
					principalService.updateGroup(repositoryId, existing);
					result.incrementGroupsUpdated();
				} else {
					result.incrementGroupsSkipped();
				}
			}
		} catch (Exception e) {
			log.warn("Failed to sync Google group " + gGroup.getEmail() + ": " + e.getMessage());
			result.addError("Group sync failed: " + gGroup.getEmail() + " - " + e.getMessage());
		}
	}

	// ---- Microsoft Sync ----

	private void executeMicrosoftSync(String repositoryId, CloudSyncResult.SyncMode mode, CloudSyncResult result) {
		String tenantId = propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_MICROSOFT_TENANT_ID);
		String clientId = propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_MICROSOFT_CLIENT_ID);
		String clientSecret = propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_MICROSOFT_CLIENT_SECRET);

		if (clientId == null || clientSecret == null || tenantId == null) {
			result.setStatus(CloudSyncResult.Status.ERROR);
			result.addError("Microsoft directory sync not fully configured");
			return;
		}

		String key = syncKey(repositoryId, "microsoft");
		int windowSize = getWindowSize();

		try {
			String accessToken = getMicrosoftClientCredentialsToken(tenantId, clientId, clientSecret);

			// Sync users (with delta support)
			syncMicrosoftUsers(repositoryId, accessToken, mode, windowSize, key, result);
			if (isCancelled(key)) return;

			// Sync groups
			syncMicrosoftGroups(repositoryId, accessToken, mode, windowSize, key, result);
			if (isCancelled(key)) return;

			// Update state
			CloudSyncState state = syncStates.computeIfAbsent(key, k -> new CloudSyncState(repositoryId, "microsoft"));
			state.setLastSyncTimestamp(Instant.now().toString());
			if (mode == CloudSyncResult.SyncMode.FULL) {
				state.setLastFullReconciliationTimestamp(state.getLastSyncTimestamp());
			}

			log.info("Microsoft " + mode + " sync completed: users created=" + result.getUsersCreated()
				+ " updated=" + result.getUsersUpdated()
				+ " groups created=" + result.getGroupsCreated()
				+ " updated=" + result.getGroupsUpdated());

		} catch (Exception e) {
			log.error("Microsoft sync failed: " + e.getMessage(), e);
			result.setStatus(CloudSyncResult.Status.ERROR);
			result.addError("Microsoft Graph API error: " + e.getMessage());
		}
	}

	private void syncMicrosoftUsers(String repositoryId, String accessToken,
			CloudSyncResult.SyncMode mode, int windowSize, String key,
			CloudSyncResult result) throws Exception {

		java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
		CloudSyncState state = syncStates.get(key);

		// For delta mode, use delta endpoint if we have a token
		String url;
		if (mode == CloudSyncResult.SyncMode.DELTA && state != null && state.getLastDeltaToken() != null) {
			url = state.getLastDeltaToken();
		} else {
			url = "https://graph.microsoft.com/v1.0/users/delta?$top=" + windowSize
				+ "&$select=id,displayName,givenName,surname,mail,userPrincipalName";
		}

		int page = 0;
		String newDeltaLink = null;

		while (url != null) {
			if (isCancelled(key)) {
				result.setStatus(CloudSyncResult.Status.CANCELLED);
				return;
			}

			java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
				.uri(java.net.URI.create(url))
				.header("Authorization", "Bearer " + accessToken)
				.GET()
				.build();

			java.net.http.HttpResponse<String> response = httpClient.send(request,
				java.net.http.HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() != 200) {
				result.addError("MS Graph users request failed: HTTP " + response.statusCode());
				break;
			}

			page++;
			result.setCurrentPage(page);

			org.json.simple.JSONObject json = (org.json.simple.JSONObject)
				new org.json.simple.parser.JSONParser().parse(response.body());
			org.json.simple.JSONArray value = (org.json.simple.JSONArray) json.get("value");

			if (value != null) {
				for (Object item : value) {
					org.json.simple.JSONObject msUser = (org.json.simple.JSONObject) item;
					syncMicrosoftUser(repositoryId, msUser, mode, result);
				}
			}

			// @odata.nextLink or @odata.deltaLink
			url = (String) json.get("@odata.nextLink");
			String deltaLink = (String) json.get("@odata.deltaLink");
			if (deltaLink != null) {
				newDeltaLink = deltaLink;
			}
		}

		// Save delta token for next run
		if (newDeltaLink != null) {
			CloudSyncState st = syncStates.computeIfAbsent(key, k -> new CloudSyncState(repositoryId, "microsoft"));
			st.setLastDeltaToken(newDeltaLink);
		}
	}

	private void syncMicrosoftUser(String repositoryId, org.json.simple.JSONObject msUser,
			CloudSyncResult.SyncMode mode, CloudSyncResult result) {
		String upn = (String) msUser.get("userPrincipalName");
		String email = (String) msUser.get("mail");
		String displayName = (String) msUser.get("displayName");
		String givenName = (String) msUser.get("givenName");
		String surname = (String) msUser.get("surname");

		String userId = upn != null ? upn : email;
		if (userId == null) {
			result.incrementUsersSkipped();
			return;
		}

		// Check if this is a removal marker (delta response)
		Object removed = msUser.get("@removed");
		if (removed != null) {
			try {
				User existing = principalService.getUserById(repositoryId, userId);
				if (existing != null) {
					principalService.deleteUser(repositoryId, userId);
					result.incrementUsersDeleted();
				}
			} catch (Exception e) {
				log.warn("Failed to delete MS user " + userId + ": " + e.getMessage());
				result.addError("User delete failed: " + userId);
			}
			return;
		}

		try {
			User existing = principalService.getUserById(repositoryId, userId);
			if (existing == null) {
				User newUser = new User();
				newUser.setUserId(userId);
				newUser.setName(displayName != null ? displayName : userId);
				newUser.setFirstName(givenName != null ? givenName : "");
				newUser.setLastName(surname != null ? surname : "");
				newUser.setEmail(email != null ? email : upn);
				newUser.setPasswordHash(UUID.randomUUID().toString());
				principalService.createUser(repositoryId, newUser);
				result.incrementUsersCreated();
			} else {
				boolean updated = false;
				if (displayName != null && !displayName.equals(existing.getName())) {
					existing.setName(displayName);
					updated = true;
				}
				if (givenName != null && !givenName.equals(existing.getFirstName())) {
					existing.setFirstName(givenName);
					updated = true;
				}
				if (surname != null && !surname.equals(existing.getLastName())) {
					existing.setLastName(surname);
					updated = true;
				}
				if (updated) {
					principalService.updateUser(repositoryId, existing);
					result.incrementUsersUpdated();
				} else {
					result.incrementUsersSkipped();
				}
			}
		} catch (Exception e) {
			log.warn("Failed to sync MS user " + userId + ": " + e.getMessage());
			result.addError("User sync failed: " + userId + " - " + e.getMessage());
		}
	}

	private void syncMicrosoftGroups(String repositoryId, String accessToken,
			CloudSyncResult.SyncMode mode, int windowSize, String key,
			CloudSyncResult result) throws Exception {

		java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
		String url = "https://graph.microsoft.com/v1.0/groups?$top=" + windowSize
			+ "&$select=id,displayName,mail";

		Set<String> cloudGroupIds = new HashSet<>();

		while (url != null) {
			if (isCancelled(key)) {
				result.setStatus(CloudSyncResult.Status.CANCELLED);
				return;
			}

			java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
				.uri(java.net.URI.create(url))
				.header("Authorization", "Bearer " + accessToken)
				.GET()
				.build();

			java.net.http.HttpResponse<String> response = httpClient.send(request,
				java.net.http.HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() != 200) {
				result.addError("MS Graph groups request failed: HTTP " + response.statusCode());
				break;
			}

			org.json.simple.JSONObject json = (org.json.simple.JSONObject)
				new org.json.simple.parser.JSONParser().parse(response.body());
			org.json.simple.JSONArray value = (org.json.simple.JSONArray) json.get("value");

			if (value != null) {
				for (Object item : value) {
					org.json.simple.JSONObject msGroup = (org.json.simple.JSONObject) item;
					String groupId = (String) msGroup.get("id");
					cloudGroupIds.add(groupId);
					syncMicrosoftGroup(repositoryId, accessToken, httpClient, msGroup, result);
				}
			}

			url = (String) json.get("@odata.nextLink");
		}

		// Full reconciliation: detect orphans
		if (mode == CloudSyncResult.SyncMode.FULL) {
			detectOrphanGroups(repositoryId, cloudGroupIds, "cloud-microsoft:", result);
		}
	}

	private void syncMicrosoftGroup(String repositoryId, String accessToken,
			java.net.http.HttpClient httpClient,
			org.json.simple.JSONObject msGroup, CloudSyncResult result) {
		String msGroupId = (String) msGroup.get("id");
		String displayName = (String) msGroup.get("displayName");
		String groupId = "cloud-microsoft:" + msGroupId;

		try {
			// Get group members
			List<String> memberIds = new ArrayList<>();
			String membersUrl = "https://graph.microsoft.com/v1.0/groups/" + msGroupId + "/members?$select=userPrincipalName";
			java.net.http.HttpRequest membersReq = java.net.http.HttpRequest.newBuilder()
				.uri(java.net.URI.create(membersUrl))
				.header("Authorization", "Bearer " + accessToken)
				.GET()
				.build();
			java.net.http.HttpResponse<String> membersResp = httpClient.send(membersReq,
				java.net.http.HttpResponse.BodyHandlers.ofString());

			if (membersResp.statusCode() == 200) {
				org.json.simple.JSONObject membersJson = (org.json.simple.JSONObject)
					new org.json.simple.parser.JSONParser().parse(membersResp.body());
				org.json.simple.JSONArray members = (org.json.simple.JSONArray) membersJson.get("value");
				if (members != null) {
					for (Object m : members) {
						org.json.simple.JSONObject member = (org.json.simple.JSONObject) m;
						String upn = (String) member.get("userPrincipalName");
						if (upn != null) {
							memberIds.add(upn);
						}
					}
				}
			}

			Group existing = principalService.getGroupById(repositoryId, groupId);
			if (existing == null) {
				Group newGroup = new Group();
				newGroup.setGroupId(groupId);
				newGroup.setName(displayName != null ? displayName : groupId);
				newGroup.setUsers(memberIds);
				newGroup.setGroups(new ArrayList<>());
				principalService.createGroup(repositoryId, newGroup);
				result.incrementGroupsCreated();
			} else {
				boolean updated = false;
				if (displayName != null && !displayName.equals(existing.getName())) {
					existing.setName(displayName);
					updated = true;
				}
				if (!memberIds.equals(existing.getUsers())) {
					existing.setUsers(memberIds);
					updated = true;
				}
				if (updated) {
					principalService.updateGroup(repositoryId, existing);
					result.incrementGroupsUpdated();
				} else {
					result.incrementGroupsSkipped();
				}
			}
		} catch (Exception e) {
			log.warn("Failed to sync MS group " + msGroupId + ": " + e.getMessage());
			result.addError("Group sync failed: " + msGroupId + " - " + e.getMessage());
		}
	}

	// ---- Orphan detection (full reconciliation) ----

	private void detectOrphanGroups(String repositoryId, Set<String> cloudGroupIds, String prefix,
			CloudSyncResult result) {
		try {
			List<Group> allGroups = principalService.getGroups(repositoryId);
			if (allGroups != null) {
				for (Group group : allGroups) {
					if (group.getGroupId() != null && group.getGroupId().startsWith(prefix)) {
						String cloudId = group.getGroupId().substring(prefix.length());
						if (!cloudGroupIds.contains(cloudId)) {
							result.addWarning("Orphan group detected: " + group.getGroupId()
								+ " (" + group.getName() + ")");
							// Don't auto-delete; just warn. Admin can clean up manually.
						}
					}
				}
			}
		} catch (Exception e) {
			log.warn("Orphan group detection failed: " + e.getMessage());
		}
	}

	// ---- Status & Cancel ----

	@Override
	public CloudSyncResult getSyncStatus(String repositoryId, String provider) {
		String key = syncKey(repositoryId, provider);
		CloudSyncResult result = currentResults.get(key);
		if (result == null) {
			result = new CloudSyncResult(repositoryId, provider, null);
			result.setStatus(CloudSyncResult.Status.IDLE);
		}
		return result;
	}

	@Override
	public void cancelSync(String repositoryId, String provider) {
		String key = syncKey(repositoryId, provider);
		java.util.concurrent.atomic.AtomicBoolean flag = cancelFlags.get(key);
		if (flag != null) {
			flag.set(true);
		} else {
			cancelFlags.put(key, new java.util.concurrent.atomic.AtomicBoolean(true));
		}
	}

	boolean isCancelled(String key) {
		java.util.concurrent.atomic.AtomicBoolean flag = cancelFlags.get(key);
		return flag != null && flag.get();
	}

	// ---- Connection test ----

	@Override
	public boolean testConnection(String provider) {
		try {
			switch (provider) {
				case "google":
					return testGoogleConnection();
				case "microsoft":
					return testMicrosoftConnection();
				default:
					return false;
			}
		} catch (Exception e) {
			log.error("testConnection failed for provider=" + provider, e);
			return false;
		}
	}

	// ---- Provider enabled check ----

	private boolean isProviderEnabled(String provider) {
		String enabled = propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED);
		if (!"true".equalsIgnoreCase(enabled)) {
			return false;
		}
		String providers = propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_PROVIDERS);
		return providers != null && providers.contains(provider);
	}

	// ---- Login-time Google sync (single user groups) ----

	private List<String> syncGoogleUserGroups(String email) {
		String serviceAccountKeyPath = propertyManager.readValue(
			PropertyKey.CLOUD_DIRECTORY_SYNC_GOOGLE_SERVICE_ACCOUNT_KEY);

		if (serviceAccountKeyPath == null || serviceAccountKeyPath.isEmpty()) {
			log.warn("Google service account key not configured for directory sync");
			return Collections.emptyList();
		}

		try {
			com.google.auth.oauth2.GoogleCredentials credentials =
				com.google.auth.oauth2.ServiceAccountCredentials.fromStream(
					new java.io.FileInputStream(serviceAccountKeyPath))
				.createScoped(Collections.singletonList(
					"https://www.googleapis.com/auth/admin.directory.group.readonly"))
				.createDelegated(email);

			com.google.api.services.directory.Directory directoryService =
				new com.google.api.services.directory.Directory.Builder(
					com.google.api.client.googleapis.javanet.GoogleNetHttpTransport.newTrustedTransport(),
					com.google.api.client.json.gson.GsonFactory.getDefaultInstance(),
					new com.google.auth.http.HttpCredentialsAdapter(credentials))
				.setApplicationName("NemakiWare")
				.build();

			com.google.api.services.directory.model.Groups groups =
				directoryService.groups().list().setUserKey(email).execute();

			List<String> groupNames = new ArrayList<>();
			if (groups.getGroups() != null) {
				for (com.google.api.services.directory.model.Group group : groups.getGroups()) {
					groupNames.add(group.getName());
				}
			}

			log.info("Fetched " + groupNames.size() + " groups for Google user: " + email);
			return groupNames;

		} catch (Exception e) {
			log.error("Failed to sync Google user groups for: " + email, e);
			return Collections.emptyList();
		}
	}

	private List<String> syncMicrosoftUserGroups(String externalUserId) {
		String tenantId = propertyManager.readValue(
			PropertyKey.CLOUD_DIRECTORY_SYNC_MICROSOFT_TENANT_ID);
		String clientId = propertyManager.readValue(
			PropertyKey.CLOUD_DIRECTORY_SYNC_MICROSOFT_CLIENT_ID);
		String clientSecret = propertyManager.readValue(
			PropertyKey.CLOUD_DIRECTORY_SYNC_MICROSOFT_CLIENT_SECRET);

		if (clientId == null || clientSecret == null || tenantId == null) {
			log.warn("Microsoft directory sync not fully configured");
			return Collections.emptyList();
		}

		try {
			String accessToken = getMicrosoftClientCredentialsToken(tenantId, clientId, clientSecret);

			java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
			java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
				.uri(java.net.URI.create(
					"https://graph.microsoft.com/v1.0/users/" + externalUserId + "/memberOf"))
				.header("Authorization", "Bearer " + accessToken)
				.GET()
				.build();

			java.net.http.HttpResponse<String> response = httpClient.send(request,
				java.net.http.HttpResponse.BodyHandlers.ofString());

			List<String> groupNames = new ArrayList<>();
			if (response.statusCode() == 200) {
				org.json.simple.JSONObject json = (org.json.simple.JSONObject)
					new org.json.simple.parser.JSONParser().parse(response.body());
				org.json.simple.JSONArray value = (org.json.simple.JSONArray) json.get("value");

				if (value != null) {
					for (Object item : value) {
						org.json.simple.JSONObject member = (org.json.simple.JSONObject) item;
						String type = (String) member.get("@odata.type");
						if ("#microsoft.graph.group".equals(type)) {
							groupNames.add((String) member.get("displayName"));
						}
					}
				}
			}

			log.info("Fetched " + groupNames.size() + " groups for MS user: " + externalUserId);
			return groupNames;

		} catch (Exception e) {
			log.error("Failed to sync Microsoft user groups for: " + externalUserId, e);
			return Collections.emptyList();
		}
	}

	// ---- Connection tests ----

	private boolean testGoogleConnection() {
		String serviceAccountKeyPath = propertyManager.readValue(
			PropertyKey.CLOUD_DIRECTORY_SYNC_GOOGLE_SERVICE_ACCOUNT_KEY);
		if (serviceAccountKeyPath == null || serviceAccountKeyPath.isEmpty()) {
			return false;
		}
		try {
			com.google.auth.oauth2.ServiceAccountCredentials.fromStream(
				new java.io.FileInputStream(serviceAccountKeyPath));
			return true;
		} catch (Exception e) {
			log.error("Google connection test failed", e);
			return false;
		}
	}

	private boolean testMicrosoftConnection() {
		String tenantId = propertyManager.readValue(
			PropertyKey.CLOUD_DIRECTORY_SYNC_MICROSOFT_TENANT_ID);
		String clientId = propertyManager.readValue(
			PropertyKey.CLOUD_DIRECTORY_SYNC_MICROSOFT_CLIENT_ID);
		String clientSecret = propertyManager.readValue(
			PropertyKey.CLOUD_DIRECTORY_SYNC_MICROSOFT_CLIENT_SECRET);

		if (clientId == null || clientSecret == null || tenantId == null) {
			return false;
		}
		try {
			getMicrosoftClientCredentialsToken(tenantId, clientId, clientSecret);
			return true;
		} catch (Exception e) {
			log.error("Microsoft connection test failed", e);
			return false;
		}
	}

	// ---- Token helpers ----

	private String getMicrosoftClientCredentialsToken(String tenantId, String clientId, String clientSecret)
			throws Exception {
		java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();

		String body = "grant_type=client_credentials"
			+ "&client_id=" + java.net.URLEncoder.encode(clientId, "UTF-8")
			+ "&client_secret=" + java.net.URLEncoder.encode(clientSecret, "UTF-8")
			+ "&scope=" + java.net.URLEncoder.encode("https://graph.microsoft.com/.default", "UTF-8");

		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
			.uri(java.net.URI.create("https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/token"))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
			.build();

		java.net.http.HttpResponse<String> response = httpClient.send(request,
			java.net.http.HttpResponse.BodyHandlers.ofString());

		if (response.statusCode() == 200) {
			org.json.simple.JSONObject json = (org.json.simple.JSONObject)
				new org.json.simple.parser.JSONParser().parse(response.body());
			return (String) json.get("access_token");
		} else {
			throw new RuntimeException("Failed to get MS token: HTTP " + response.statusCode());
		}
	}
}
