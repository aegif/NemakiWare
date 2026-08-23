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
package jp.aegif.nemaki.cmis.service;

import jp.aegif.nemaki.cmis.service.impl.ObjectServiceImpl;
import jp.aegif.nemaki.model.Document;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code bulkUpdateProperties} folds add/removeSecondaryTypeIds into the ONE path that guards
 * evidence types.
 *
 * <p>The task accepted both lists, stored them — and never read them: bulk updates silently
 * could not touch secondary types at all (audit #16, pre-existing). The fix folds them into
 * {@code cmis:secondaryObjectTypeIds} so the change flows through {@code updateProperties} →
 * {@code modifyProperties} → {@code buildSecondaryTypes}, where {@code keepEvidenceAspects}
 * refuses to detach an evidence type. A separate attach/detach call would have bypassed that
 * guard — the audit's warning ("直すなら必ず keepEvidenceAspects 経由") in code form.
 */
class BulkUpdateSecondaryTypeChangesTest {

    @SuppressWarnings("unchecked")
    private static Properties fold(Properties original, Document content,
            List<String> add, List<String> remove) throws Exception {
        ObjectServiceImpl service = new ObjectServiceImpl();
        Class<?> taskClass = null;
        for (Class<?> declared : ObjectServiceImpl.class.getDeclaredClasses()) {
            if (declared.getSimpleName().equals("BulkUpdateTask")) {
                taskClass = declared;
            }
        }
        Constructor<?> ctor = taskClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object task = ctor.newInstance(service, null, "bedroom", null, original, add, remove, null);
        Method m = taskClass.getDeclaredMethod("withSecondaryTypeChanges",
                Properties.class, jp.aegif.nemaki.model.Content.class);
        m.setAccessible(true);
        return (Properties) m.invoke(task, original, content);
    }

    private static Document withSecondaryIds(String... ids) {
        Document doc = new Document();
        doc.setId("obj-1");
        doc.setSecondaryIds(new ArrayList<>(List.of(ids)));
        return doc;
    }

    private static List<String> secondaryIdsOf(Properties properties) {
        return properties.getProperties().get(PropertyIds.SECONDARY_OBJECT_TYPE_IDS)
                .getValues().stream().map(String::valueOf).toList();
    }

    @Test
    @DisplayName("remove folds into the property and flows through the guarded path")
    void removeIsFolded() throws Exception {
        PropertiesImpl original = new PropertiesImpl();
        original.addProperty(new PropertyStringImpl(PropertyIds.NAME, "renamed"));

        Properties folded = fold(original, withSecondaryIds("nemaki:comment", "nemaki:tagged"),
                null, List.of("nemaki:comment"));

        assertEquals(List.of("nemaki:tagged"), secondaryIdsOf(folded),
                "removeSecondaryTypeIds was accepted and ignored — the bulk update silently "
                        + "could not detach anything");
        assertTrue(folded.getProperties().containsKey(PropertyIds.NAME),
                "the fold must not lose the other requested properties");
    }

    @Test
    @DisplayName("add folds in, deduplicated, current ids preserved")
    void addIsFolded() throws Exception {
        Properties folded = fold(new PropertiesImpl(), withSecondaryIds("nemaki:comment"),
                List.of("nemaki:tagged", "nemaki:comment"), null);

        assertEquals(List.of("nemaki:comment", "nemaki:tagged"), secondaryIdsOf(folded));
    }

    @Test
    @DisplayName("a request-supplied list is the base, not the object's current ids")
    void requestListWins() throws Exception {
        PropertiesImpl original = new PropertiesImpl();
        original.addProperty(new PropertyIdImpl(PropertyIds.SECONDARY_OBJECT_TYPE_IDS,
                new ArrayList<>(List.of("nemaki:fromRequest"))));

        Properties folded = fold(original, withSecondaryIds("nemaki:current"),
                List.of("nemaki:added"), null);

        assertEquals(List.of("nemaki:fromRequest", "nemaki:added"), secondaryIdsOf(folded),
                "CMIS says an explicit cmis:secondaryObjectTypeIds is the whole truth; the "
                        + "add/remove lists refine IT, not the stored state");
    }

    @Test
    @DisplayName("call() hands the FOLDED properties to updateProperties — the wiring pin")
    void callUsesTheFoldedProperties() throws Exception {
        // The helper tests above cannot see a regression at the call site (they invoke the
        // helper directly). This one runs call() itself: reverting the call site to pass the
        // raw request — the pre-fix wiring — leaves the captured request without any
        // secondary-ids property, and this test fails.
        ObjectServiceImpl service = new ObjectServiceImpl();
        jp.aegif.nemaki.businesslogic.ContentService contentService =
                mock(jp.aegif.nemaki.businesslogic.ContentService.class);
        service.setContentService(contentService);
        service.setExceptionService(mock(jp.aegif.nemaki.cmis.aspect.ExceptionService.class));
        service.setTypeManager(mock(jp.aegif.nemaki.cmis.aspect.type.TypeManager.class));
        jp.aegif.nemaki.util.cache.NemakiCachePool pool =
                mock(jp.aegif.nemaki.util.cache.NemakiCachePool.class,
                        org.mockito.Mockito.RETURNS_DEEP_STUBS);
        service.setNemakiCachePool(pool);
        jp.aegif.nemaki.util.lock.ThreadLockService locks =
                mock(jp.aegif.nemaki.util.lock.ThreadLockService.class);
        when(locks.getWriteLock(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new java.util.concurrent.locks.ReentrantLock());
        service.setThreadLockService(locks);

        // A folder, so the document-only constraint checks stay out of the way.
        jp.aegif.nemaki.model.Folder stored = new jp.aegif.nemaki.model.Folder();
        stored.setId("obj-1");
        stored.setSecondaryIds(new ArrayList<>(List.of("nemaki:comment", "nemaki:tagged")));
        when(contentService.getContent(org.mockito.ArgumentMatchers.eq("bedroom"),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(stored);

        Class<?> taskClass = null;
        for (Class<?> declared : ObjectServiceImpl.class.getDeclaredClasses()) {
            if (declared.getSimpleName().equals("BulkUpdateTask")) {
                taskClass = declared;
            }
        }
        Constructor<?> ctor = taskClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        PropertiesImpl request = new PropertiesImpl();
        request.addProperty(new PropertyStringImpl(PropertyIds.NAME, "renamed"));
        Object task = ctor.newInstance(service,
                mock(org.apache.chemistry.opencmis.commons.server.CallContext.class), "bedroom",
                new org.apache.chemistry.opencmis.commons.impl.dataobjects
                        .BulkUpdateObjectIdAndChangeTokenImpl("obj-1", "token"),
                request, null, List.of("nemaki:comment"), null);
        ((java.util.concurrent.Callable<?>) task).call();

        org.mockito.ArgumentCaptor<Properties> sent =
                org.mockito.ArgumentCaptor.forClass(Properties.class);
        org.mockito.Mockito.verify(contentService).updateProperties(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("bedroom"),
                sent.capture(), org.mockito.ArgumentMatchers.eq(stored));
        assertEquals(List.of("nemaki:tagged"), secondaryIdsOf(sent.getValue()),
                "call() did not fold the remove list — updateProperties saw the raw request "
                        + "and the bulk update silently ignored removeSecondaryTypeIds again");
    }

    @Test
    @DisplayName("no add, no remove: the properties object is returned untouched — the control")
    void noChangesMeansIdentity() throws Exception {
        PropertiesImpl original = new PropertiesImpl();
        original.addProperty(new PropertyStringImpl(PropertyIds.NAME, "renamed"));

        Properties folded = fold(original, withSecondaryIds("nemaki:comment"), null, null);

        assertSame(original, folded,
                "an ordinary bulk update must keep its exact old shape — rebuilding the "
                        + "properties for nothing invites subtle drift");
    }
}
