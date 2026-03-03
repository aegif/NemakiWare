/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Static Factory for ObjectMapper instances used throughout NemakiWare
 *
 * This factory provides static methods to create ObjectMapper instances with
 * consistent configuration for different use cases. It's designed to work with
 * both Spring annotations (@Configuration) and XML-based configuration.
 *
 * The factory ensures that all ObjectMapper instances share the same core
 * configuration patterns while allowing for specialized use cases.
 */
public class ObjectMapperFactory {

    /**
     * Create the primary ObjectMapper for application-wide use
     *
     * This configuration is optimized for NemakiWare's specific requirements:
     * - CouchDB/Cloudant document serialization
     * - Complex inheritance hierarchies (CouchNodeBase, CouchTypeDefinition)
     * - Mixed @JsonProperty and field access patterns
     * - CMIS data structure compatibility
     */
    public static ObjectMapper createNemakiObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // Basic configuration
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        mapper.configure(JsonGenerator.Feature.WRITE_NUMBERS_AS_STRINGS, true);

        // Visibility configuration - CRITICAL for CouchTypeDefinition
        // Enable both field and getter/setter access to support mixed patterns
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.PUBLIC_ONLY);
        mapper.setVisibility(PropertyAccessor.SETTER, JsonAutoDetect.Visibility.PUBLIC_ONLY);
        mapper.setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.PUBLIC_ONLY);

        // CouchDB specific settings
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        return mapper;
    }

    /**
     * Create specialized ObjectMapper for CouchDB/Cloudant operations
     *
     * This is used specifically in CloudantClientWrapper and other DAO components
     * where strict control over serialization is required.
     */
    public static ObjectMapper createCouchdbObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // Core configuration
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

        // CRITICAL: Enable all access methods to ensure @JsonProperty works
        // This fixes the CouchTypeDefinition properties field serialization issue
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.PUBLIC_ONLY);
        mapper.setVisibility(PropertyAccessor.SETTER, JsonAutoDetect.Visibility.PUBLIC_ONLY);
        mapper.setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.PUBLIC_ONLY);

        // Ensure null values are not serialized to reduce document size
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        return mapper;
    }

    /**
     * Create ObjectMapper for logging and debugging purposes
     *
     * This provides human-readable JSON output for development and debugging.
     */
    public static ObjectMapper createDebugObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return mapper;
    }
}
