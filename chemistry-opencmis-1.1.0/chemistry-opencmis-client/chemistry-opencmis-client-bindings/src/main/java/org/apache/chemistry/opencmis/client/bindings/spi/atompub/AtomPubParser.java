/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.chemistry.opencmis.client.bindings.spi.atompub;

import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.CONTENT_SRC;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.LINK_HREF;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.LINK_REL;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.LINK_TYPE;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_ACL;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_ALLOWABLEACTIONS;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_CHILDREN;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_COLLECTION;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_COLLECTION_TYPE;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_CONTENT;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_ENTRY;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_FEED;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_HTML;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_LINK;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_NUM_ITEMS;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_OBJECT;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_PATH_SEGMENT;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_RELATIVE_PATH_SEGMENT;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_REPOSITORY_INFO;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_SERVICE;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_TEMPLATE_TEMPLATE;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_TEMPLATE_TYPE;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_TYPE;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_URI_TEMPLATE;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_WORKSPACE;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomAcl;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomAllowableActions;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomBase;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomElement;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomEntry;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomFeed;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.AtomLink;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.HtmlDoc;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.RepositoryWorkspace;
import org.apache.chemistry.opencmis.client.bindings.spi.atompub.objects.ServiceDoc;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.impl.XMLConstants;
import org.apache.chemistry.opencmis.commons.impl.XMLConstraints;
import org.apache.chemistry.opencmis.commons.impl.XMLConverter;
import org.apache.chemistry.opencmis.commons.impl.XMLUtils;

/**
 * AtomPub Parser.
 */
public class AtomPubParser {

    // public constants
    public static final String LINK_REL_CONTENT = "@@content@@";

    private final InputStream stream;
    private AtomBase parseResult;

    public AtomPubParser(InputStream stream) {
        if (stream == null) {
            throw new IllegalArgumentException("No stream.");
        }

        this.stream = stream;
    }

    /**
     * Parses the stream.
     */
    public void parse() throws XMLStreamException {
        XMLStreamReader parser = XMLUtils.createParser(stream);

        try {
            while (true) {
                int event = parser.getEventType();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    QName name = parser.getName();

                    if (XMLConstants.NAMESPACE_ATOM.equals(name.getNamespaceURI())) {
                        if (TAG_FEED.equals(name.getLocalPart())) {
                            parseResult = parseFeed(parser);
                            break;
                        } else if (TAG_ENTRY.equals(name.getLocalPart())) {
                            parseResult = parseEntry(parser);
                            break;
                        }
                    } else if (XMLConstants.NAMESPACE_CMIS.equals(name.getNamespaceURI())) {
                        if (TAG_ALLOWABLEACTIONS.equals(name.getLocalPart())) {
                            parseResult = parseAllowableActions(parser);
                            break;
                        } else if (TAG_ACL.equals(name.getLocalPart())) {
                            parseResult = parseACL(parser);
                            break;
                        }
                    } else if (XMLConstants.NAMESPACE_APP.equals(name.getNamespaceURI())) {
                        if (TAG_SERVICE.equals(name.getLocalPart())) {
                            parseResult = parseServiceDoc(parser);
                            break;
                        }
                    } else if (TAG_HTML.equalsIgnoreCase(name.getLocalPart())) {
                        parseResult = new HtmlDoc();
                        break;
                    }
                }

                if (!XMLUtils.next(parser)) {
                    break;
                }
            }

        } finally {
            try {
                parser.close();
            } catch (XMLStreamException xse) {
                // there is nothing we can do
            }

            // make sure the stream is read and closed in all cases
            IOUtils.consumeAndClose(stream);
        }
    }

    /**
     * Return the parse results.
     */
    public AtomBase getResults() {
        return parseResult;
    }

    /**
     * Parses a service document.
     */
    private static ServiceDoc parseServiceDoc(XMLStreamReader parser) throws XMLStreamException {
        ServiceDoc result = new ServiceDoc();

        XMLUtils.next(parser);

        while (true) {
            int event = parser.getEventType();
            if (event == XMLStreamConstants.START_ELEMENT) {
                QName name = parser.getName();

                if (XMLConstants.NAMESPACE_APP.equals(name.getNamespaceURI())) {
                    if (TAG_WORKSPACE.equals(name.getLocalPart())) {
                        result.addWorkspace(parseWorkspace(parser));
                    } else {
                        XMLUtils.skip(parser);
                    }
                } else {
                    XMLUtils.skip(parser);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                break;
            } else {
                if (!XMLUtils.next(parser)) {
                    break;
                }
            }
        }

        return result;
    }

    /**
     * Parses a workspace element in a service document.
     */
    private static RepositoryWorkspace parseWorkspace(XMLStreamReader parser) throws XMLStreamException {
        RepositoryWorkspace workspace = new RepositoryWorkspace();

        XMLUtils.next(parser);

        while (true) {
            int event = parser.getEventType();
            if (event == XMLStreamConstants.START_ELEMENT) {
                AtomElement element = parseWorkspaceElement(parser);

                // check if we can extract the workspace id
                if ((element != null) && (element.getObject() instanceof RepositoryInfo)) {
                    workspace.setId(((RepositoryInfo) element.getObject()).getId());
                }

                // add to workspace
                workspace.addElement(element);
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                break;
            } else {
                if (!XMLUtils.next(parser)) {
                    break;
                }
            }
        }

        XMLUtils.next(parser);

        return workspace;
    }

    /**
     * Parses an Atom feed.
     */
    private AtomFeed parseFeed(XMLStreamReader parser) throws XMLStreamException {
        AtomFeed result = new AtomFeed();

        XMLUtils.next(parser);

        while (true) {
            int event = parser.getEventType();
            if (event == XMLStreamConstants.START_ELEMENT) {
                QName name = parser.getName();

                if (XMLConstants.NAMESPACE_ATOM.equals(name.getNamespaceURI())) {
                    if (TAG_LINK.equals(name.getLocalPart())) {
                        result.addElement(parseLink(parser));
                    } else if (TAG_ENTRY.equals(name.getLocalPart())) {
                        result.addEntry(parseEntry(parser));
                    } else {
                        XMLUtils.skip(parser);
                    }
                } else if (XMLConstants.NAMESPACE_RESTATOM.equals(name.getNamespaceURI())) {
                    if (TAG_NUM_ITEMS.equals(name.getLocalPart())) {
                        result.addElement(parseBigInteger(parser));
                    } else {
                        XMLUtils.skip(parser);
                    }
                } else if (XMLConstants.NAMESPACE_APACHE_CHEMISTRY.equals(name.getNamespaceURI())) {
                    result.addElement(parseText(parser));
                } else {
                    XMLUtils.skip(parser);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                break;
            } else {
                if (!XMLUtils.next(parser)) {
                    break;
                }
            }
        }

        XMLUtils.next(parser);

        return result;
    }

    /**
     * Parses an Atom entry.
     */
    private AtomEntry parseEntry(XMLStreamReader parser) throws XMLStreamException {
        AtomEntry result = new AtomEntry();

        XMLUtils.next(parser);

        // walk through all tags in entry
        while (true) {
            int event = parser.getEventType();
            if (event == XMLStreamConstants.START_ELEMENT) {
                AtomElement element = parseElement(parser);
                if (element != null) {
                    // add to entry
                    result.addElement(element);

                    // find and set object id
                    if (element.getObject() instanceof ObjectData) {
                        result.setId(((ObjectData) element.getObject()).getId());
                    } else if (element.getObject() instanceof TypeDefinition) {
                        result.setId(((TypeDefinition) element.getObject()).getId());
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                break;
            } else {
                if (!XMLUtils.next(parser)) {
                    break;
                }
            }
        }

        XMLUtils.next(parser);

        return result;
    }

    /**
     * Parses an Allowable Actions document.
     */
    private static AtomAllowableActions parseAllowableActions(XMLStreamReader parser) throws XMLStreamException {
        return new AtomAllowableActions(XMLConverter.convertAllowableActions(parser));
    }

    /**
     * Parses an ACL document.
     */
    private static AtomAcl parseACL(XMLStreamReader parser) throws XMLStreamException {
        return new AtomAcl(XMLConverter.convertAcl(parser));
    }

    /**
     * Parses an element.
     */
    private AtomElement parseElement(XMLStreamReader parser) throws XMLStreamException {
        QName name = parser.getName();

        if (XMLConstants.NAMESPACE_RESTATOM.equals(name.getNamespaceURI())) {
            if (TAG_OBJECT.equals(name.getLocalPart())) {
                return new AtomElement(name, XMLConverter.convertObject(parser));
            } else if (TAG_PATH_SEGMENT.equals(name.getLocalPart())
                    || TAG_RELATIVE_PATH_SEGMENT.equals(name.getLocalPart())) {
                return parseText(parser);
            } else if (TAG_TYPE.equals(name.getLocalPart())) {
                return new AtomElement(name, XMLConverter.convertTypeDefinition(parser));
            } else if (TAG_CHILDREN.equals(name.getLocalPart())) {
                return parseChildren(parser);
            }
        } else if (XMLConstants.NAMESPACE_ATOM.equals(name.getNamespaceURI())) {
            if (TAG_LINK.equals(name.getLocalPart())) {
                return parseLink(parser);
            } else if (TAG_CONTENT.equals(name.getLocalPart())) {
                return parseAtomContentSrc(parser);
            }
        }

        // we don't know it - skip it
        XMLUtils.skip(parser);

        return null;
    }

    /**
     * Parses a children element.
     */
    private AtomElement parseChildren(XMLStreamReader parser) throws XMLStreamException {
        AtomElement result = null;
        QName childName = parser.getName();

        XMLUtils.next(parser);

        // walk through the children tag
        while (true) {
            int event = parser.getEventType();
            if (event == XMLStreamConstants.START_ELEMENT) {
                QName name = parser.getName();

                if (XMLConstants.NAMESPACE_ATOM.equals(name.getNamespaceURI())) {
                    if (TAG_FEED.equals(name.getLocalPart())) {
                        result = new AtomElement(childName, parseFeed(parser));
                    } else {
                        XMLUtils.skip(parser);
                    }
                } else {
                    XMLUtils.skip(parser);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                break;
            } else {
                if (!XMLUtils.next(parser)) {
                    break;
                }
            }
        }

        XMLUtils.next(parser);

        return result;
    }

    /**
     * Parses a workspace element.
     */
    private static AtomElement parseWorkspaceElement(XMLStreamReader parser) throws XMLStreamException {
        QName name = parser.getName();

        if (XMLConstants.NAMESPACE_RESTATOM.equals(name.getNamespaceURI())) {
            if (TAG_REPOSITORY_INFO.equals(name.getLocalPart())) {
                return new AtomElement(name, XMLConverter.convertRepositoryInfo(parser));
            } else if (TAG_URI_TEMPLATE.equals(name.getLocalPart())) {
                return parseTemplate(parser);
            }
        } else if (XMLConstants.NAMESPACE_ATOM.equals(name.getNamespaceURI())) {
            if (TAG_LINK.equals(name.getLocalPart())) {
                return parseLink(parser);
            }
        } else if (XMLConstants.NAMESPACE_APP.equals(name.getNamespaceURI())) {
            if (TAG_COLLECTION.equals(name.getLocalPart())) {
                return parseCollection(parser);
            }
        }

        // we don't know it - skip it
        XMLUtils.skip(parser);

        return null;
    }

    /**
     * Parses a collection tag.
     */
    private static AtomElement parseCollection(XMLStreamReader parser) throws XMLStreamException {
        QName name = parser.getName();
        Map<String, String> result = new HashMap<String, String>();

        result.put("href", parser.getAttributeValue(null, "href"));

        XMLUtils.next(parser);

        while (true) {
            int event = parser.getEventType();
            if (event == XMLStreamConstants.START_ELEMENT) {
                QName tagName = parser.getName();
                if (XMLConstants.NAMESPACE_RESTATOM.equals(tagName.getNamespaceURI())
                        && TAG_COLLECTION_TYPE.equals(tagName.getLocalPart())) {
                    result.put("collectionType", XMLUtils.readText(parser, XMLConstraints.MAX_STRING_LENGTH));
                } else {
                    XMLUtils.skip(parser);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                break;
            } else {
                if (!XMLUtils.next(parser)) {
                    break;
                }
            }
        }

        XMLUtils.next(parser);

        return new AtomElement(name, result);
    }

    /**
     * Parses a template tag.
     */
    private static AtomElement parseTemplate(XMLStreamReader parser) throws XMLStreamException {
        QName name = parser.getName();
        Map<String, String> result = new HashMap<String, String>();

        XMLUtils.next(parser);

        while (true) {
            int event = parser.getEventType();
            if (event == XMLStreamConstants.START_ELEMENT) {
                QName tagName = parser.getName();
                if (XMLConstants.NAMESPACE_RESTATOM.equals(tagName.getNamespaceURI())) {
                    if (TAG_TEMPLATE_TEMPLATE.equals(tagName.getLocalPart())) {
                        result.put("template", XMLUtils.readText(parser, XMLConstraints.MAX_STRING_LENGTH));
                    } else if (TAG_TEMPLATE_TYPE.equals(tagName.getLocalPart())) {
                        result.put("type", XMLUtils.readText(parser, XMLConstraints.MAX_STRING_LENGTH));
                    } else {
                        XMLUtils.skip(parser);
                    }
                } else {
                    XMLUtils.skip(parser);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                break;
            } else {
                if (!XMLUtils.next(parser)) {
                    break;
                }
            }
        }

        XMLUtils.next(parser);

        return new AtomElement(name, result);
    }

    /**
     * Parses a link tag.
     */
    private static AtomElement parseLink(XMLStreamReader parser) throws XMLStreamException {
        QName name = parser.getName();
        AtomLink result = new AtomLink();

        // save attributes
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            if (LINK_REL.equals(parser.getAttributeLocalName(i))) {
                result.setRel(parser.getAttributeValue(i));
            } else if (LINK_HREF.equals(parser.getAttributeLocalName(i))) {
                result.setHref(parser.getAttributeValue(i));
            } else if (LINK_TYPE.equals(parser.getAttributeLocalName(i))) {
                result.setType(parser.getAttributeValue(i));
            }
        }

        // skip enclosed tags, if any
        XMLUtils.skip(parser);

        return new AtomElement(name, result);
    }

    /**
     * Parses a link tag.
     */
    private static AtomElement parseAtomContentSrc(XMLStreamReader parser) throws XMLStreamException {
        QName name = parser.getName();
        AtomLink result = new AtomLink();
        result.setRel(LINK_REL_CONTENT);

        // save attributes
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            if (CONTENT_SRC.equals(parser.getAttributeLocalName(i))) {
                result.setHref(parser.getAttributeValue(i));
            }
        }

        // skip enclosed tags, if any
        XMLUtils.skip(parser);

        return new AtomElement(name, result);
    }

    /**
     * Parses a text tag.
     */
    private static AtomElement parseText(XMLStreamReader parser) throws XMLStreamException {
        QName name = parser.getName();
        return new AtomElement(name, XMLUtils.readText(parser, XMLConstraints.MAX_STRING_LENGTH));
    }

    /**
     * Parses a text tag and convert it into an integer.
     */
    private static AtomElement parseBigInteger(XMLStreamReader parser) throws XMLStreamException {
        QName name = parser.getName();
        return new AtomElement(name, new BigInteger(XMLUtils.readText(parser, XMLConstraints.MAX_STRING_LENGTH)));
    }
}
