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

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The fixity a PREMIS document records, read as XML.
 *
 * <h2>Why not string matching</h2>
 *
 * <p>The first version counted the literal {@code "<premis:messageDigest>"}. A prefix is not
 * part of an XML name: a document can bind the same PREMIS namespace to {@code p} and write
 * {@code <p:messageDigest>}, and the count stayed at one while the document carried two —
 * matching first and contradicting second, reported as {@code PASSED} (Codex, fifth review).
 * The mirror error is as bad: the same literal inside a COMMENT would have been counted as a
 * second digest, refusing a package over text that is not markup.
 *
 * <p>So the document is parsed. External entities and DTDs are refused — this runs over
 * packages from another organisation, and a verifier that fetched a URL named inside one would
 * be doing what it exists to avoid.
 *
 * <p><b>There is a twin.</b> {@code core}'s {@code SipVerifier} answers the same question
 * inside the product (this version exposes no endpoint for it — its callers are tests), in a
 * module this one may not depend on. The two are held
 * to the same sentences of {@code evidence-profile-v1.md} §9 by a lock on each side; an
 * operator reaching the product's endpoint and a receiver running the CLI must not get
 * different answers about one file.
 */
final class Premis {

    /**
     * What was found.
     *
     * @param unreadable non-null when the document did not parse
     * @param contradiction the algorithm one {@code premis:object} records TWO DIFFERENT
     *        digests under, or null. That is PREMIS contradicting ITSELF about one file, and
     *        seeing it needs no object-to-file linkage — unlike two digests spread across two
     *        objects (an ordinary CSIP package) or two digests under two DIFFERENT algorithms
     *        (what {@code premis:fixity} is repeatable FOR)
     */
    record Fixity(List<String> digests, List<String> algorithms, String unreadable,
            String contradiction) {

        boolean parsed() {
            return unreadable == null;
        }

        /** Not read because it nests past {@link #MAX_ELEMENT_DEPTH} — a limit, not a defect. */
        boolean tooDeep() {
            return unreadable != null && isDepthRefusal(unreadable);
        }
    }

    /**
     * How deep a PREMIS or METS may nest before this verifier declines to read it.
     *
     * <p>Stated here so the answer is the same on every JDK: a JDK 24+ parser refuses anything
     * deeper than 100 levels by default, JDK 21 refuses nothing — and the recursive walks of
     * this class died with a StackOverflowError on the latter (9-6 review, P3). The walks are
     * iterative now; the bound that remains is this one, and a document past it is
     * {@code RESOURCE_LIMIT} ("too deep for this verifier"), never "not XML".
     */
    static final int MAX_ELEMENT_DEPTH = 50_000;

    /** Applies {@link #MAX_ELEMENT_DEPTH} to a factory; a parser without the property keeps none. */
    static void limitDepth(DocumentBuilderFactory factory) {
        try {
            factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth",
                    MAX_ELEMENT_DEPTH);
        } catch (IllegalArgumentException notThisParser) {
            // Not the JDK's parser. The walks below do not recurse, so a deeper document costs
            // memory, not a crash; the bound just is not enforced by the parser here.
        }
    }

    /** Whether a parser's refusal was the depth limit (JAXP00010006 names the property). */
    static boolean isDepthRefusal(String message) {
        return message != null && message.contains("maxElementDepth");
    }

    /**
     * The PREMIS namespaces this reader recognises.
     *
     * <p>v2 as well as v3: a document in {@code info:lc/xmlns/premis-v2} records its digest in
     * elements of the same local names, and reading only v3 turned every such package into
     * {@code NOT_PRESENT} — which the string matching it replaced did NOT do (subagent, sixth
     * review, P3). Fail-closed is still a refusal.
     */
    private static final java.util.Set<String> NAMESPACES = java.util.Set.of(
            "http://www.loc.gov/premis/v3", "info:lc/xmlns/premis-v2");

    private Premis() {
    }

    static Fixity read(byte[] xml) {
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // No EXTERNAL entity resolution: a package is untrusted input, and the one thing a
            // verifier must never do is fetch something a package names. An internal DOCTYPE is
            // allowed, because the profile does not forbid one and refusing it turned a
            // legitimate third-party PREMIS into PREMIS_NOT_PARSED (Codex, sixth review, P2).
            // Secure processing above caps entity expansion, so an internal subset cannot be
            // used to exhaust this process either.
            factory.setFeature(
                    "http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            // INTERNAL entities are expanded. External resolution is off above, and secure
            // processing REFUSES a document whose expansion runs away — measured on a
            // billion-laughs document: rejected as JAXP00010001 in about 74 ms, answered as
            // "could not read", never as "read and empty". Leaving expansion off while allowing
            // a DOCTYPE made a digest written as an internal entity read as "records no message
            // digest" — "read and absent" for something that was not read (subagent, seventh
            // and eighth reviews, P3).
            factory.setExpandEntityReferences(true);
            limitDepth(factory);
            document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception notXml) {
            return new Fixity(List.of(), List.of(), String.valueOf(notXml.getMessage()), null);
        }
        return new Fixity(textsOf(document, "messageDigest"),
                textsOf(document, "messageDigestAlgorithm"), null,
                contradictionIn(document.getDocumentElement()));
    }

    /**
     * The algorithm one {@code premis:object} records two DIFFERENT digests under, or null.
     *
     * <p>§9 asks for this by name: one object describing one file twice is visible without
     * reading WHICH file it describes. The sentence it used to quote verbatim is no longer in
     * the spec — the correction rewrote it — so it is paraphrased here, and §9 is the canon.
     *
     * <p><b>Counting the digests was wrong.</b> {@code objectCharacteristics/fixity} is
     * REPEATABLE in PREMIS, and recording the same bytes under MD5 and under SHA-256 is what
     * that repetition is for. Counting made a conformant package with two correct digests read
     * as "the PREMIS contradicts itself", exit 2 — and the check thirty lines below, on the same
     * document, calls two algorithms an AMBIGUITY. One document, two answers from one profile
     * (subagent, tenth review, P1, measured). So the digests are grouped by the algorithm that
     * produced them, and only a disagreement WITHIN one algorithm is a contradiction.
     *
     * <p>Grouped per {@code fixity} element when there is one, because that is where an
     * algorithm and its digest belong together; an object with no {@code fixity} wrapper is
     * treated as a single group, so a digest is never silently left ungrouped.
     */
    private static String contradictionIn(Element root) {
        for (Element object : elementsNamed(root, "object")) {
            List<Element> groups = elementsNamed(object, "fixity");
            if (groups.isEmpty()) {
                groups = List.of(object);
            }
            java.util.Map<String, java.util.Set<String>> byAlgorithm =
                    new java.util.LinkedHashMap<>();
            for (Element group : groups) {
                List<String> algorithms = new ArrayList<>();
                collect(group, "messageDigestAlgorithm", algorithms);
                List<String> digests = new ArrayList<>();
                collect(group, "messageDigest", digests);
                String algorithm = algorithms.isEmpty() ? ""
                        : algorithms.get(0).trim().toUpperCase(java.util.Locale.ROOT);
                for (String digest : digests) {
                    byAlgorithm.computeIfAbsent(algorithm, any -> new java.util.LinkedHashSet<>())
                            .add(digest.trim().toLowerCase(java.util.Locale.ROOT));
                }
            }
            for (java.util.Map.Entry<String, java.util.Set<String>> entry
                    : byAlgorithm.entrySet()) {
                if (entry.getValue().size() > 1) {
                    return entry.getKey().isEmpty() ? "an unstated algorithm" : entry.getKey();
                }
            }
        }
        return null;
    }

    /** Every descendant element with this local name, in the PREMIS namespace. */
    private static List<Element> elementsNamed(Element element, String localName) {
        List<Element> found = new ArrayList<>();
        // Walked with a stack, not by recursion: a package is untrusted input, and a document
        // nested deeper than the thread's stack made the recursive walk die with a
        // StackOverflowError — exit 1, a number the CLI does not document (9-6 review, P3).
        // Children are pushed last-to-first so the order found is document order.
        java.util.ArrayDeque<Element> pending = new java.util.ArrayDeque<>();
        if (element != null) {
            pending.push(element);
        }
        while (!pending.isEmpty()) {
            Element current = pending.pop();
            String name = current.getLocalName() == null ? current.getNodeName()
                    : current.getLocalName();
            String namespace = current.getNamespaceURI();
            if (localName.equals(name) && (namespace == null || NAMESPACES.contains(namespace))) {
                found.add(current);
            }
            NodeList children = current.getChildNodes();
            for (int i = children.getLength() - 1; i >= 0; i--) {
                if (children.item(i) instanceof Element child) {
                    pending.push(child);
                }
            }
        }
        return found;
    }

    /**
     * The text of every element with this local name, in document order.
     *
     * <p>Matched on LOCAL NAME within the PREMIS namespace, and also on local name alone when
     * the document uses no namespace: real packages declare it, and a fixture or a producer
     * that does not is still saying {@code messageDigest}. Prefix is never consulted — that is
     * the evasion this class exists to close.
     */
    private static List<String> textsOf(Document document, String localName) {
        List<String> found = new ArrayList<>();
        collect(document.getDocumentElement(), localName, found);
        return found;
    }

    private static void collect(Element element, String localName, List<String> found) {
        // A stack, not recursion — see elementsNamed.
        for (Element match : elementsNamed(element, localName)) {
            found.add(match.getTextContent());
        }
    }
}
