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

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Jackson Configuration for NemakiWare
 *
 * This class provides unified Jackson ObjectMapper configuration for the entire application,
 * ensuring consistency between Spring Framework, Jersey JAX-RS, and direct usage.
 *
 * Key design decisions:
 * 1. Use Jackson's native ObjectMapper API directly (Spring 7 deprecated Jackson2ObjectMapperBuilder)
 * 2. Support both @JsonProperty annotations and field access
 * 3. Handle CouchDB/Cloudant specific serialization requirements
 * 4. Maintain backward compatibility with existing code
 */
@Configuration
public class JacksonConfig {

    /**
     * Primary ObjectMapper bean for application-wide use
     *
     * This configuration is optimized for NemakiWare's specific requirements:
     * - CouchDB/Cloudant document serialization
     * - Complex inheritance hierarchies (CouchNodeBase, CouchTypeDefinition)
     * - Mixed @JsonProperty and field access patterns
     * - CMIS data structure compatibility
     */
    @Bean
    @Primary
    public ObjectMapper nemakiObjectMapper() {
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
     * Specialized ObjectMapper for CouchDB/Cloudant operations
     *
     * This is used specifically in CloudantClientWrapper and other DAO components
     * where strict control over serialization is required.
     */
    @Bean("couchdbObjectMapper")
    public ObjectMapper couchdbObjectMapper() {
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
     * ObjectMapper for logging and debugging purposes
     *
     * This provides human-readable JSON output for development and debugging.
     */
    @Bean("debugObjectMapper")
    public ObjectMapper debugObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return mapper;
    }
}
