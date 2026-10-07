package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.constant.CallContextKey;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the RC5 (v2 §12.1) synthetic CallContext factory.
 * Pins the safety properties documented on
 * {@link DelegatedCallContextFactory}: never returns admin context,
 * fails closed on missing user / lookup error, never caches across
 * calls.
 */
class DelegatedCallContextFactoryTest {

    private static final String REPO = "bedroom";
    private static final String USER = "alice";

    private DelegatedCallContextFactory factory;
    private ContentService contentService;

    @BeforeEach
    void setUp() {
        factory = new DelegatedCallContextFactory();
        contentService = mock(ContentService.class);
        factory.setContentService(contentService);
    }

    private UserItem userItem(String id, boolean admin) {
        UserItem u = new UserItem();
        u.setUserId(id);
        u.setAdmin(admin);
        return u;
    }

    @Test
    void buildOrNull_returnsContextWithCorrectUsernameAndRepo() {
        when(contentService.getUserItemById(eq(REPO), eq(USER)))
                .thenReturn(userItem(USER, false));

        CallContext ctx = factory.buildOrNull(REPO, USER);
        assertNotNull(ctx, "expected synthetic context for active user");
        assertEquals(USER, ctx.getUsername());
        assertEquals(REPO, ctx.getRepositoryId());
        assertEquals(CallContext.BINDING_BROWSER, ctx.getBinding());
    }

    @Test
    void buildOrNull_neverReturnsAdminContext_evenWhenUserItemIsAdmin() {
        // The single most important invariant: an admin UserItem must NOT
        // produce a synthetic context that reports IS_ADMIN=true, because
        // the scheduler relies on the delegation gate to re-evaluate ACLs
        // per tick. Admins should use the manual path if they want to
        // bypass the gate.
        when(contentService.getUserItemById(eq(REPO), eq(USER)))
                .thenReturn(userItem(USER, true));

        CallContext ctx = factory.buildOrNull(REPO, USER);
        assertNotNull(ctx);
        assertEquals(Boolean.FALSE, ctx.get(CallContextKey.IS_ADMIN),
                "synthetic context must report IS_ADMIN=false even for admin users");
    }

    @Test
    void buildOrNull_returnsNullWhenUserItemMissing() {
        // Inactive-user definition in NemakiWare: not findable == inactive
        // (LDAP sync hard-deletes the record upstream).
        when(contentService.getUserItemById(eq(REPO), eq(USER))).thenReturn(null);

        assertNull(factory.buildOrNull(REPO, USER));
    }

    @Test
    void buildOrNull_refusesRatherThanCallingAFailureInactive() {
        // Still fail-CLOSED — the scheduler denies the run either way — but no longer
        // fail-SILENT: a CouchDB hiccup used to be reported as CREATOR_USER_INACTIVE, a
        // statement about the user, and fed the streak that auto-disables a profile after
        // three of them. The caller now distinguishes the two answers.
        when(contentService.getUserItemById(any(), any()))
                .thenThrow(new RuntimeException("boom"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> factory.buildOrNull(REPO, USER),
                "a failed lookup was answered as 'the creator is inactive'");
    }

    @Test
    void buildOrNull_returnsNullOnBlankInputs() {
        assertNull(factory.buildOrNull(null, USER));
        assertNull(factory.buildOrNull("", USER));
        assertNull(factory.buildOrNull(REPO, null));
        assertNull(factory.buildOrNull(REPO, ""));
        assertNull(factory.buildOrNull(REPO, "   "));
    }

    @Test
    void buildOrNull_returnsNullWhenContentServiceNotWired() {
        DelegatedCallContextFactory bare = new DelegatedCallContextFactory();
        assertNull(bare.buildOrNull(REPO, USER));
    }

    @Test
    void syntheticContext_isAdminAttributeIsImmutable() {
        // Even if a misbehaving caller tries to mutate the synthetic context's
        // attribute map, the hard-coded override in get() must still report
        // IS_ADMIN=false. Defence in depth against accidental mutation.
        when(contentService.getUserItemById(eq(REPO), eq(USER)))
                .thenReturn(userItem(USER, false));

        DelegatedCallContextFactory.SyntheticCallContext ctx =
                (DelegatedCallContextFactory.SyntheticCallContext)
                        factory.buildOrNull(REPO, USER);
        assertNotNull(ctx);
        ctx.attrs.put(CallContextKey.IS_ADMIN, Boolean.TRUE);   // simulate misuse
        assertEquals(Boolean.FALSE, ctx.get(CallContextKey.IS_ADMIN),
                "get(IS_ADMIN) must return false regardless of map contents");
    }

    @Test
    void syntheticContext_passwordAndLocaleAndTempDirAreNull_doesNotNpe() {
        // Downstream code that does `if (ctx.getPassword() != null) ...`
        // must not NPE. Same for getLocale(), getTempDirectory(), etc.
        when(contentService.getUserItemById(eq(REPO), eq(USER)))
                .thenReturn(userItem(USER, false));

        CallContext ctx = factory.buildOrNull(REPO, USER);
        assertNull(ctx.getPassword());
        assertNull(ctx.getLocale());
        assertNull(ctx.getTempDirectory());
        assertNull(ctx.getOffset());
        assertNull(ctx.getLength());
        assertEquals(0, ctx.getMemoryThreshold());
        assertEquals(0L, ctx.getMaxContentSize());
        assertFalse(ctx.encryptTempFiles());
        assertFalse(ctx.isObjectInfoRequired());
    }
}
