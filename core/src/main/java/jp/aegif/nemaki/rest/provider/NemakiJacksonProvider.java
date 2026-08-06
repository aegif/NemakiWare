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

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.springframework.stereotype.Component;

import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Provider;

/**
 * The mapper Jersey uses for {@code /core/rest/*} — deliberately still Jackson 2.
 *
 * <h2>Why this file did not migrate</h2>
 *
 * <p>Jersey's JSON support ({@code JacksonFeature} → {@code DefaultJacksonJaxbJsonProvider})
 * resolves the application's mapper with
 * {@code getContextResolver(com.fasterxml.jackson.databind.ObjectMapper.class, …)}. A
 * ContextResolver is matched by its generic type argument, so declaring
 * {@code ContextResolver<tools.jackson.databind.ObjectMapper>} compiles, wires, and is then
 * NEVER FOUND: Jersey silently falls back to its own stock Jackson 2 mapper and every
 * {@code /core/rest/*} response quietly loses this profile — including
 * {@code WRITE_NUMBERS_AS_STRINGS}, which is a wire contract for existing clients. The
 * Jackson 2 type IS the interface here; migrating it is not a rename but an unplugging.
 *
 * <h2>Why the profile is duplicated rather than injected</h2>
 *
 * <p>The Spring {@code ObjectMapper} bean is Jackson 3 now, so there is nothing to inject.
 * The settings below must mirror {@link jp.aegif.nemaki.config.ObjectMapperFactory}'s nemaki
 * profile, and {@code JerseyBoundaryMapperParityTest} fails if the two ever disagree about
 * what they emit — duplication that a test keeps honest, rather than duplication that drifts.
 */
@Provider
@Component
public class NemakiJacksonProvider implements ContextResolver<ObjectMapper> {

    private final ObjectMapper objectMapper;

    public NemakiJacksonProvider() {
        this.objectMapper = createJerseyObjectMapper();
    }

    /** The nemaki profile, expressed in Jackson 2 for the Jersey boundary. */
    public static ObjectMapper createJerseyObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        mapper.setVisibility(mapper.getSerializationConfig().getDefaultVisibilityChecker()
                .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                .withGetterVisibility(JsonAutoDetect.Visibility.PUBLIC_ONLY)
                .withSetterVisibility(JsonAutoDetect.Visibility.PUBLIC_ONLY)
                .withIsGetterVisibility(JsonAutoDetect.Visibility.PUBLIC_ONLY));
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.configure(JsonGenerator.Feature.WRITE_NUMBERS_AS_STRINGS, true);
        return mapper;
    }

    @Override
    public ObjectMapper getContext(Class<?> type) {
        return objectMapper;
    }
}
