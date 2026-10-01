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
package jp.aegif.nemaki.rest.ingest;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every way the store can answer a target-folder path, and what each one must produce (R45).
 *
 * <h2>Why a table</h2>
 *
 * <p>This method has eight outcomes and they are easy to confuse in ONE direction at a time:
 * three of them were, over three separate reviews — a document at the path, a permission denial
 * and a store failure each answered "the profile configured no folder", which is a sentence
 * about the PROFILE derived from a read of the STORE. Each was fixed with a test for that arm.
 *
 * <p>What no single-arm test says is the other half: which answers must NOT refuse. A verifier
 * that refuses everything passes every over-permission test ever written. The plan (A-4) asks
 * for both directions in one expectation table, written as an oracle separate from the
 * implementation — so the table below is a list of STORE BEHAVIOURS and EXPECTED ANSWERS, and
 * nothing in it is read from the class under test.
 */
class TargetFolderResolutionTableTest {

    /** What the resolution must answer. Not derived from the implementation. */
    enum Expected {
        /** A folder id comes back. */
        RESOLVED,
        /** Null comes back, meaning "this profile has no folder" — an ANSWER, not a refusal. */
        NO_FOLDER,
        /** A refusal a retry could fix: the read did not answer. */
        REFUSED_RETRYABLE,
        /** A refusal a retry cannot fix: the read answered, and the answer is unusable. */
        REFUSED_PERMANENT
    }

    /**
     * One row of the oracle.
     *
     * @param what      the store behaviour, in words
     * @param arrange   how to make the mocked object service behave that way
     * @param onProfile any change to the profile this row needs
     * @param expected  what the resolution must answer
     */
    record Row(String what, Consumer<jp.aegif.nemaki.cmis.service.ObjectService> arrange,
            Consumer<ImportProfileDefinition> onProfile, Expected expected) {}

    private static ObjectData folderAt(String id) {
        ObjectData data = mock(ObjectData.class);
        when(data.getId()).thenReturn(id);
        PropertyData<?> baseType = mock(PropertyData.class);
        when(baseType.getFirstValue()).thenAnswer(inv -> "cmis:folder");
        Properties properties = mock(Properties.class);
        Map<String, PropertyData<?>> map = new LinkedHashMap<>();
        map.put("cmis:baseTypeId", baseType);
        when(properties.getProperties()).thenAnswer(inv -> map);
        when(data.getProperties()).thenReturn(properties);
        return data;
    }

    private static ObjectData documentAt(String id) {
        ObjectData data = mock(ObjectData.class);
        when(data.getId()).thenReturn(id);
        PropertyData<?> baseType = mock(PropertyData.class);
        when(baseType.getFirstValue()).thenAnswer(inv -> "cmis:document");
        Properties properties = mock(Properties.class);
        Map<String, PropertyData<?>> map = new LinkedHashMap<>();
        map.put("cmis:baseTypeId", baseType);
        when(properties.getProperties()).thenAnswer(inv -> map);
        when(data.getProperties()).thenReturn(properties);
        return data;
    }

    private static void answers(jp.aegif.nemaki.cmis.service.ObjectService service,
            ObjectData data) {
        when(service.getObjectByPath(any(), anyString(), anyString(), any(), anyBoolean(),
                any(), any(), anyBoolean(), anyBoolean(), any())).thenReturn(data);
    }

    private static void throwsFrom(jp.aegif.nemaki.cmis.service.ObjectService service,
            RuntimeException failure) {
        when(service.getObjectByPath(any(), anyString(), anyString(), any(), anyBoolean(),
                any(), any(), anyBoolean(), anyBoolean(), any())).thenThrow(failure);
    }

    /**
     * THE ORACLE. Read it before the implementation, not from it.
     *
     * <p>The two columns that matter are the last one and its opposite: rows expecting a refusal
     * measure over-permission, rows expecting an answer measure over-refusal. A change that
     * makes the method refuse more will fail the second group; a change that makes it refuse
     * less will fail the first.
     */
    private static List<Row> oracle() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("targetFolderId is set — the path is not consulted at all",
                service -> throwsFrom(service, new CmisRuntimeException("must not be called")),
                profile -> profile.setTargetFolderId("folder-1"),
                Expected.RESOLVED));
        rows.add(new Row("the path resolves to a folder",
                service -> answers(service, folderAt("folder-2")),
                profile -> { },
                Expected.RESOLVED));
        rows.add(new Row("the path resolves to a DOCUMENT — answered, and unusable",
                service -> answers(service, documentAt("doc-1")),
                profile -> { },
                Expected.REFUSED_PERMANENT));
        rows.add(new Row("the store answers with no object and no exception",
                service -> answers(service, null),
                profile -> { },
                Expected.REFUSED_RETRYABLE));
        rows.add(new Row("the store answers: there is no such path",
                service -> throwsFrom(service, new CmisObjectNotFoundException("no such path")),
                profile -> { },
                Expected.NO_FOLDER));
        rows.add(new Row("the store answers: the importing user may not read it",
                service -> throwsFrom(service, new CmisPermissionDeniedException("denied")),
                profile -> { },
                Expected.REFUSED_PERMANENT));
        rows.add(new Row("the store fails — the read did not answer",
                service -> throwsFrom(service, new CmisRuntimeException("connection reset")),
                profile -> { },
                Expected.REFUSED_RETRYABLE));
        rows.add(new Row("neither field is configured",
                service -> throwsFrom(service, new CmisRuntimeException("must not be called")),
                profile -> profile.setTargetFolderPath(null),
                Expected.NO_FOLDER));
        return rows;
    }

    @Test
    @DisplayName("every store answer produces the outcome the table names — both directions")
    void theTableHolds() throws Exception {
        List<String> wrong = new ArrayList<>();
        int row = 0;
        for (Row expectation : oracle()) {
            row++;
            jp.aegif.nemaki.cmis.service.ObjectService objectService =
                    mock(jp.aegif.nemaki.cmis.service.ObjectService.class);
            expectation.arrange().accept(objectService);
            CanonicalImportServiceImpl service = new CanonicalImportServiceImpl();
            service.setObjectService(objectService);
            ImportProfileDefinition profile = new ImportProfileDefinition();
            profile.setProfileId("p" + row);
            profile.setRepositoryId("bedroom");
            // A distinct path per row: the resolution caches by repository + path, and one
            // shared path would let row 2's success answer row 7's failure.
            profile.setTargetFolderPath("/row/" + row);
            expectation.onProfile().accept(profile);

            Expected actual = resolve(service, profile);
            if (actual != expectation.expected()) {
                wrong.add("row " + row + " (" + expectation.what() + "): expected "
                        + expectation.expected() + ", got " + actual);
            }
        }

        assertTrue(wrong.isEmpty(), "the resolution does not match the table:\n  "
                + String.join("\n  ", wrong));
    }

    /** Runs one row and classifies the answer, without asking the implementation what it meant. */
    private static Expected resolve(CanonicalImportServiceImpl service,
            ImportProfileDefinition profile) throws Exception {
        Method resolve = CanonicalImportServiceImpl.class.getDeclaredMethod(
                "resolveTargetFolderId", ImportProfileDefinition.class, String.class,
                CallContext.class);
        resolve.setAccessible(true);
        try {
            Object answer = resolve.invoke(service, profile, "bedroom", (CallContext) null);
            return answer == null ? Expected.NO_FOLDER : Expected.RESOLVED;
        } catch (InvocationTargetException thrown) {
            Throwable cause = thrown.getCause();
            if (cause instanceof CanonicalImportServiceImpl.TargetFolderUnreadableException typed) {
                return typed.isRetryable() ? Expected.REFUSED_RETRYABLE
                        : Expected.REFUSED_PERMANENT;
            }
            return Expected.REFUSED_PERMANENT;
        }
    }

    @Test
    @DisplayName("the table covers every outcome the resolution can produce")
    void theTableIsNotPartial() {
        // A table missing a column measures nothing about that column. Four outcomes exist;
        // the table has to exercise all four, or "both directions" is a claim about the rows
        // that happen to be in it.
        List<Expected> covered = oracle().stream().map(Row::expected).distinct().toList();

        for (Expected outcome : Expected.values()) {
            assertTrue(covered.contains(outcome),
                    "no row in the table produces " + outcome + ", so nothing measures it");
        }
    }
}
