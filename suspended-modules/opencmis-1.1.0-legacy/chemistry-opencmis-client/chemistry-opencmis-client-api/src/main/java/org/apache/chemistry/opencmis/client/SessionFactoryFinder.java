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
package org.apache.chemistry.opencmis.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

import org.apache.chemistry.opencmis.client.api.SessionFactory;

/**
 * Finds a {@link SessionFactory} implementation and creates a factory object.
 * <p>
 * Sample code:
 * </p>
 * 
 * <pre>
 * SessionFactory factory = SessionFactoryFinder.find();
 * 
 * Map&lt;String, String&gt; parameter = new HashMap&lt;String, String&gt;();
 * parameter.put(SessionParameter.USER, "Otto");
 * parameter.put(SessionParameter.PASSWORD, "****");
 * 
 * parameter.put(SessionParameter.ATOMPUB_URL, "http://localhost/cmis/atom");
 * parameter.put(SessionParameter.BINDING_TYPE, BindingType.ATOMPUB.value());
 * parameter.put(SessionParameter.REPOSITORY_ID, "myRepository");
 * ...
 * Session session = factory.createSession(parameter);
 * </pre>
 * 
 * @see SessionFactory
 */
public final class SessionFactoryFinder {

    /**
     * Private constructor.
     */
    private SessionFactoryFinder() {
    }

    /**
     * Creates a default {@link SessionFactory} object.
     * 
     * @return the newly created {@link SessionFactory} object
     * 
     * @throws ClassNotFoundException
     *             if the session factory class cannot be found
     * @throws InstantiationException
     *             if the session factory object cannot be instantiated
     */
    public static SessionFactory find() throws ClassNotFoundException, InstantiationException {
        return find("org.apache.chemistry.opencmis.client.SessionFactory", null);
    }

    /**
     * Creates a {@link SessionFactory} object.
     * 
     * @param factoryId
     *            the factory ID of the {@link SessionFactory}
     * 
     * @return the newly created {@link SessionFactory} object
     * 
     * @throws ClassNotFoundException
     *             if the session factory class cannot be found
     * @throws InstantiationException
     *             if the session factory object cannot be instantiated
     */
    public static SessionFactory find(String factoryId) throws ClassNotFoundException, InstantiationException {
        return find(factoryId, null);
    }

    /**
     * Creates a {@link SessionFactory} object.
     * 
     * @param factoryId
     *            the factory ID of the {@link SessionFactory}
     * @param classLoader
     *            the class loader to use
     * 
     * @return the newly created {@link SessionFactory} object
     * 
     * @throws ClassNotFoundException
     *             if the session factory class cannot be found
     * @throws InstantiationException
     *             if the session factory object cannot be instantiated
     */
    public static SessionFactory find(String factoryId, ClassLoader classLoader) throws ClassNotFoundException,
            InstantiationException {
        return find(factoryId, classLoader, "org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl");
    }

    /**
     * Creates a {@link SessionFactory} object.
     * 
     * @param factoryId
     *            the factory ID of the {@link SessionFactory}
     * @param classLoader
     *            the class loader to use
     * @param fallbackClassName
     *            the name of the class to use if no other class name has been
     *            provided
     * 
     * @return the newly created {@link SessionFactory} object
     * 
     * @throws ClassNotFoundException
     *             if the session factory class cannot be found
     * @throws InstantiationException
     *             if the session factory object cannot be instantiated
     */
    private static SessionFactory find(String factoryId, ClassLoader classLoader, String fallbackClassName)
            throws ClassNotFoundException, InstantiationException {
        ClassLoader cl = classLoader;
        if (cl == null) {
            cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = SessionFactoryFinder.class.getClassLoader();
            }
        }

        String factoryClassName = null;

        if (factoryId != null) {
            factoryClassName = System.getProperty(factoryId);

            if (factoryClassName == null) {
                String serviceId = "META-INF/services/" + factoryId;
                InputStream stream = cl.getResourceAsStream(serviceId);
                if (stream != null) {
                    BufferedReader reader = null;
                    try {
                        reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
                        factoryClassName = reader.readLine();
                    } catch (IOException e) {
                        factoryClassName = null;
                    } finally {
                        try {
                            if (reader != null) {
                                reader.close();
                            } else {
                                stream.close();
                            }
                        } catch (IOException e) {
                            // ignore
                        }
                    }
                }
            }
        }

        if (factoryClassName == null) {
            factoryClassName = fallbackClassName;
        }

        Class<?> clazz = null;
        if (cl != null) {
            clazz = cl.loadClass(factoryClassName);
        } else {
            clazz = Class.forName(factoryClassName);
        }

        SessionFactory result = null;
        try {
            Method newInstanceMethod = clazz.getMethod("newInstance", new Class[0]);

            if (!SessionFactory.class.isAssignableFrom(newInstanceMethod.getReturnType())) {
                throw new ClassNotFoundException("newInstance() method does not return a SessionFactory object!");
            }

            try {
                result = (SessionFactory) newInstanceMethod.invoke(null, new Object[0]);
            } catch (Exception e) {
                throw new InstantiationException("Could not create SessionFactory object!");
            }
        } catch (NoSuchMethodException nsme) {
            if (!SessionFactory.class.isAssignableFrom(clazz)) {
                throw new ClassNotFoundException("The class does not implemnt the SessionFactory interface!", nsme);
            }

            try {
                result = (SessionFactory) clazz.newInstance();
            } catch (Exception e) {
                throw new InstantiationException("Could not create SessionFactory object!");
            }
        }

        return result;
    }
}
