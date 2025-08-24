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

import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.w3c.dom.Document;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.util.Xml;

public final class XMLUtils {

    private XMLUtils() {
    }

    // --------------
    // --- writer ---
    // --------------

    /**
     * Creates a new XML writer.
     */
    public static XmlSerializer createWriter(OutputStream out) throws IOException {
        assert out != null;

        XmlSerializer writer = Xml.newSerializer();
        writer.setOutput(out, IOUtils.UTF8);
        
        return writer;
    }

    /**
     * Starts a XML document.
     */
    public static void startXmlDocument(XmlSerializer writer) throws IOException {
        assert writer != null;
        
        writer.setPrefix(XMLConstants.PREFIX_ATOM, XMLConstants.NAMESPACE_ATOM);
        writer.setPrefix(XMLConstants.PREFIX_CMIS, XMLConstants.NAMESPACE_CMIS);
        writer.setPrefix(XMLConstants.PREFIX_RESTATOM, XMLConstants.NAMESPACE_RESTATOM);
        writer.setPrefix(XMLConstants.PREFIX_APACHE_CHEMISTY, XMLConstants.NAMESPACE_APACHE_CHEMISTRY);

        writer.startDocument("UTF-8", false);
    }

    /**
     * Ends a XML document.
     */
    public static void endXmlDocument(XmlSerializer writer) throws IOException {
        assert writer != null;
        
        // end document
        writer.endDocument();
        writer.flush();
        writer.toString();
    }

    /**
     * Writes a String tag.
     */
    public static void write(XmlSerializer writer, String prefix, String namespace, String tag, String value)
            throws IOException {
        assert writer != null;

        if (value == null) {
            return;
        }

        if (namespace == null) {
            writer.startTag(null, tag);
        } else {
            writer.startTag(namespace, tag);
        }
        writer.text(value);
        writer.endTag(namespace, tag);
    }

    /**
     * Writes an Integer tag.
     */
    public static void write(XmlSerializer writer, String prefix, String namespace, String tag, BigInteger value)
            throws IOException {
        assert writer != null;

        if (value == null) {
            return;
        }

        write(writer, prefix, namespace, tag, value.toString());
    }

    /**
     * Writes a Decimal tag.
     */
    public static void write(XmlSerializer writer, String prefix, String namespace, String tag, BigDecimal value)
            throws IOException {
        assert writer != null;

        if (value == null) {
            return;
        }

        write(writer, prefix, namespace, tag, value.toString());
    }

    /**
     * Writes a DateTime tag.
     */
    public static void write(XmlSerializer writer, String prefix, String namespace, String tag, GregorianCalendar value)
            throws IOException {
        assert writer != null;

        if (value == null) {
            return;
        }

        write(writer, prefix, namespace, tag, DateTimeHelper.formatXmlDateTime(value));
    }

    /**
     * Writes a Boolean tag.
     */
    public static void write(XmlSerializer writer, String prefix, String namespace, String tag, Boolean value)
            throws IOException {
        assert writer != null;

        if (value == null) {
            return;
        }

        write(writer, prefix, namespace, tag, value ? "true" : "false");
    }

    /**
     * Writes an Enum tag.
     */
    public static void write(XmlSerializer writer, String prefix, String namespace, String tag, Enum<?> value)
            throws IOException {
        assert writer != null;

        if (value == null) {
            return;
        }

        Object enumValue;
        try {
            enumValue = value.getClass().getMethod("value", new Class[0]).invoke(value, new Object[0]);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot get enum value", e);
        }

        write(writer, prefix, namespace, tag, enumValue.toString());
    }

    // ---------------
    // ---- parser ---
    // ---------------

    /**
     * Creates a new XML parser with OpenCMIS default settings.
     */
    public static XmlPullParser createParser(InputStream stream) throws XmlPullParserException {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(stream, IOUtils.UTF8);
        return parser;
    }

    /**
     * Moves the parser to the next element.
     */
    public static boolean next(XmlPullParser parser) {
        assert parser != null;

        try {
            if (hasNext(parser)) {
                parser.next();
                return true;
            }
            return false;
        } catch (Exception e) {
            // EOF exceptions
            return false;
        }
    }

    public static boolean hasNext(XmlPullParser parser) throws XmlPullParserException {
        assert parser != null;

        return parser.getEventType() != XmlPullParser.END_DOCUMENT;
    }

    /**
     * Skips a tag or subtree.
     */
    public static void skip(XmlPullParser parser) throws XmlPullParserException {
        assert parser != null;

        int level = 1;
        while (next(parser)) {
            int event = parser.getEventType();
            if (event == XmlPullParser.START_TAG) {
                level++;
            } else if (event == XmlPullParser.END_TAG) {
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
     * @return <code>true</code> if another start element has been found,
     *         <code>false</code> otherwise
     */
    public static boolean findNextStartElemenet(XmlPullParser parser) throws XmlPullParserException, IOException {
        assert parser != null;

        while (true) {
            int event = parser.getEventType();

            if (event == XmlPullParser.START_TAG) {
                return true;
            }

            if (hasNext(parser)) {
                parser.next();
            } else {
                return false;
            }
        }
    }

    /**
     * Parses a tag that contains text.
     */
    public static String readText(XmlPullParser parser, int maxLength) throws XmlPullParserException {
        assert parser != null;
        assert maxLength >= 0;

        StringBuilder sb = new StringBuilder(128);

        next(parser);

        while (true) {
            int event = parser.getEventType();
            if (event == XmlPullParser.END_TAG) {
                break;
            } else if (event == XmlPullParser.TEXT) {
                int len = 0;
                if (parser.getText() != null) {
                    len = parser.getText().length();
                }
                if (len > 0) {
                    if (sb.length() + len > maxLength) {
                        throw new CmisInvalidArgumentException("String limit exceeded!");
                    }
                    sb.append(parser.getText());
                }
            } else if (event == XmlPullParser.START_TAG) {
                throw new XmlPullParserException("Unexpected tag: " + parser.getName());
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
    
    public static Document newDomDocument() {
        throw new CmisRuntimeException("This method should never be used on Android!");
    }
}
