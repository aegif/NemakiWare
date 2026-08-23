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

    /**
     * The explicit signature, not {@code getDeclaredConstructors()[0]} — the array's order is
     * JVM-dependent, so a second constructor would make the lookup nondeterministic (it fails
     * loudly either way, but flakily).
     */
    private static Constructor<?> taskConstructor() throws Exception {
        Class<?> taskClass = null;
        for (Class<?> declared : ObjectServiceImpl.class.getDeclaredClasses()) {
            if (declared.getSimpleName().equals("BulkUpdateTask")) {
                taskClass = declared;
            }
        }
        Constructor<?> ctor = taskClass.getDeclaredConstructor(ObjectServiceImpl.class,
                org.apache.chemistry.opencmis.commons.server.CallContext.class, String.class,
                org.apache.chemistry.opencmis.commons.data.BulkUpdateObjectIdAndChangeToken.class,
                Properties.class, List.class, List.class,
                org.apache.chemistry.opencmis.commons.data.ExtensionsData.class);
        ctor.setAccessible(true);
        return ctor;
    }

    private static Properties fold(Properties original, Document content,
            List<String> add, List<String> remove) throws Exception {
        Constructor<?> ctor = taskConstructor();
        Object task = ctor.newInstance(new ObjectServiceImpl(), null, "bedroom", null, original,
                add, remove, null);
        Method m = ctor.getDeclaringClass().getDeclaredMethod("withSecondaryTypeChanges",
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
    @DisplayName("null properties + removes: the fold materializes a fresh Properties")
    void nullPropertiesAreMaterialized() throws Exception {
        // CMIS allows a bulk update that ONLY touches secondary types;
        // checkExceptionBeforeUpdateProperties carves out null properties for exactly that.
        // The first fold NPE'd here (review) — and because modifyProperties returns early on
        // null, the shape had never worked at all: the fold materializing the ids is what
        // makes it work.
        Properties folded = fold(null, withSecondaryIds("nemaki:comment", "nemaki:tagged"),
                null, List.of("nemaki:comment"));

        assertEquals(List.of("nemaki:tagged"), secondaryIdsOf(folded),
                "a secondary-type-only bulk update (properties == null) fell over instead of "
                        + "folding");
        assertEquals(1, folded.getPropertyList().size(),
                "nothing but the folded secondary-ids property should be materialized");
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

        Constructor<?> ctor = taskConstructor();
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
    @DisplayName("add/remove ids get the SAME validation as the properties list at the entrance")
    void addRemoveIdsAreValidatedAtTheEntrance() {
        // The properties-list variant of a bad type id fails with invalidArgument at the bulk
        // entrance; the add/remove lists skipped that check entirely, so a typo'd id sailed
        // through, buildSecondaryTypes dropped it at debug level, and numUpdated=N read as
        // success while nothing was attached (review F-1).
        ObjectServiceImpl service = new ObjectServiceImpl();
        jp.aegif.nemaki.cmis.aspect.ExceptionService exceptions =
                mock(jp.aegif.nemaki.cmis.aspect.ExceptionService.class);
        service.setExceptionService(exceptions);

        service.bulkUpdateProperties(
                mock(org.apache.chemistry.opencmis.commons.server.CallContext.class), "bedroom",
                new ArrayList<>(), null, List.of("nemaki:ghost"), List.of("nemaki:tagged"), null);

        org.mockito.ArgumentCaptor<Properties> checked =
                org.mockito.ArgumentCaptor.forClass(Properties.class);
        org.mockito.Mockito.verify(exceptions, org.mockito.Mockito.times(2))
                .invalidArgumentSecondaryTypeIds(
                        org.mockito.ArgumentMatchers.eq("bedroom"), checked.capture());
        assertEquals(List.of("nemaki:ghost", "nemaki:tagged"),
                secondaryIdsOf(checked.getAllValues().get(1)),
                "the add/remove ids must reach the same validator the properties list gets");
    }

    @Test
    @DisplayName("a validator refusal PROPAGATES out of bulkUpdateProperties — not per-object")
    void validatorRefusalPropagates() {
        // "Called twice" alone would still pass if the call were moved inside the per-object
        // task, where the catch swallows it into a skipped object (review P3). The refusal must
        // escape the entrance synchronously, like the properties-variant's does.
        ObjectServiceImpl service = new ObjectServiceImpl();
        jp.aegif.nemaki.cmis.aspect.ExceptionService exceptions =
                mock(jp.aegif.nemaki.cmis.aspect.ExceptionService.class);
        org.mockito.Mockito.doThrow(new org.apache.chemistry.opencmis.commons.exceptions
                        .CmisInvalidArgumentException("Invalid cmis:SecondaryObjectTypeIds"))
                .when(exceptions).invalidArgumentSecondaryTypeIds(
                        org.mockito.ArgumentMatchers.eq("bedroom"),
                        org.mockito.ArgumentMatchers.argThat(p -> p != null
                                && p.getProperties().containsKey(
                                        PropertyIds.SECONDARY_OBJECT_TYPE_IDS)));
        service.setExceptionService(exceptions);

        org.junit.jupiter.api.Assertions.assertThrows(
                org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException.class,
                () -> service.bulkUpdateProperties(
                        mock(org.apache.chemistry.opencmis.commons.server.CallContext.class),
                        "bedroom", new ArrayList<>(), null, List.of("nemaki:ghost"), null, null),
                "a typo'd add id must fail the request, not read as numUpdated=N success");
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
