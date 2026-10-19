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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.AuthenticationUtil;
import jp.aegif.nemaki.util.AuthenticationUtil.GenerationSave;
import jp.aegif.nemaki.util.cache.model.NemakiCache;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.test.JavaSource;

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
        assertTrue(update > 0, "the user update is gone — this lock reads that method");
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

        // AND «could not read» is not «there is nothing stored» (R195 — both reviewers, batch-19's
        // confirmation round). Two shapes, because get(Class, String) reports a read it could not
        // make BOTH ways: by throwing, and by answering null — its own javadoc says "during a
        // CouchDB outage every object looks absent".
        int caught = guardBody.indexOf("catch (");
        assertTrue(caught > 0, "the guard no longer catches the read's failure, so this lock is not "
                + "reading the arm it is about");
        String afterCatch = guardBody.substring(caught);
        int throws_ = afterCatch.indexOf("throw ");
        int returns = afterCatch.indexOf("return");
        assertTrue(throws_ >= 0, "the guard no longer throws anywhere after catching the read's "
                + "failure, so it lets the save through: " + guardBody);
        assertTrue(returns < 0 || throws_ < returns,
                "the guard lets the save through when the stored document could not be READ, which is"
                        + " how a generation goes backwards during an outage: " + guardBody);

        // THE STRICT ASK, AND ITS ANSWER USED TO REFUSE. 「existsStrict( appears」 was not enough:
        // reading the value into a variable and ignoring it left this green (batch-22..24 review,
        // Codex P2). The null answer needs it because get(Class, String) returns null for a 404, a
        // tombstone and the provisioning window — and only the strict ask separates 「the document is
        // there」 from those.
        int strict = guardBody.indexOf("existsStrict(");
        assertTrue(strict > 0, "the guard treats a null answer as «not there» without the strict ask: "
                + guardBody);
        String fromStrict = guardBody.substring(strict);
        assertTrue(fromStrict.indexOf("throw ") >= 0
                        && (fromStrict.indexOf("return") < 0 || fromStrict.indexOf("throw ") < fromStrict.indexOf("return")),
                "the guard asks whether the document is there and does not refuse the save when it is"
                        + " (the answer is read and dropped): " + guardBody);
        assertTrue(guardBody.substring(0, strict).contains("if (") || fromStrict.startsWith("existsStrict"),
                "the strict ask is not in a condition, so nothing branches on it: " + guardBody);
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
}
