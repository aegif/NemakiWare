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
package org.apache.chemistry.opencmis.inmemory.server;

import static org.apache.chemistry.opencmis.commons.impl.XMLUtils.next;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.CmisVersion;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.commons.impl.XMLConverter;
import org.apache.chemistry.opencmis.commons.impl.XMLUtils;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractTypeDefinition;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.BindingsObjectFactoryImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.server.CmisService;
import org.apache.chemistry.opencmis.commons.spi.BindingsObjectFactory;
import org.apache.chemistry.opencmis.inmemory.ConfigConstants;
import org.apache.chemistry.opencmis.inmemory.ConfigurationSettings;
import org.apache.chemistry.opencmis.inmemory.content.ObjectGenerator;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.ObjectStore;
import org.apache.chemistry.opencmis.inmemory.storedobj.api.StoreManager;
import org.apache.chemistry.opencmis.inmemory.storedobj.impl.StoreManagerFactory;
import org.apache.chemistry.opencmis.inmemory.storedobj.impl.StoreManagerImpl;
import org.apache.chemistry.opencmis.server.async.impl.AbstractAsyncServiceFactory;
import org.apache.chemistry.opencmis.server.support.TypeManager;
import org.apache.chemistry.opencmis.server.support.wrapper.ConformanceCmisServiceWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InMemoryServiceFactoryImpl extends AbstractAsyncServiceFactory {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryServiceFactoryImpl.class.getName());
    private static final BigInteger DEFAULT_MAX_ITEMS_OBJECTS = BigInteger.valueOf(1000);
    private static final BigInteger DEFAULT_MAX_ITEMS_TYPES = BigInteger.valueOf(100);
    private static final BigInteger DEFAULT_DEPTH_OBJECTS = BigInteger.valueOf(2);
    private static final BigInteger DEFAULT_DEPTH_TYPES = BigInteger.valueOf(-1);
    private static CallContext overrideCtx;

    private boolean fUseOverrideCtx = false;
    private StoreManager storeManager; // singleton root of everything
    private CleanManager cleanManager = null;

    private File tempDir;
    private int memoryThreshold;
    private long maxContentSize;
    private boolean encrypt;

    @Override
    public void init(Map<String, String> parameters) {
        LOG.info("Initializing in-memory repository...");
        LOG.debug("Init paramaters: " + parameters);

        super.init(parameters);

        String overrideCtxParam = parameters.get(ConfigConstants.OVERRIDE_CALL_CONTEXT);
        if (null != overrideCtxParam) {
            fUseOverrideCtx = true;
        }

        ConfigurationSettings.init(parameters);

        String repositoryClassName = parameters.get(ConfigConstants.REPOSITORY_CLASS);
        if (null == repositoryClassName) {
            repositoryClassName = StoreManagerImpl.class.getName();
        }

        if (null == storeManager) {
            storeManager = StoreManagerFactory.createInstance(repositoryClassName);
        }

        String tempDirStr = parameters.get(ConfigConstants.TEMP_DIR);
        tempDir = (tempDirStr == null ? super.getTempDirectory() : new File(tempDirStr));

        String memoryThresholdStr = parameters.get(ConfigConstants.MEMORY_THRESHOLD);
        memoryThreshold = (memoryThresholdStr == null ? super.getMemoryThreshold() : Integer
                .parseInt(memoryThresholdStr));

        String maxContentSizeStr = parameters.get(ConfigConstants.MAX_CONTENT_SIZE);
        maxContentSize = (maxContentSizeStr == null ? super.getMaxContentSize() : Long.parseLong(maxContentSizeStr));

        String encryptTempFilesStr = parameters.get(ConfigConstants.ENCRYPT_TEMP_FILES);
        encrypt = (encryptTempFilesStr == null ? super.encryptTempFiles() : Boolean.parseBoolean(encryptTempFilesStr));

        Date deploymentTime = new Date();
        String strDate = new SimpleDateFormat("EEE MMM dd hh:mm:ss a z yyyy", Locale.US).format(deploymentTime);

        parameters.put(ConfigConstants.DEPLOYMENT_TIME, strDate);

        boolean created = initStorageManager(parameters);

        if (created) {
            fillRepositoryIfConfigured(parameters);
        }

        Long cleanInterval = ConfigurationSettings
                .getConfigurationValueAsLong(ConfigConstants.CLEAN_REPOSITORY_INTERVAL);
        if (null != cleanInterval && cleanInterval > 0) {
            scheduleCleanRepositoryJob(cleanInterval);
        }

        LOG.info("...initialized in-memory repository.");
    }

    public static void setOverrideCallContext(CallContext ctx) {
        overrideCtx = ctx;
    }

    @Override
    public CmisService getService(CallContext context) {
        LOG.debug("start getService()");
        CallContext contextToUse = context;
        // Some unit tests set their own context. So if we find one then we use
        // this one and ignore the provided one. Otherwise we set a new context.
        if (fUseOverrideCtx && null != overrideCtx) {
            contextToUse = overrideCtx;
        }

        LOG.debug("Creating new InMemoryService instance!");
        ConformanceCmisServiceWrapper wrapperService;
        InMemoryService inMemoryService = new InMemoryService(storeManager, contextToUse);
        wrapperService = new ConformanceCmisServiceWrapper(inMemoryService, DEFAULT_MAX_ITEMS_TYPES,
                DEFAULT_DEPTH_TYPES, DEFAULT_MAX_ITEMS_OBJECTS, DEFAULT_DEPTH_OBJECTS);

        return inMemoryService; // wrapperService;
    }

    @Override
    public File getTempDirectory() {
        return tempDir;
    }

    @Override
    public boolean encryptTempFiles() {
        return encrypt;
    }

    @Override
    public int getMemoryThreshold() {
        return memoryThreshold;
    }

    @Override
    public long getMaxContentSize() {
        return maxContentSize;
    }

    @Override
    public void destroy() {
        LOG.debug("Destroying InMemory service instance.");
        if (null != cleanManager) {
            cleanManager.stopCleanRepositoryJob();
        }

        super.destroy();
    }

    public StoreManager getStoreManger() {
        return storeManager;
    }

    private boolean initStorageManager(Map<String, String> parameters) {
        // initialize in-memory management
        boolean created = false;
        String repositoryClassName = parameters.get(ConfigConstants.REPOSITORY_CLASS);
        if (null == repositoryClassName) {
            repositoryClassName = StoreManagerImpl.class.getName();
        }

        if (null == storeManager) {
            storeManager = StoreManagerFactory.createInstance(repositoryClassName);
        }

        String repositoryId = parameters.get(ConfigConstants.REPOSITORY_ID);

        List<String> allAvailableRepositories = storeManager.getAllRepositoryIds();

        // init existing repositories
        for (String existingRepId : allAvailableRepositories) {
            storeManager.initRepository(existingRepId);
        }

        // create repository if configured as a startup parameter
        if (null != repositoryId) {
            if (allAvailableRepositories.contains(repositoryId)) {
                LOG.warn("Repostory " + repositoryId + " already exists and will not be created.");
            } else {
                String typeCreatorClassName = parameters.get(ConfigConstants.TYPE_CREATOR_CLASS);
                storeManager.createAndInitRepository(repositoryId, typeCreatorClassName);
                created = true;
            }
        }

        // check if a type definitions XML file is configured. if yes import
        // type definitions
        String typeDefsFileName = parameters.get(ConfigConstants.TYPE_XML);
        if (null == typeDefsFileName) {
            LOG.info("No file name for type definitions given, no types will be created.");
        } else {
            TypeManager typeManager = storeManager.getTypeManager(repositoryId);
            TypeManager tmc = typeManager;
            importTypesFromFile(tmc, typeDefsFileName);
        }
        
        // check if relaxed parser mode is configured (only for unit tests)
        String parserMode = parameters.get(ConfigConstants.PARSER_MODE);
        if (null != parserMode) {
        	storeManager.addFlag(parserMode);
        }
        return created;
    }

    private void importTypesFromFile(TypeManager tmc, String typeDefsFileName) {

        BufferedInputStream stream = null;
        TypeDefinition typeDef = null;
        File f = new File(typeDefsFileName);
        InputStream typesStream = null;

        if (!f.isFile()) {
            typesStream = this.getClass().getResourceAsStream("/" + typeDefsFileName);
        } else if (f.canRead()) {
            try {
                typesStream = new FileInputStream(f);
            } catch (Exception e) {
                LOG.error("Could not load type definitions from file '" + typeDefsFileName + "': " + e);
            }
        }

        if (typesStream == null) {
            LOG.warn("Resource file with type definitions " + typeDefsFileName
                    + " could not be found, no types will be created.");
            return;
        }

        try {
            stream = new BufferedInputStream(typesStream);
            XMLStreamReader parser = XMLUtils.createParser(stream);
            XMLUtils.findNextStartElemenet(parser);

            // walk through all nested tags in top element
            while (true) {
                int event = parser.getEventType();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    QName name = parser.getName();
                    if (name.getLocalPart().equals("type")) {
                        typeDef = XMLConverter.convertTypeDefinition(parser);
                        LOG.debug("Found type in file: " + typeDef.getLocalName());
                        if (typeDef.getPropertyDefinitions() == null) {
                            ((AbstractTypeDefinition) typeDef)
                                    .setPropertyDefinitions(new LinkedHashMap<String, PropertyDefinition<?>>());
                        }
                        tmc.addTypeDefinition(typeDef, true);
                    }
                    XMLUtils.next(parser);
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    break;
                } else {
                    if (!next(parser)) {
                        break;
                    }
                }
            }
            parser.close();
        } catch (Exception e) {
            LOG.error("Could not load type definitions from file '" + typeDefsFileName + "': " + e);
        } finally {
            IOUtils.closeQuietly(stream);
        }
    }

    private static List<String> readPropertiesToSetFromConfig(Map<String, String> parameters, String keyPrefix) {
        List<String> propsToSet = new ArrayList<String>();
        for (int i = 0;; ++i) {
            String propertyKey = keyPrefix + Integer.toString(i);
            String propertyToAdd = parameters.get(propertyKey);
            if (null == propertyToAdd) {
                break;
            } else {
                propsToSet.add(propertyToAdd);
            }
        }
        return propsToSet;
    }

    private void fillRepositoryIfConfigured(Map<String, String> parameters) {

        class DummyCallContext implements CallContext {

            @Override
            public String get(String key) {
                return null;
            }

            @Override
            public String getBinding() {
                return null;
            }

            @Override
            public boolean isObjectInfoRequired() {
                return false;
            }

            @Override
            public CmisVersion getCmisVersion() {
                return CmisVersion.CMIS_1_1;
            }

            @Override
            public String getRepositoryId() {
                return null;
            }

            @Override
            public String getLocale() {
                return null;
            }

            @Override
            public BigInteger getOffset() {
                return null;
            }

            @Override
            public BigInteger getLength() {
                return null;
            }

            @Override
            public String getPassword() {
                return null;
            }

            @Override
            public String getUsername() {
                return null;
            }

            @Override
            public File getTempDirectory() {

                return tempDir;
            }

            @Override
            public boolean encryptTempFiles() {
                return encrypt;
            }

            @Override
            public int getMemoryThreshold() {
                return memoryThreshold;
            }

            @Override
            public long getMaxContentSize() {
                return maxContentSize;
            }
        }

        String repositoryId = parameters.get(ConfigConstants.REPOSITORY_ID);
        String doFillRepositoryStr = parameters.get(ConfigConstants.USE_REPOSITORY_FILER);
        String contentKindStr = parameters.get(ConfigConstants.CONTENT_KIND);
        boolean doFillRepository = doFillRepositoryStr == null ? false : Boolean.parseBoolean(doFillRepositoryStr);
        // Simulate a runtime context with configuration parameters
        // Attach the CallContext to a thread local context that can be
        // accessed from everywhere
        DummyCallContext ctx = new DummyCallContext();

        if (doFillRepository) {

            // create an initial temporary service instance to fill the
            // repository

            InMemoryService svc = new InMemoryService(storeManager, ctx);

            BindingsObjectFactory objectFactory = new BindingsObjectFactoryImpl();

            String levelsStr = parameters.get(ConfigConstants.FILLER_DEPTH);
            int levels = 1;
            if (null != levelsStr) {
                levels = Integer.parseInt(levelsStr);
            }

            String docsPerLevelStr = parameters.get(ConfigConstants.FILLER_DOCS_PER_FOLDER);
            int docsPerLevel = 1;
            if (null != docsPerLevelStr) {
                docsPerLevel = Integer.parseInt(docsPerLevelStr);
            }

            String childrenPerLevelStr = parameters.get(ConfigConstants.FILLER_FOLDERS_PER_FOLDER);
            int childrenPerLevel = 2;
            if (null != childrenPerLevelStr) {
                childrenPerLevel = Integer.parseInt(childrenPerLevelStr);
            }

            String documentTypeId = parameters.get(ConfigConstants.FILLER_DOCUMENT_TYPE_ID);
            if (null == documentTypeId) {
                documentTypeId = BaseTypeId.CMIS_DOCUMENT.value();
            }

            String folderTypeId = parameters.get(ConfigConstants.FILLER_FOLDER_TYPE_ID);
            if (null == folderTypeId) {
                folderTypeId = BaseTypeId.CMIS_FOLDER.value();
            }

            int contentSizeKB = 0;
            String contentSizeKBStr = parameters.get(ConfigConstants.FILLER_CONTENT_SIZE);
            if (null != contentSizeKBStr) {
                contentSizeKB = Integer.parseInt(contentSizeKBStr);
            }

            ObjectGenerator.ContentKind contentKind;
            if (null == contentKindStr) {
                contentKind = ObjectGenerator.ContentKind.LOREM_IPSUM_TEXT;
            } else {
                if (contentKindStr.equals("static/text")) {
                    contentKind = ObjectGenerator.ContentKind.STATIC_TEXT;
                } else if (contentKindStr.equals("lorem/text")) {
                    contentKind = ObjectGenerator.ContentKind.LOREM_IPSUM_TEXT;
                } else if (contentKindStr.equals("lorem/html")) {
                    contentKind = ObjectGenerator.ContentKind.LOREM_IPSUM_HTML;
                } else if (contentKindStr.equals("fractal/jpeg")) {
                    contentKind = ObjectGenerator.ContentKind.IMAGE_FRACTAL_JPEG;
                } else {
                    contentKind = ObjectGenerator.ContentKind.STATIC_TEXT;
                }
            }
            // Create a hierarchy of folders and fill it with some documents
            ObjectGenerator gen = new ObjectGenerator(objectFactory, svc, svc, svc, repositoryId, contentKind);

            gen.setNumberOfDocumentsToCreatePerFolder(docsPerLevel);

            // Set the type id for all created documents:
            gen.setDocumentTypeId(documentTypeId);

            // Set the type id for all created folders:
            gen.setFolderTypeId(folderTypeId);

            // Set contentSize
            gen.setContentSizeInKB(contentSizeKB);

            // set properties that need to be filled
            // set the properties the generator should fill with values for
            // documents:
            // Note: must be valid properties in configured document and folder
            // type

            List<String> propsToSet = readPropertiesToSetFromConfig(parameters,
                    ConfigConstants.FILLER_DOCUMENT_PROPERTY);
            if (null != propsToSet) {
                gen.setDocumentPropertiesToGenerate(propsToSet);
            }

            propsToSet = readPropertiesToSetFromConfig(parameters, ConfigConstants.FILLER_FOLDER_PROPERTY);
            if (null != propsToSet) {
                gen.setFolderPropertiesToGenerate(propsToSet);
            }

            // create thread local storage and attach call context
            getService(ctx);

            // Build the tree
            RepositoryInfo rep = svc.getRepositoryInfo(repositoryId, null);
            String rootFolderId = rep.getRootFolderId();

            try {
                gen.createFolderHierachy(levels, childrenPerLevel, rootFolderId);
                // Dump the tree
                gen.dumpFolder(rootFolderId, "*");
            } catch (Exception e) {
                LOG.error("Could not create folder hierarchy with documents. ", e);
            }
            svc.close();
        } // if

    } // fillRepositoryIfConfigured

    class CleanManager {

        private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        private ScheduledFuture<?> cleanerHandle = null;

        public void startCleanRepositoryJob(long intervalInMinutes) {

            final Runnable cleaner = new Runnable() {
                @Override
                public void run() {
                    LOG.info("Cleaning repository as part of a scheduled maintenance job.");
                    for (String repositoryId : storeManager.getAllRepositoryIds()) {
                        ObjectStore store = storeManager.getObjectStore(repositoryId);
                        store.clear();
                        fillRepositoryIfConfigured(ConfigurationSettings.getParameters());
                    }
                    LOG.info("Repository cleaned. Freeing memory.");
                    System.gc();
                }
            };

            LOG.info("Repository Clean Job starting clean job, interval " + intervalInMinutes + " min");
            cleanerHandle = scheduler.scheduleAtFixedRate(cleaner, intervalInMinutes, intervalInMinutes,
                    TimeUnit.MINUTES);
        }

        public void stopCleanRepositoryJob() {
            LOG.info("Repository Clean Job cancelling clean job.");
            boolean ok = cleanerHandle.cancel(true);
            LOG.info("Repository Clean Job cancelled with result: " + ok);
            scheduler.shutdownNow();
        }
    }

    private void scheduleCleanRepositoryJob(long minutes) {
        cleanManager = new CleanManager();
        cleanManager.startCleanRepositoryJob(minutes);
    }

}
