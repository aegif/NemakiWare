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
package jp.aegif.nemaki.dao.impl.cached;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.couch.CouchUserItem;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.AuthenticationUtil;
import jp.aegif.nemaki.util.AuthenticationUtil.GenerationSave;
import jp.aegif.nemaki.util.cache.model.NemakiCache;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.test.JavaSource;
import jp.aegif.nemaki.util.test.HarnessBroken;

/**
 * Saving a user cannot take their credential generation backwards (R170, 3.4.1 batch 19).
 *
 * <p>The generation is what makes a password change end the sessions it opened. Every path that
 * replaces the WHOLE user document — the old REST update, an import, a restore — can drop the field
 * or write an older value without meaning to, and then every credential that change invalidated
 * works again. Nothing in the answer to the caller would say so.
 *
 * <p>Three things are measured: the rule, the place it is asked from, and what happens to the CACHE
 * when the answer is no. The last one is the one that bites: the cache hands out the very instance a
 * password change mutates, so a refused save would leave the cache ahead of CouchDB and the
 * credential check would then refuse every live credential of that account — an over-refusal, which
 * this tree weighs the same as letting one through.
 */
class SavingAUserCannotLowerTheCredentialGenerationTest {

    private static final String REPO = "bedroom";
    private static final Path COUCH_DAO =
            Path.of("src/main/java/jp/aegif/nemaki/dao/impl/couch/ContentDaoServiceImpl.java");

    /** A user document; {@code generation} null means the property is absent. */
    private UserItem user(Object generation) {
        UserItem item = new UserItem("alice-doc", "nemaki:user", "alice", "alice", "hash", false, null);
        List<Property> properties = new ArrayList<>();
        if (generation != null) {
            properties.add(new Property("nemaki:credentialGeneration", generation));
        }
        item.setSubTypeProperties(properties);
        return item;
    }

    @Test
    @DisplayName("the rule: keeping or raising it is allowed, lowering or dropping it is not")
    void theRule() {
        assertEquals(GenerationSave.OK, AuthenticationUtil.generationSave(user(2), user(2)));
        assertEquals(GenerationSave.OK, AuthenticationUtil.generationSave(user(2), user(3)));
        assertEquals(GenerationSave.WOULD_LOWER, AuthenticationUtil.generationSave(user(2), user(1)),
                "a save that takes the generation backwards was allowed, so every credential the"
                        + " password change invalidated works again");
        assertEquals(GenerationSave.WOULD_LOWER, AuthenticationUtil.generationSave(user(2), user(null)),
                "a document-replacing save that DROPS the field was allowed — absent reads as 0, and"
                        + " that is the whole shape this guard exists for");
        // Absent on both sides is an account that never changed its password: allowed, or upgrading
        // would refuse every user update in the repository.
        assertEquals(GenerationSave.OK, AuthenticationUtil.generationSave(user(null), user(null)));
    }

    @Test
    @DisplayName("unreadable is not zero, on either side")
    void unreadableIsNotZero() {
        assertEquals(GenerationSave.STORED_UNREADABLE,
                AuthenticationUtil.generationSave(user("nonsense"), user(1)),
                "a stored value nobody can read was treated as comparable, so the save went ahead on"
                        + " a guess");
        assertEquals(GenerationSave.SAVING_UNREADABLE,
                AuthenticationUtil.generationSave(user(1), user(-2)),
                "a negative generation was saved; the rule's own arms treat it as unreadable because"
                        + " no account can be at generation -2");
    }

    @Test
    @DisplayName("the couch DAO asks the rule before it writes, and reads the stored document itself")
    void theCouchDaoAsksBeforeWriting() throws IOException {
        // Read from the source: driving this needs a CouchDB connector. What it measures is that the
        // guard is in the layer BOTH callers go through — ContentServiceImpl.updateInternal and the
        // MD5-to-BCrypt upgrade, which goes through no service at all.
        String code = JavaSource.withoutComments(
                Files.readString(COUCH_DAO, StandardCharsets.UTF_8));
        int update = code.indexOf("public UserItem update(String repositoryId, UserItem userItem)");
        HarnessBroken.unless(update > 0, "the user update is gone — this lock reads that method");
        String body = code.substring(update, JavaSource.matchingClose(code, code.indexOf('{', update)));

        int guard = body.indexOf("refuseALowerCredentialGeneration(");
        assertTrue(guard > 0,
                "the user update does not ask whether the save lowers the credential generation, so"
                        + " a document-replacing PUT can drop it and revive every credential a"
                        + " password change ended");
        int put = body.indexOf(".update(update)");
        assertTrue(put > guard,
                "the guard is asked after the document is written, or not at all: " + body);

        String guardBody = code.substring(code.indexOf("private void refuseALowerCredentialGeneration"));
        guardBody = guardBody.substring(0, JavaSource.matchingClose(guardBody, guardBody.indexOf('{')));
        assertTrue(guardBody.contains("connectorPool.getClient(repositoryId).get(CouchUserItem.class"),
                "the guard does not read the stored document from CouchDB. A read through the cache"
                        + " returns the instance the caller just mutated, so the comparison would be"
                        + " the new value against itself: " + guardBody);
        assertTrue(guardBody.contains("generationSave("),
                "the guard does not use the one rule, so a second reading of 'absent' or"
                        + " 'unreadable' can drift from it");

        // WHAT THE GUARD DOES with a read it could not make is measured by RUNNING it — the three
        // tests below drive the couch DAO with a client that answers null-and-present, absent, and
        // throwing. Four source-level forms of that check were written and all four were satisfiable
        // while the fail-open stayed open: 「existsStrict( appears」 (the answer read into a variable
        // and dropped), 「a throw before a return」 (the VERDICT's own throw is one), 「an if( appears
        // before it」 (a tautology — the substring starts at the call), and 「the throw is in the block
        // of its own un-negated condition」 (a contradictory condition inside that block, and a
        // reordering that returns before ever asking). Two reviewers found two more bypasses each
        // round; the run has none (batch-25 review: Codex P2-3, subagent P2-5 and P3-1).
    }

    @Test
    @DisplayName("a refused save evicts the user from the caches — otherwise live credentials are refused")
    void aRefusedSaveEvictsTheUser() {
        ContentDaoService nonCached = mock(ContentDaoService.class);
        NemakiCachePool pool = mock(NemakiCachePool.class);
        NemakiCache<UserItem> userCache = mock(NemakiCache.class);
        @SuppressWarnings("unchecked")
        NemakiCache<jp.aegif.nemaki.model.Content> contentCache = mock(NemakiCache.class);
        NemakiCache<org.apache.chemistry.opencmis.commons.data.ObjectData> objectDataCache =
                mock(NemakiCache.class);
        jp.aegif.nemaki.util.cache.CacheService caches =
                mock(jp.aegif.nemaki.util.cache.CacheService.class);
        when(pool.get(REPO)).thenReturn(caches);
        when(caches.getUserItemCache()).thenReturn(userCache);
        when(caches.getContentCache()).thenReturn(contentCache);
        when(caches.getObjectDataCache()).thenReturn(objectDataCache);

        ContentDaoServiceImpl cached = new ContentDaoServiceImpl();
        cached.setNonCachedContentDaoService(nonCached);
        cached.setNemakiCachePool(pool);

        UserItem saving = user(1);
        IllegalStateException refusal = new IllegalStateException("WOULD_LOWER");
        when(nonCached.update(eq(REPO), any(UserItem.class))).thenThrow(refusal);

        assertEquals(refusal, assertThrows(IllegalStateException.class,
                () -> cached.update(REPO, saving),
                "a refused save was reported as success"));

        verify(userCache).remove("alice");
        verify(contentCache).remove("alice-doc");
        verify(objectDataCache).remove("alice-doc");
        verify(userCache, org.mockito.Mockito.never()).put(anyString(), any(UserItem.class));
    }

    @Test
    @DisplayName("the store read asks the delegate's OWN store read — not its cached-equivalent sibling (R207)")
    void theCachedStoreReadGoesToTheDelegatesStoreRead() {
        // The SSO doors read the binding back with getUserItemByIdFromStore because the cache holds
        // the instance the write produced. That claim ends at this layer: if it delegates to
        // getUserItemById, the two are synonyms in the couch layer TODAY and nothing shows — and the
        // day the store read stops being the plain read, this door silently does not get it
        // (batch-22..24 review, subagent P3-5). It also must not touch the user cache at all.
        ContentDaoService nonCached = mock(ContentDaoService.class);
        NemakiCachePool pool = mock(NemakiCachePool.class);
        @SuppressWarnings("unchecked")
        NemakiCache<UserItem> userCache = mock(NemakiCache.class);
        jp.aegif.nemaki.util.cache.CacheService caches =
                mock(jp.aegif.nemaki.util.cache.CacheService.class);
        when(pool.get(REPO)).thenReturn(caches);
        when(caches.getUserItemCache()).thenReturn(userCache);

        ContentDaoServiceImpl cached = new ContentDaoServiceImpl();
        cached.setNonCachedContentDaoService(nonCached);
        cached.setNemakiCachePool(pool);

        UserItem fromStore = user(2);
        when(nonCached.getUserItemByIdFromStore(REPO, "alice")).thenReturn(fromStore);

        assertEquals(fromStore, cached.getUserItemByIdFromStore(REPO, "alice"),
                "the store read did not answer with what the delegate's store read holds");
        verify(nonCached).getUserItemByIdFromStore(REPO, "alice");
        verify(nonCached, org.mockito.Mockito.never()).getUserItemById(anyString(), anyString());
        // No 「the user cache was not read」 here: this method does not touch the cache pool at all,
        // so that verify is satisfied by every implementation including a wrong one (batch-25
        // review, subagent P3-3). The two verifies above are what discriminate.
    }

    @Test
    @DisplayName("the stored document could not be READ — the save is refused, driven through the DAO (R195)")
    void aReadThatCouldNotBeMadeRefusesTheSave() {
        // DRIVEN, not read. Every source form of this check was satisfiable while the fail-open
        // stayed open: 「existsStrict( appears」, 「a throw follows it」, 「the throw is in the block of
        // its own un-negated condition」 — the last is satisfied by a contradictory condition inside
        // that block (batch-25 review, Codex P2-3), and none of them says the strict ask is on the
        // path OUT of the null arm (the same review, subagent P3-1: moving the early return in front
        // of it left all three green).
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.get(eq(CouchUserItem.class), anyString())).thenReturn(null);
        // THERE, and unreadable: get() answers null for a 404, a _deleted tombstone AND any failure
        // during the provisioning window (get(String)'s isStartupPhase arm). The strict ask is what
        // separates them, and here it says the document is there.
        when(client.existsStrict(anyString())).thenReturn(true);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> couchDaoWhoseClientIs(client).update(REPO, savable()),
                "the stored document is there and could not be read, and the save went through — a"
                        + " document-replacing PUT then drops the credential generation and every"
                        + " credential the password change ended starts working again");
        assertTrue(refused.getMessage().contains("could not be read"), refused.getMessage());
        verify(client, org.mockito.Mockito.never()).update(any(CouchUserItem.class));
    }

    @Test
    @DisplayName("the stored document is genuinely absent — the save proceeds (the over-refusal side)")
    void aGenuineAbsenceDoesNotRefuseTheSave() {
        // The other half, because over-refusal weighs the same in this tree: the first save of an
        // account the store does not hold yet must not be refused by a guard that asks about the
        // document it would replace.
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.get(eq(CouchUserItem.class), anyString())).thenReturn(null);
        when(client.existsStrict(anyString())).thenReturn(false);

        assertDoesNotThrow(() -> couchDaoWhoseClientIs(client).update(REPO, savable()),
                "a genuine absence was read as 「could not be read」, so saving an account the store"
                        + " does not hold yet is refused");
        verify(client).update(any(CouchUserItem.class));
    }

    @Test
    @DisplayName("the read itself THREW — the save is refused (the other shape of the same failure)")
    void aReadThatThrewRefusesTheSave() {
        CloudantClientWrapper client = mock(CloudantClientWrapper.class);
        when(client.get(eq(CouchUserItem.class), anyString()))
                .thenThrow(new RuntimeException("the database did not answer"));

        assertThrows(IllegalStateException.class,
                () -> couchDaoWhoseClientIs(client).update(REPO, savable()),
                "a read that threw let the save through, so an outage authorises lowering the"
                        + " credential generation");
        verify(client, org.mockito.Mockito.never()).update(any(CouchUserItem.class));
    }

    /** The couch DAO — where the guard lives — with its connector pool answering with this client. */
    private static jp.aegif.nemaki.dao.impl.couch.ContentDaoServiceImpl couchDaoWhoseClientIs(
            CloudantClientWrapper client) {
        CloudantClientPool pool = mock(CloudantClientPool.class);
        when(pool.getClient(anyString())).thenReturn(client);
        jp.aegif.nemaki.dao.impl.couch.ContentDaoServiceImpl dao =
                new jp.aegif.nemaki.dao.impl.couch.ContentDaoServiceImpl();
        dao.setConnectorPool(pool);
        return dao;
    }

    /** A user the DAO will accept for an update: it needs a revision of its own. */
    private UserItem savable() {
        UserItem saving = user(1);
        saving.setRevision("1-abc");
        return saving;
    }
}
