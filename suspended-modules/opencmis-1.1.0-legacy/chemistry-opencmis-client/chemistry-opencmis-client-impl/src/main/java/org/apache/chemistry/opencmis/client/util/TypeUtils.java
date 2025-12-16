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
package org.apache.chemistry.opencmis.client.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.FolderTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.ItemTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PolicyTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyBooleanDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDateTimeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDecimalDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyHtmlDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyIdDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyIntegerDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyStringDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyUriDefinition;
import org.apache.chemistry.opencmis.commons.definitions.RelationshipTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.SecondaryTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.enums.DateTimeFormat;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.impl.JSONConverter;
import org.apache.chemistry.opencmis.commons.impl.XMLConstants;
import org.apache.chemistry.opencmis.commons.impl.XMLConverter;
import org.apache.chemistry.opencmis.commons.impl.XMLUtils;
import org.apache.chemistry.opencmis.commons.impl.json.parser.JSONParseException;
import org.apache.chemistry.opencmis.commons.impl.json.parser.JSONParser;

public final class TypeUtils {

    private TypeUtils() {
    }

    /**
     * Serializes the type definition to XML, using the format defined in the
     * CMIS specification.
     * 
     * The XML is UTF-8 encoded and the stream is not closed.
     */
    public static void writeToXML(TypeDefinition type, OutputStream stream) throws XMLStreamException {
        if (type == null) {
            throw new IllegalArgumentException("Type must be set!");
        }
        if (stream == null) {
            throw new IllegalArgumentException("Output stream must be set!");
        }

        XMLStreamWriter writer = XMLUtils.createWriter(stream);
        XMLUtils.startXmlDocument(writer);
        XMLConverter.writeTypeDefinition(writer, CmisVersion.CMIS_1_1, XMLConstants.NAMESPACE_CMIS, type);
        XMLUtils.endXmlDocument(writer);
        writer.close();
    }

    /**
     * Serializes the type definition to JSON, using the format defined in the
     * CMIS specification.
     * 
     * The JSON is UTF-8 encoded and the stream is not closed.
     */
    public static void writeToJSON(TypeDefinition type, OutputStream stream) throws IOException {
        if (type == null) {
            throw new IllegalArgumentException("Type must be set!");
        }
        if (stream == null) {
            throw new IllegalArgumentException("Output stream must be set!");
        }

        Writer writer = new BufferedWriter(new OutputStreamWriter(stream, IOUtils.UTF8));
        JSONConverter.convert(type, DateTimeFormat.SIMPLE).writeJSONString(writer);
        writer.flush();
    }

    /**
     * Reads a type definition from a XML stream.
     * 
     * The stream must be UTF-8 encoded.
     */
    public static TypeDefinition readFromXML(InputStream stream) throws XMLStreamException {
        if (stream == null) {
            throw new IllegalArgumentException("Input stream must be set!");
        }

        XMLStreamReader parser = XMLUtils.createParser(stream);
        if (!XMLUtils.findNextStartElemenet(parser)) {
            return null;
        }

        TypeDefinition typeDef = XMLConverter.convertTypeDefinition(parser);

        parser.close();

        return typeDef;
    }

    /**
     * Reads a type definition from a JSON stream.
     * 
     * The stream must be UTF-8 encoded.
     */
    @SuppressWarnings("unchecked")
    public static TypeDefinition readFromJSON(InputStream stream) throws IOException, JSONParseException {
        if (stream == null) {
            throw new IllegalArgumentException("Input stream must be set!");
        }

        JSONParser parser = new JSONParser();
        Object json = parser.parse(new InputStreamReader(stream, IOUtils.UTF8));

        if (!(json instanceof Map)) {
            throw new CmisRuntimeException("Invalid stream! Not a type definition!");
        }

        return JSONConverter.convertTypeDefinition((Map<String, Object>) json);
    }

    /**
     * Checks if a property query name is valid.
     * 
     * @param queryName
     *            the query name
     * @return {@code true} if the query name is valid, {@code false} otherwise
     */
    public static boolean checkQueryName(String queryName) {
        return queryName != null && queryName.length() > 0 && queryName.indexOf(' ') < 0 && queryName.indexOf('\t') < 0
                && queryName.indexOf('\n') < 0 && queryName.indexOf('\r') < 0 && queryName.indexOf('\f') < 0
                && queryName.indexOf(',') < 0 && queryName.indexOf('"') < 0 && queryName.indexOf('\'') < 0
                && queryName.indexOf('\\') < 0 && queryName.indexOf('.') < 0 && queryName.indexOf('(') < 0
                && queryName.indexOf(')') < 0;
    }

    /**
     * Validates a type definition.
     * 
     * @return the list of validation errors
     */
    public static List<ValidationError> validateTypeDefinition(TypeDefinition type) {
        if (type == null) {
            throw new IllegalArgumentException("Type is null!");
        }

        List<ValidationError> errors = new ArrayList<TypeUtils.ValidationError>();

        if (type.getId() == null || type.getId().length() == 0) {
            errors.add(new ValidationError("id", "Type id must be set."));
        }

        if (type.getLocalName() == null || type.getLocalName().length() == 0) {
            errors.add(new ValidationError("localName", "Local name must be set."));
        }

        if (type.getQueryName() != null) {
            if (type.getQueryName().length() == 0) {
                errors.add(new ValidationError("queryName", "Query name must not be empty."));
            } else if (!checkQueryName(type.getQueryName())) {
                errors.add(new ValidationError("queryName", "Query name contains invalid characters."));
            }
        }

        if (type.isCreatable() == null) {
            errors.add(new ValidationError("creatable", "Creatable flag must be set."));
        }

        if (type.isFileable() == null) {
            errors.add(new ValidationError("fileable", "Fileable flag must be set."));
        }

        if (type.isQueryable() == null) {
            errors.add(new ValidationError("queryable", "Queryable flag must be set."));
        } else if (type.isQueryable().booleanValue()) {
            if (type.getQueryName() == null || type.getQueryName().length() == 0) {
                errors.add(new ValidationError("queryable",
                        "Queryable flag is set to TRUE, but the query name is not set."));
            }
        }

        if (type.isControllablePolicy() == null) {
            errors.add(new ValidationError("controllablePolicy", "ControllablePolicy flag must be set."));
        } else if (type.getBaseTypeId() == BaseTypeId.CMIS_SECONDARY
                && Boolean.TRUE.equals(type.isControllablePolicy())) {
            errors.add(new ValidationError("controllablePolicy",
                    "ControllablePolicy flag must be FALSE for secondary types."));
        }

        if (type.isControllableAcl() == null) {
            errors.add(new ValidationError("controllableACL", "ControllableACL flag must be set."));
        } else if (type.getBaseTypeId() == BaseTypeId.CMIS_SECONDARY && Boolean.TRUE.equals(type.isControllableAcl())) {
            errors.add(
                    new ValidationError("controllableACL", "ControllableACL flag must be FALSE for secondary types."));
        }

        if (type.isFulltextIndexed() == null) {
            errors.add(new ValidationError("fulltextIndexed", "FulltextIndexed flag must be set."));
        }

        if (type.isIncludedInSupertypeQuery() == null) {
            errors.add(new ValidationError("includedInSupertypeQuery", "IncludedInSupertypeQuery flag must be set."));
        }

        if (type.getBaseTypeId() == null) {
            errors.add(new ValidationError("baseId", "Base type id must be set."));
        } else if (!type.getBaseTypeId().value().equals(type.getParentTypeId())) {
            if (type.getParentTypeId() == null || type.getParentTypeId().length() == 0) {
                errors.add(new ValidationError("parentId", "Parent type id must be set."));
            }
        }

        if (type instanceof DocumentTypeDefinition) {
            if (type.getBaseTypeId() != BaseTypeId.CMIS_DOCUMENT) {
                errors.add(new ValidationError("baseId", "Base type id does not match the type."));
            }

            DocumentTypeDefinition docType = (DocumentTypeDefinition) type;

            if (docType.isVersionable() == null) {
                errors.add(new ValidationError("versionable", "Versionable flag must be set."));
            }

            if (docType.getContentStreamAllowed() == null) {
                errors.add(new ValidationError("contentStreamAllowed", "ContentStreamAllowed flag must be set."));
            }

        } else if (type instanceof FolderTypeDefinition) {
            if (type.getBaseTypeId() != BaseTypeId.CMIS_FOLDER) {
                errors.add(new ValidationError("baseId", "Base type id does not match the type."));
            }

        } else if (type instanceof RelationshipTypeDefinition) {
            if (type.getBaseTypeId() != BaseTypeId.CMIS_RELATIONSHIP) {
                errors.add(new ValidationError("baseId", "Base type id does not match the type."));
            }

        } else if (type instanceof PolicyTypeDefinition) {
            if (type.getBaseTypeId() != BaseTypeId.CMIS_POLICY) {
                errors.add(new ValidationError("baseId", "Base type id does not match the type."));
            }

        } else if (type instanceof ItemTypeDefinition) {
            if (type.getBaseTypeId() != BaseTypeId.CMIS_ITEM) {
                errors.add(new ValidationError("baseId", "Base type id does not match the type."));
            }

        } else if (type instanceof SecondaryTypeDefinition) {
            if (type.getBaseTypeId() != BaseTypeId.CMIS_SECONDARY) {
                errors.add(new ValidationError("baseId", "Base type id does not match the type."));
            }

        } else {
            errors.add(new ValidationError("baseId", "Unknown base interface."));
        }

        return errors;
    }

    /**
     * Validates a property definition.
     * 
     * @return the list of validation errors
     */
    public static List<ValidationError> validatePropertyDefinition(PropertyDefinition<?> propDef) {
        if (propDef == null) {
            throw new IllegalArgumentException("Type is null!");
        }

        List<ValidationError> errors = new ArrayList<TypeUtils.ValidationError>();

        if (propDef.getId() == null || propDef.getId().length() == 0) {
            errors.add(new ValidationError("id", "Type id must be set."));
        }

        if (propDef.getQueryName() != null) {
            if (propDef.getQueryName().length() == 0) {
                errors.add(new ValidationError("queryName", "Query name must not be empty."));
            } else if (!checkQueryName(propDef.getQueryName())) {
                errors.add(new ValidationError("queryName", "Query name contains invalid characters."));
            }
        }

        if (propDef.getCardinality() == null) {
            errors.add(new ValidationError("cardinality", "Cardinality must be set."));
        }

        if (propDef.getUpdatability() == null) {
            errors.add(new ValidationError("updatability", "Updatability must be set."));
        }

        if (propDef.isInherited() == null) {
            errors.add(new ValidationError("inherited", "Inherited flag must be set."));
        }

        if (propDef.isRequired() == null) {
            errors.add(new ValidationError("required", "Required flag must be set."));
        }

        if (propDef.isQueryable() == null) {
            errors.add(new ValidationError("queryable", "Queryable flag must be set."));
        } else if (propDef.isQueryable().booleanValue()) {
            if (propDef.getQueryName() == null || propDef.getQueryName().length() == 0) {
                errors.add(new ValidationError("queryable",
                        "Queryable flag is set to TRUE, but the query name is not set."));
            }
        }

        if (propDef.isOrderable() == null) {
            errors.add(new ValidationError("orderable", "Orderable flag must be set."));
        } else if (propDef.isOrderable().booleanValue()) {
            if (propDef.getCardinality() == Cardinality.MULTI) {
                errors.add(
                        new ValidationError("orderable", "Orderable flag is set to TRUE for a multi-value property."));
            }
        }

        if (propDef.getPropertyType() == null) {
            errors.add(new ValidationError("propertyType", "Property type id must be set."));
        }

        if (propDef instanceof PropertyIdDefinition) {
            if (propDef.getPropertyType() != PropertyType.ID) {
                errors.add(
                        new ValidationError("propertyType", "Property type does not match the property definition."));
            }
        } else if (propDef instanceof PropertyStringDefinition) {
            if (propDef.getPropertyType() != PropertyType.STRING) {
                errors.add(
                        new ValidationError("propertyType", "Property type does not match the property definition."));
            }
        } else if (propDef instanceof PropertyIntegerDefinition) {
            if (propDef.getPropertyType() != PropertyType.INTEGER) {
                errors.add(
                        new ValidationError("propertyType", "Property type does not match the property definition."));
            }
        } else if (propDef instanceof PropertyDecimalDefinition) {
            if (propDef.getPropertyType() != PropertyType.DECIMAL) {
                errors.add(
                        new ValidationError("propertyType", "Property type does not match the property definition."));
            }
        } else if (propDef instanceof PropertyBooleanDefinition) {
            if (propDef.getPropertyType() != PropertyType.BOOLEAN) {
                errors.add(
                        new ValidationError("propertyType", "Property type does not match the property definition."));
            }
        } else if (propDef instanceof PropertyDateTimeDefinition) {
            if (propDef.getPropertyType() != PropertyType.DATETIME) {
                errors.add(
                        new ValidationError("propertyType", "Property type does not match the property definition."));
            }
        } else if (propDef instanceof PropertyHtmlDefinition) {
            if (propDef.getPropertyType() != PropertyType.HTML) {
                errors.add(
                        new ValidationError("propertyType", "Property type does not match the property definition."));
            }
        } else if (propDef instanceof PropertyUriDefinition) {
            if (propDef.getPropertyType() != PropertyType.URI) {
                errors.add(
                        new ValidationError("propertyType", "Property type does not match the property definition."));
            }
        }

        return errors;
    }

    public static class ValidationError {
        private final String attribute;
        private final String error;

        public ValidationError(String attribute, String error) {
            this.attribute = attribute;
            this.error = error;
        }

        public String getAttribute() {
            return attribute;
        }

        public String getError() {
            return error;
        }

        @Override
        public String toString() {
            return attribute + ": " + error;
        }
    }
}
