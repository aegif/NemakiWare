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

import static org.apache.chemistry.opencmis.commons.impl.XMLUtils.next;
import static org.apache.chemistry.opencmis.commons.impl.XMLUtils.skip;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.CmisExtensionElementImpl;

public abstract class XMLWalker<T> {

    public T walk(XMLStreamReader parser) throws XMLStreamException {
        assert parser != null;

        final T result = prepareTarget(parser, parser.getName());

        next(parser);

        // walk through all tags
        while (true) {
            int event = parser.getEventType();
            if (event == XMLStreamConstants.START_ELEMENT) {
                QName name = parser.getName();
                if (!read(parser, name, result)) {
                    if (result instanceof ExtensionsData) {
                        handleExtension(parser, (ExtensionsData) result);
                    } else {
                        skip(parser);
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                break;
            } else {
                if (!next(parser)) {
                    break;
                }
            }
        }

        next(parser);

        return result;
    }

    protected boolean isCmisNamespace(QName name) {
        assert name != null;

        return XMLConstants.NAMESPACE_CMIS.hashCode() == name.getNamespaceURI().hashCode()
                && XMLConstants.NAMESPACE_CMIS.equals(name.getNamespaceURI());
    }

    protected boolean isAtomNamespace(QName name) {
        assert name != null;

        return XMLConstants.NAMESPACE_ATOM.hashCode() == name.getNamespaceURI().hashCode()
                && XMLConstants.NAMESPACE_ATOM.equals(name.getNamespaceURI());
    }

    protected boolean isTag(QName name, String tag) {
        assert name != null;
        assert tag != null;

        return tag.hashCode() == name.getLocalPart().hashCode() && tag.equals(name.getLocalPart());
    }

    protected void handleExtension(XMLStreamReader parser, ExtensionsData extData) throws XMLStreamException {
        assert parser != null;

        List<CmisExtensionElement> extensions = extData.getExtensions();
        if (extensions == null) {
            extensions = new ArrayList<CmisExtensionElement>();
            extData.setExtensions(extensions);
        }

        if (extensions.size() + 1 > XMLConstraints.MAX_EXTENSIONS_WIDTH) {
            throw new CmisInvalidArgumentException("Too many extensions! (More than "
                    + XMLConstraints.MAX_EXTENSIONS_WIDTH + " extensions.)");
        }

        extensions.add(handleExtensionLevel(parser, 0));
    }

    private CmisExtensionElement handleExtensionLevel(final XMLStreamReader parser, final int level)
            throws XMLStreamException {
        assert parser != null;

        final QName name = parser.getName();
        Map<String, String> attributes = null;
        StringBuilder sb = new StringBuilder(128);
        List<CmisExtensionElement> children = null;

        if (parser.getAttributeCount() > 0) {
            attributes = new HashMap<String, String>();

            for (int i = 0; i < parser.getAttributeCount(); i++) {
                attributes.put(parser.getAttributeLocalName(i), parser.getAttributeValue(i));
            }
        }

        next(parser);

        while (true) {
            int event = parser.getEventType();
            if (event == XMLStreamConstants.END_ELEMENT) {
                break;
            } else if (event == XMLStreamConstants.CHARACTERS) {
                String s = parser.getText();
                if (s != null) {
                    if (sb.length() + s.length() > XMLConstraints.MAX_STRING_LENGTH) {
                        throw new CmisInvalidArgumentException("String limit exceeded!");
                    }
                    sb.append(s);
                }
            } else if (event == XMLStreamConstants.START_ELEMENT) {
                if (level + 1 > XMLConstraints.MAX_EXTENSIONS_DEPTH) {
                    throw new CmisInvalidArgumentException("Extensions tree too deep!  (More than "
                            + XMLConstraints.MAX_EXTENSIONS_DEPTH + " levels.)");
                }

                if (children == null) {
                    children = new ArrayList<CmisExtensionElement>();
                }

                if (children.size() + 1 > XMLConstraints.MAX_EXTENSIONS_WIDTH) {
                    throw new CmisInvalidArgumentException("Extensions tree too wide! (More than "
                            + XMLConstraints.MAX_EXTENSIONS_WIDTH + " extensions on one level.)");
                }

                children.add(handleExtensionLevel(parser, level + 1));

                continue;
            }

            if (!next(parser)) {
                break;
            }
        }

        next(parser);

        if (children != null) {
            return new CmisExtensionElementImpl(name.getNamespaceURI(), name.getLocalPart(), attributes, children);
        } else {
            return new CmisExtensionElementImpl(name.getNamespaceURI(), name.getLocalPart(), attributes, sb.toString());
        }
    }

    protected <S> List<S> addToList(List<S> list, S value) {
        if (list == null || list.isEmpty()) {
            list = new ArrayList<S>();
        }
        list.add(value);

        return list;
    }

    protected String readText(final XMLStreamReader parser) throws XMLStreamException {
        assert parser != null;

        return XMLUtils.readText(parser, XMLConstraints.MAX_STRING_LENGTH);
    }

    protected Boolean readBoolean(final XMLStreamReader parser) throws XMLStreamException {
        assert parser != null;

        String value = readText(parser);

        if ("true".equals(value) || "1".equals(value)) {
            return Boolean.TRUE;
        }

        if ("false".equals(value) || "0".equals(value)) {
            return Boolean.FALSE;
        }

        throw new CmisInvalidArgumentException("Invalid boolean value!");
    }

    protected BigInteger readInteger(final XMLStreamReader parser) throws XMLStreamException {
        assert parser != null;

        String value = readText(parser);

        try {
            return new BigInteger(value);
        } catch (NumberFormatException e) {
            throw new CmisInvalidArgumentException("Invalid integer value!", e);
        }
    }

    protected BigDecimal readDecimal(final XMLStreamReader parser) throws XMLStreamException {
        assert parser != null;

        String value = readText(parser);

        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new CmisInvalidArgumentException("Invalid decimal value!", e);
        }
    }

    protected GregorianCalendar readDateTime(final XMLStreamReader parser) throws XMLStreamException {
        assert parser != null;

        String value = readText(parser);

        GregorianCalendar result = DateTimeHelper.parseXmlDateTime(value);
        if (result == null) {
            throw new CmisInvalidArgumentException("Invalid datetime value!");
        }

        return result;
    }

    public <E extends Enum<E>> E readEnum(final XMLStreamReader parser, final Class<E> clazz) throws XMLStreamException {
        assert parser != null;
        assert clazz != null;

        return CmisEnumHelper.fromValue(readText(parser), clazz);
    }

    protected abstract T prepareTarget(XMLStreamReader parser, QName name) throws XMLStreamException;

    protected abstract boolean read(XMLStreamReader parser, QName name, T target) throws XMLStreamException;
}
