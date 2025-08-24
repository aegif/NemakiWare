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
package org.apache.chemistry.opencmis.fileshare;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.impl.server.AbstractServiceFactory;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.server.support.wrapper.CallContextAwareCmisService;
import org.apache.chemistry.opencmis.server.support.wrapper.CmisServiceWrapperManager;
import org.apache.chemistry.opencmis.server.support.wrapper.ConformanceCmisServiceWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FileShare Service Factory.
 */
public class FileShareCmisServiceFactory extends AbstractServiceFactory {

    private static final Logger LOG = LoggerFactory.getLogger(FileShareCmisServiceFactory.class);

    private static final String PREFIX_LOGIN = "login.";
    private static final String PREFIX_REPOSITORY = "repository.";
    private static final String PREFIX_TYPE = "type.";
    private static final String SUFFIX_READWRITE = ".readwrite";
    private static final String SUFFIX_READONLY = ".readonly";

    /** Default maxItems value for getTypeChildren()}. */
    private static final BigInteger DEFAULT_MAX_ITEMS_TYPES = BigInteger.valueOf(50);

    /** Default depth value for getTypeDescendants(). */
    private static final BigInteger DEFAULT_DEPTH_TYPES = BigInteger.valueOf(-1);

    /**
     * Default maxItems value for getChildren() and other methods returning
     * lists of objects.
     */
    private static final BigInteger DEFAULT_MAX_ITEMS_OBJECTS = BigInteger.valueOf(200);

    /** Default depth value for getDescendants(). */
    private static final BigInteger DEFAULT_DEPTH_OBJECTS = BigInteger.valueOf(10);

    /** Each thread gets its own {@link FileShareCmisService} instance. */
    private ThreadLocal<CallContextAwareCmisService> threadLocalService = new ThreadLocal<CallContextAwareCmisService>();

    private FileShareRepositoryManager repositoryManager;
    private FileShareUserManager userManager;
    private FileShareTypeManager typeManager;
    private CmisServiceWrapperManager wrapperManager;

    public FileShareRepositoryManager getRepositoryManager() {
        return repositoryManager;
    }

    public FileShareUserManager getUserManager() {
        return userManager;
    }

    public FileShareTypeManager getTypeManager() {
        return typeManager;
    }

    @Override
    public void init(Map<String, String> parameters) {
        repositoryManager = new FileShareRepositoryManager();
        userManager = new FileShareUserManager();
        typeManager = new FileShareTypeManager();

        wrapperManager = new CmisServiceWrapperManager();
        wrapperManager.addWrappersFromServiceFactoryParameters(parameters);
        wrapperManager.addOuterWrapper(ConformanceCmisServiceWrapper.class, DEFAULT_MAX_ITEMS_TYPES,
                DEFAULT_DEPTH_TYPES, DEFAULT_MAX_ITEMS_OBJECTS, DEFAULT_DEPTH_OBJECTS);

        readConfiguration(parameters);
    }

    @Override
    public void destroy() {
        threadLocalService = null;
    }

    @Override
    public CmisService getService(CallContext context) {
        // authenticate the user
        // if the authentication fails, authenticate() throws a
        // CmisPermissionDeniedException
        userManager.authenticate(context);

        // get service object for this thread
        CallContextAwareCmisService service = threadLocalService.get();
        if (service == null) {
            // there is no service object for this thread -> create one
            FileShareCmisService fileShareService = new FileShareCmisService(repositoryManager);

            service = (CallContextAwareCmisService) wrapperManager.wrap(fileShareService);

            threadLocalService.set(service);
        }

        // hand over the call context to the service object
        service.setCallContext(context);

        return service;
    }

    // ---- helpers ----

    /**
     * Reads the configuration and sets up the repositories, logins, and type
     * definitions.
     */
    private void readConfiguration(Map<String, String> parameters) {
        List<String> keys = new ArrayList<String>(parameters.keySet());
        Collections.sort(keys);

        for (String key : keys) {
            if (key.startsWith(PREFIX_LOGIN)) {
                // get logins
                String usernameAndPassword = replaceSystemProperties(parameters.get(key));
                if (usernameAndPassword == null) {
                    continue;
                }

                String username = usernameAndPassword;
                String password = "";

                int x = usernameAndPassword.indexOf(':');
                if (x > -1) {
                    username = usernameAndPassword.substring(0, x);
                    password = usernameAndPassword.substring(x + 1);
                }

                LOG.info("Adding login '{}'.", username);

                userManager.addLogin(username, password);
            } else if (key.startsWith(PREFIX_TYPE)) {
                // load type definition
                String typeFile = replaceSystemProperties(parameters.get(key).trim());
                if (typeFile.length() == 0) {
                    continue;
                }

                LOG.info("Loading type definition: {}", typeFile);

                if (typeFile.charAt(0) == '/') {
                    try {
                        typeManager.loadTypeDefinitionFromResource(typeFile);
                        continue;
                    } catch (IllegalArgumentException e) {
                        // resource not found -> try it as a regular file
                    } catch (Exception e) {
                        LOG.warn("Could not load type defintion from resource '{}': {}", typeFile, e.getMessage(), e);
                        continue;
                    }
                }

                try {
                    typeManager.loadTypeDefinitionFromFile(typeFile);
                } catch (Exception e) {
                    LOG.warn("Could not load type defintion from file '{}': {}", typeFile, e.getMessage(), e);
                }
            } else if (key.startsWith(PREFIX_REPOSITORY)) {
                // configure repositories
                String repositoryId = key.substring(PREFIX_REPOSITORY.length()).trim();
                int x = repositoryId.lastIndexOf('.');
                if (x > 0) {
                    repositoryId = repositoryId.substring(0, x);
                }

                if (repositoryId.length() == 0) {
                    throw new IllegalArgumentException("No repository id!");
                }

                if (key.endsWith(SUFFIX_READWRITE)) {
                    // read-write users
                    FileShareRepository fsr = repositoryManager.getRepository(repositoryId);
                    for (String user : split(parameters.get(key))) {
                        fsr.setUserReadWrite(replaceSystemProperties(user));
                    }
                } else if (key.endsWith(SUFFIX_READONLY)) {
                    // read-only users
                    FileShareRepository fsr = repositoryManager.getRepository(repositoryId);
                    for (String user : split(parameters.get(key))) {
                        fsr.setUserReadOnly(replaceSystemProperties(user));
                    }
                } else {
                    // new repository
                    String root = replaceSystemProperties(parameters.get(key));

                    LOG.info("Adding repository '{}': {}", repositoryId, root);

                    FileShareRepository fsr = new FileShareRepository(repositoryId, root, typeManager);
                    repositoryManager.addRepository(fsr);
                }
            }
        }
    }

    /**
     * Splits a string by comma.
     */
    private List<String> split(String csl) {
        if (csl == null) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<String>();
        for (String s : csl.split(",")) {
            result.add(s.trim());
        }

        return result;
    }

    /**
     * Finds all substrings in curly braces and replaces them with the value of
     * the corresponding system property.
     */
    private String replaceSystemProperties(String s) {
        if (s == null) {
            return null;
        }

        StringBuilder result = new StringBuilder(128);
        StringBuilder property = null;
        boolean inProperty = false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (inProperty) {
                if (c == '}') {
                    String value = System.getProperty(property.toString());
                    if (value != null) {
                        result.append(value);
                    }
                    inProperty = false;
                } else {
                    property.append(c);
                }
            } else {
                if (c == '{') {
                    property = new StringBuilder(32);
                    inProperty = true;
                } else {
                    result.append(c);
                }
            }
        }

        return result.toString();
    }

}
