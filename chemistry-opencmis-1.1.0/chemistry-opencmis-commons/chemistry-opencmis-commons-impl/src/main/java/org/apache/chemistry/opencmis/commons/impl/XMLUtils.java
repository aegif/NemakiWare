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
package org.apache.chemistry.opencmis.commons.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.GregorianCalendar;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;

import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import com.ctc.wstx.api.InvalidCharHandler;
import com.ctc.wstx.api.WstxOutputProperties;
import com.ctc.wstx.stax.WstxInputFactory;
import com.ctc.wstx.stax.WstxOutputFactory;

public final class XMLUtils {

    private static final Logger LOG = LoggerFactory.getLogger(XMLUtils.class);

    private static final XMLInputFactory XML_INPUT_FACTORY;
    static {
        XMLInputFactory factory;

        try {
            // Woodstox is the only supported and tested StAX implementation
            WstxInputFactory wstxFactory = (WstxInputFactory) ClassLoaderUtil.loadClass(
                    "com.ctc.wstx.stax.WstxInputFactory").newInstance();
            wstxFactory.configureForSpeed();

            factory = wstxFactory;
        } catch (Exception e) {
            // other StAX implementations may work, too
            factory = XMLInputFactory.newInstance();

            try {
                // for the SJSXP parser
                factory.setProperty("reuse-instance", Boolean.FALSE);
            } catch (IllegalArgumentException ex) {
                // ignore
            }

            LOG.warn("Unsupported StAX parser: {} (Exception: {})", factory.getClass().getName(), e.toString(), e);
        }

        factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_VALIDATING, Boolean.FALSE);

        XML_INPUT_FACTORY = factory;
    }

    private static final XMLOutputFactory XML_OUTPUT_FACTORY;
    static {
        XMLOutputFactory factory;

        try {
            // Woodstox is the only supported and tested StAX implementation
            WstxOutputFactory wstxFactory = (WstxOutputFactory) ClassLoaderUtil.loadClass(
                    "com.ctc.wstx.stax.WstxOutputFactory").newInstance();
            wstxFactory.configureForSpeed();
            wstxFactory.setProperty(WstxOutputProperties.P_OUTPUT_INVALID_CHAR_HANDLER,
                    new InvalidCharHandler.ReplacingHandler(' '));

            factory = wstxFactory;
        } catch (Exception e) {
            // other StAX implementations may work, too
            factory = XMLOutputFactory.newInstance();

            try {
                // for the SJSXP parser
                factory.setProperty("reuse-instance", Boolean.FALSE);
            } catch (IllegalArgumentException ex) {
                // ignore
            }

            LOG.warn("Unsupported StAX parser: {} (Exception: {})", factory.getClass().getName(), e.toString(), e);
        }

        factory.setProperty(XMLOutputFactory.IS_REPAIRING_NAMESPACES, Boolean.FALSE);

        XML_OUTPUT_FACTORY = factory;
    }

    private XMLUtils() {
    }

    // --------------
    // --- writer ---
    // --------------

    /**
     * Creates a new XML writer.
     */
    public static XMLStreamWriter createWriter(OutputStream out) throws XMLStreamException {
        assert out != null;

        return XML_OUTPUT_FACTORY.createXMLStreamWriter(out, IOUtils.UTF8);
    }

    /**
     * Starts a XML document.
     */
    public static void startXmlDocument(XMLStreamWriter writer) throws XMLStreamException {
        assert writer != null;

        writer.setPrefix(XMLConstants.PREFIX_ATOM, XMLConstants.NAMESPACE_ATOM);
        writer.setPrefix(XMLConstants.PREFIX_CMIS, XMLConstants.NAMESPACE_CMIS);
        writer.setPrefix(XMLConstants.PREFIX_RESTATOM, XMLConstants.NAMESPACE_RESTATOM);
        writer.setPrefix(XMLConstants.PREFIX_APACHE_CHEMISTY, XMLConstants.NAMESPACE_APACHE_CHEMISTRY);

        writer.writeStartDocument();
    }

    /**
     * Ends a XML document.
     */
    public static void endXmlDocument(XMLStreamWriter writer) throws XMLStreamException {
        assert writer != null;

        writer.writeEndDocument();
        writer.close();
    }

    /**
     * Writes a String tag.
     */
    public static void write(XMLStreamWriter writer, String prefix, String namespace, String tag, String value)
            throws XMLStreamException {
        assert writer != null;

        if (value == null) {
            return;
        }

        if (namespace == null) {
            writer.writeStartElement(tag);
        } else {
            writer.writeStartElement(prefix, tag, namespace);
        }
        writer.writeCharacters(value);
        writer.writeEndElement();
    }

    /**
     * Writes an Integer tag.
     */
    public static void write(XMLStreamWriter writer, String prefix, String namespace, String tag, BigInteger value)
            throws XMLStreamException {
        assert writer != null;

        if (value == null) {
            return;
        }

        write(writer, prefix, namespace, tag, value.toString());
    }

    /**
     * Writes a Decimal tag.
     */
    public static void write(XMLStreamWriter writer, String prefix, String namespace, String tag, BigDecimal value)
            throws XMLStreamException {
        assert writer != null;

        if (value == null) {
            return;
        }

        write(writer, prefix, namespace, tag, value.toPlainString());
    }

    /**
     * Writes a DateTime tag.
     */
    public static void write(XMLStreamWriter writer, String prefix, String namespace, String tag,
            GregorianCalendar value) throws XMLStreamException {
        assert writer != null;

        if (value == null) {
            return;
        }

        write(writer, prefix, namespace, tag, DateTimeHelper.formatXmlDateTime(value));
    }

    /**
     * Writes a Boolean tag.
     */
    public static void write(XMLStreamWriter writer, String prefix, String namespace, String tag, Boolean value)
            throws XMLStreamException {
        assert writer != null;

        if (value == null) {
            return;
        }

        write(writer, prefix, namespace, tag, value ? "true" : "false");
    }

    /**
     * Writes an Enum tag.
     */
    public static void write(XMLStreamWriter writer, String prefix, String namespace, String tag, Enum<?> value)
            throws XMLStreamException {
        assert writer != null;

        if (value == null) {
            return;
        }

        Object enumValue;
        try {
            enumValue = value.getClass().getMethod("value", new Class[0]).invoke(value, new Object[0]);
        } catch (Exception e) {
            throw new XMLStreamException("Cannot get enum value", e);
        }

        write(writer, prefix, namespace, tag, enumValue.toString());
    }

    // ---------------
    // ---- parser ---
    // ---------------

    /**
     * Creates a new XML parser with OpenCMIS default settings.
     */
    public static XMLStreamReader createParser(InputStream stream) throws XMLStreamException {
        return XML_INPUT_FACTORY.createXMLStreamReader(stream);
    }

    /**
     * Moves the parser to the next element.
     */
    public static boolean next(XMLStreamReader parser) throws XMLStreamException {
        assert parser != null;

        if (parser.hasNext()) {
            parser.next();
            return true;
        }

        return false;
    }

    /**
     * Skips a tag or subtree.
     */
    public static void skip(XMLStreamReader parser) throws XMLStreamException {
        assert parser != null;

        int level = 1;
        while (next(parser)) {
            int event = parser.getEventType();
            if (event == XMLStreamConstants.START_ELEMENT) {
                level++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                level--;
                if (level == 0) {
                    break;
                }
            }
        }

        next(parser);
    }

    /**
     * Moves the parser to the next start element.
     * 
     * @return {@code true} if another start element has been found,
     *         {@code false} otherwise
     */
    public static boolean findNextStartElemenet(XMLStreamReader parser) throws XMLStreamException {
        assert parser != null;

        while (true) {
            int event = parser.getEventType();

            if (event == XMLStreamConstants.START_ELEMENT) {
                return true;
            }

            if (parser.hasNext()) {
                parser.next();
            } else {
                return false;
            }
        }
    }

    /**
     * Parses a tag that contains text.
     */
    public static String readText(XMLStreamReader parser, int maxLength) throws XMLStreamException {
        assert parser != null;
        assert maxLength >= 0;

        StringBuilder sb = new StringBuilder(128);

        next(parser);

        while (true) {
            int event = parser.getEventType();
            if (event == XMLStreamConstants.END_ELEMENT) {
                break;
            } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                int len = parser.getTextLength();
                if (len > 0) {
                    if (sb.length() + len > maxLength) {
                        throw new CmisInvalidArgumentException("String limit exceeded!");
                    }

                    char[] chars = parser.getTextCharacters();
                    int offset = parser.getTextStart();

                    sb.append(chars, offset, len);
                }
            } else if (event == XMLStreamConstants.START_ELEMENT) {
                throw new XMLStreamException("Unexpected tag: " + parser.getName());
            }

            if (!next(parser)) {
                break;
            }
        }

        next(parser);

        return sb.toString();
    }

    // ------------------
    // ---- DOM stuff ---
    // ------------------

    /**
     * Creates a new {@link DocumentBuilder} object.
     */
    private static DocumentBuilder newDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        factory.setIgnoringComments(true);
        factory.setExpandEntityReferences(false);
        factory.setCoalescing(false);

        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

        return factory.newDocumentBuilder();
    }

    /**
     * Creates a new DOM document.
     */
    public static Document newDomDocument() throws ParserConfigurationException {
        return newDocumentBuilder().newDocument();
    }

    /**
     * Parses a stream and returns the DOM document.
     */
    public static Document parseDomDocument(InputStream stream) throws ParserConfigurationException, SAXException,
            IOException {
        return newDocumentBuilder().parse(stream);
    }

    // --------------------------
    // ---- Transformer stuff ---
    // --------------------------

    private static TransformerFactory newTransformerFactory() throws TransformerConfigurationException {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);

        return factory;
    }

    public static Transformer newTransformer() throws TransformerConfigurationException {
        return newTransformerFactory().newTransformer();
    }

    public static Transformer newTransformer(int indent) throws TransformerConfigurationException {
        TransformerFactory factory = newTransformerFactory();
        factory.setAttribute("indent-number", Integer.valueOf(indent));

        Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");

        return transformer;
    }
}
