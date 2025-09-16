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
package jp.aegif.nemaki.rest.provider;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Provider;

/**
 * Jersey JAX-RS provider for unified ObjectMapper configuration
 * 
 * This provider ensures that Jersey uses the same ObjectMapper configuration
 * as the rest of the Spring application, maintaining consistency across
 * all JSON serialization/deserialization operations.
 * 
 * The provider integrates with Spring's dependency injection to obtain
 * the configured ObjectMapper from JacksonConfig.
 */
@Provider
// @Component annotation removed to prevent conflicts with component scanning exclusion in applicationContext.xml
public class NemakiJacksonProvider implements ContextResolver<ObjectMapper> {

    private final ObjectMapper objectMapper;

    /**
     * Constructor with Spring dependency injection
     * 
     * @param objectMapper The primary ObjectMapper bean from JacksonConfig
     */
    @Autowired
    public NemakiJacksonProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Provides the ObjectMapper instance for Jersey to use
     * 
     * @param type The class type being serialized/deserialized
     * @return The configured ObjectMapper instance
     */
    @Override
    public ObjectMapper getContext(Class<?> type) {
        return objectMapper;
    }
}
