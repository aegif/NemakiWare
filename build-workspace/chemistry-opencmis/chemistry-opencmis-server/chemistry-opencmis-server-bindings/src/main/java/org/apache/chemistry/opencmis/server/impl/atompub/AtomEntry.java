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
package org.apache.chemistry.opencmis.server.impl.atompub;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.impl.Constants;
import org.apache.chemistry.opencmis.commons.impl.XMLConstants;
import org.apache.chemistry.opencmis.commons.impl.XMLConverter;
import org.apache.chemistry.opencmis.commons.server.ObjectInfo;

/**
 * Atom Entry class.
 */
public class AtomEntry extends AtomDocumentBase {

    private static final String DEFAULT_AUTHOR = "unknown";

    // private boolean contentTagAdded;

    /**
     * Creates an Atom entry document.
     */
    public AtomEntry() {
    }

    /**
     * Creates an Atom entry that is embedded somewhere.
     */
    public AtomEntry(XMLStreamWriter writer) {
        setWriter(writer);
    }

    /**
     * Opens the entry tag.
     */
    public void startEntry(boolean isRoot) throws XMLStreamException {
        XMLStreamWriter xsw = getWriter();

        xsw.writeStartElement(XMLConstants.PREFIX_ATOM, "entry", XMLConstants.NAMESPACE_ATOM);

        if (isRoot) {
            xsw.writeNamespace(XMLConstants.PREFIX_ATOM, XMLConstants.NAMESPACE_ATOM);
            xsw.writeNamespace(XMLConstants.PREFIX_CMIS, XMLConstants.NAMESPACE_CMIS);
            xsw.writeNamespace(XMLConstants.PREFIX_RESTATOM, XMLConstants.NAMESPACE_RESTATOM);
            xsw.writeNamespace(XMLConstants.PREFIX_APP, XMLConstants.NAMESPACE_APP);

            writeAllCustomNamespace();
        }

        // contentTagAdded = false;
    }

    /**
     * Closes the entry tag.
     */
    public void endEntry() throws XMLStreamException {
        // if (!contentTagAdded) {
        // writeEmptyContent();
        // }

        getWriter().writeEndElement();
    }

    /**
     * Writes an entry self link.
     */
    public void writeSelfLink(String href, String id) throws XMLStreamException {
        writeSelfLink(href, Constants.MEDIATYPE_ENTRY, id);
    }

    /**
     * Writes an object.
     */
    public void writeObject(ObjectData object, ObjectInfo info, String contentSrc, String contentType,
            String pathSegment, String relativePathSegment, CmisVersion cmisVersion) throws XMLStreamException {
        if (object == null) {
            return;
        }

        writeAuthor(info.getCreatedBy());
        writeId(info.getAtomId() == null ? generateAtomId(info.getId()) : info.getAtomId());
        writePublished(info.getCreationDate());
        writeTitle(info.getName());
        writeUpdated(info.getLastModificationDate());

        writeContent(contentSrc, contentType);

        XMLConverter.writeObject(getWriter(), cmisVersion, false, XMLConstants.TAG_OBJECT,
                XMLConstants.NAMESPACE_RESTATOM, object);

        writePathSegment(pathSegment);
        writeRelativePathSegment(relativePathSegment);
    }

    /**
     * Writes a delete object.
     */
    public void writeDeletedObject(ObjectData object, CmisVersion cmisVersion) throws XMLStreamException {
        if (object == null) {
            return;
        }

        long now = System.currentTimeMillis();

        writeAuthor(DEFAULT_AUTHOR);
        writeId(generateAtomId(object.getId()));
        writePublished(now);
        writeTitle(object.getId());
        writeUpdated(now);

        XMLConverter.writeObject(getWriter(), cmisVersion, false, XMLConstants.TAG_OBJECT,
                XMLConstants.NAMESPACE_RESTATOM, object);
    }

    /**
     * Writes a type.
     */
    public void writeType(TypeDefinition type, CmisVersion cmisVersion) throws XMLStreamException {
        if (type == null) {
            return;
        }

        long now = System.currentTimeMillis();

        writeAuthor(DEFAULT_AUTHOR);
        writeId(generateAtomId(type.getId()));
        writeTitle(type.getDisplayName());
        writeUpdated(now);

        XMLConverter.writeTypeDefinition(getWriter(), cmisVersion, XMLConstants.NAMESPACE_RESTATOM, type);
    }

    /**
     * Writes a content tag.
     */
    public void writeContent(String src, String type) throws XMLStreamException {
        if (src == null) {
            return;
        }

        XMLStreamWriter xsw = getWriter();
        xsw.writeStartElement(XMLConstants.PREFIX_ATOM, "content", XMLConstants.NAMESPACE_ATOM);

        xsw.writeAttribute("src", src);
        if (type != null) {
            xsw.writeAttribute("type", type);
        }

        xsw.writeEndElement();

        // contentTagAdded = true;
    }

    /**
     * Writes an empty content tag for Atom spec compliance.
     */
    public void writeEmptyContent() throws XMLStreamException {
        XMLStreamWriter xsw = getWriter();
        xsw.writeStartElement(XMLConstants.PREFIX_ATOM, "content", XMLConstants.NAMESPACE_ATOM);
        xsw.writeEndElement();

        // contentTagAdded = true;
    }
}
