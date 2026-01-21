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
package org.apache.chemistry.opencmis.client.bindings.framework;

import java.util.List;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderContainer;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;

public class Tools {

    private Tools() {
    }

    public static void print(RepositoryInfo repositoryInfo) {
        if (repositoryInfo == null) {
            return;
        }

        System.out.println("-------------");
        System.out.println("Id:               " + repositoryInfo.getId());
        System.out.println("Name:             " + repositoryInfo.getName());
        System.out.println("CMIS Version:     " + repositoryInfo.getCmisVersionSupported());
        System.out.println("Product:          " + repositoryInfo.getVendorName() + " / "
                + repositoryInfo.getProductName() + " " + repositoryInfo.getProductVersion());
        System.out.println("Root Folder:      " + repositoryInfo.getRootFolderId());
        System.out.println("Capabilities:     " + repositoryInfo.getCapabilities());
        System.out.println("ACL Capabilities: " + repositoryInfo.getAclCapabilities());
        System.out.println("-------------");
    }

    public static void printTypes(String title, List<TypeDefinitionContainer> typeContainerList) {
        System.out.println("-------------");
        System.out.println(title);
        System.out.println("-------------");

        printTypes(typeContainerList, 0);
    }

    private static void printTypes(List<TypeDefinitionContainer> typeContainerList, int level) {
        if (typeContainerList == null) {
            return;
        }

        for (TypeDefinitionContainer container : typeContainerList) {
            for (int i = 0; i < level; i++) {
                System.out.print("  ");
            }

            container.getTypeDefinition().getId();
            System.out.println(container.getTypeDefinition().getId());

            printTypes(container.getChildren(), level + 1);
        }
    }

    public static void print(String title, List<ObjectInFolderContainer> containerList) {
        System.out.println("-------------");
        System.out.println(title);
        System.out.println("-------------");

        print(containerList, 0);
    }

    private static void print(List<ObjectInFolderContainer> containerList, int level) {
        if (containerList == null) {
            return;
        }

        for (ObjectInFolderContainer container : containerList) {
            for (int i = 0; i < level; i++) {
                System.out.print("  ");
            }

            Properties properties = container.getObject().getObject().getProperties();
            System.out.println(properties.getProperties().get(PropertyIds.NAME).getFirstValue() + " ("
                    + properties.getProperties().get(PropertyIds.OBJECT_TYPE_ID).getFirstValue() + ")");

            print(container.getChildren(), level + 1);
        }
    }
}
