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
package org.apache.chemistry.opencmis.client.osgi;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;

import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.impl.ClassLoaderUtil;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.Constants;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.framework.wiring.BundleWiring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSGi Bundle activator for the OpenCMIS client which registers an instance of
 * the {@link SessionFactory} in the OSGi service registry.
 */
public class Activator implements BundleActivator, SynchronousBundleListener {

    /**
     * Represents the manifest header indicating this bundle holds an OpenCMIS
     * SPI implementation
     */
    private static final String OPENCMIS_SPI_HEADER = "OpenCMIS-SPI";

    private static Logger LOG = LoggerFactory.getLogger(Activator.class);

    private BundleContext bundleContext;

    @Override
    public void start(final BundleContext context) {

        this.bundleContext = context;

        // add bundle listener
        context.addBundleListener(this);

        try {
            // check existing bundles in framework for chemistry SPIs
            for (Bundle bundle : context.getBundles()) {
                if (bundle.getState() == Bundle.RESOLVED || bundle.getState() == Bundle.STARTING
                        || bundle.getState() == Bundle.ACTIVE || bundle.getState() == Bundle.STOPPING) {
                    register(bundle);
                }
            }
        } catch (Exception e) {
            // this catch block is only necessary for broken OSGi
            // implementations
            LOG.warn("Could not find and register bundles that contain " + OPENCMIS_SPI_HEADER + " headers: ",
                    e.toString(), e);
        }

        // register the MetaTypeService now, that we are ready
        Dictionary<String, String> props = new Hashtable<String, String>();
        props.put(Constants.SERVICE_DESCRIPTION, "Apache Chemistry OpenCMIS Client Session Factory");
        props.put(Constants.SERVICE_VENDOR, "Apache Software Foundation");

        SessionFactory sessionFactory = SessionFactoryImpl.newInstance();
        context.registerService(SessionFactory.class.getName(), sessionFactory, props);
    }

    @Override
    public void stop(final BundleContext context) {
        // remove bundle listener
        context.removeBundleListener(this);

        // forget our bundle context
        bundleContext = null;

        // unregister all classloaders
        ClassLoaderUtil.unregisterAllBundleClassLoaders();

        // The SessionFactory service will be unregistered automatically
    }

    @Override
    public void bundleChanged(final BundleEvent event) {
        // bundle context might not yet been initialized
        synchronized (this) {
            if (bundleContext == null) {
                return;
            }
        }

        if (event.getType() == BundleEvent.RESOLVED) {
            register(event.getBundle());
        } else if (event.getType() == BundleEvent.UNRESOLVED || event.getType() == BundleEvent.UNINSTALLED) {
            unregister(event.getBundle().getBundleId());
        }
    }

    private void register(final Bundle bundle) {
        BundleWiring bundleWiring = bundle.adapt(BundleWiring.class);
        if (bundleWiring == null) {
            return;
        }

        ClassLoader classLoader = bundleWiring.getClassLoader();
        if (classLoader == null) {
            return;
        }

        List<String> classes = getOpenCmisSpiHeader(bundle);
        if (classes != null) {
            ClassLoaderUtil.registerBundleClassLoader(bundle.getBundleId(), classLoader, classes);
        }
    }

    private void unregister(final long bundleId) {
        ClassLoaderUtil.unregisterBundleClassLoader(bundleId);
    }

    private List<String> getOpenCmisSpiHeader(final Bundle bundle) {
        String spiHeader = bundle.getHeaders().get(OPENCMIS_SPI_HEADER);
        if (spiHeader == null) {
            return null;
        }

        List<String> headerValues = new ArrayList<String>();

        String[] split = spiHeader.split(",");
        for (String className : split) {
            if (className != null && !className.trim().isEmpty()) {
                headerValues.add(className.trim());
            }
        }

        return headerValues;
    }
}
