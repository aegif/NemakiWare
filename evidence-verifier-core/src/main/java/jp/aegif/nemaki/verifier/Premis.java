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
 * <p><b>There is a twin.</b> {@code core}'s {@code SipVerifier} answers the same question for
 * the product's own {@code /verify}, in a module this one may not depend on. The two are held
 * to the same sentences of {@code evidence-profile-v1.md} §9 by a lock on each side; an
 * operator reaching the product's endpoint and a receiver running the CLI must not get
 * different answers about one file.
 */
final class Premis {

    /** What was found. {@code null} {@code fixities} means the document did not parse. */
    record Fixity(List<String> digests, List<String> algorithms, String unreadable) {

        boolean parsed() {
            return unreadable == null;
        }
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
            // processing caps expansion (measured: a billion-laughs document expands to
            // nothing in 19 ms). Leaving expansion off while allowing a DOCTYPE made a digest
            // written as an internal entity read as "PREMIS records no message digest" —
            // "read and absent" for something that was not read (subagent, seventh review, P3).
            factory.setExpandEntityReferences(true);
            document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception notXml) {
            return new Fixity(List.of(), List.of(), String.valueOf(notXml.getMessage()));
        }
        return new Fixity(textsOf(document, "messageDigest"),
                textsOf(document, "messageDigestAlgorithm"), null);
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
        if (element == null) {
            return;
        }
        String name = element.getLocalName() == null ? element.getNodeName()
                : element.getLocalName();
        String namespace = element.getNamespaceURI();
        if (localName.equals(name) && (namespace == null || NAMESPACES.contains(namespace))) {
            found.add(element.getTextContent());
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                collect(child, localName, found);
            }
        }
    }
}
