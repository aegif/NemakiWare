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
package jp.aegif.nemaki.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@code <property>} in the Spring XML names a property the bean's class actually has.
 *
 * <h2>Why this file exists</h2>
 *
 * <p>A {@code <property name="dispositionRecorder">} intended for {@code retentionScheduler} was
 * added to the bean ABOVE it instead — {@code cloudDirectorySyncScheduler}, which has no such
 * setter. Both beans end with the same three lines ({@code propertyManager}, a multi-replica
 * comment, {@code leaderElection}), and the edit anchored on the first match.
 *
 * <p>That is a context that <b>does not start</b>: the bean is an eager singleton, so Spring
 * raises {@code NotWritablePropertyException} during refresh. Nothing in a 5,800-test suite
 * noticed, because nothing loads this file — {@code CaptureWiringResolvesTest} says so in its own
 * javadoc, and it is right to: instantiating these beans needs CouchDB.
 *
 * <p>So this checks the one thing that can be checked without a database — that every property
 * NAME resolves to a settable property on the declared class. It reads the XML and the class
 * metadata, instantiates nothing, and takes milliseconds.
 *
 * <h2>What it does not establish</h2>
 *
 * <p>That the context starts. Missing bean references, circular dependencies, wrong types, an
 * {@code init-method} that throws — none of that is visible here. It closes one hole: the
 * property that goes to the wrong bean, which is the one this project has now made.
 */
class XmlBeanPropertiesResolveTest {

    /** The XML files that define beans for the running server. */
    private static final List<String> CONTEXTS = List.of(
            "serviceContext.xml", "applicationContext.xml", "businesslogicContext.xml",
            "daoContext.xml", "propertyContext.xml", "patchContext.xml",
            "jacksonContext.xml", "spring-mvc-context.xml");

    private static final Path CLASSES = Path.of("src/main/webapp/WEB-INF/classes");

    @Test
    @DisplayName("every <property> names a settable property on its bean's class")
    void everyPropertyResolves() throws Exception {
        List<String> problems = new ArrayList<>();
        int checked = 0;

        for (String context : CONTEXTS) {
            Path file = CLASSES.resolve(context);
            if (!Files.exists(file)) {
                continue;
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(false);
            org.w3c.dom.Document document = factory.newDocumentBuilder().parse(file.toFile());

            NodeList beans = document.getElementsByTagName("bean");
            for (int i = 0; i < beans.getLength(); i++) {
                Element bean = (Element) beans.item(i);
                String className = bean.getAttribute("class");
                if (className.isBlank()) {
                    // A parent-derived or factory-produced bean: the class is not stated here,
                    // so there is nothing this test can check without resolving the hierarchy.
                    continue;
                }
                Class<?> type;
                try {
                    type = Class.forName(className, false,
                            XmlBeanPropertiesResolveTest.class.getClassLoader());
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    // Not on the test classpath (provided-scope, or a class this build does not
                    // package). Skipped rather than reported: a false alarm here would train
                    // somebody to ignore this test.
                    continue;
                }
                Set<String> settable = settablePropertiesOf(type);
                for (String property : directPropertyNames(bean)) {
                    checked++;
                    if (!settable.contains(property)) {
                        problems.add(context + ": bean id=" + bean.getAttribute("id")
                                + " (" + className + ") has <property name=\"" + property
                                + "\"> but that class has no setter for it. Spring raises "
                                + "NotWritablePropertyException at refresh — the server does "
                                + "not start.");
                    }
                }
            }
        }

        // A count guard: if the paths above ever stop matching, this test would pass by
        // checking nothing, which is the failure mode of every scanning test.
        assertTrue(checked > 100,
                "only " + checked + " properties were checked. This test scans "
                        + CLASSES.toAbsolutePath() + "; if the layout moved, it is now passing "
                        + "because it looked at nothing.");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /** Property names declared directly on this bean, not on a nested one. */
    private static List<String> directPropertyNames(Element bean) {
        List<String> names = new ArrayList<>();
        NodeList children = bean.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && "property".equals(child.getNodeName())) {
                String name = ((Element) child).getAttribute("name");
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * Property names this class accepts.
     *
     * <p>Derived from the methods rather than from {@code java.beans.Introspector}, because a
     * setter returning {@code this} (the builder style) is not a JavaBeans property but Spring
     * accepts it. Missing one of those would produce a false alarm, and a scanning test that
     * cries wolf is worse than none.
     */
    private static Set<String> settablePropertiesOf(Class<?> type) {
        Set<String> names = new LinkedHashSet<>();
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            Stream.of(current.getMethods(), current.getDeclaredMethods())
                    .flatMap(Stream::of)
                    .filter(method -> method.getName().startsWith("set"))
                    .filter(method -> method.getName().length() > 3)
                    .filter(method -> method.getParameterCount() == 1)
                    .forEach(method -> {
                        String tail = method.getName().substring(3);
                        names.add(Character.toLowerCase(tail.charAt(0)) + tail.substring(1));
                        // Spring also accepts an all-caps prefix as written, e.g. setURL -> URL.
                        names.add(tail);
                    });
        }
        return names;
    }
}
