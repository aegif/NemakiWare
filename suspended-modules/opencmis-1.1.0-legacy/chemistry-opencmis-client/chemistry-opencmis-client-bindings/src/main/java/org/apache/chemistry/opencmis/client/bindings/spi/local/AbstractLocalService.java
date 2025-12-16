/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.chemistry.opencmis.client.bindings.spi.local;

import org.apache.chemistry.opencmis.client.bindings.spi.BindingSession;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.server.CmisServiceFactory;
import org.apache.chemistry.opencmis.commons.server.ProgressControlCmisService;
import org.apache.chemistry.opencmis.commons.server.ProgressControlCmisService.Progress;

/**
 * Base class for all local clients.
 */
public abstract class AbstractLocalService {

    private BindingSession session;
    private CmisServiceFactory factory;

    private String user;
    private String password;
    private String language;
    private String country;

    /**
     * Sets the current session.
     */
    protected void setSession(BindingSession session) {
        this.session = session;

        Object userObj = session.get(SessionParameter.USER);
        user = userObj instanceof String ? userObj.toString() : null;

        Object passwordObj = session.get(SessionParameter.PASSWORD);
        password = passwordObj instanceof String ? passwordObj.toString() : null;

        Object localeLanguageObj = session.get(SessionParameter.LOCALE_ISO639_LANGUAGE);
        language = localeLanguageObj instanceof String ? localeLanguageObj.toString() : null;

        Object localeCountryObj = session.get(SessionParameter.LOCALE_ISO3166_COUNTRY);
        country = localeCountryObj instanceof String ? localeCountryObj.toString() : null;
    }

    /**
     * Gets the current session.
     */
    protected BindingSession getSession() {
        return session;
    }

    /**
     * Sets the service factory.
     */
    protected void setServiceFactory(CmisServiceFactory factory) {
        this.factory = factory;
    }

    /**
     * Gets the service factory.
     */
    protected CmisServiceFactory getServiceFactory() {
        return factory;
    }

    /**
     * Determines if the processing should be stopped before the service method
     * is called.
     * 
     * @return {@code true} if the processing should be stopped, {@code false}
     *         otherwise
     */
    protected boolean stopBeforeService(CmisService service) {
        if (!(service instanceof ProgressControlCmisService)) {
            return false;
        }

        return ((ProgressControlCmisService) service).beforeServiceCall() == Progress.STOP;
    }

    /**
     * Determines if the processing should be stopped after the service method
     * is called.
     * 
     * @return {@code true} if the processing should be stopped, {@code false}
     *         otherwise
     */
    protected boolean stopAfterService(CmisService service) {
        if (!(service instanceof ProgressControlCmisService)) {
            return false;
        }

        return ((ProgressControlCmisService) service).afterServiceCall() == Progress.STOP;
    }

    /**
     * creates a local call context.
     */
    protected CallContext createCallContext(String repositoryId) {
        return new LocalCallContext(repositoryId, user, password, language, country);
    }

    protected CmisService getService(String repositoryId) {
        return factory.getService(createCallContext(repositoryId));
    }
}
