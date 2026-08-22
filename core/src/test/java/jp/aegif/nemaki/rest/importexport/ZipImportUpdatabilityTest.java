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
package jp.aegif.nemaki.rest.importexport;

import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.model.NemakiPropertyDefinition;
import jp.aegif.nemaki.model.NemakiPropertyDefinitionDetail;
import jp.aegif.nemaki.model.NemakiTypeDefinition;
import jp.aegif.nemaki.rest.importexport.ImportExportUtils.ImportResult;
import jp.aegif.nemaki.util.spring.SpringContext;

import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An archive that does not say whether a property may be edited is refused, not guessed at.
 *
 * <h2>What this is and is not about</h2>
 *
 * <p>It is NOT about unprotecting an existing type: {@code importTypeDefinitions} skips a type
 * that already exists, whole, so an import cannot reach a property a protection migration has
 * already made read-only. The design said otherwise and was wrong (external review).
 *
 * <p>What is left is what gets CREATED on a repository that lacks the type. Both defaults are
 * dishonest there. Read-write lets an archive that merely predates the field declare a property
 * editable; read-only — the shape this briefly had — locks every ordinary custom property in an
 * old export and silently swallows a typo such as {@code "read-write"} into the most restrictive
 * setting. CMIS requires the field, so the import says the archive is malformed.
 *
 * <p>The test drives {@code importTypeDefinitions} itself, over a real zip. That method reaches
 * its TypeService through a static Spring lookup that answers null outside a container — so a
 * test that forgot to seed the context would exercise nothing at all and still pass. The context
 * is seeded below, and {@code typesCreated} in the warnings is what tells the two apart.
 */
class ZipImportUpdatabilityTest {

    private static final String REPO = "bedroom";

    private TypeService typeService;
    private List<NemakiPropertyDefinition> createdProperties;
    private List<NemakiTypeDefinition> createdTypes;
    private ApplicationContext previousContext;
    private File zipFile;

    @BeforeEach
    void seedSpringContext() {
        typeService = mock(TypeService.class);
        createdProperties = new ArrayList<>();
        createdTypes = new ArrayList<>();

        // No type exists yet: this is the create path, the only one an import can reach.
        when(typeService.getTypeDefinition(anyString(), anyString())).thenReturn(null);
        when(typeService.createPropertyDefinition(anyString(), any())).thenAnswer(inv -> {
            NemakiPropertyDefinition npd = inv.getArgument(1);
            createdProperties.add(npd);
            NemakiPropertyDefinitionDetail detail = new NemakiPropertyDefinitionDetail();
            detail.setId("detail-" + npd.getPropertyId());
            detail.setUpdatability(npd.getUpdatability());
            return detail;
        });
        when(typeService.createTypeDefinition(anyString(), any())).thenAnswer(inv -> {
            createdTypes.add(inv.getArgument(1));
            return inv.getArgument(1);
        });

        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBean("TypeService", TypeService.class)).thenReturn(typeService);
        when(context.getBean("typeService", TypeService.class)).thenReturn(typeService);

        previousContext = SpringContext.getApplicationContext();
        new SpringContext().setApplicationContext(context);
    }

    @AfterEach
    void restoreSpringContext() {
        new SpringContext().setApplicationContext(previousContext);
        if (zipFile != null) {
            zipFile.delete();
        }
    }

    /**
     * Two properties, and the offending one is SECOND on purpose.
     *
     * <p>With a single property the orphan assertion cannot fail: deleting the pre-pass would
     * still throw at `Updatability.fromValue(null)` before the one and only
     * `createPropertyDefinition`, so nothing is created either way and the test would be
     * measuring nothing (external review). With a good property first, the pre-pass is the only
     * thing standing between the archive and a half-created type.
     */
    private static String typeJson(String updatabilityLine) {
        return "{"
                + "\"id\": \"nemaki:legacyType\","
                + "\"localName\": \"legacyType\","
                + "\"queryName\": \"nemaki:legacyType\","
                + "\"baseId\": \"cmis:document\","
                + "\"parentId\": \"cmis:document\","
                + "\"propertyDefinitions\": [{"
                + "  \"id\": \"nemaki:goodField\","
                + "  \"propertyType\": \"string\","
                + "  \"cardinality\": \"single\","
                + "  \"updatability\": \"readwrite\""
                + "}, {"
                + "  \"id\": \"nemaki:someField\","
                + "  \"propertyType\": \"string\","
                + "  \"cardinality\": \"single\""
                + updatabilityLine
                + "}]}";
    }

    /** Runs the real import over a real zip holding one type definition entry. */
    private ImportResult runImport(String json) throws Exception {
        zipFile = File.createTempFile("nemaki-type-import", ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry(
                    ImportExportUtils.TYPE_DEFINITIONS_DIR + "legacy"
                            + ImportExportUtils.TYPE_DEFINITION_SUFFIX));
            zos.write(json.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        ImportResult result = new ImportResult();
        Method method = ZipImporter.class.getDeclaredMethod(
                "importTypeDefinitions", String.class, ZipFile.class, ImportResult.class);
        method.setAccessible(true);
        try (ZipFile zf = new ZipFile(zipFile)) {
            method.invoke(new ZipImporter(), REPO, zf, result);
        }
        return result;
    }

    @Test
    @DisplayName("a property with no updatability stops the type from being created")
    void absentUpdatabilityIsRefused() throws Exception {
        ImportResult result = runImport(typeJson(""));

        assertEquals(List.of(), createdTypes.stream().map(NemakiTypeDefinition::getTypeId).toList(),
                "a type was created from an archive that never said whether its property is editable");
        assertEquals(0, createdProperties.size(),
                "the good property was created before the bad one was rejected, so a refused type "
                        + "left orphan property definitions behind: " + createdProperties.size());
        assertTrue(result.warnings.stream().anyMatch(w -> w.contains("nemaki:someField")),
                "the refusal did not name the property at fault: " + result.warnings);
    }

    @Test
    @DisplayName("a misspelt updatability is refused rather than resolved to a default")
    void misspeltUpdatabilityIsRefused() throws Exception {
        ImportResult result = runImport(typeJson(", \"updatability\": \"read-write\""));

        assertEquals(0, createdTypes.size(), "a misspelt updatability was accepted");
        assertTrue(result.warnings.stream().anyMatch(w -> w.contains("read-write")),
                "the refusal did not quote the bad value: " + result.warnings);
    }

    @Test
    @DisplayName("a well-formed archive still imports, with the value it states")
    void aWellFormedArchiveStillImports() throws Exception {
        // The guard must not turn into "type import no longer works".
        runImport(typeJson(", \"updatability\": \"readwrite\""));

        assertEquals(1, createdTypes.size(),
                "a valid archive was refused, so type import is broken");
        assertEquals(2, createdProperties.size());
        assertEquals(Updatability.READWRITE, createdProperties.get(1).getUpdatability());
    }

    @Test
    @DisplayName("whencheckedout survives the round trip")
    void whenCheckedOutIsAccepted() throws Exception {
        runImport(typeJson(", \"updatability\": \"whencheckedout\""));

        assertEquals(1, createdTypes.size());
        assertEquals(Updatability.WHENCHECKEDOUT, createdProperties.get(1).getUpdatability());
    }
}
