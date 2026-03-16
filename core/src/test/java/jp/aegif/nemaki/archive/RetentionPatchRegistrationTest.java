package jp.aegif.nemaki.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to verify retention-related patch registration consistency.
 *
 * Ensures:
 * - All retention patches are registered in patchContext.xml (cmisPatchList)
 * - All retention patches are registered in NemakiPatchInitializationListener (fallback list)
 * - Both registrations are consistent with each other
 * - Patch classes actually exist
 */
public class RetentionPatchRegistrationTest {

    /** All retention patch class names that must be registered */
    private static final List<String> RETENTION_PATCH_CLASSES = Arrays.asList(
            "Patch_RetentionMigrationLogViews",
            "Patch_RetentionSecondaryTypes",
            "Patch_RetentionExpirationView",
            "Patch_RetentionLastModificationView"
    );

    /** Corresponding bean IDs in NemakiPatchInitializationListener */
    private static final List<String> RETENTION_PATCH_BEAN_IDS = Arrays.asList(
            "patch_RetentionMigrationLogViews",
            "patch_RetentionSecondaryTypes",
            "patch_RetentionExpirationView",
            "patch_RetentionLastModificationView"
    );

    @Test
    @DisplayName("全リテンションパッチクラスが実在する")
    public void testRetentionPatchClassesExist() {
        String packagePrefix = "jp.aegif.nemaki.patch.";

        for (String className : RETENTION_PATCH_CLASSES) {
            try {
                Class.forName(packagePrefix + className);
            } catch (ClassNotFoundException e) {
                fail("Retention patch class not found: " + packagePrefix + className);
            }
        }
    }

    @Test
    @DisplayName("全リテンションパッチが AbstractNemakiPatch を継承している")
    public void testRetentionPatchClassesExtendAbstractNemakiPatch() throws Exception {
        String packagePrefix = "jp.aegif.nemaki.patch.";
        Class<?> abstractPatchClass = Class.forName("jp.aegif.nemaki.patch.AbstractNemakiPatch");

        for (String className : RETENTION_PATCH_CLASSES) {
            Class<?> clazz = Class.forName(packagePrefix + className);
            assertTrue(abstractPatchClass.isAssignableFrom(clazz),
                    className + " should extend AbstractNemakiPatch");
        }
    }

    @Test
    @DisplayName("全リテンションパッチが patchContext.xml の cmisPatchList に登録されている")
    public void testRetentionPatchesRegisteredInPatchContext() throws Exception {
        String patchContextContent = readResourceFile(
                "core/src/main/webapp/WEB-INF/classes/patchContext.xml");

        for (String className : RETENTION_PATCH_CLASSES) {
            String fullClass = "jp.aegif.nemaki.patch." + className;
            assertTrue(patchContextContent.contains(fullClass),
                    className + " should be registered in patchContext.xml cmisPatchList");
        }
    }

    @Test
    @DisplayName("全リテンションパッチが NemakiPatchInitializationListener のフォールバックリストに登録されている")
    public void testRetentionPatchesRegisteredInFallbackListener() throws Exception {
        String listenerContent = readResourceFile(
                "core/src/main/java/jp/aegif/nemaki/init/NemakiPatchInitializationListener.java");

        for (String beanId : RETENTION_PATCH_BEAN_IDS) {
            assertTrue(listenerContent.contains("\"" + beanId + "\""),
                    beanId + " should be registered in NemakiPatchInitializationListener fallback list");
        }
    }

    @Test
    @DisplayName("patchContext.xml の登録順序: RetentionSecondaryTypes → ExpirationView → LastModificationView")
    public void testRetentionPatchOrderInContext() throws Exception {
        String content = readResourceFile(
                "core/src/main/webapp/WEB-INF/classes/patchContext.xml");

        int secondaryTypesPos = content.indexOf("Patch_RetentionSecondaryTypes");
        int expirationViewPos = content.indexOf("Patch_RetentionExpirationView");
        int lastModViewPos = content.indexOf("Patch_RetentionLastModificationView");

        assertTrue(secondaryTypesPos > 0, "RetentionSecondaryTypes should be in patchContext.xml");
        assertTrue(expirationViewPos > 0, "RetentionExpirationView should be in patchContext.xml");
        assertTrue(lastModViewPos > 0, "RetentionLastModificationView should be in patchContext.xml");

        // SecondaryTypes must come before views (views depend on type definitions)
        assertTrue(secondaryTypesPos < expirationViewPos,
                "RetentionSecondaryTypes must be registered before RetentionExpirationView");
        assertTrue(secondaryTypesPos < lastModViewPos,
                "RetentionSecondaryTypes must be registered before RetentionLastModificationView");
    }

    @Test
    @DisplayName("NemakiPatchInitializationListener の登録順序: SecondaryTypes → ExpirationView → LastModificationView")
    public void testRetentionPatchOrderInListener() throws Exception {
        String content = readResourceFile(
                "core/src/main/java/jp/aegif/nemaki/init/NemakiPatchInitializationListener.java");

        int secondaryTypesPos = content.indexOf("patch_RetentionSecondaryTypes");
        int expirationViewPos = content.indexOf("patch_RetentionExpirationView");
        int lastModViewPos = content.indexOf("patch_RetentionLastModificationView");

        assertTrue(secondaryTypesPos > 0, "patch_RetentionSecondaryTypes should be in listener");
        assertTrue(expirationViewPos > 0, "patch_RetentionExpirationView should be in listener");
        assertTrue(lastModViewPos > 0, "patch_RetentionLastModificationView should be in listener");

        assertTrue(secondaryTypesPos < expirationViewPos,
                "patch_RetentionSecondaryTypes must come before patch_RetentionExpirationView");
        assertTrue(secondaryTypesPos < lastModViewPos,
                "patch_RetentionSecondaryTypes must come before patch_RetentionLastModificationView");
    }

    @Test
    @DisplayName("全リテンションパッチが patchContext.xml で独立 bean 定義を持つ")
    public void testRetentionPatchesHaveIndividualBeanDefinitions() throws Exception {
        String content = readResourceFile(
                "core/src/main/webapp/WEB-INF/classes/patchContext.xml");

        for (String beanId : RETENTION_PATCH_BEAN_IDS) {
            String beanDef = "id=\"" + beanId + "\"";
            assertTrue(content.contains(beanDef),
                    beanId + " should have an individual bean definition in patchContext.xml");
        }
    }

    @Test
    @DisplayName("nemakiware.properties にリテンション設定キーが存在する")
    public void testRetentionPropertiesExist() throws Exception {
        String propsContent = readResourceFile(
                "core/src/main/webapp/WEB-INF/classes/nemakiware.properties");

        String[] requiredKeys = {
                "retention.enabled",
                "retention.archive.local.after.days",
                "retention.archive.cold.after.days",
                "retention.schedule.archive.local",
                "retention.schedule.archive.cold",
                "retention.cold.keep.local.copy"
        };

        for (String key : requiredKeys) {
            assertTrue(propsContent.contains(key),
                    "nemakiware.properties should contain key: " + key);
        }
    }

    @Test
    @DisplayName("PropertyKey にリテンション関連の定数が定義されている")
    public void testPropertyKeyConstants() throws Exception {
        Class<?> pkClass = Class.forName("jp.aegif.nemaki.util.constant.PropertyKey");

        String[] expectedFields = {
                "RETENTION_ENABLED",
                "RETENTION_ARCHIVE_LOCAL_AFTER_DAYS",
                "RETENTION_ARCHIVE_COLD_AFTER_DAYS",
                "RETENTION_SCHEDULE_ARCHIVE_LOCAL",
                "RETENTION_SCHEDULE_ARCHIVE_COLD",
                "RETENTION_COLD_KEEP_LOCAL_COPY"
        };

        for (String fieldName : expectedFields) {
            try {
                Field field = pkClass.getDeclaredField(fieldName);
                assertNotNull(field, "PropertyKey." + fieldName + " should exist");
                // Verify it's a String constant
                Object value = field.get(null);
                assertNotNull(value, "PropertyKey." + fieldName + " should have a non-null value");
                assertTrue(value instanceof String, "PropertyKey." + fieldName + " should be a String");
                String strValue = (String) value;
                assertTrue(strValue.startsWith("retention."),
                        "PropertyKey." + fieldName + " value should start with 'retention.' but was: " + strValue);
            } catch (NoSuchFieldException e) {
                fail("PropertyKey constant not found: " + fieldName);
            }
        }
    }

    /**
     * Read a source file relative to project root.
     * Tries to resolve from working directory or known project paths.
     */
    private String readResourceFile(String relativePath) throws IOException {
        // Try direct path from working directory
        Path path = Paths.get(relativePath);
        if (!Files.exists(path)) {
            // Try from parent directories
            Path current = Paths.get(System.getProperty("user.dir"));
            while (current != null) {
                path = current.resolve(relativePath);
                if (Files.exists(path)) {
                    break;
                }
                current = current.getParent();
            }
        }

        if (!Files.exists(path)) {
            fail("File not found: " + relativePath + " (searched from " + System.getProperty("user.dir") + ")");
        }

        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
