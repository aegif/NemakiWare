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
package org.apache.chemistry.opencmis.inmemory;

public final class ConfigConstants {
    public static final String REPOSITORY_ID = "InMemoryServer.RepositoryId";
    public static final String TYPE_XML = "InMemoryServer.TypeDefinitionsFile";
    public static final String TYPE_CREATOR_CLASS = "InMemoryServer.TypesCreatorClass";
    public static final String REPOSITORY_INFO_CREATOR_CLASS = "InMemoryServer.RepositoryInfoCreatorClass";
    public static final String REPOSITORY_CLASS = "InMemoryServer.Class";
    public static final String OVERRIDE_CALL_CONTEXT = "InMemoryServer.OverrideCallContext";
    public static final String MEMORY_THRESHOLD = "InMemoryServer.MemoryThreshold";
    public static final String TEMP_DIR = "InMemoryServer.TempDir";
    public static final String MAX_CONTENT_SIZE = "InMemoryServer.MaxContentSize";
    public static final String ENCRYPT_TEMP_FILES = "InMemoryServer.EncryptTempFiles";

    // Helper constants that allow to fill a repository with data on
    // initialization
    public static final String USE_REPOSITORY_FILER = "RepositoryFiller.Enable";
    public static final String FILLER_DOCUMENT_TYPE_ID = "RepositoryFiller.DocumentTypeId";
    public static final String FILLER_FOLDER_TYPE_ID = "RepositoryFiller.FolderTypeId";
    public static final String FILLER_DOCS_PER_FOLDER = "RepositoryFiller.DocsPerFolder";
    public static final String FILLER_FOLDERS_PER_FOLDER = "RepositoryFiller.FolderPerFolder";
    public static final String FILLER_DEPTH = "RepositoryFiller.Depth";
    public static final String FILLER_CONTENT_SIZE = "RepositoryFiller.ContentSizeInKB";
    public static final String FILLER_DOCUMENT_PROPERTY = "RepositoryFiller.DocumentProperty.";
    public static final String FILLER_FOLDER_PROPERTY = "RepositoryFiller.FolderProperty.";
    public static final String CONTENT_KIND = "RepositoryFiller.ContentKind";

    // runtime configuration values
    public static final String MAX_CONTENT_SIZE_KB = "InMemoryServer.MaxContentSizeKB";
    public static final String CLEAN_REPOSITORY_INTERVAL = "InMemoryServer.CleanIntervalMinutes";
    public static final String DEPLOYMENT_TIME = "InMemoryServer.DeploymentTime";
    public static final String PARSER_MODE = "InMemoryServer.ParserMode";

    private ConfigConstants() {
    }

}
