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
package org.apache.chemistry.opencmis.workbench;

/**
 * Returns command line parameters depending on the Java version.
 */
public class JavaDetector {

    /**
     * Main.
     */
    public static void main(String[] args) {
        String javaVersionStr = System.getProperty("java.specification.version");
        if (javaVersionStr == null) {
            return;
        }

        int javaVersion = 0;

        try {
            javaVersion = Integer.parseInt(javaVersionStr);
        } catch (NumberFormatException nfe) {
            // ignore
        }

        if (javaVersion == 9 || javaVersion == 10) {
            // enables Java EE APIs for the Web Services binding
            System.out.println("--add-modules java.se.ee");
        }
    }
}
