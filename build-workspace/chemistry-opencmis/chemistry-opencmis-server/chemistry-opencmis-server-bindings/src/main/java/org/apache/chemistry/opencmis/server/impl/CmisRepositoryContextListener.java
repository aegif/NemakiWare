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
package org.apache.chemistry.opencmis.server.impl;

import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

import org.apache.chemistry.opencmis.commons.impl.ClassLoaderUtil;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.server.CmisServiceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CMIS context listener.
 */
public class CmisRepositoryContextListener implements ServletContextListener {

    public static final String SERVICES_FACTORY = "org.apache.chemistry.opencmis.servicesfactory";

    private static final Logger LOG = LoggerFactory.getLogger(CmisRepositoryContextListener.class.getName());

    private static final String CONFIG_INIT_PARAM = "org.apache.chemistry.opencmis.REPOSITORY_CONFIG_FILE";
    private static final String CONFIG_FILENAME = "/repository.properties";
    private static final String PROPERTY_CLASS = "class";

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        // get config file name or use default
        String configFilename = sce.getServletContext().getInitParameter(CONFIG_INIT_PARAM);
        if (configFilename == null) {
            configFilename = CONFIG_FILENAME;
        }

        // create services factory
        CmisServiceFactory factory = null;
        try {
            factory = createServiceFactory(configFilename);
        } catch (Exception e) {
            LOG.error("Service factory couldn't be created: {}", e.toString(), e);
            return;
        }

        // set the services factory into the servlet context
        sce.getServletContext().setAttribute(SERVICES_FACTORY, factory);
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        // destroy services factory
        CmisServiceFactory factory = (CmisServiceFactory) sce.getServletContext().getAttribute(SERVICES_FACTORY);
        if (factory != null) {
            try {
                factory.destroy();
            } catch (Exception e) {
                LOG.error("Service factory couldn't be destroyed: {}", e.toString(), e);
                return;
            }
        }
    }

    /**
     * Gets the service factory from the servlet context.
     */
    public static CmisServiceFactory getServiceFactory(final ServletContext servletContext) {
        return (CmisServiceFactory) servletContext.getAttribute(SERVICES_FACTORY);
    }

    /**
     * Creates a service factory.
     */
    private CmisServiceFactory createServiceFactory(String filename) {
        // load properties
        InputStream stream = this.getClass().getResourceAsStream(filename);

        if (stream == null) {
            LOG.warn("Cannot find configuration!");
            return null;
        }

        Properties props = new Properties();
        try {
            props.load(stream);
        } catch (IOException e) {
            LOG.warn("Cannot load configuration: {}", e.toString(), e);
            return null;
        } finally {
            IOUtils.closeQuietly(stream);
        }

        // get 'class' property
        String className = props.getProperty(PROPERTY_CLASS);
        if (className == null) {
            LOG.warn("Configuration doesn't contain the property 'class'!");
            return null;
        }

        // create a factory instance
        Object object = null;
        try {
            object = ClassLoaderUtil.loadClass(className).getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            LOG.warn("Could not create a services factory instance: {}", e.toString(), e);
            return null;
        }

        if (!(object instanceof CmisServiceFactory)) {
            LOG.warn("The provided class is not an instance of CmisServiceFactory!");
        }

        CmisServiceFactory factory = (CmisServiceFactory) object;

        // initialize factory instance
        Map<String, String> parameters = new HashMap<String, String>(props.size());

        for (Enumeration<?> e = props.propertyNames(); e.hasMoreElements();) {
            String key = (String) e.nextElement();
            String value = props.getProperty(key);
            parameters.put(key, value);
        }

        factory.init(parameters);

        if (LOG.isInfoEnabled()) {
            LOG.info("Initialized Services Factory: {}", factory.getClass().getName());
        }

        return factory;
    }
}
