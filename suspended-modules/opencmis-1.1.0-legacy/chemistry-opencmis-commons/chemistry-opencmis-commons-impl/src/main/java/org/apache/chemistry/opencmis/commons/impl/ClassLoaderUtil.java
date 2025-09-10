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
package org.apache.chemistry.opencmis.commons.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class ClassLoaderUtil {

    private static ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();
    private static Map<Long, Map<String, ClassLoader>> CLASSLOADER_MAP = null;

    private ClassLoaderUtil() {
    }

    /**
     * Registers a bundle classloader.
     */
    public static void registerBundleClassLoader(long bundleId, ClassLoader classLoader, List<String> classes) {
        if (classLoader == null || classes == null || classes.isEmpty()) {
            return;
        }

        Map<String, ClassLoader> bundleMap = new HashMap<String, ClassLoader>();

        for (String clazz : classes) {
            if (clazz != null && !clazz.isEmpty()) {
                bundleMap.put(clazz, classLoader);
            }
        }

        if (bundleMap.isEmpty()) {
            return;
        }

        LOCK.writeLock().lock();
        try {
            if (CLASSLOADER_MAP == null) {
                CLASSLOADER_MAP = new HashMap<Long, Map<String, ClassLoader>>();
            }

            CLASSLOADER_MAP.put(bundleId, bundleMap);
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    /**
     * Unregisters a bundle classloader.
     */
    public static void unregisterBundleClassLoader(long bundleId) {
        LOCK.writeLock().lock();
        try {
            if (CLASSLOADER_MAP != null) {
                CLASSLOADER_MAP.remove(bundleId);
            }
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    /**
     * Unregisters all bundle classloaders.
     */
    public static void unregisterAllBundleClassLoaders() {
        LOCK.writeLock().lock();
        try {
            CLASSLOADER_MAP = null;
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    /**
     * Loads a class. If the context class loader is set, it is used.
     */
    public static Class<?> loadClass(String className) throws ClassNotFoundException {
        ClassLoader ccl = Thread.currentThread().getContextClassLoader();
        if (ccl == null) {
            try {
                return loadClass(className, null);
            } catch (ClassNotFoundException cnf) {
                return loadClassWithRegisteredClassLoaders(className);
            }
        }

        try {
            return loadClass(className, ccl);
        } catch (ClassNotFoundException cnf) {
            try {
                return loadClass(className, null);
            } catch (ClassNotFoundException cnf2) {
                return loadClassWithRegisteredClassLoaders(className);
            }
        }
    }

    /**
     * Loads a class with the reigistered class loaders.
     */
    private static Class<?> loadClassWithRegisteredClassLoaders(String className) throws ClassNotFoundException {
        if (className == null) {
            throw new ClassNotFoundException("Class name is null!");
        }

        LOCK.readLock().lock();
        try {
            if (CLASSLOADER_MAP == null) {
                throw new ClassNotFoundException();
            }

            for (Map<String, ClassLoader> clm : CLASSLOADER_MAP.values()) {
                for (Map.Entry<String, ClassLoader> cle : clm.entrySet()) {
                    if (cle.getKey().equals(className)) {
                        return loadClass(className, cle.getValue());
                    }
                }
            }

            throw new ClassNotFoundException();
        } finally {
            LOCK.readLock().unlock();
        }
    }

    /**
     * Loads a class from the given class loader.
     */
    public static Class<?> loadClass(String className, ClassLoader classLoader) throws ClassNotFoundException {
        if (classLoader == null) {
            return Class.forName(className);
        } else {
            return Class.forName(className, true, classLoader);
        }
    }
}
