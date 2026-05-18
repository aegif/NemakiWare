package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.constant.CallContextKey;

import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.server.CallContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/**
 * Synthesises a {@link CallContext} for a named non-admin user so the
 * {@link IngestSchedulerService} can run a delegated profile under that
 * user's permissions, with no inbound HTTP request to derive a context
 * from.
 *
 * <p><b>Why this exists.</b> The runtime ingest gate
 * ({@link ExternalIngestController#enforceDelegatedExecution}) and the
 * {@link IngestAuthorizationService} both consume a {@code CallContext}
 * and reach into {@code getUsername()} + {@code get(IS_ADMIN)} to decide
 * whether a caller can manage a folder / use a connector. The scheduler
 * has no HTTP request to derive that from — it has only a profile
 * record with a {@code createdByUserId} string. RC5 v2 §12.1 closes the
 * gap by letting the scheduler synthesise a context for the recorded
 * creator.
 *
 * <p><b>Safety properties.</b>
 *
 * <ul>
 *   <li><b>Never short-circuits to admin.</b> Even if {@code UserItem.isAdmin()}
 *       returns true, the synthesised context returns {@code IS_ADMIN=false}.
 *       The delegation gate must run regardless of who the creator
 *       happens to be — admins can use the manual path if they want
 *       to bypass it.</li>
 *   <li><b>Per-tick scope.</b> Each call to {@link #buildOrNull(String, String)}
 *       does a fresh {@code UserItem} lookup. There is no JVM-scope
 *       cache; mid-day group / membership changes are picked up on
 *       the next poll.</li>
 *   <li><b>Inactive-user detection.</b> If the user record is missing,
 *       {@link #buildOrNull} returns {@code null}. The caller is
 *       expected to emit a
 *       {@link DenialReason#CREATOR_USER_INACTIVE} audit and skip the
 *       tick.</li>
 *   <li><b>Fail-closed on lookup error.</b> Any {@code RuntimeException}
 *       from {@code ContentService.getUserItemById} is logged and
 *       treated as "user not active" — same as the null path.</li>
 * </ul>
 *
 * <p><b>Why a custom {@link CallContext} implementation and not</b>
 * {@code CallContextImpl}? The OpenCMIS impl has a 9-arg constructor
 * pulling {@code ServletContext} / {@code HttpServletRequest} /
 * {@code CmisServiceFactory} which the scheduler does not have. The
 * synthesis only needs the four methods our delegation code actually
 * reads; everything else returns sensible defaults (null for
 * locale / password, BINDING_BROWSER for binding) so existing
 * downstream code that does
 * {@code if (ctx.getPassword() != null) ...} doesn't NPE.
 */
public class DelegatedCallContextFactory {

    private static final Logger logger = LoggerFactory.getLogger(DelegatedCallContextFactory.class);

    private ContentService contentService;

    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    /**
     * Build a CallContext for {@code username} in {@code repositoryId},
     * or return {@code null} if the user no longer exists / can't be
     * loaded. The caller should treat null as
     * {@code CREATOR_USER_INACTIVE} and skip the scheduled tick.
     */
    public CallContext buildOrNull(String repositoryId, String username) {
        if (contentService == null) {
            logger.warn("DelegatedCallContextFactory: contentService not wired; cannot build context");
            return null;
        }
        if (username == null || username.isBlank()
                || repositoryId == null || repositoryId.isBlank()) {
            return null;
        }
        try {
            UserItem user = contentService.getUserItemById(repositoryId, username);
            if (user == null) {
                logger.debug("UserItem not found for delegated scheduler: repo={}, user={}",
                        repositoryId, username);
                return null;
            }
            // Inactive-user definition: NemakiWare's UserItem model does
            // not carry an explicit "active/disabled" flag (LDAP sync
            // hard-deletes the record when a user is deactivated
            // upstream). So "not findable" IS the inactive predicate.
            return new SyntheticCallContext(repositoryId, username);
        } catch (RuntimeException e) {
            logger.warn("UserItem lookup failed for delegated scheduler (repo={}, user={}): {}",
                    repositoryId, username, e.getMessage());
            return null;
        }
    }

    /**
     * Minimal CallContext that satisfies the methods the delegation
     * gate touches. Anything else returns defaults compatible with the
     * Browser binding the scheduler conceptually impersonates.
     */
    static final class SyntheticCallContext implements CallContext {
        private final String repositoryId;
        private final String username;
        // Package-private so the unit test can simulate a misbehaving
        // caller mutating the map and confirm get(IS_ADMIN) still
        // hard-codes false. See DelegatedCallContextFactoryTest.
        final Map<String, Object> attrs = new HashMap<>();

        SyntheticCallContext(String repositoryId, String username) {
            this.repositoryId = repositoryId;
            this.username = username;
            // Explicit non-admin — must hold even if the underlying
            // UserItem.isAdmin() returns true. The delegation gate
            // would otherwise short-circuit and the per-tick ACL
            // re-evaluation we worked so hard to add would be a no-op.
            attrs.put(CallContextKey.IS_ADMIN, Boolean.FALSE);
        }

        @Override public String getBinding() { return CallContext.BINDING_BROWSER; }
        @Override public boolean isObjectInfoRequired() { return false; }
        @Override public Object get(String key) {
            // Hard-coded IS_ADMIN=false takes precedence over the map
            // entry just in case a misbehaving caller put() something
            // there. (The map is package-private only for testing.)
            if (CallContextKey.IS_ADMIN.equals(key)) return Boolean.FALSE;
            return attrs.get(key);
        }
        @Override public CmisVersion getCmisVersion() { return CmisVersion.CMIS_1_1; }
        @Override public String getRepositoryId() { return repositoryId; }
        @Override public String getUsername() { return username; }
        @Override public String getPassword() { return null; }
        @Override public String getLocale() { return null; }
        @Override public BigInteger getOffset() { return null; }
        @Override public BigInteger getLength() { return null; }
        @Override public java.io.File getTempDirectory() { return null; }
        @Override public boolean encryptTempFiles() { return false; }
        @Override public int getMemoryThreshold() { return 0; }
        @Override public long getMaxContentSize() { return 0L; }
    }
}
