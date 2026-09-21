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
package jp.aegif.nemaki.verifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claim this whole module exists to make: it verifies a package without the product.
 *
 * <p>A verifier that shares the writer's code verifies that the writer agrees with itself. So
 * the independence is not a style preference, it is the substance of the claim — and it is
 * measured here rather than trusted, because a classpath that happens not to contain core today
 * says nothing about the released artifact.
 */
class TheVerifierStandsAloneTest {

    private static final Path POM = Path.of("pom.xml");
    private static final Path SOURCES = Path.of("src/main/java");

    /**
     * What plan §10 forbids this module to reach for.
     *
     * <p>{@code org.bouncycastle} is deliberately NOT on this list. The JDK has no public API
     * that verifies an RFC 3161 token, so P3 without a cryptography library means hand-written
     * DER parsing and CMS signature verification — which is not defensible in a component whose
     * job is to be trusted by another organisation. The cost is stated in the pom: one more
     * library a receiving party has to trust.
     */
    private static final List<String> FORBIDDEN = List.of(
            // The whole product root. The first version named "jp.aegif.nemaki.core", a
            // package that does not exist, so the product ban matched nothing while the pom
            // check carried the rule alone (parallel review, P2). The module's own package is
            // exempted below, not here.
            "jp.aegif.nemaki.", "org.springframework", "com.ibm.cloud",
            "org.apache.chemistry", "jakarta.servlet", "tools.jackson",
            "com.fasterxml.jackson", "org.roda_project");

    @Test
    @DisplayName("the pom declares no dependency on the product")
    void thePomDeclaresNoProductDependency() throws IOException {
        assertTrue(Files.exists(POM), "the module's pom is not at " + POM);
        String pom = Files.readString(POM, StandardCharsets.UTF_8);

        // Comments stripped: this pom EXPLAINS the rule in a comment naming the very things it
        // must not depend on, and a grep a comment can satisfy has been the defect repeatedly
        // in this branch.
        String declared = pom.replaceAll("(?s)<!--.*?-->", "");
        for (String forbidden : List.of("nemakiware-core", "<artifactId>core</artifactId>",
                "spring", "cloudant", "chemistry", "commons-ip")) {
            assertTrue(!declared.toLowerCase(java.util.Locale.ROOT).contains(forbidden),
                    "the verifier declares a dependency on " + forbidden + ". A verifier that "
                            + "shares the writer's code verifies that the writer agrees with "
                            + "itself, which is not the claim this module makes");
        }
    }

    @Test
    @DisplayName("no source in this module imports the product or a framework")
    void noSourceImportsTheProduct() throws IOException {
        assertTrue(Files.isDirectory(SOURCES), "the module has no sources at " + SOURCES);
        List<String> offences = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Matcher imports = Pattern.compile("(?m)^import\\s+(static\\s+)?([^;]+);")
                        .matcher(text);
                while (imports.find()) {
                    String imported = imports.group(2).trim();
                    if (imported.startsWith("jp.aegif.nemaki.verifier")) {
                        continue; // this module importing itself is the one product-root import allowed
                    }
                    for (String forbidden : FORBIDDEN) {
                        if (imported.startsWith(forbidden)) {
                            offences.add(file + " imports " + imported);
                        }
                    }
                }
            }
        }
        assertTrue(offences.isEmpty(),
                "this module must verify a package with nothing but the JDK. Reaching for the "
                        + "product's classes — or a JSON library the product also uses — is how "
                        + "'an independent implementation agrees' quietly becomes 'the same "
                        + "code ran twice':\n  " + String.join("\n  ", offences));
    }

    @Test
    @DisplayName("the Merkle construction is the spec's, not RFC 6962's")
    void theMerkleConstructionIsTheSpecs() {
        // Two leaves, so the node rule is actually exercised. RFC 6962 hashes 0x01 followed by
        // the two RAW digests; the spec hashes 0x01 followed by the two HEX STRINGS. A third
        // party who reached for an RFC 6962 library would get a different root here, which is
        // exactly the mistake the spec calls out in bold.
        String a = Merkle.hashLeaf("a");
        String b = Merkle.hashLeaf("b");
        assertEquals(Merkle.hashNode(a, b), Merkle.root(List.of("a", "b")));

        // And an odd leaf is carried, not duplicated.
        String c = Merkle.hashLeaf("c");
        assertEquals(Merkle.hashNode(Merkle.hashNode(a, b), c), Merkle.root(List.of("a", "b", "c")));
    }

    @Test
    @DisplayName("an empty tree has no root")
    void anEmptyTreeHasNoRoot() {
        assertEquals(null, Merkle.root(List.of()),
                "the hash of the empty input would be a root that verifies against no entry at "
                        + "all, and nothing about that value says the tree was empty");
    }

    @Test
    @DisplayName("a zero-step proof holds only for a one-leaf tree")
    void aZeroStepProofOnlyHoldsForOneLeaf() {
        String only = "entry-1";
        assertTrue(Merkle.verifies(only, List.of(), Merkle.root(List.of(only))));
        assertTrue(!Merkle.verifies(only, List.of(), Merkle.root(List.of(only, "entry-2"))),
                "an empty path is a valid proof for a tree of one leaf and must not pass for a "
                        + "larger one");
    }

    @Test
    @DisplayName("key order does not change a document's digest, and key BYTES decide the order")
    void theEncodingSortsByUtf8Bytes() {
        java.util.Map<String, Object> one = new java.util.LinkedHashMap<>();
        one.put("b", 2L);
        one.put("a", "x");
        java.util.Map<String, Object> other = new java.util.LinkedHashMap<>();
        other.put("a", "x");
        other.put("b", 2L);
        assertEquals(Canonical.documentDigest(one), Canonical.documentDigest(other));

        // The pair has to DISCRIMINATE. "é" (U+00E9) against the emoji does not: it sorts
        // first under both orderings, so asserting on it measured nothing — control IR3 did
        // not fire until this was replaced.
        //
        // U+E000 is in the private-use area, ABOVE the surrogate range. In UTF-8 it is
        // 0xEE 0x80 0x80 and the emoji is 0xF0 0x9F 0x98 0x80, so U+E000 sorts FIRST. In
        // UTF-16 the emoji's leading surrogate is 0xD83D, below 0xE000, so it would sort
        // first instead. That inversion is the entire reason this comparator exists.
        assertTrue(Canonical.UTF8_ORDER.compare("\uE000", "😀") < 0,
                "by UTF-8 bytes U+E000 comes before a supplementary character; by UTF-16 code "
                        + "unit it does not, and a verifier using the wrong one digests any "
                        + "document with such a key differently — and only those documents");
        assertTrue("\uE000".compareTo("😀") > 0,
                "this test's premise: String.compareTo really does order them the other way, "
                        + "so the assertion above is measuring a difference and not a tautology");
    }

    @Test
    @DisplayName("a value the encoding has no tag for is refused, not stringified")
    void anUnencodableValueIsRefused() {
        try {
            Canonical.encode(1.5d);
            assertTrue(false, "a non-integral number has no canonical form and rounding it "
                    + "would digest a value the document does not contain");
        } catch (Canonical.NotEncodable expected) {
            assertTrue(expected.getMessage().contains("no tag"));
        }
    }
}
