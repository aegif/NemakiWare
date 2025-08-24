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
package org.apache.chemistry.opencmis.tck.runner;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.impl.ClassLoaderUtil;
import org.apache.chemistry.opencmis.commons.impl.IOUtils;
import org.apache.chemistry.opencmis.tck.CmisTest;
import org.apache.chemistry.opencmis.tck.CmisTestGroup;
import org.apache.chemistry.opencmis.tck.CmisTestProgressMonitor;
import org.apache.chemistry.opencmis.tck.impl.WrapperCmisTestGroup;

/**
 * Base class for runners.
 */
public abstract class AbstractRunner {

    public static final String OVERRIDE_KEY = "org.apache.chemistry";
    public static final String DEFAULT_TCK_GROUPS = "/cmis-tck-groups.txt";
    public static final String TCK_BUILD_TIMESTAMP = "/META-INF/build-timestamp.txt";
    public static final String TCK_BUILD_TIMESTAMP_PARAMETER = "org.apache.chemistry.opencmis.tck.timestamp";
    public static final String TCK_REVISION = "/META-INF/tck-revision.txt";
    public static final String TCK_REVISION_PARAMETER = "org.apache.chemistry.opencmis.tck.revision";

    private Map<String, String> parameters;
    private final List<CmisTestGroup> groups = new ArrayList<CmisTestGroup>();
    private boolean isCanceled = false;

    // --- parameters ---

    public void setParameters(Map<String, String> orgParameters) {
        this.parameters = new HashMap<String, String>();
        if (orgParameters != null) {
            this.parameters.putAll(orgParameters);
        }

        // override with system properties
        for (Object key : System.getProperties().keySet()) {
            if (!key.toString().startsWith(OVERRIDE_KEY)) {
                continue;
            }

            parameters.put(key.toString(), System.getProperties().getProperty(key.toString()));
        }

        // set TCK build timestamp and revision
        parameters.put(TCK_BUILD_TIMESTAMP_PARAMETER, loadTCKTimestamp());
        String revision = loadTCKRevision();
        if (revision != null) {
            parameters.put(TCK_REVISION_PARAMETER, revision);
        }
    }

    public void loadParameters(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("File not found!");
        }

        InputStream stream = new FileInputStream(file);
        try {
            loadParameters(stream);
        } finally {
            IOUtils.closeQuietly(stream);
        }
    }

    public void loadParameters(InputStream stream) throws IOException {
        if (stream == null) {
            throw new IllegalArgumentException("Stream is null!");
        }

        setParameters(IOUtils.readAllLinesAsMap(stream));
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    private String loadTCKTimestamp() {
        InputStream stream = getClass().getResourceAsStream(TCK_BUILD_TIMESTAMP);

        if (stream != null) {
            try {
                return IOUtils.readAllLines(stream, 1000);
            } catch (IOException e) {
                return "";
            } finally {
                IOUtils.closeQuietly(stream);
            }
        }

        return "";
    }

    private String loadTCKRevision() {
        InputStream stream = getClass().getResourceAsStream(TCK_REVISION);

        if (stream != null) {
            try {
                String revStr = IOUtils.readFirstLine(stream);
                return String.valueOf(Integer.parseInt(revStr.trim()));
            } catch (NumberFormatException e) {
                return null;
            } catch (IOException e) {
                return null;
            } finally {
                IOUtils.closeQuietly(stream);
            }
        }

        return null;
    }

    // --- groups ---

    public void loadDefaultTckGroups() throws Exception {
        InputStream stream = this.getClass().getResourceAsStream(DEFAULT_TCK_GROUPS);
        if (stream != null) {
            try {
                loadGroups(stream);
            } finally {
                IOUtils.closeQuietly(stream);
            }
        }
    }

    public void loadGroups(File file) throws Exception {
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("File not found!");
        }

        InputStream stream = new FileInputStream(file);
        try {
            loadGroups(stream);
        } finally {
            IOUtils.closeQuietly(stream);
        }
    }

    public void loadGroups(InputStream stream) throws Exception {
        if (stream == null) {
            throw new IllegalArgumentException("Stream is null!");
        }

        for (String groupName : IOUtils.readAllLinesAsList(stream, 10000)) {
            addGroup(groupName);
        }
    }

    public void addGroups(String[] groupClasses) throws Exception {
        if (groupClasses == null) {
            return;
        }

        for (String groupClass : groupClasses) {
            addGroup(groupClass);
        }
    }

    public void addGroup(String groupClass) throws Exception {
        if (groupClass == null) {
            return;
        }

        groupClass = groupClass.trim();
        if (groupClass.length() == 0) {
            return;
        }

        Class<?> clazz = ClassLoaderUtil.loadClass(groupClass);
        Object o = clazz.newInstance();
        CmisTestGroup group = null;

        if (o instanceof CmisTestGroup) {
            group = (CmisTestGroup) o;
        } else if (o instanceof CmisTest) {
            group = new WrapperCmisTestGroup((CmisTest) o);
        } else {
            throw new InstantiationException("Not a CmisTestGroup or CmisTest class!");
        }

        addGroup(group);
    }

    public void addGroup(CmisTestGroup group) throws Exception {
        if (group != null) {
            group.init(parameters);
            groups.add(group);
        }
    }

    public List<CmisTestGroup> getGroups() {
        return groups;
    }

    // --- run ---

    /**
     * Runs all configured groups.
     */
    public void run(CmisTestProgressMonitor monitor) throws Exception {
        synchronized (this) {
            isCanceled = false;
        }

        for (CmisTestGroup group : groups) {
            synchronized (this) {
                if (isCanceled) {
                    break;
                }
            }

            if (group == null || !group.isEnabled()) {
                continue;
            }

            group.setProgressMonitor(monitor);
            group.run();
        }
    }

    public synchronized boolean isCanceled() {
        return isCanceled;
    }

    public synchronized void cancel() {
        isCanceled = true;
    }
}
