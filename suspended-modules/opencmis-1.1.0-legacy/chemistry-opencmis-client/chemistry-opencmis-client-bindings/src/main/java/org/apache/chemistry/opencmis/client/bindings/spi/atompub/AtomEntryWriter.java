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

import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_ATOM_ID;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_ATOM_TITLE;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_ATOM_UPDATED;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_CONTENT;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_CONTENT_BASE64;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_CONTENT_FILENAME;
import static org.apache.chemistry.opencmis.client.bindings.spi.atompub.CmisAtomPubConstants.TAG_CONTENT_MEDIATYPE;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.PropertyString;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.impl.Base64;
import org.apache.chemistry.opencmis.commons.impl.DateTimeHelper;
import org.apache.chemistry.opencmis.commons.impl.XMLConstants;
import org.apache.chemistry.opencmis.commons.impl.XMLConverter;
import org.apache.chemistry.opencmis.commons.impl.XMLUtils;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.BulkUpdateImpl;

/**
 * Writes a CMIS Atom entry to an output stream.
 */
public class AtomEntryWriter {

    private static final int BUFFER_SIZE = 64 * 1024;

    private final CmisVersion cmisVersion;
    private final ObjectData object;
    private final ContentStream contentStream;
    private final InputStream stream;
    private final TypeDefinition typeDef;
    private final BulkUpdateImpl bulkUpdate;

    /**
     * Constructor for objects.
     */
    public AtomEntryWriter(ObjectData object, CmisVersion cmisVersion) {
        this(object, cmisVersion, null);
    }

    /**
     * Constructor for objects.
     */
    public AtomEntryWriter(ObjectData object, CmisVersion cmisVersion, ContentStream contentStream) {
        if ((object == null) || (object.getProperties() == null)) {
            throw new CmisInvalidArgumentException("Object and properties must not be null!");
        }

        if ((contentStream != null) && (contentStream.getMimeType() == null)) {
            throw new CmisInvalidArgumentException("Media type must be set if a stream is present!");
        }

        this.object = object;
        this.cmisVersion = cmisVersion;
        this.contentStream = contentStream;
        if (contentStream != null && contentStream.getStream() != null) {
            InputStream in = contentStream.getStream();

            // avoid double buffering
            if (!(in instanceof BufferedInputStream) && !(in instanceof ByteArrayInputStream)) {
                stream = new BufferedInputStream(in, BUFFER_SIZE);
            } else {
                stream = in;
            }
        } else {
            stream = null;
        }
        this.typeDef = null;
        this.bulkUpdate = null;
    }

    /**
     * Constructor for types.
     */
    public AtomEntryWriter(TypeDefinition type, CmisVersion cmisVersion) {
        if (type == null) {
            throw new CmisInvalidArgumentException("Type must not be null!");
        }

        this.typeDef = type;
        this.cmisVersion = cmisVersion;
        this.object = null;
        this.contentStream = null;
        this.stream = null;
        this.bulkUpdate = null;
    }

    /**
     * Constructor for bulk updates.
     */
    public AtomEntryWriter(BulkUpdateImpl bulkUpdate) {
        if (bulkUpdate == null) {
            throw new CmisInvalidArgumentException("Bulk update data must not be null!");
        }

        this.bulkUpdate = bulkUpdate;
        this.typeDef = null;
        this.cmisVersion = CmisVersion.CMIS_1_1;
        this.object = null;
        this.contentStream = null;
        this.stream = null;
    }

    /**
     * Writes the entry to an output stream.
     */
    public void write(OutputStream out) throws XMLStreamException, IOException {
        XMLStreamWriter writer = XMLUtils.createWriter(out);

        XMLUtils.startXmlDocument(writer);

        writer.writeStartElement(XMLConstants.PREFIX_ATOM, "entry", XMLConstants.NAMESPACE_ATOM);

        writer.writeNamespace(XMLConstants.PREFIX_ATOM, XMLConstants.NAMESPACE_ATOM);
        writer.writeNamespace(XMLConstants.PREFIX_CMIS, XMLConstants.NAMESPACE_CMIS);
        writer.writeNamespace(XMLConstants.PREFIX_RESTATOM, XMLConstants.NAMESPACE_RESTATOM);
        if (contentStream != null && contentStream.getFileName() != null) {
            writer.writeNamespace(XMLConstants.PREFIX_APACHE_CHEMISTY, XMLConstants.NAMESPACE_APACHE_CHEMISTRY);
        }

        // atom:id
        XMLUtils.write(writer, XMLConstants.PREFIX_ATOM, XMLConstants.NAMESPACE_ATOM, TAG_ATOM_ID,
                "urn:uuid:00000000-0000-0000-0000-00000000000");

        // atom:title
        XMLUtils.write(writer, XMLConstants.PREFIX_ATOM, XMLConstants.NAMESPACE_ATOM, TAG_ATOM_TITLE, getTitle());

        // atom:updated
        XMLUtils.write(writer, XMLConstants.PREFIX_ATOM, XMLConstants.NAMESPACE_ATOM, TAG_ATOM_UPDATED,
                new GregorianCalendar(DateTimeHelper.GMT));

        // content
        if (stream != null) {
            writer.writeStartElement(XMLConstants.PREFIX_RESTATOM, TAG_CONTENT, XMLConstants.NAMESPACE_RESTATOM);

            XMLUtils.write(writer, XMLConstants.PREFIX_RESTATOM, XMLConstants.NAMESPACE_RESTATOM,
                    TAG_CONTENT_MEDIATYPE, contentStream.getMimeType());

            if (contentStream.getFileName() != null) {
                XMLUtils.write(writer, XMLConstants.PREFIX_APACHE_CHEMISTY, XMLConstants.NAMESPACE_APACHE_CHEMISTRY,
                        TAG_CONTENT_FILENAME, contentStream.getFileName());
            }

            writer.writeStartElement(XMLConstants.PREFIX_RESTATOM, TAG_CONTENT_BASE64, XMLConstants.NAMESPACE_RESTATOM);
            writeContent(writer);
            writer.writeEndElement();

            writer.writeEndElement();
        }

        // object
        if (object != null) {
            XMLConverter.writeObject(writer, cmisVersion, XMLConstants.NAMESPACE_RESTATOM, object);
        }

        // type
        if (typeDef != null) {
            XMLConverter.writeTypeDefinition(writer, cmisVersion, XMLConstants.NAMESPACE_RESTATOM, typeDef);
        }

        // bulk update
        if (bulkUpdate != null) {
            XMLConverter.writeBulkUpdate(writer, XMLConstants.NAMESPACE_RESTATOM, bulkUpdate);
        }

        // end entry
        writer.writeEndElement();

        // end document
        XMLUtils.endXmlDocument(writer);
    }

    // ---- internal ----

    private String getTitle() {
        String result = "";

        if (object != null) {
            PropertyData<?> nameProperty = object.getProperties().getProperties().get(PropertyIds.NAME);
            if (nameProperty instanceof PropertyString) {
                result = ((PropertyString) nameProperty).getFirstValue();
            }
        }

        if (typeDef != null) {
            if (typeDef.getDisplayName() != null) {
                result = typeDef.getDisplayName();
            }
        }

        if (bulkUpdate != null) {
            result = "Bulk Update Properties";
        }

        return result;
    }

    private void writeContent(XMLStreamWriter writer) throws XMLStreamException, IOException {
        @SuppressWarnings("resource")
        Base64.InputStream b64stream = new Base64.InputStream(stream, Base64.ENCODE);

        char[] buffer = new char[BUFFER_SIZE];
        int pos = 0;
        int b;
        while ((b = b64stream.read()) > -1) {
            buffer[pos++] = (char) (b & 0xFF);
            if (pos == buffer.length) {
                writer.writeCharacters(buffer, 0, buffer.length);
                pos = 0;
            }
        }
        if (pos > 0) {
            writer.writeCharacters(buffer, 0, pos);
        }
    }
}
