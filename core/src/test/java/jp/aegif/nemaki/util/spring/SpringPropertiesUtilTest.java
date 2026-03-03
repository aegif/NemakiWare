package jp.aegif.nemaki.util.spring;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for {@link SpringPropertiesUtil} after its migration from
 * the deprecated {@code PropertyPlaceholderConfigurer} to
 * {@code PropertySourcesPlaceholderConfigurer} (Spring 7 compatibility).
 *
 * <p>Verifies that the property resolution pipeline — mergeProperties →
 * postProcessBeanFactory → propertiesMap — works correctly, including:</p>
 * <ul>
 *   <li>Basic property resolution via getValue / getValues / getHeadValue / getKeys</li>
 *   <li>Multiple properties files with override ordering (last-wins)</li>
 *   <li>Property source tracking (which file a property came from)</li>
 *   <li>ignoreResourceNotFound behaviour for missing files</li>
 *   <li>Null / missing key handling</li>
 * </ul>
 */
class SpringPropertiesUtilTest {

    private SpringPropertiesUtil sut;

    /**
     * Helper: create a Resource backed by a properties-formatted string.
     */
    private static Resource propsResource(String filename, String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }

            @Override
            public String getDescription() {
                return "test resource [" + filename + "]";
            }
        };
    }

    /**
     * Build and initialise the {@link SpringPropertiesUtil} against the given resources.
     */
    private SpringPropertiesUtil buildUtil(Resource... resources) {
        return buildUtil(false, resources);
    }

    /**
     * Build with optional StandardEnvironment (enables system/env property override).
     */
    private SpringPropertiesUtil buildUtil(boolean withEnvironment, Resource... resources) {
        SpringPropertiesUtil util = new SpringPropertiesUtil();
        util.setLocations(resources);
        util.setIgnoreResourceNotFound(true);
        util.setIgnoreUnresolvablePlaceholders(false);
        if (withEnvironment) {
            util.setEnvironment(new StandardEnvironment());
        }
        // Trigger the full resolution pipeline
        util.postProcessBeanFactory(new DefaultListableBeanFactory());
        return util;
    }

    // ===================== Basic property resolution =====================

    @Test
    void getValue_returnsPropertyValue() {
        sut = buildUtil(propsResource("a.properties", "foo=bar\nbaz=qux"));
        assertEquals("bar", sut.getValue("foo"));
        assertEquals("qux", sut.getValue("baz"));
    }

    @Test
    void getValue_returnsNullForMissingKey() {
        sut = buildUtil(propsResource("a.properties", "foo=bar"));
        assertNull(sut.getValue("nonexistent"));
    }

    @Test
    void getKeys_returnsAllPropertyKeys() {
        sut = buildUtil(propsResource("a.properties", "a=1\nb=2\nc=3"));
        Set<String> keys = sut.getKeys();
        assertEquals(3, keys.size());
        assertTrue(keys.contains("a"));
        assertTrue(keys.contains("b"));
        assertTrue(keys.contains("c"));
    }

    // ===================== Comma-separated values =====================

    @Test
    void getValues_splitsCommaSeparatedValues() {
        sut = buildUtil(propsResource("a.properties", "list=alpha, beta , gamma"));
        List<String> values = sut.getValues("list");
        assertEquals(3, values.size());
        assertEquals("alpha", values.get(0));
        assertEquals("beta", values.get(1));
        assertEquals("gamma", values.get(2));
    }

    @Test
    void getHeadValue_returnsFirstCommaSeparatedValue() {
        sut = buildUtil(propsResource("a.properties", "list=first,second,third"));
        assertEquals("first", sut.getHeadValue("list"));
    }

    @Test
    void getValues_returnsNullForMissingKey() {
        sut = buildUtil(propsResource("a.properties", "foo=bar"));
        assertNull(sut.getValues("nonexistent"));
    }

    // ===================== Override ordering (last-wins) =====================

    @Test
    void laterFileOverridesEarlierFile() {
        Resource first = propsResource("base.properties", "key=original\nonly_in_first=yes");
        Resource second = propsResource("override.properties", "key=overridden");
        sut = buildUtil(first, second);

        assertEquals("overridden", sut.getValue("key"));
        assertEquals("yes", sut.getValue("only_in_first"));
    }

    @Test
    void threeFilesOverrideInOrder() {
        Resource r1 = propsResource("a.properties", "x=1\ny=1\nz=1");
        Resource r2 = propsResource("b.properties", "y=2\nz=2");
        Resource r3 = propsResource("c.properties", "z=3");
        sut = buildUtil(r1, r2, r3);

        assertEquals("1", sut.getValue("x"));
        assertEquals("2", sut.getValue("y"));
        assertEquals("3", sut.getValue("z"));
    }

    // ===================== Property source tracking =====================

    @Test
    void getPropertySource_tracksWhichFileDefinedProperty() {
        Resource first = propsResource("base.properties", "a=1\nb=2");
        Resource second = propsResource("override.properties", "b=3\nc=4");
        sut = buildUtil(first, second);

        assertEquals("base.properties", sut.getPropertySource("a"));
        // "b" is defined in both; last file wins for source tracking
        assertEquals("override.properties", sut.getPropertySource("b"));
        assertEquals("override.properties", sut.getPropertySource("c"));
    }

    @Test
    void getPropertySource_returnsNullForUnknownKey() {
        sut = buildUtil(propsResource("a.properties", "foo=bar"));
        assertNull(sut.getPropertySource("nonexistent"));
    }

    @Test
    void getPropertySourceMap_isUnmodifiable() {
        sut = buildUtil(propsResource("a.properties", "foo=bar"));
        assertThrows(UnsupportedOperationException.class, () ->
                sut.getPropertySourceMap().put("hack", "value"));
    }

    // ===================== Missing resource handling =====================

    @Test
    void missingResourceIsIgnoredWhenIgnoreResourceNotFoundIsTrue() {
        Resource existing = propsResource("exists.properties", "key=value");
        // A resource whose getInputStream() will throw because exists() returns false
        Resource missing = new ByteArrayResource(new byte[0]) {
            @Override
            public boolean exists() {
                return false;
            }

            @Override
            public String getFilename() {
                return "missing.properties";
            }
        };
        sut = buildUtil(existing, missing);
        assertEquals("value", sut.getValue("key"));
    }

    // ===================== Placeholder behaviour =====================

    /**
     * PropertySourcesPlaceholderConfigurer resolves placeholders in Spring Bean
     * definitions, NOT in the raw property values captured in propertiesMap.
     * This is a key behavioural difference from the old PropertyPlaceholderConfigurer
     * and is the correct post-migration behaviour.
     *
     * <p>The NemakiWare codebase accesses properties via getValue() at runtime,
     * so it is important that placeholders in property files do NOT use ${...}
     * referencing other local properties (none of the existing .properties files
     * actually do this).</p>
     */
    @Test
    void propertiesMap_containsRawValuesWithoutPlaceholderResolution() {
        SpringPropertiesUtil util = new SpringPropertiesUtil();
        util.setLocations(propsResource("a.properties", "base=/nemaki\nfull=${base}/data"));
        util.setIgnoreResourceNotFound(true);
        util.setIgnoreUnresolvablePlaceholders(true);
        util.postProcessBeanFactory(new DefaultListableBeanFactory());
        // Raw value is captured — placeholder is NOT resolved in propertiesMap
        assertEquals("${base}/data", util.getValue("full"));
        assertEquals("/nemaki", util.getValue("base"));
    }

    // ===================== System property override (priority) =====================

    /**
     * PropertySourcesPlaceholderConfigurer applies property sources in priority
     * order: environment (system properties + env vars) first, then local files.
     * This means System.setProperty() overrides values from .properties files.
     */
    /**
     * With StandardEnvironment enabled (as in production ApplicationContext),
     * system properties override local file properties. The applied property
     * sources order is: environment (system props + env vars) first, then local.
     */
    @Test
    void systemPropertyOverridesLocalFileProperty() {
        String key = "nemaki.test.sysprop.override." + System.nanoTime();
        try {
            System.setProperty(key, "from-system");
            sut = buildUtil(true, propsResource("local.properties", key + "=from-file"));
            // System property should win over local file
            assertEquals("from-system", sut.getValue(key));
        } finally {
            System.clearProperty(key);
        }
    }

    /**
     * getPropertySource() still tracks the file name even when the resolved value
     * comes from a system property override. This is correct: source tracking
     * records which file *defines* the key, not where the final value comes from.
     */
    @Test
    void getPropertySource_tracksFileEvenWhenSystemPropertyOverrides() {
        String key = "nemaki.test.source.track." + System.nanoTime();
        try {
            System.setProperty(key, "system-value");
            sut = buildUtil(true, propsResource("tracked.properties", key + "=file-value"));
            // Source is the file that defined the key
            assertEquals("tracked.properties", sut.getPropertySource(key));
            // But the resolved value comes from the system property
            assertEquals("system-value", sut.getValue(key));
        } finally {
            System.clearProperty(key);
        }
    }

    /**
     * When only a local file defines a key (no system property), the local value
     * is used and source tracking points to the correct file.
     */
    @Test
    void localPropertyUsedWhenNoSystemPropertyOverride() {
        String key = "nemaki.test.local.only." + System.nanoTime();
        // Ensure no system property with this key
        assertNull(System.getProperty(key));
        sut = buildUtil(true, propsResource("local.properties", key + "=local-value"));
        assertEquals("local-value", sut.getValue(key));
        assertEquals("local.properties", sut.getPropertySource(key));
    }

    /**
     * Without Environment (e.g. bare-bones postProcessBeanFactory call),
     * only local file properties are available — system properties have no effect.
     * This documents the difference between unit-test and production contexts.
     */
    @Test
    void withoutEnvironment_systemPropertyDoesNotOverride() {
        String key = "nemaki.test.noenv." + System.nanoTime();
        try {
            System.setProperty(key, "from-system");
            sut = buildUtil(false, propsResource("local.properties", key + "=from-file"));
            // Without Environment, only local source is available
            assertEquals("from-file", sut.getValue(key));
        } finally {
            System.clearProperty(key);
        }
    }

    // ===================== Edge cases =====================

    @Test
    void emptyPropertiesFileProducesEmptyMap() {
        sut = buildUtil(propsResource("empty.properties", ""));
        assertTrue(sut.getKeys().isEmpty());
    }

    @Test
    void propertyValueWithEqualsSign() {
        sut = buildUtil(propsResource("a.properties", "url=jdbc:mysql://host:3306/db?user=root&pass=secret"));
        assertEquals("jdbc:mysql://host:3306/db?user=root&pass=secret", sut.getValue("url"));
    }

    @Test
    void propertyValueWithTrailingWhitespace() {
        // java.util.Properties.load() does NOT trim trailing whitespace on values.
        // This documents actual behaviour — callers should ensure .properties files
        // have no trailing whitespace.
        sut = buildUtil(propsResource("a.properties", "trimmed=value   "));
        // Trailing spaces are preserved in the raw value
        assertTrue(sut.getValue("trimmed").startsWith("value"));
    }

    @Test
    void singleValueList_getValues_returnsSingleElementList() {
        sut = buildUtil(propsResource("a.properties", "single=onlyone"));
        List<String> values = sut.getValues("single");
        assertEquals(1, values.size());
        assertEquals("onlyone", values.get(0));
    }
}
