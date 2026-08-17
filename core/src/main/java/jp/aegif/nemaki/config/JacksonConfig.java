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
 ******************************************************************************/
package jp.aegif.nemaki.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import tools.jackson.databind.ObjectMapper;

/**
 * Spring bean definitions for the mapper profiles.
 *
 * <p>Thin on purpose: every profile is defined ONCE, in {@link ObjectMapperFactory}. This
 * class used to carry its own near-identical copies of each configuration, and the copies
 * had already drifted (the factory set visibility rules the beans lacked). Delegation is the
 * fix that stays fixed.
 */
@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper nemakiObjectMapper() {
        return ObjectMapperFactory.createNemakiObjectMapper();
    }

    @Bean("couchdbObjectMapper")
    public ObjectMapper couchdbObjectMapper() {
        return ObjectMapperFactory.createCouchdbObjectMapper();
    }

    @Bean("debugObjectMapper")
    public ObjectMapper debugObjectMapper() {
        return ObjectMapperFactory.createDebugObjectMapper();
    }
}
